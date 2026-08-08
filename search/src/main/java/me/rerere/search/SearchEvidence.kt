package me.rerere.search

import java.net.URI
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class SearchEvidencePolicy(
    val maxItems: Int = 20,
    val maxImages: Int = 4,
    val maxAnswerChars: Int = 4_000,
    val maxTitleChars: Int = 300,
    val maxUrlChars: Int = 2_048,
    val maxSnippetChars: Int = 3_000,
    val maxBundleChars: Int = 32_000,
) {
    init {
        require(maxItems in 1..100)
        require(maxImages in 0..20)
        require(maxAnswerChars > 0 && maxTitleChars > 0 && maxUrlChars > 0 && maxSnippetChars > 0)
        require(maxBundleChars >= 1_024)
    }
}

/** Bounded provider-neutral payload that is safe to place in model context. */
@Serializable
data class SearchEvidenceBundle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val answer: String? = null,
    val items: List<SearchEvidenceItem>,
    val images: List<String> = emptyList(),
    val originalItemCount: Int,
    val originalImageCount: Int,
    val truncated: Boolean,
    val truncationReasons: Set<SearchEvidenceTruncationReason> = emptySet(),
    /** Opaque handle to the immutable raw result; never a filesystem path. */
    val rawContentBlobRef: String? = null,
    val trust: EvidenceTrust = EvidenceTrust.UNTRUSTED_WEB,
    val mayAuthorizeTools: Boolean = false,
) {
    init {
        require(schemaVersion > 0)
        require(originalItemCount >= items.size)
        require(originalImageCount >= images.size)
        require(truncated == truncationReasons.isNotEmpty())
        require(items.map(SearchEvidenceItem::id).distinct().size == items.size)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class SearchEvidenceItem(
    val id: String,
    val index: Int,
    val title: String,
    val url: String,
    val text: String,
    val trust: EvidenceTrust = EvidenceTrust.UNTRUSTED_WEB,
) {
    init {
        require(id.matches(Regex("[a-f0-9]{6}")))
        require(index > 0)
        require(title.isNotBlank())
        require(url.startsWith("https://") || url.startsWith("http://"))
    }
}

@Serializable
enum class EvidenceTrust { UNTRUSTED_WEB }

@Serializable
enum class SearchEvidenceTruncationReason {
    ITEM_LIMIT,
    IMAGE_LIMIT,
    FIELD_LIMIT,
    TOTAL_BUDGET,
    INVALID_URL,
}

object SearchEvidenceCompiler {
    fun compile(
        result: SearchResult,
        policy: SearchEvidencePolicy = SearchEvidencePolicy(),
        rawContentBlobRef: String? = null,
    ): SearchEvidenceBundle {
        val reasons = linkedSetOf<SearchEvidenceTruncationReason>()
        var remaining = policy.maxBundleChars
        val answer = result.answer?.takeIf(String::isNotBlank)?.let { raw ->
            val bounded = raw.truncateUnicode(policy.maxAnswerChars)
            if (bounded.length < raw.length) reasons += SearchEvidenceTruncationReason.FIELD_LIMIT
            remaining -= bounded.length
            bounded
        }
        val usedIds = mutableSetOf<String>()
        val items = buildList {
            result.items.forEachIndexed { sourceIndex, source ->
                if (size >= policy.maxItems) {
                    reasons += SearchEvidenceTruncationReason.ITEM_LIMIT
                    return@forEachIndexed
                }
                val url = canonicalPublicUrl(source.url, policy.maxUrlChars)
                if (url == null) {
                    reasons += SearchEvidenceTruncationReason.INVALID_URL
                    return@forEachIndexed
                }
                if (url.length < source.url.trim().length) reasons += SearchEvidenceTruncationReason.FIELD_LIMIT
                val titleSource = source.title.ifBlank { URI(url).host.orEmpty() }.ifBlank { "Untitled source" }
                val title = titleSource.truncateUnicode(policy.maxTitleChars)
                if (title.length < titleSource.length) reasons += SearchEvidenceTruncationReason.FIELD_LIMIT
                val fixedCost = title.length + url.length + 64
                if (remaining <= fixedCost) {
                    reasons += SearchEvidenceTruncationReason.TOTAL_BUDGET
                    return@forEachIndexed
                }
                val snippetLimit = minOf(policy.maxSnippetChars, remaining - fixedCost)
                val text = source.text.truncateUnicode(snippetLimit)
                if (text.length < source.text.length) {
                    reasons += if (snippetLimit < policy.maxSnippetChars) {
                        SearchEvidenceTruncationReason.TOTAL_BUDGET
                    } else {
                        SearchEvidenceTruncationReason.FIELD_LIMIT
                    }
                }
                val id = stableCitationId(url, sourceIndex, usedIds)
                usedIds += id
                add(SearchEvidenceItem(id, sourceIndex + 1, title, url, text))
                remaining -= fixedCost + text.length
            }
        }
        val images = result.images.mapNotNull { canonicalPublicUrl(it, policy.maxUrlChars) }
            .distinct()
            .take(policy.maxImages)
        if (images.size < result.images.size) {
            reasons += if (result.images.size > policy.maxImages) {
                SearchEvidenceTruncationReason.IMAGE_LIMIT
            } else {
                SearchEvidenceTruncationReason.INVALID_URL
            }
        }
        return SearchEvidenceBundle(
            answer = answer,
            items = items,
            images = images,
            originalItemCount = result.items.size,
            originalImageCount = result.images.size,
            truncated = reasons.isNotEmpty(),
            truncationReasons = reasons,
            rawContentBlobRef = rawContentBlobRef,
        )
    }

    private fun stableCitationId(url: String, ordinal: Int, usedIds: Set<String>): String {
        var salt = ordinal
        while (true) {
            val candidate = sha256("$url\u0000$salt").take(6)
            if (candidate !in usedIds) return candidate
            salt += 1
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun canonicalPublicUrl(raw: String, maxChars: Int): String? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        URI(
            uri.scheme.lowercase(Locale.ROOT),
            null,
            uri.host.lowercase(Locale.ROOT),
            uri.port,
            uri.rawPath.ifBlank { "/" },
            uri.rawQuery,
            null,
        ).toASCIIString().takeIf { it.length <= maxChars }
    }.getOrNull()

    private fun String.truncateUnicode(maxChars: Int): String {
        if (length <= maxChars) return this
        var end = maxChars
        if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
            end -= 1
        }
        return substring(0, end)
    }
}

@Serializable
data class ScrapeEvidenceBundle(
    val schemaVersion: Int = 1,
    val pages: List<ScrapeEvidencePage>,
    val originalPageCount: Int,
    val truncated: Boolean,
    val rawContentBlobRef: String? = null,
    val trust: EvidenceTrust = EvidenceTrust.UNTRUSTED_WEB,
    val mayAuthorizeTools: Boolean = false,
)

@Serializable
data class ScrapeEvidencePage(
    val url: String,
    val title: String? = null,
    val content: String,
)

object ScrapeEvidenceCompiler {
    private const val MAX_PAGES = 8
    private const val MAX_PAGE_CHARS = 12_000
    private const val MAX_TOTAL_CHARS = 48_000

    fun compile(result: ScrapedResult, rawContentBlobRef: String? = null): ScrapeEvidenceBundle {
        var remaining = MAX_TOTAL_CHARS
        var truncated = result.urls.size > MAX_PAGES
        val pages = buildList {
            result.urls.take(MAX_PAGES).forEach { page ->
                val safeUrl = SearchUrlPolicy.canonicalHttpUrl(page.url) ?: run {
                    truncated = true
                    return@forEach
                }
                val content = page.content.take(minOf(MAX_PAGE_CHARS, remaining))
                if (content.length < page.content.length) truncated = true
                if (content.isNotBlank()) {
                    add(ScrapeEvidencePage(safeUrl, page.metadata?.title?.take(300), content))
                    remaining -= content.length
                }
                if (remaining == 0) truncated = true
            }
        }
        return ScrapeEvidenceBundle(
            pages = pages,
            originalPageCount = result.urls.size,
            truncated = truncated,
            rawContentBlobRef = rawContentBlobRef,
        )
    }
}

/** Deterministic first-line SSRF gate. Transports must still reject redirects to private networks. */
object SearchUrlPolicy {
    fun canonicalHttpUrl(value: String): String? = runCatching {
        val uri = URI(value.trim()).normalize()
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return null
        }
        URI(uri.scheme.lowercase(Locale.ROOT), null, host, uri.port, uri.rawPath.ifEmpty { "/" }, uri.rawQuery, null).toASCIIString()
    }.getOrNull()

    fun canonicalPublicUrl(value: String): String? {
        val canonical = canonicalHttpUrl(value) ?: return null
        return runCatching {
            val addresses = InetAddress.getAllByName(URI(canonical).host)
            canonical.takeIf { addresses.isNotEmpty() && addresses.none(::isPrivateAddress) }
        }.getOrNull()
    }

    fun requirePublicUrl(value: String): String = canonicalPublicUrl(value)
        ?: throw IllegalArgumentException("URL is not allowed by the public-network policy")

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || isReserved(address.address)

    private fun isReserved(bytes: ByteArray): Boolean {
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 198 && b in 18..19)
        }
        return bytes.size == 16 && ((bytes[0].toInt() and 0xfe) == 0xfc)
    }
}

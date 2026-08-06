package me.rerere.rikkahub.data.db.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.CitationSourceEntity
import me.rerere.rikkahub.data.db.entity.CitationValues
import me.rerere.rikkahub.data.db.entity.MessageCitationEntity
import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class CitationProjection(
    val message: UIMessage,
    val sources: List<CitationSourceEntity>,
    val citations: List<MessageCitationEntity>,
)

/** Pure v1 normalization shared by live writes and restartable legacy backfill. */
internal class CitationProjector(private val json: Json) {
    fun project(conversationId: String, message: UIMessage): CitationProjection {
        val messageId = message.id.toString()
        val candidates = mutableListOf<CitationCandidate>()

        message.annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()
            .take(MAX_CITATIONS_PER_MESSAGE)
            .forEach { annotation ->
                val canonicalUrl = canonicalizeCitationUrl(annotation.url) ?: return@forEach
                candidates += CitationCandidate(
                    canonicalUrl = canonicalUrl,
                    title = annotation.title.ifBlank { displayHost(canonicalUrl) },
                    publisher = annotation.publisher ?: displayHost(canonicalUrl),
                    retrievedAt = annotation.retrievedAt,
                    textStart = annotation.startIndex,
                    textEnd = annotation.endIndex,
                    textPartOrdinal = annotation.textPartOrdinal,
                    offsetUnit = annotation.offsetUnit ?: CitationValues.OFFSET_PROVIDER_CHARACTER,
                    quote = annotation.quote,
                    isAvailable = annotation.isAvailable,
                    provenance = annotation.provenance ?: CitationValues.PROVENANCE_PROVIDER,
                    providerMetadata = annotation.providerMetadata ?: JsonObject(emptyMap()),
                    existingSourceId = annotation.sourceId,
                    existingCitationId = annotation.citationId,
                )
            }

        val searchSources = extractSearchSources(message)
        val markerBoundSearchIds = mutableSetOf<String>()
        var flattenedOffset = 0
        message.parts.forEach { part ->
            if (part !is UIMessagePart.Text) return@forEach
            CITATION_MARKER.findAll(part.text).forEach { match ->
                val shortId = match.groupValues[2].trim()
                val source = searchSources[shortId] ?: return@forEach
                markerBoundSearchIds += shortId
                val start = flattenedOffset + match.range.first
                val end = flattenedOffset + match.range.last + 1
                val existingIndex = candidates.indexOfFirst { candidate ->
                        candidate.provenance == CitationValues.PROVENANCE_SEARCH_TOOL &&
                            candidate.canonicalUrl == source.canonicalUrl &&
                            candidate.textStart == start && candidate.textEnd == end
                }
                if (existingIndex >= 0) {
                    candidates[existingIndex] = candidates[existingIndex].enrichFromSearch(source)
                    return@forEach
                }
                if (candidates.size >= MAX_CITATIONS_PER_MESSAGE) return@forEach
                candidates += CitationCandidate(
                    canonicalUrl = source.canonicalUrl,
                    title = source.title,
                    publisher = source.publisher,
                    retrievedAt = null,
                    snippet = source.snippet,
                    textStart = start,
                    textEnd = end,
                    textPartOrdinal = null,
                    offsetUnit = CitationValues.OFFSET_MESSAGE_FLATTENED_UTF16,
                    quote = null,
                    isAvailable = true,
                    provenance = CitationValues.PROVENANCE_SEARCH_TOOL,
                    providerMetadata = JsonObject(mapOf("legacyShortId" to JsonPrimitive(shortId))),
                )
            }
            flattenedOffset += part.text.length + 1
        }
        // Structured tool sources are first-class even when the model omits the legacy marker.
        // A marker adds a precise span; marker-less results remain message-level citations.
        searchSources.forEach { (shortId, source) ->
            if (shortId in markerBoundSearchIds) return@forEach
            val existingIndex = candidates.indexOfFirst { candidate ->
                    candidate.provenance == CitationValues.PROVENANCE_SEARCH_TOOL &&
                        candidate.canonicalUrl == source.canonicalUrl
                }
            if (existingIndex >= 0) {
                candidates[existingIndex] = candidates[existingIndex].enrichFromSearch(source)
                return@forEach
            }
            if (candidates.size >= MAX_CITATIONS_PER_MESSAGE) return@forEach
            candidates += CitationCandidate(
                canonicalUrl = source.canonicalUrl,
                title = source.title,
                publisher = source.publisher,
                retrievedAt = null,
                snippet = source.snippet,
                textStart = null,
                textEnd = null,
                textPartOrdinal = null,
                offsetUnit = CitationValues.OFFSET_MESSAGE_FLATTENED_UTF16,
                quote = null,
                isAvailable = true,
                provenance = CitationValues.PROVENANCE_SEARCH_TOOL,
                providerMetadata = JsonObject(mapOf("legacyShortId" to JsonPrimitive(shortId))),
            )
        }

        val sourcesById = linkedMapOf<String, CitationSourceEntity>()
        val seenCitationIds = mutableSetOf<String>()
        val occurrenceCounts = mutableMapOf<String, Int>()
        var remainingMetadataBytes = MAX_CITATION_PROVIDER_METADATA_BYTES_PER_MESSAGE
        var remainingQuoteBytes = MAX_CITATION_QUOTE_BYTES_PER_MESSAGE
        val citations = candidates.mapIndexed { ordinal, candidate ->
            val remainingOccurrences = candidates.size - ordinal
            val metadataBudgetBytes = (remainingMetadataBytes / remainingOccurrences)
                .coerceIn(MIN_CITATION_METADATA_BYTES, MAX_CITATION_PROVIDER_METADATA_BYTES)
            val remainingQuotedOccurrences = candidates.subList(ordinal, candidates.size)
                .count { it.quote != null }
            val quoteBudgetBytes = if (candidate.quote == null || remainingQuotedOccurrences == 0) {
                0
            } else {
                (remainingQuoteBytes / remainingQuotedOccurrences)
                    .coerceAtMost(MAX_CITATION_QUOTE_BYTES)
            }
            val expectedSourceId = deterministicConversationV2Id("citation-source-v1", candidate.canonicalUrl)
            val sourceId = candidate.existingSourceId
                ?.takeIf { isSafeStableId(it) && it == expectedSourceId }
                ?: expectedSourceId
            val source = candidate.toSource(sourceId)
            sourcesById[sourceId] = sourcesById[sourceId]?.mergePreferRicher(source) ?: source
            val occurrence = candidate.normalizedOccurrence(
                metadataBudgetBytes = metadataBudgetBytes,
                quoteBudgetBytes = quoteBudgetBytes,
            )
            remainingMetadataBytes -= occurrence.providerMetadataJson.utf8Size()
            remainingQuoteBytes -= occurrence.quote?.utf8Size() ?: 0
            val occurrenceKey = occurrence.stableOccurrenceKey(sourceId)
            val duplicateOrdinal = occurrenceCounts.getOrDefault(occurrenceKey, 0)
            occurrenceCounts[occurrenceKey] = duplicateOrdinal + 1
            val expectedCitationId = deterministicConversationV2Id(
                "message-citation-v1",
                conversationId,
                messageId,
                sourceId,
                occurrenceKey,
                duplicateOrdinal.toString(),
            )
            val preservedCitationId = candidate.existingCitationId
                ?.takeIf { isSafeStableId(it) && it == expectedCitationId }
                ?.takeIf(seenCitationIds::add)
            val citationId = preservedCitationId ?: expectedCitationId
            check(seenCitationIds.add(citationId) || citationId == preservedCitationId) {
                "Deterministic citation identity collision"
            }
            occurrence.toCitation(
                citationId = citationId,
                conversationId = conversationId,
                messageId = messageId,
                sourceId = sourceId,
                ordinal = ordinal,
            )
        }
        val sources = sourcesById.values.toList()
        requireValidCitationProjection(conversationId, sources, citations)
        val normalizedAnnotations = citations.map { citation ->
            val source = checkNotNull(sourcesById[citation.sourceId])
            UIMessageAnnotation.UrlCitation(
                title = citation.displayTitle,
                url = source.canonicalUrl,
                sourceId = source.sourceId,
                citationId = citation.citationId,
                ordinal = citation.ordinal,
                publisher = citation.displayPublisher,
                retrievedAt = citation.displayRetrievedAt,
                startIndex = citation.textStart,
                endIndex = citation.textEnd,
                textPartOrdinal = citation.textPartOrdinal,
                offsetUnit = citation.offsetUnit,
                quote = citation.quote,
                isAvailable = citation.isAvailable && source.deletedAt == null,
                provenance = citation.provenance,
                providerMetadata = parseObjectOrEmpty(citation.providerMetadataJson),
            )
        }
        val passthrough = message.annotations.filterNot { it is UIMessageAnnotation.UrlCitation }
        return CitationProjection(
            message = message.copy(annotations = passthrough + normalizedAnnotations),
            sources = sources,
            citations = citations,
        )
    }

    private fun extractSearchSources(message: UIMessage): Map<String, SearchSource> {
        val candidates = linkedMapOf<String, SearchSource?>()
        var inspectedItems = 0
        val searchTools = message.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName == "search_web" && it.isExecuted }
        toolLoop@ for (tool in searchTools) {
            if (inspectedItems >= MAX_CITATIONS_PER_MESSAGE) break
            val outputParts = tool.output.filterIsInstance<UIMessagePart.Text>()
            val rawLength = outputParts.sumOf { it.text.length.toLong() } +
                (outputParts.size - 1).coerceAtLeast(0)
            if (rawLength > MAX_SEARCH_CITATION_PAYLOAD_CHARS) continue
            val raw = outputParts.joinToString("\n") { it.text }
            val root = parseJsonObject(raw) ?: continue
            for (itemElement in root["items"]?.jsonArrayOrNull().orEmpty()) {
                if (inspectedItems++ >= MAX_CITATIONS_PER_MESSAGE) break@toolLoop
                val item = itemElement as? JsonObject ?: continue
                val shortId = (item["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (!SHORT_CITATION_ID.matches(shortId)) continue
                val canonicalUrl = (item["url"] as? JsonPrimitive)?.contentOrNull
                    ?.let(::canonicalizeCitationUrl) ?: continue
                val source = SearchSource(
                    canonicalUrl = canonicalUrl,
                    title = sanitizeCitationPersistedText(
                        (item["title"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf(String::isNotBlank) ?: displayHost(canonicalUrl),
                        MAX_CITATION_TITLE_LENGTH,
                    ),
                    publisher = displayHost(canonicalUrl),
                    snippet = (item["text"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let {
                            sanitizeCitationPersistedText(
                                it,
                                MAX_CITATION_TEXT_LENGTH,
                                MAX_CITATION_SNIPPET_BYTES,
                            )
                        },
                )
                val previous = candidates[shortId]
                candidates[shortId] = when {
                    previous == null && !candidates.containsKey(shortId) -> source
                    previous?.canonicalUrl == canonicalUrl -> previous
                    else -> null // Ambiguous six-character id: fail closed instead of misbinding a source.
                }
            }
        }
        return candidates.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        val payload = if (trimmed.startsWith("```")) {
            trimmed.substringAfter('\n').substringBeforeLast("```").trim()
        } else {
            trimmed
        }
        return runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
    }

    private fun parseObjectOrEmpty(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
}

internal fun canonicalizeCitationUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_CITATION_URL_LENGTH) return null
    return runCatching {
        val parsed = URI(trimmed)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (!parsed.rawUserInfo.isNullOrBlank()) return null
        val authority = canonicalCitationAuthority(parsed, scheme) ?: return null
        val sanitizedQuery = parsed.rawQuery?.let(::sanitizeCitationQuery)
        val rawPath = parsed.rawPath?.ifEmpty { "/" } ?: "/"
        // Reparse a raw URI instead of using the component constructor: the latter escapes an
        // already-valid '%' again on every projection (%E4 -> %25E4 -> %2525E4).
        buildString {
            append(scheme).append("://").append(authority)
            append(rawPath)
            sanitizedQuery?.let { append('?').append(it) }
        }.let(::URI).normalize().toASCIIString()
            .takeIf { it.length <= MAX_CITATION_URL_LENGTH }
    }.getOrNull()
}

private fun canonicalCitationAuthority(parsed: URI, scheme: String): String? {
    val rawAuthority = parsed.rawAuthority ?: return null
    // URI.rawUserInfo catches encoded ':' in credentials; the raw '@' check also rejects
    // authorities that Java could not parse into a structured user-info component.
    if ('@' in rawAuthority || !parsed.rawUserInfo.isNullOrBlank()) return null

    val parsedHost = parsed.host
    val rawPort: Int
    val canonicalHost = if (parsedHost != null) {
        rawPort = parsed.port
        if (parsedHost.startsWith('[') && parsedHost.endsWith(']')) {
            parsedHost.lowercase(Locale.ROOT)
        } else {
            IDN.toASCII(parsedHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }
    } else {
        // java.net.URI intentionally leaves Unicode DNS names in rawAuthority. Split an optional
        // numeric port, then IDNA-normalize the host without touching Unicode path/query data.
        if (rawAuthority.startsWith('[') || rawAuthority.count { it == ':' } > 1) return null
        val portSeparator = rawAuthority.lastIndexOf(':')
        val hasPort = portSeparator > 0 && rawAuthority.substring(portSeparator + 1).all(Char::isDigit)
        val rawHost = if (hasPort) rawAuthority.substring(0, portSeparator) else rawAuthority
        rawPort = if (hasPort) rawAuthority.substring(portSeparator + 1).toIntOrNull() ?: return null else -1
        IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    }.takeIf(String::isNotBlank) ?: return null

    if (rawPort !in -1..65_535) return null
    val port = when {
        scheme == "http" && rawPort == 80 -> -1
        scheme == "https" && rawPort == 443 -> -1
        else -> rawPort
    }
    return if (port == -1) canonicalHost else "$canonicalHost:$port"
}

internal fun digestCitationProjection(
    sources: List<CitationSourceEntity>,
    citations: List<MessageCitationEntity>,
): String {
    val sourceById = sources.associateBy(CitationSourceEntity::sourceId)
    val accumulator = CitationProjectionDigestAccumulator()
    citations.sortedWith(
        compareBy<MessageCitationEntity>(MessageCitationEntity::messageId)
            .thenBy(MessageCitationEntity::ordinal)
            .thenBy(MessageCitationEntity::citationId),
    ).forEach { citation ->
        val source = checkNotNull(sourceById[citation.sourceId]) {
            "Citation ${citation.citationId} references a missing source"
        }
        accumulator.add(citation, source)
    }
    return accumulator.finish()
}

/** Streaming form of the v1 projection digest used by bounded citation hydration. */
internal class CitationProjectionDigestAccumulator {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        update("rikkahub-citation-projection-v1")
        updateSeparator()
    }

    fun add(citation: MessageCitationEntity, source: CitationSourceEntity) {
        updateField(citation.citationId)
        updateField(citation.recordDigest)
        updateField(citation.revision.toString())
        updateField(source.sourceId)
        // Titles/snippets are reusable source metadata and may become richer when another
        // conversation cites the same URL. Only immutable source identity belongs here.
        updateField(source.canonicalUrl)
    }

    fun finish(): String = digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun updateField(value: String) {
        update(value)
        updateSeparator()
    }

    private fun update(value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
    }

    private fun updateSeparator() {
        digest.update(byteArrayOf(0))
    }
}

/**
 * Verifies the semantic identities that record digests intentionally do not cover. A copied or
 * hand-edited database must not be able to bind a canonical URL to a foreign source ID, or move an
 * occurrence to a different message while retaining otherwise self-consistent digests.
 */
internal fun requireValidCitationProjection(
    conversationId: String,
    sources: List<CitationSourceEntity>,
    citations: List<MessageCitationEntity>,
) {
    requireCitationPersistenceBudget(sources, citations)
    val sourcesById = sources.associateBy(CitationSourceEntity::sourceId)
    require(sourcesById.size == sources.size) { "Citation projection contains duplicate source IDs" }
    require(sources.map(CitationSourceEntity::canonicalUrl).distinct().size == sources.size) {
        "Citation projection contains duplicate canonical URLs"
    }
    sources.forEach { source ->
        val canonicalUrl = canonicalizeCitationUrl(source.canonicalUrl)
        require(canonicalUrl == source.canonicalUrl) {
            "Citation source ${source.sourceId} does not contain a canonical safe URL"
        }
        val expectedSourceId = deterministicConversationV2Id("citation-source-v1", source.canonicalUrl)
        require(isSafeStableId(source.sourceId) && source.sourceId == expectedSourceId) {
            "Citation source identity does not match its canonical URL"
        }
        require(source.recordDigest == citationSourceRecordDigest(source)) {
            "Citation source ${source.sourceId} has an invalid record digest"
        }
        require(source.contentHash == source.snippet?.let(::sha256Hex)) {
            "Citation source ${source.sourceId} has an invalid content hash"
        }
    }

    require(citations.map(MessageCitationEntity::citationId).distinct().size == citations.size) {
        "Citation projection contains duplicate occurrence IDs"
    }
    require(sourcesById.keys == citations.mapTo(mutableSetOf(), MessageCitationEntity::sourceId)) {
        "Citation projection source set does not match its occurrences"
    }
    citations.groupBy(MessageCitationEntity::messageId).forEach { (messageId, messageCitations) ->
        val occurrenceCounts = mutableMapOf<String, Int>()
        messageCitations.sortedBy(MessageCitationEntity::ordinal).forEachIndexed { expectedOrdinal, citation ->
            require(citation.conversationId == conversationId) {
                "Citation ${citation.citationId} belongs to a different conversation"
            }
            require(citation.ordinal == expectedOrdinal) {
                "Message $messageId citation ordinals are not contiguous"
            }
            require(sourcesById.containsKey(citation.sourceId)) {
                "Citation ${citation.citationId} references a missing source"
            }
            val occurrenceKey = citation.stableOccurrenceKey()
            val duplicateOrdinal = occurrenceCounts.getOrDefault(occurrenceKey, 0)
            occurrenceCounts[occurrenceKey] = duplicateOrdinal + 1
            val expectedCitationId = deterministicConversationV2Id(
                "message-citation-v1",
                conversationId,
                messageId,
                citation.sourceId,
                occurrenceKey,
                duplicateOrdinal.toString(),
            )
            require(isSafeStableId(citation.citationId) && citation.citationId == expectedCitationId) {
                "Citation occurrence identity does not match its owner and coordinates"
            }
            require(citation.recordDigest == messageCitationRecordDigest(citation)) {
                "Citation ${citation.citationId} has an invalid record digest"
            }
        }
    }
}

/** Shared persistence budget gate for live projection and paged authoritative loads. */
internal fun requireCitationPersistenceBudget(
    sources: Collection<CitationSourceEntity>,
    citations: Collection<MessageCitationEntity>,
) {
    citations.groupBy(MessageCitationEntity::messageId).forEach { (messageId, messageCitations) ->
        require(messageCitations.size <= MAX_CITATIONS_PER_MESSAGE) {
            "Message $messageId exceeds the $MAX_CITATIONS_PER_MESSAGE citation persistence budget"
        }
        val metadataBytes = messageCitations.sumOf { it.providerMetadataJson.utf8Size().toLong() }
        require(metadataBytes <= MAX_CITATION_PROVIDER_METADATA_BYTES_PER_MESSAGE) {
            "Message $messageId exceeds the provider metadata persistence budget"
        }
        val quoteBytes = messageCitations.sumOf { it.quote?.utf8Size()?.toLong() ?: 0L }
        require(quoteBytes <= MAX_CITATION_QUOTE_BYTES_PER_MESSAGE) {
            "Message $messageId exceeds the citation quote persistence budget"
        }
    }
    sources.forEach { source ->
        require(source.canonicalUrl.length <= MAX_CITATION_URL_LENGTH)
        require(source.title.codePointLength() <= MAX_CITATION_TITLE_LENGTH)
        require((source.publisher?.codePointLength() ?: 0) <= MAX_CITATION_TITLE_LENGTH)
        require((source.snippet?.codePointLength() ?: 0) <= MAX_CITATION_TEXT_LENGTH)
        require((source.snippet?.utf8Size() ?: 0) <= MAX_CITATION_SNIPPET_BYTES)
        require(source.metadataJson.toByteArray(Charsets.UTF_8).size <= MAX_CITATION_PROVIDER_METADATA_BYTES)
    }
    citations.forEach { citation ->
        require(citation.displayTitle.codePointLength() <= MAX_CITATION_TITLE_LENGTH)
        require((citation.displayPublisher?.codePointLength() ?: 0) <= MAX_CITATION_TITLE_LENGTH)
        require((citation.quote?.codePointLength() ?: 0) <= MAX_CITATION_TEXT_LENGTH)
        require((citation.quote?.utf8Size() ?: 0) <= MAX_CITATION_QUOTE_BYTES)
        require(citation.offsetUnit.codePointLength() <= MAX_CITATION_OFFSET_UNIT_LENGTH)
        require(citation.provenance.codePointLength() <= MAX_CITATION_PROVENANCE_LENGTH)
        require(
            citation.providerMetadataJson.toByteArray(Charsets.UTF_8).size <=
                MAX_CITATION_PROVIDER_METADATA_BYTES,
        ) { "Citation ${citation.citationId} exceeds the provider metadata persistence budget" }
    }
}

private fun MessageCitationEntity.stableOccurrenceKey(): String = citationStableOccurrenceKey(
    sourceId = sourceId,
    provenance = provenance,
    textPartOrdinal = textPartOrdinal,
    textStart = textStart,
    textEnd = textEnd,
    offsetUnit = offsetUnit,
)

private fun citationStableOccurrenceKey(
    sourceId: String,
    provenance: String,
    textPartOrdinal: Int?,
    textStart: Int?,
    textEnd: Int?,
    offsetUnit: String,
): String = listOf(
    sourceId,
    provenance,
    textPartOrdinal?.toString().orEmpty(),
    textStart?.toString().orEmpty(),
    textEnd?.toString().orEmpty(),
    offsetUnit,
).joinToString("\u0000")

private data class SearchSource(
    val canonicalUrl: String,
    val title: String,
    val publisher: String?,
    val snippet: String?,
)

private data class CitationCandidate(
    val canonicalUrl: String,
    val title: String,
    val publisher: String?,
    val retrievedAt: Long?,
    val snippet: String? = null,
    val textStart: Int?,
    val textEnd: Int?,
    val textPartOrdinal: Int?,
    val offsetUnit: String,
    val quote: String?,
    val isAvailable: Boolean,
    val provenance: String,
    val providerMetadata: JsonObject,
    val existingSourceId: String? = null,
    val existingCitationId: String? = null,
) {
    fun enrichFromSearch(source: SearchSource): CitationCandidate {
        require(canonicalUrl == source.canonicalUrl)
        val richerSnippet = source.snippet?.takeIf { it.length > (snippet?.length ?: -1) } ?: snippet
        return copy(
            title = source.title.takeIf { it.length > title.length } ?: title,
            publisher = publisher ?: source.publisher,
            snippet = richerSnippet,
        )
    }

    fun toSource(sourceId: String): CitationSourceEntity {
        // Provider spans and legacy short IDs describe this citation occurrence, not the URL source.
        val metadataJson = "{}"
        val safeTitle = sanitizeCitationPersistedText(title, MAX_CITATION_TITLE_LENGTH)
        val safePublisher = publisher?.let {
            sanitizeCitationPersistedText(it, MAX_CITATION_TITLE_LENGTH)
        }
        val safeSnippet = snippet?.let {
            sanitizeCitationPersistedText(
                it,
                MAX_CITATION_TEXT_LENGTH,
                MAX_CITATION_SNIPPET_BYTES,
            )
        }
        val contentHash = safeSnippet?.let(::sha256Hex)
        val source = CitationSourceEntity(
            sourceId = sourceId,
            canonicalUrl = canonicalUrl,
            title = safeTitle,
            publisher = safePublisher,
            retrievedAt = retrievedAt?.takeIf { it >= 0L },
            snippet = safeSnippet,
            contentHash = contentHash,
            metadataJson = metadataJson,
            recordDigest = "",
            deletedAt = null,
        )
        return source.copy(recordDigest = citationSourceRecordDigest(source))
    }

    fun normalizedOccurrence(
        metadataBudgetBytes: Int,
        quoteBudgetBytes: Int,
    ): NormalizedCitationOccurrence {
        val safeStart = textStart?.takeIf { it >= 0 }
        return NormalizedCitationOccurrence(
            displayTitle = sanitizeCitationPersistedText(title, MAX_CITATION_TITLE_LENGTH),
            displayPublisher = publisher?.let {
                sanitizeCitationPersistedText(it, MAX_CITATION_TITLE_LENGTH)
            },
            displayRetrievedAt = retrievedAt?.takeIf { it >= 0L },
            isAvailable = isAvailable,
            textStart = safeStart,
            textEnd = safeStart?.let { start -> textEnd?.takeIf { it >= start } },
            textPartOrdinal = textPartOrdinal?.takeIf { it >= 0 },
            offsetUnit = sanitizeCitationPersistedText(offsetUnit, MAX_CITATION_OFFSET_UNIT_LENGTH)
                .ifBlank { CitationValues.OFFSET_UNKNOWN },
            quote = quote?.let {
                sanitizeCitationPersistedText(it, MAX_CITATION_TEXT_LENGTH, quoteBudgetBytes)
                    .takeIf(String::isNotEmpty)
            },
            provenance = sanitizeCitationPersistedText(provenance, MAX_CITATION_PROVENANCE_LENGTH)
                .ifBlank { CitationValues.PROVENANCE_PROVIDER },
            providerMetadataJson = boundedCitationMetadata(providerMetadata, metadataBudgetBytes),
        )
    }
}

private data class NormalizedCitationOccurrence(
    val displayTitle: String,
    val displayPublisher: String?,
    val displayRetrievedAt: Long?,
    val isAvailable: Boolean,
    val textStart: Int?,
    val textEnd: Int?,
    val textPartOrdinal: Int?,
    val offsetUnit: String,
    val quote: String?,
    val provenance: String,
    val providerMetadataJson: String,
) {
    fun stableOccurrenceKey(sourceId: String): String = citationStableOccurrenceKey(
        sourceId = sourceId,
        provenance = provenance,
        textPartOrdinal = textPartOrdinal,
        textStart = textStart,
        textEnd = textEnd,
        offsetUnit = offsetUnit,
    )

    fun toCitation(
        citationId: String,
        conversationId: String,
        messageId: String,
        sourceId: String,
        ordinal: Int,
    ): MessageCitationEntity {
        val citation = MessageCitationEntity(
            citationId = citationId,
            conversationId = conversationId,
            messageId = messageId,
            sourceId = sourceId,
            ordinal = ordinal,
            displayTitle = displayTitle,
            displayPublisher = displayPublisher,
            displayRetrievedAt = displayRetrievedAt,
            isAvailable = isAvailable,
            textStart = textStart,
            textEnd = textEnd,
            textPartOrdinal = textPartOrdinal,
            offsetUnit = offsetUnit,
            quote = quote,
            provenance = provenance,
            providerMetadataJson = providerMetadataJson,
            recordDigest = "",
        )
        return citation.copy(recordDigest = messageCitationRecordDigest(citation))
    }
}

internal fun CitationSourceEntity.mergePreferRicher(other: CitationSourceEntity): CitationSourceEntity {
    require(sourceId == other.sourceId && canonicalUrl == other.canonicalUrl)
    val chosenTitle = (other.title.takeIf { it.length > title.length } ?: title)
        .let { sanitizeCitationPersistedText(it, MAX_CITATION_TITLE_LENGTH) }
    val chosenPublisher = (publisher ?: other.publisher)?.let {
        sanitizeCitationPersistedText(it, MAX_CITATION_TITLE_LENGTH)
    }
    val chosenSnippet = (other.snippet?.takeIf { it.length > (snippet?.length ?: -1) } ?: snippet)
        ?.let {
            sanitizeCitationPersistedText(
                it,
                MAX_CITATION_TEXT_LENGTH,
                MAX_CITATION_SNIPPET_BYTES,
            )
        }
    val chosen = copy(
        title = chosenTitle,
        publisher = chosenPublisher,
        retrievedAt = listOfNotNull(retrievedAt, other.retrievedAt).maxOrNull(),
        snippet = chosenSnippet,
        contentHash = chosenSnippet?.let(::sha256Hex),
        metadataJson = if (other.metadataJson.length > metadataJson.length) other.metadataJson else metadataJson,
    )
    return chosen.copy(recordDigest = citationSourceRecordDigest(chosen))
}

internal fun citationSourceRecordDigest(source: CitationSourceEntity): String = sha256Hex(
    listOf(
        source.canonicalUrl,
        source.title,
        source.publisher.orEmpty(),
        source.retrievedAt?.toString().orEmpty(),
        source.snippet.orEmpty(),
        source.contentHash.orEmpty(),
        source.metadataJson,
    ).joinToString("\u0000"),
)

internal fun messageCitationRecordDigest(citation: MessageCitationEntity): String = sha256Hex(
    listOf(
        citation.conversationId,
        citation.messageId,
        citation.sourceId,
        citation.ordinal.toString(),
        citation.displayTitle,
        citation.displayPublisher.orEmpty(),
        citation.displayRetrievedAt?.toString().orEmpty(),
        citation.isAvailable.toString(),
        citation.textStart?.toString().orEmpty(),
        citation.textEnd?.toString().orEmpty(),
        citation.textPartOrdinal?.toString().orEmpty(),
        citation.offsetUnit,
        citation.quote.orEmpty(),
        citation.provenance,
        citation.providerMetadataJson,
    ).joinToString("\u0000"),
)

internal fun boundedCitationMetadata(
    metadata: JsonObject,
    maxBytes: Int = MAX_CITATION_PROVIDER_METADATA_BYTES,
): String {
    require(maxBytes >= MIN_CITATION_METADATA_BYTES)
    val effectiveMaxBytes = maxBytes.coerceAtMost(MAX_CITATION_PROVIDER_METADATA_BYTES)
    val sanitized = sanitizeCitationMetadata(metadata) as JsonObject
    val canonical = sanitized.toCanonicalJson()
    val byteCount = canonical.toByteArray(Charsets.UTF_8).size
    if (byteCount <= effectiveMaxBytes) return canonical
    val envelope = JsonObject(
        buildMap {
            put("_rikkahubTruncated", JsonPrimitive(true))
            put("originalUtf8Bytes", JsonPrimitive(byteCount))
            put("originalSha256", JsonPrimitive(sha256Hex(canonical)))
            (sanitized["type"] as? JsonPrimitive)?.contentOrNull?.take(128)
                ?.let { put("type", JsonPrimitive(it)) }
        },
    ).toCanonicalJson()
    return envelope.takeIf { it.utf8Size() <= effectiveMaxBytes } ?: "{}"
}

private fun sanitizeCitationMetadata(
    element: JsonElement,
    key: String? = null,
    depth: Int = 0,
): JsonElement {
    if (depth > MAX_CITATION_METADATA_DEPTH) return JsonPrimitive("[truncated-depth]")
    if (key != null && isSensitiveCitationField(key)) return JsonPrimitive("[redacted]")
    return when (element) {
        JsonNull -> JsonNull
        is JsonObject -> {
            if (element.isSemanticCitationSecretEntry()) {
                JsonObject(mapOf("_rikkahubRedacted" to JsonPrimitive(true)))
            } else {
                val isHeaderMap = key?.let(::normalizeCitationField)
                    ?.let(CITATION_HEADER_CONTAINER_FIELDS::contains) == true
                JsonObject(
                    element.entries
                        .take(MAX_CITATION_METADATA_ENTRIES)
                        .associate { (childKey, value) ->
                            childKey to if (
                                isHeaderMap &&
                                normalizeCitationField(childKey) !in SAFE_CITATION_HEADER_VALUE_NAMES
                            ) {
                                JsonPrimitive("[redacted]")
                            } else {
                                sanitizeCitationMetadata(value, childKey, depth + 1)
                            }
                        },
                )
            }
        }
        is JsonArray -> JsonArray(
            element.take(MAX_CITATION_METADATA_ENTRIES).map { value ->
                sanitizeCitationMetadata(value, depth = depth + 1)
            },
        )
        is JsonPrimitive -> {
            val raw = element.contentOrNull
            if (raw != null && (isCitationUrlField(key) || raw.startsWith("http://") || raw.startsWith("https://"))) {
                JsonPrimitive(canonicalizeCitationUrl(raw) ?: "[redacted-url]")
            } else if (raw != null && citationTextContainsSecret(raw)) {
                JsonPrimitive("[redacted]")
            } else if (raw != null && depth < MAX_CITATION_METADATA_DEPTH) {
                val trimmed = raw.trim()
                val nested = trimmed.takeIf { it.startsWith('{') || it.startsWith('[') }
                    ?.let { runCatching { CITATION_SANITIZER_JSON.parseToJsonElement(it) }.getOrNull() }
                nested?.let {
                    JsonPrimitive(sanitizeCitationMetadata(it, depth = depth + 1).toString())
                } ?: element
            } else {
                element
            }
        }
    }
}

private fun JsonElement?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun displayHost(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

private fun isSafeStableId(value: String): Boolean = SAFE_STABLE_ID.matches(value)

private fun sanitizeCitationQuery(rawQuery: String): String? = rawQuery
    .split('&')
    .filterNot { parameter ->
        val rawKey = parameter.substringBefore('=')
        val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
        isSensitiveCitationField(decodeCitationQueryComponent(rawKey)) ||
            containsCitationCredentialMaterial(decodeCitationQueryComponent(rawValue))
    }
    .joinToString("&")
    .takeIf(String::isNotEmpty)

private fun JsonObject.isSemanticCitationSecretEntry(): Boolean {
    val headerNames = semanticCitationStringValues(SEMANTIC_CITATION_HEADER_NAME_FIELDS)
    if (headerNames.any { name ->
            isSensitiveCitationField(name) || normalizeCitationField(name) in SENSITIVE_CITATION_HEADER_NAMES
        }
    ) {
        return true
    }
    if (hasSemanticCitationField(SEMANTIC_CITATION_VALUE_FIELDS) &&
        headerNames.any { name -> normalizeCitationField(name) !in SAFE_CITATION_HEADER_VALUE_NAMES }
    ) {
        return true
    }
    val pairHeaderName = semanticCitationStringValues(PAIR_CITATION_HEADER_NAME_FIELDS).firstOrNull()
    if (pairHeaderName != null &&
        hasSemanticCitationField(PAIR_CITATION_HEADER_VALUE_FIELDS) &&
        normalizeCitationField(pairHeaderName) !in SAFE_CITATION_HEADER_VALUE_NAMES
    ) {
        return true
    }
    return semanticCitationStringValues(SEMANTIC_CITATION_PARAMETER_NAME_FIELDS)
        .any(::isSensitiveCitationField)
}

private fun JsonObject.semanticCitationStringValues(fields: Set<String>): List<String> = entries
    .mapNotNull { (key, value) ->
        if (normalizeCitationField(key) in fields) (value as? JsonPrimitive)?.contentOrNull else null
    }

private fun JsonObject.hasSemanticCitationField(fields: Set<String>): Boolean =
    keys.any { normalizeCitationField(it) in fields }

private fun containsCitationCredentialMaterial(value: String): Boolean {
    val trimmed = value.trim()
    return CITATION_BEARER_CREDENTIAL.containsMatchIn(trimmed) ||
        CITATION_CREDENTIAL_ASSIGNMENT.containsMatchIn(trimmed)
}

/**
 * Provider-owned display fields are persisted outside the metadata JSON and therefore need the
 * same credential boundary. Detection uses decoded variants, but a safe value is returned exactly
 * as supplied (up to its field budget) so ordinary prose and percent-encoded text remain intact.
 */
private fun sanitizeCitationPersistedText(
    value: String,
    maxLength: Int,
    maxUtf8Bytes: Int = Int.MAX_VALUE,
): String {
    if (citationTextContainsSecret(value)) return "[redacted]"
    val codePointCount = value.codePointCount(0, value.length)
    val endIndex = value.offsetByCodePoints(0, codePointCount.coerceAtMost(maxLength))
    return value.substring(0, endIndex).truncateUtf8(maxUtf8Bytes)
}

private fun String.truncateUtf8(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (utf8Size() <= maxBytes) return this
    val result = StringBuilder(length.coerceAtMost(maxBytes))
    var index = 0
    var usedBytes = 0
    while (index < length) {
        val first = this[index]
        val (codePoint, consumedChars) = when {
            Character.isHighSurrogate(first) && index + 1 < length &&
                Character.isLowSurrogate(this[index + 1]) -> Character.toCodePoint(first, this[index + 1]) to 2
            Character.isSurrogate(first) -> 0xFFFD to 1
            else -> first.code to 1
        }
        val requiredBytes = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        if (usedBytes + requiredBytes > maxBytes) break
        result.appendCodePoint(codePoint)
        usedBytes += requiredBytes
        index += consumedChars
    }
    return result.toString()
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun String.codePointLength(): Int = codePointCount(0, length)

private fun citationTextContainsSecret(value: String): Boolean = citationDecodedVariants(value).any { candidate ->
    containsCitationCredentialMaterial(candidate) || citationJsonContainsSecret(candidate)
}

private fun citationDecodedVariants(value: String): Sequence<String> = sequence {
    var current = value
    yield(current)
    repeat(MAX_CITATION_QUERY_DECODE_PASSES) {
        val decoded = runCatching {
            java.net.URLDecoder.decode(current, Charsets.UTF_8.name())
        }.getOrDefault(current)
        if (decoded == current) return@sequence
        current = decoded
        yield(current)
    }
}

private fun citationJsonContainsSecret(value: String, depth: Int = 0): Boolean {
    val trimmed = value.trim()
    if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return false
    val element = runCatching { CITATION_SANITIZER_JSON.parseToJsonElement(trimmed) }.getOrNull() ?: return false
    return element.containsCitationSecret(depth)
}

private fun JsonElement.containsCitationSecret(depth: Int): Boolean {
    if (depth > MAX_CITATION_METADATA_DEPTH) return false
    return when (this) {
        JsonNull -> false
        is JsonArray -> any { it.containsCitationSecret(depth + 1) }
        is JsonObject -> {
            val semanticNames = semanticCitationStringValues(SEMANTIC_CITATION_HEADER_NAME_FIELDS) +
                semanticCitationStringValues(PAIR_CITATION_HEADER_NAME_FIELDS) +
                semanticCitationStringValues(SEMANTIC_CITATION_PARAMETER_NAME_FIELDS)
            semanticNames.any { name ->
                isSensitiveCitationField(name) ||
                    normalizeCitationField(name) in SENSITIVE_CITATION_HEADER_NAMES
            } || entries.any { (key, child) ->
                isSensitiveCitationField(key) ||
                    normalizeCitationField(key) in SENSITIVE_CITATION_HEADER_NAMES ||
                    child.containsCitationSecret(depth + 1)
            }
        }
        is JsonPrimitive -> contentOrNull?.let { nested ->
            citationDecodedVariants(nested).any { decoded ->
                containsCitationCredentialMaterial(decoded) ||
                    citationJsonContainsSecret(decoded, depth + 1)
            }
        } ?: false
    }
}

private fun decodeCitationQueryComponent(value: String): String {
    var decoded = value
    repeat(MAX_CITATION_QUERY_DECODE_PASSES) {
        val next = runCatching {
            java.net.URLDecoder.decode(decoded, Charsets.UTF_8.name())
        }.getOrDefault(decoded)
        if (next == decoded) return decoded
        decoded = next
    }
    return decoded
}

private fun normalizeCitationField(value: String): String = value
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

private fun isSensitiveCitationField(key: String): Boolean {
    val normalized = normalizeCitationField(key)
    return normalized in SENSITIVE_CITATION_FIELDS ||
        normalized.endsWith("token") ||
        normalized.endsWith("secret") ||
        normalized.endsWith("password") ||
        normalized.endsWith("passwd") ||
        normalized.endsWith("credential") ||
        normalized.endsWith("signature") ||
        normalized.contains("apikey")
}

private fun isCitationUrlField(key: String?): Boolean = key
    ?.lowercase(Locale.ROOT)
    ?.filter(Char::isLetterOrDigit)
    ?.let { it in CITATION_URL_FIELDS }
    ?: false

private val SAFE_STABLE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val SHORT_CITATION_ID = Regex("[A-Za-z0-9]{6}")
private val CITATION_MARKER = Regex("\\[citation,([^]\\r\\n]+)]\\(([A-Za-z0-9]{6})\\)")
private val CITATION_SANITIZER_JSON = Json { ignoreUnknownKeys = true }
private val CITATION_BEARER_CREDENTIAL = Regex("(?i)\\bbearer(?:\\s+|%20|\\+)[^\\s,;\\\"']+")
private val CITATION_CREDENTIAL_ASSIGNMENT = Regex(
    "(?i)\\b(?:authorization|proxy[-_ ]?authorization|api[-_ ]?key|x[-_ ]?api[-_ ]?key|" +
        "access[-_ ]?token|refresh[-_ ]?token|session[-_ ]?token|token|secret|password|passwd|" +
        "signature|x[-_ ]?amz[-_ ]?signature|x[-_ ]?goog[-_ ]?signature)[\\\"']?" +
        "\\s*[:=]\\s*[^\\s,;]+",
)
private val SEMANTIC_CITATION_HEADER_NAME_FIELDS = setOf("name", "header", "headername")
private val SEMANTIC_CITATION_PARAMETER_NAME_FIELDS = setOf("key", "parameter", "parametername")
private val SEMANTIC_CITATION_VALUE_FIELDS = setOf("value", "values", "headervalue", "headervalues")
private val PAIR_CITATION_HEADER_NAME_FIELDS = setOf("first")
private val PAIR_CITATION_HEADER_VALUE_FIELDS = setOf("second")
private val SAFE_CITATION_HEADER_VALUE_NAMES = setOf("accept", "acceptencoding", "contenttype", "useragent")
private val CITATION_HEADER_CONTAINER_FIELDS = setOf("headers", "customheaders", "mcpheaders", "requestheaders")
private val SENSITIVE_CITATION_HEADER_NAMES = setOf(
    "authorization",
    "proxyauthorization",
    "cookie",
    "setcookie",
    "xapikey",
)
private val SENSITIVE_CITATION_FIELDS = setOf(
    "authorization",
    "auth",
    "bearer",
    "key",
    "apikey",
    "xapikey",
    "accesstoken",
    "refreshtoken",
    "token",
    "secret",
    "password",
    "passwd",
    "clientsecret",
    "privatekey",
    "accesskey",
    "secretkey",
    "credential",
    "signature",
    "sig",
    "session",
    "sessionid",
    "jwt",
    "code",
    "xgoogsignature",
    "xamzsignature",
    "xamzcredential",
    "xamzsecuritytoken",
)
private val CITATION_URL_FIELDS = setOf("url", "uri", "href", "link", "canonicalurl")
private const val MAX_CITATION_URL_LENGTH = 8 * 1024
private const val MAX_CITATION_TITLE_LENGTH = 512
private const val MAX_CITATION_TEXT_LENGTH = 16 * 1024
private const val MAX_CITATION_OFFSET_UNIT_LENGTH = 64
private const val MAX_CITATION_PROVENANCE_LENGTH = 64
internal const val MAX_CITATIONS_PER_MESSAGE = 128
internal const val MAX_CITATION_PROVIDER_METADATA_BYTES = 8 * 1024
internal const val MAX_CITATION_PROVIDER_METADATA_BYTES_PER_MESSAGE = 256 * 1024
internal const val MAX_CITATION_QUOTE_BYTES = 4 * 1024
internal const val MAX_CITATION_QUOTE_BYTES_PER_MESSAGE = 64 * 1024
private const val MAX_CITATION_SNIPPET_BYTES = 4 * 1024
private const val MIN_CITATION_METADATA_BYTES = 2
private const val MAX_CITATION_METADATA_DEPTH = 16
private const val MAX_CITATION_METADATA_ENTRIES = 4_096
private const val MAX_CITATION_QUERY_DECODE_PASSES = 3
private const val MAX_SEARCH_CITATION_PAYLOAD_CHARS = 512 * 1024L

package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.conversation.canonicalizeCitationUrl
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Last-mile policy for citation data leaving the trusted app process.
 *
 * Provider metadata is intentionally open-ended, so persistence-time filtering cannot be the only
 * security boundary. Web DTOs, clipboard text and exports all pass through this object before
 * exposing citation data to another process. Safe presentation fields remain available while
 * credentials, signed URL parameters and semantic header entries are removed.
 */
internal object CitationEgressSanitizer {
    fun sanitizeAnnotations(annotations: List<UIMessageAnnotation>): List<UIMessageAnnotation> =
        annotations.mapNotNull { annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> sanitize(annotation)
                is UIMessageAnnotation.ProviderToolEvent -> null
            }
        }

    fun sanitize(citation: UIMessageAnnotation.UrlCitation): UIMessageAnnotation.UrlCitation {
        val safeUrl = sanitizeUrl(citation.url)
        return citation.copy(
            title = sanitizeDisplayText(citation.title).orEmpty(),
            url = safeUrl.orEmpty(),
            publisher = citation.publisher?.let(::sanitizeDisplayText),
            quote = citation.quote?.let(::sanitizeDisplayText),
            // URL safety and source liveness are separate facts. An unsafe URL becomes
            // non-navigable, but a safe title/publisher may still be shown to the user.
            isAvailable = citation.isAvailable,
            providerMetadata = citation.providerMetadata
                ?.let(::sanitizeMetadata)
                ?.takeUnless(JsonObject::isEmpty),
        )
    }

    fun sanitizeMetadata(metadata: JsonObject): JsonObject =
        sanitizeElement(metadata, depth = 0) as? JsonObject ?: JsonObject(emptyMap())

    fun sanitizeMessageParts(parts: List<UIMessagePart>): List<UIMessagePart> =
        sanitizeMessageParts(parts, toolPayload = false)

    fun sanitizeToolPayloadText(raw: String): String {
        val trimmed = raw.trim()
        val structured = trimmed.takeIf { it.startsWith('{') || it.startsWith('[') }
            ?.let { runCatching { SANITIZER_JSON.parseToJsonElement(it) }.getOrNull() }
        if (structured != null) return sanitizeElement(structured, depth = 0).toString()
        if (containsCredentialMaterial(raw)) return REDACTED_VALUE
        return HTTP_URL_IN_TEXT.replace(raw) { match -> sanitizeUrl(match.value) ?: REDACTED_URL }
    }

    fun sanitizeDiagnosticText(raw: String?, fallback: String): String {
        val safe = raw?.takeIf(String::isNotBlank)?.let(::sanitizeToolPayloadText)
        return safe?.takeUnless { it == REDACTED_VALUE || it.isBlank() } ?: fallback
    }

    fun sanitizeUrl(raw: String): String? {
        val canonical = canonicalizeCitationUrl(raw) ?: return null
        val parsed = runCatching { URI(canonical) }.getOrNull() ?: return null
        val rawQuery = parsed.rawQuery ?: return canonical
        val safeParameters = rawQuery.split('&').filterNot { parameter ->
            val rawKey = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
            isSensitiveField(decodeQueryComponent(rawKey)) ||
                containsCredentialMaterial(decodeQueryComponent(rawValue))
        }
        val base = canonical.substringBefore('?')
        return safeParameters.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "&", prefix = "$base?")
            ?: base
    }

    fun sanitizeResourceUrl(raw: String): String {
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return sanitizeUrl(raw).orEmpty()
        }
        val withoutFragment = raw.substringBefore('#')
        val rawQuery = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (rawQuery.isEmpty()) return withoutFragment
        val safeParameters = rawQuery.split('&').filterNot { parameter ->
            val rawKey = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
            isSensitiveField(decodeQueryComponent(rawKey)) ||
                containsCredentialMaterial(decodeQueryComponent(rawValue))
        }
        val base = withoutFragment.substringBefore('?')
        return safeParameters.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "&", prefix = "$base?")
            ?: base
    }

    /** Returns null instead of emitting a display field that itself contains credential material. */
    fun sanitizeDisplayText(raw: String): String? {
        val trimmed = raw.trim().take(MAX_DISPLAY_TEXT_LENGTH)
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return sanitizeUrl(trimmed)
        }
        if (containsCredentialMaterial(trimmed) ||
            containsCredentialMaterial(decodeQueryComponent(trimmed))
        ) {
            return null
        }

        val structured = trimmed.takeIf { it.startsWith('{') || it.startsWith('[') }
            ?.let { runCatching { SANITIZER_JSON.parseToJsonElement(it) }.getOrNull() }
        if (structured != null) {
            val sanitized = sanitizeElement(structured, depth = 0)
            if (sanitized.toString().contains(REDACTED_MARKER)) return null
            return sanitized.toString()
        }
        return trimmed
    }

    private fun sanitizeElement(
        element: JsonElement,
        key: String? = null,
        depth: Int,
    ): JsonElement {
        if (depth > MAX_METADATA_DEPTH) return JsonPrimitive(TRUNCATED_DEPTH)
        if (key != null && isSensitiveField(key)) return JsonNull
        return when (element) {
            JsonNull -> JsonNull
            is JsonObject -> sanitizeObject(element, key, depth)
            is JsonArray -> JsonArray(
                element.take(MAX_METADATA_ENTRIES).map { child ->
                    val childKey = key?.takeUnless {
                        normalize(it) in HEADER_CONTAINER_FIELDS &&
                            child is JsonObject &&
                            child.hasSemanticHeaderShape()
                    }
                    sanitizeElement(child, key = childKey, depth = depth + 1)
                },
            )
            is JsonPrimitive -> sanitizePrimitive(element, key, depth)
        }
    }

    private fun sanitizeObject(element: JsonObject, parentKey: String?, depth: Int): JsonObject {
        if (element.isSemanticSecretEntry()) {
            return JsonObject(mapOf(REDACTED_MARKER to JsonPrimitive(true)))
        }
        val isHeaderMap = parentKey?.let(::normalize)?.let(HEADER_CONTAINER_FIELDS::contains) == true
        return JsonObject(
            element.entries
                .asSequence()
                .take(MAX_METADATA_ENTRIES)
                .filterNot { (key, _) ->
                    isSensitiveField(key) || (isHeaderMap && normalize(key) !in SAFE_HEADER_VALUE_NAMES)
                }
                .map { (key, value) -> key to sanitizeElement(value, key, depth + 1) }
                .filterNot { (_, value) -> value === JsonNull }
                .toMap(linkedMapOf()),
        )
    }

    @Suppress("DEPRECATION")
    private fun sanitizeMessageParts(
        parts: List<UIMessagePart>,
        toolPayload: Boolean,
    ): List<UIMessagePart> = parts.mapNotNull { part ->
        val metadata = part.metadata
            ?.let(::sanitizeMetadata)
            ?.takeUnless(JsonObject::isEmpty)
        when (part) {
            is UIMessagePart.Text -> part.copy(
                text = if (toolPayload) sanitizeToolPayloadText(part.text) else part.text,
                metadata = metadata,
            )
            is UIMessagePart.Image -> part.copy(
                url = sanitizeResourceUrl(part.url),
                metadata = metadata,
            )
            is UIMessagePart.Video -> part.copy(
                url = sanitizeResourceUrl(part.url),
                metadata = metadata,
            )
            is UIMessagePart.Audio -> part.copy(
                url = sanitizeResourceUrl(part.url),
                metadata = metadata,
            )
            is UIMessagePart.Document -> part.copy(
                url = sanitizeResourceUrl(part.url),
                metadata = metadata,
            )
            is UIMessagePart.Reasoning -> part.copy(
                reasoning = if (toolPayload) sanitizeToolPayloadText(part.reasoning) else part.reasoning,
                metadata = metadata,
            )
            is UIMessagePart.ProviderOpaque -> null
            UIMessagePart.Search -> part.takeIf { part.metadata == null }
            is UIMessagePart.ToolCall -> part.copy(
                arguments = sanitizeToolPayloadText(part.arguments),
                metadata = metadata,
            )
            is UIMessagePart.ToolResult -> part.copy(
                content = sanitizeElement(part.content, depth = 0),
                arguments = sanitizeElement(part.arguments, depth = 0),
                metadata = metadata,
            )
            is UIMessagePart.Tool -> part.copy(
                input = sanitizeToolPayloadText(part.input),
                output = sanitizeMessageParts(part.output, toolPayload = true),
                progress = sanitizeMessageParts(part.progress, toolPayload = true),
                metadata = metadata,
            )
        }
    }

    private fun sanitizePrimitive(element: JsonPrimitive, key: String?, depth: Int): JsonElement {
        if (!element.isString) return element
        val raw = element.contentOrNull.orEmpty()
        if (isUrlField(key) || raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return JsonPrimitive(sanitizeUrl(raw) ?: REDACTED_URL)
        }
        if (containsCredentialMaterial(raw)) return JsonPrimitive(REDACTED_VALUE)
        val trimmed = raw.trim()
        if ((trimmed.startsWith('{') || trimmed.startsWith('[')) && depth < MAX_METADATA_DEPTH) {
            val nested = runCatching { SANITIZER_JSON.parseToJsonElement(trimmed) }.getOrNull()
            if (nested != null) return JsonPrimitive(sanitizeElement(nested, depth = depth + 1).toString())
        }
        return JsonPrimitive(raw.take(MAX_METADATA_STRING_LENGTH))
    }

    private fun JsonObject.isSemanticSecretEntry(): Boolean {
        val headerNames = semanticStringValues(SEMANTIC_HEADER_NAME_FIELDS)
        if (headerNames.any { name -> isSensitiveField(name) || normalize(name) in SENSITIVE_HEADER_NAMES }) {
            return true
        }
        if (hasSemanticField(SEMANTIC_VALUE_FIELDS) &&
            headerNames.any { name -> normalize(name) !in SAFE_HEADER_VALUE_NAMES }
        ) {
            return true
        }
        val pairHeaderName = semanticStringValues(PAIR_HEADER_NAME_FIELDS).firstOrNull()
        if (pairHeaderName != null &&
            hasSemanticField(PAIR_HEADER_VALUE_FIELDS) &&
            normalize(pairHeaderName) !in SAFE_HEADER_VALUE_NAMES
        ) {
            return true
        }
        return semanticStringValues(SEMANTIC_PARAMETER_NAME_FIELDS).any(::isSensitiveField)
    }

    private fun JsonObject.hasSemanticHeaderShape(): Boolean =
        semanticStringValues(SEMANTIC_HEADER_NAME_FIELDS).isNotEmpty()

    private fun JsonObject.semanticStringValues(fields: Set<String>): List<String> = entries
        .mapNotNull { (key, value) ->
            if (normalize(key) in fields) (value as? JsonPrimitive)?.contentOrNull else null
        }

    private fun JsonObject.hasSemanticField(fields: Set<String>): Boolean = keys.any { normalize(it) in fields }

    private fun containsCredentialMaterial(value: String): Boolean {
        val trimmed = value.trim()
        return BEARER_CREDENTIAL.containsMatchIn(trimmed) ||
            CREDENTIAL_ASSIGNMENT.containsMatchIn(trimmed)
    }

    private fun isSensitiveField(raw: String): Boolean {
        val normalized = normalize(raw)
        return normalized in SENSITIVE_FIELDS ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password") ||
            normalized.endsWith("passwd") ||
            normalized.endsWith("credential") ||
            normalized.endsWith("signature") ||
            normalized.contains("apikey")
    }

    private fun isUrlField(raw: String?): Boolean = raw
        ?.let(::normalize)
        ?.let(URL_FIELDS::contains)
        ?: false

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun decodeQueryComponent(value: String): String {
        var decoded = value
        repeat(MAX_QUERY_DECODE_PASSES) {
            val next = runCatching { URLDecoder.decode(decoded, Charsets.UTF_8.name()) }.getOrDefault(decoded)
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private val SANITIZER_JSON = Json { ignoreUnknownKeys = true }
    private val BEARER_CREDENTIAL = Regex("(?i)\\bbearer(?:\\s+|%20|\\+)[^\\s,;\\\"']+")
    private val CREDENTIAL_ASSIGNMENT = Regex(
        "(?i)\\b(?:authorization|proxy[-_ ]?authorization|api[-_ ]?key|x[-_ ]?api[-_ ]?key|" +
            "access[-_ ]?token|refresh[-_ ]?token|session[-_ ]?token|token|secret|password|passwd|" +
            "signature|x[-_ ]?amz[-_ ]?signature|x[-_ ]?goog[-_ ]?signature)[\\\"']?" +
            "\\s*[:=]\\s*[^\\s,;]+",
    )
    private val HTTP_URL_IN_TEXT = Regex("https?://[^\\s<>\\]})]+", RegexOption.IGNORE_CASE)
    private val SEMANTIC_HEADER_NAME_FIELDS = setOf("name", "header", "headername")
    private val SEMANTIC_PARAMETER_NAME_FIELDS = setOf("key", "parameter", "parametername")
    private val SEMANTIC_VALUE_FIELDS = setOf("value", "values", "headervalue", "headervalues")
    private val PAIR_HEADER_NAME_FIELDS = setOf("first")
    private val PAIR_HEADER_VALUE_FIELDS = setOf("second")
    private val SAFE_HEADER_VALUE_NAMES = setOf("accept", "acceptencoding", "contenttype", "useragent")
    private val HEADER_CONTAINER_FIELDS = setOf("headers", "customheaders", "mcpheaders", "requestheaders")
    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "proxyauthorization",
        "cookie",
        "setcookie",
        "xapikey",
    )
    private val SENSITIVE_FIELDS = setOf(
        "authorization",
        "proxyauthorization",
        "auth",
        "bearer",
        "key",
        "apikey",
        "xapikey",
        "accesstoken",
        "refreshtoken",
        "sessiontoken",
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
        "cookie",
        "setcookie",
        "xgoogsignature",
        "xamzsignature",
        "xamzcredential",
        "xamzsecuritytoken",
    )
    private val URL_FIELDS = setOf("url", "uri", "href", "link", "canonicalurl")

    private const val REDACTED_MARKER = "_rikkahubRedacted"
    private const val REDACTED_VALUE = "[redacted]"
    private const val REDACTED_URL = "[redacted-url]"
    private const val TRUNCATED_DEPTH = "[truncated-depth]"
    private const val MAX_METADATA_DEPTH = 16
    private const val MAX_METADATA_ENTRIES = 4_096
    private const val MAX_METADATA_STRING_LENGTH = 16 * 1024
    private const val MAX_DISPLAY_TEXT_LENGTH = 16 * 1024
    private const val MAX_QUERY_DECODE_PASSES = 3
}

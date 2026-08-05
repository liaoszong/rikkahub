package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toPortableText())
}

/** Lossless-enough plain text for clipboard/share surfaces that cannot carry annotation objects. */
fun UIMessage.toPortableText(): String = buildString {
    append(toText())
    val citations = portableCitations()
    if (citations.isNotEmpty()) {
        append("\n\nSources:\n")
        citations.forEachIndexed { index, citation ->
            append('[').append(index + 1).append("] ")
            val safeUrl = citation.url.safeHttpUrlOrNull().takeIf { citation.isAvailable }
            val label = if (citation.isAvailable) {
                citation.title.ifBlank { citation.publisher ?: safeUrl ?: "Source unavailable" }
            } else {
                "Source unavailable"
            }
            append(label.replace(Regex("[\\r\\n]+"), " "))
            safeUrl?.let { append(" — ").append(it) }
            if (index != citations.lastIndex) append('\n')
        }
    }
}

fun UIMessage.portableCitations(): List<UIMessageAnnotation.UrlCitation> = annotations
    .filterIsInstance<UIMessageAnnotation.UrlCitation>()
    .map(CitationEgressSanitizer::sanitize)
    .distinctBy { it.sourceId ?: it.url.ifBlank { it.citationId ?: it.title } }

fun resolveMessageCitationUrl(
    citationId: String,
    annotations: List<UIMessageAnnotation>,
    parts: List<UIMessagePart>,
): String? {
    val stableCitations = annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()
        .filter { it.citationId != null || it.sourceId != null }
    stableCitations.firstOrNull { citation ->
        citation.citationId == citationId ||
            citation.sourceId == citationId ||
            (citation.providerMetadata?.get("legacyShortId") as? JsonPrimitive)?.contentOrNull == citationId
    }?.let { authority ->
        return authority.url.safeHttpUrlOrNull().takeIf { authority.isAvailable }
    }
    // Once Room 31 authority exists for this message, an unknown or tombstoned reference
    // must not be resurrected from the legacy search tool payload.
    if (stableCitations.isNotEmpty()) return null

    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == "search_web" && it.isExecuted }
        .forEach { tool ->
            val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val items = runCatching {
                (JsonInstant.parseToJsonElement(outputText) as? JsonObject)
                    ?.get("items") as? JsonArray
            }.getOrNull() ?: return@forEach
            items.forEach itemLoop@ { item ->
                val itemObject = item as? JsonObject ?: return@itemLoop
                if ((itemObject["id"] as? JsonPrimitive)?.contentOrNull != citationId) return@itemLoop
                return (itemObject["url"] as? JsonPrimitive)?.contentOrNull?.safeHttpUrlOrNull()
            }
        }
    return null
}

fun String.safeHttpUrlOrNull(): String? {
    val raw = trim()
    if (raw.isEmpty() || raw.length > 8 * 1024) return null
    return CitationEgressSanitizer.sanitizeUrl(raw)
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip"
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml",
    "py", "rb", "lua", "sql", "java", "kt", "ts", "tsx",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "rs", "cs", "markdown", "mdx",
    "toml", "ini", "env", "gradle", "kts", "properties",
    "proto", "graphql", "gql", "yml", "yaml"
)

fun isAllowedFileType(fileName: String, mime: String): Boolean {
    if (mime in ALLOWED_MIME_TYPES || mime.startsWith("text/")) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in ALLOWED_FILE_EXTENSIONS
}

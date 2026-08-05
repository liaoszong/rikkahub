package me.rerere.common.android

import android.util.Log
import java.net.URI
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100
private const val MAX_STABLE_FIELD_LENGTH = 128
private const val REDACTED = "<redacted>"

private val stableFieldPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
private val sensitiveFieldPattern = Regex(
    pattern = "authorization|bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|cookie",
    option = RegexOption.IGNORE_CASE,
)
private val safeErrorClassPattern = Regex("[A-Za-z_][A-Za-z0-9_.$]{0,127}")
private val safeContentTypePattern = Regex(
    "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;\\s*charset=[A-Za-z0-9._-]+)?",
)
private val safeBodyDescriptionPattern = Regex(
    "<redacted;\\s*contentType=([^;<>]{1,96});\\s*contentLength=(-?[0-9]{1,20})>",
)
private val uriSchemePattern = Regex("[a-z][a-z0-9+.-]{0,31}")
private val headerNamePattern = Regex("[A-Za-z0-9-]{1,64}")
private val contentLengthPattern = Regex("[0-9]{1,20}")

enum class SafeLogOutcome(val wireValue: String) {
    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REJECTED("rejected"),
}

enum class SafeLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false

    /**
     * Compatibility entry point for legacy free-form logs.
     *
     * Free-form text can contain prompts, response bodies, credentials, URIs, or Throwable output,
     * so it is deliberately not retained. New callers should use [logOperation] or [logError].
     */
    fun log(tag: String, message: String) {
        addLog(
            LogEntry.TextLog(
                tag = sanitizeStableField(tag, fallback = "unknown"),
                message = "event=legacy_text_redacted inputLength=${message.length}",
            )
        )
    }

    /**
     * Stores an allowlisted, structured operation event and returns the exact same safe text for
     * callers that also need to emit it to Logcat. Arbitrary metadata is intentionally unsupported.
     */
    fun logOperation(
        tag: String,
        domain: String,
        operation: String,
        outcome: SafeLogOutcome,
        requestId: String? = null,
        httpStatus: Int? = null,
        itemCount: Int? = null,
    ): String {
        val message = safeOperationMessage(
            domain = domain,
            operation = operation,
            outcome = outcome,
            requestId = requestId,
            httpStatus = httpStatus,
            itemCount = itemCount,
        )
        addLog(
            LogEntry.TextLog(
                tag = sanitizeStableField(tag, fallback = "unknown"),
                message = message,
            )
        )
        return message
    }

    fun safeOperationMessage(
        domain: String,
        operation: String,
        outcome: SafeLogOutcome,
        requestId: String? = null,
        httpStatus: Int? = null,
        itemCount: Int? = null,
    ): String = renderOperation(
        domain = domain,
        operation = operation,
        outcome = outcome,
        requestId = requestId,
        httpStatus = httpStatus,
        itemCount = itemCount,
    )

    fun logOperationToLogcat(
        tag: String,
        domain: String,
        operation: String,
        outcome: SafeLogOutcome,
        level: SafeLogLevel = SafeLogLevel.INFO,
        requestId: String? = null,
        httpStatus: Int? = null,
        itemCount: Int? = null,
        persist: Boolean = true,
    ): String {
        val message = if (persist) {
            logOperation(tag, domain, operation, outcome, requestId, httpStatus, itemCount)
        } else {
            safeOperationMessage(domain, operation, outcome, requestId, httpStatus, itemCount)
        }
        emitLogcat(tag = tag, level = level, message = message)
        return message
    }

    /**
     * Records only Throwable type information. Throwable messages, causes' messages, and stack
     * traces are never read, formatted, or retained.
     */
    fun logError(
        tag: String,
        domain: String,
        operation: String,
        error: Throwable,
        requestId: String? = null,
        httpStatus: Int? = null,
    ): String {
        val message = safeErrorMessage(
            domain = domain,
            operation = operation,
            error = error,
            requestId = requestId,
            httpStatus = httpStatus,
        )
        addLog(
            LogEntry.TextLog(
                tag = sanitizeStableField(tag, fallback = "unknown"),
                message = message,
            )
        )
        return message
    }

    fun safeErrorMessage(
        domain: String,
        operation: String,
        error: Throwable,
        requestId: String? = null,
        httpStatus: Int? = null,
    ): String = buildString {
        append(
            renderOperation(
                domain = domain,
                operation = operation,
                outcome = SafeLogOutcome.FAILED,
                requestId = requestId,
                httpStatus = httpStatus,
                itemCount = null,
            )
        )
        append(" errorClass=")
        append(sanitizeErrorClass(error.javaClass.simpleName))
        error.cause
            ?.takeUnless { cause -> cause === error }
            ?.javaClass
            ?.simpleName
            ?.let { causeClass ->
                append(" causeClass=")
                append(sanitizeErrorClass(causeClass))
            }
    }

    fun logErrorToLogcat(
        tag: String,
        domain: String,
        operation: String,
        error: Throwable,
        level: SafeLogLevel = SafeLogLevel.ERROR,
        requestId: String? = null,
        httpStatus: Int? = null,
        persist: Boolean = true,
    ): String {
        val message = if (persist) {
            logError(tag, domain, operation, error, requestId, httpStatus)
        } else {
            safeErrorMessage(domain, operation, error, requestId, httpStatus)
        }
        emitLogcat(tag = tag, level = level, message = message)
        return message
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(
            entry.copy(
                tag = sanitizeStableField(entry.tag, fallback = "HTTP"),
                url = sanitizeRequestUri(entry.url),
                method = sanitizeHttpMethod(entry.method),
                requestHeaders = sanitizeHeaders(entry.requestHeaders),
                requestBody = sanitizeBodyDescription(entry.requestBody),
                responseHeaders = sanitizeHeaders(entry.responseHeaders),
                error = sanitizeRequestError(entry.error),
            )
        )
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }

    private fun renderOperation(
        domain: String,
        operation: String,
        outcome: SafeLogOutcome,
        requestId: String?,
        httpStatus: Int?,
        itemCount: Int?,
    ): String = buildString {
        append("event=operation")
        append(" domain=")
        append(sanitizeStableField(domain, fallback = "unknown"))
        append(" operation=")
        append(sanitizeStableField(operation, fallback = "unknown"))
        append(" outcome=")
        append(outcome.wireValue)
        sanitizeCorrelationId(requestId)?.let { safeRequestId ->
            append(" requestId=")
            append(safeRequestId)
        }
        httpStatus?.takeIf { it in 100..599 }?.let { safeStatus ->
            append(" httpStatus=")
            append(safeStatus)
        }
        itemCount?.takeIf { it >= 0 }?.let { safeCount ->
            append(" itemCount=")
            append(safeCount)
        }
    }

    private fun emitLogcat(tag: String, level: SafeLogLevel, message: String) {
        val safeTag = sanitizeStableField(tag, fallback = "App")
        when (level) {
            SafeLogLevel.DEBUG -> Log.d(safeTag, message)
            SafeLogLevel.INFO -> Log.i(safeTag, message)
            SafeLogLevel.WARN -> Log.w(safeTag, message)
            SafeLogLevel.ERROR -> Log.e(safeTag, message)
        }
    }
}

private fun sanitizeStableField(value: String, fallback: String): String {
    val candidate = value.trim().take(MAX_STABLE_FIELD_LENGTH)
    return candidate.takeIf { stableFieldPattern.matches(it) && !sensitiveFieldPattern.containsMatchIn(it) }
        ?: fallback
}

private fun sanitizeCorrelationId(value: String?): String? {
    value ?: return null
    val candidate = value.trim().take(MAX_STABLE_FIELD_LENGTH)
    return candidate.takeIf { stableFieldPattern.matches(it) && !sensitiveFieldPattern.containsMatchIn(it) }
}

private fun sanitizeErrorClass(value: String): String {
    val candidate = value.take(MAX_STABLE_FIELD_LENGTH)
    return candidate.takeIf { safeErrorClassPattern.matches(it) && !sensitiveFieldPattern.containsMatchIn(it) }
        ?: "UnknownError"
}

private fun sanitizeHttpMethod(value: String): String = when (val method = value.trim().uppercase()) {
    "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT" -> method
    else -> "OTHER"
}

private fun sanitizeRequestUri(value: String): String {
    val raw = value.trim()
    val scheme = raw.substringBefore(':', missingDelimiterValue = "")
        .lowercase()
        .takeIf(uriSchemePattern::matches)
        ?: return "<redacted-uri>"
    if (scheme != "http" && scheme != "https") {
        return "<redacted-uri scheme=$scheme>"
    }
    return runCatching {
        // The upstream interceptor may already have replaced a query with this marker. URI rejects
        // angle brackets, so use a non-sensitive parse-only placeholder before applying this
        // second, authoritative persistence boundary.
        val uri = URI(raw.replace(REDACTED, "redacted"))
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return@runCatching "<redacted-uri>"
        buildString {
            append(scheme)
            append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            if (uri.port >= 0 && !isDefaultPort(scheme, uri.port)) append(':').append(uri.port)
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") append("/<redacted>")
            if (uri.rawQuery != null) append("?<redacted>")
        }
    }.getOrDefault("<redacted-uri>")
}

private fun isDefaultPort(scheme: String, port: Int): Boolean =
    (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> = headers.entries.associate { entry ->
    val name = entry.key.trim().take(64).takeIf(headerNamePattern::matches) ?: "<invalid>"
    val value = when (name.lowercase()) {
        "content-length" -> entry.value.trim().takeIf(contentLengthPattern::matches) ?: REDACTED
        "content-type" -> entry.value.trim().takeIf(safeContentTypePattern::matches) ?: REDACTED
        "accept", "user-agent" -> "<present>"
        else -> REDACTED
    }
    name to value
}

private fun sanitizeBodyDescription(value: String?): String? {
    value ?: return null
    val match = safeBodyDescriptionPattern.matchEntire(value.trim()) ?: return REDACTED
    val contentType = match.groupValues[1].trim().takeIf(safeContentTypePattern::matches) ?: "unknown"
    val contentLength = match.groupValues[2].toLongOrNull()?.takeIf { it >= -1L } ?: -1L
    return "<redacted; contentType=$contentType; contentLength=$contentLength>"
}

private fun sanitizeRequestError(value: String?): String? {
    value ?: return null
    val candidate = value.trim().take(MAX_STABLE_FIELD_LENGTH)
    val isErrorType = candidate.endsWith("Exception") || candidate.endsWith("Error")
    return candidate.takeIf {
        isErrorType && safeErrorClassPattern.matches(it) && !sensitiveFieldPattern.containsMatchIn(it)
    } ?: REDACTED
}

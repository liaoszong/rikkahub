package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = HttpLogSanitizer.sanitizeHeaders(request.headers)
        val requestBody = HttpLogSanitizer.describeBody(request.body)

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.javaClass.simpleName
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = HttpLogSanitizer.sanitizeUrl(request.url),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = error,
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = HttpLogSanitizer.sanitizeHeaders(response.headers)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = HttpLogSanitizer.sanitizeUrl(request.url),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

}

internal object HttpLogSanitizer {
    private val safeHeaderNames = setOf(
        "accept",
        "content-length",
        "content-type",
        "user-agent",
    )

    fun sanitizeUrl(url: okhttp3.HttpUrl): String = buildString {
        append(url.scheme)
        append("://")
        append(url.host)
        val isDefaultPort = (url.scheme == "http" && url.port == 80) ||
            (url.scheme == "https" && url.port == 443)
        if (!isDefaultPort) append(':').append(url.port)
        append(url.encodedPath)
        if (url.querySize > 0) append("?<redacted>")
    }

    fun sanitizeHeaders(headers: Headers): Map<String, String> = headers.names().associateWith { name ->
        if (name.lowercase() in safeHeaderNames) headers[name].orEmpty() else "<redacted>"
    }

    fun describeBody(body: RequestBody?): String? {
        body ?: return null
        val length = runCatching { body.contentLength() }.getOrDefault(-1L)
        return "<redacted; contentType=${body.contentType() ?: "unknown"}; contentLength=$length>"
    }
}

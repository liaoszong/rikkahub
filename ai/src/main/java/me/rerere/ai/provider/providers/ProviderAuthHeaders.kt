package me.rerere.ai.provider.providers

import me.rerere.ai.provider.CustomHeader
import okhttp3.Headers

/**
 * Builds request headers with user headers taking precedence over adapter credentials.
 * Blank fallback values are never emitted, so unauthenticated endpoints do not receive
 * syntactically present but empty credential headers.
 */
internal fun providerAuthHeaders(
    customHeaders: List<CustomHeader>,
    vararg fallbacks: Pair<String, String?>,
): Headers {
    val headers = Headers.Builder()
    customHeaders
        .filter { it.name.isNotBlank() && it.value.isNotBlank() }
        .forEach { headers[it.name] = it.value }
    fallbacks.forEach { (name, value) ->
        if (value.isNullOrBlank() || headers[name] != null) return@forEach
        headers[name] = value
    }
    return headers.build()
}

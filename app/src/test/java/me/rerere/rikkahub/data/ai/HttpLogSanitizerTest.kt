package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpLogSanitizerTest {
    @Test
    fun `url query values are never logged`() {
        val sanitized = HttpLogSanitizer.sanitizeUrl(
            "https://api.example.com/v1/models?key=top-secret&prompt=private".toHttpUrl()
        )

        assertEquals("https://api.example.com/v1/models?<redacted>", sanitized)
        assertFalse(sanitized.contains("top-secret"))
        assertFalse(sanitized.contains("private"))
    }

    @Test
    fun `only allowlisted header values remain visible`() {
        val sanitized = HttpLogSanitizer.sanitizeHeaders(
            Headers.headersOf(
                "Authorization", "Bearer secret",
                "X-Api-Key", "secret-key",
                "Content-Type", "application/json",
            )
        )

        assertEquals("<redacted>", sanitized["Authorization"])
        assertEquals("<redacted>", sanitized["X-Api-Key"])
        assertEquals("application/json", sanitized["Content-Type"])
    }

    @Test
    fun `body logging records metadata without content`() {
        val description = HttpLogSanitizer.describeBody(
            "private prompt".toRequestBody("application/json".toMediaType())
        ).orEmpty()

        assertTrue(description.contains("contentLength=14"))
        assertFalse(description.contains("private prompt"))
    }
}

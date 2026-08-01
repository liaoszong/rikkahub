package me.rerere.ai.provider.providers

import me.rerere.ai.provider.CustomHeader
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderAuthHeadersTest {
    @Test
    fun `openai custom authorization replaces provider bearer without duplicates`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(CustomHeader("authorization", "Custom token")),
                "Authorization" to "Bearer provider-token",
            )
        )

        assertEquals(listOf("Custom token"), request.headers.values("Authorization"))
    }

    @Test
    fun `blank custom authorization does not suppress provider bearer`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(CustomHeader("Authorization", "   ")),
                "Authorization" to "Bearer provider-token",
            )
        )

        assertEquals(listOf("Bearer provider-token"), request.headers.values("Authorization"))
    }

    @Test
    fun `blank custom api key does not suppress provider api key`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(CustomHeader("X-API-Key", "")),
                "x-api-key" to "provider-key",
            )
        )

        assertEquals(listOf("provider-key"), request.headers.values("X-API-Key"))
    }

    @Test
    fun `openai x api key can be the only credential without blank bearer`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(CustomHeader("X-API-Key", "gateway-token")),
                "Authorization" to null,
            )
        )

        assertEquals("gateway-token", request.header("X-API-Key"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `google custom api key wins and no empty adapter key is emitted`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(CustomHeader("x-goog-api-key", "google-custom")),
                "x-goog-api-key" to "",
            )
        )

        assertEquals(listOf("google-custom"), request.headers.values("x-goog-api-key"))
    }

    @Test
    fun `claude custom key and version each remain singular`() {
        val request = requestWith(
            providerAuthHeaders(
                listOf(
                    CustomHeader("X-API-Key", "claude-custom"),
                    CustomHeader("Anthropic-Version", "custom-version"),
                ),
                "x-api-key" to "provider-key",
                "anthropic-version" to "2023-06-01",
            )
        )

        assertEquals(listOf("claude-custom"), request.headers.values("x-api-key"))
        assertEquals(listOf("custom-version"), request.headers.values("anthropic-version"))
    }

    private fun requestWith(headers: okhttp3.Headers): Request = Request.Builder()
        .url("https://example.test/request")
        .headers(headers)
        .build()
}

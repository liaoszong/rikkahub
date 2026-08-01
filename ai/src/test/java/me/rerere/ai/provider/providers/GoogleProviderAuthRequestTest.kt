package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleProviderAuthRequestTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `blank custom credential is filtered before adapter key fallback`() = runBlocking {
        val request = provider.transformRequest(
            ProviderSetting.Google(apiKey = "provider-key"),
            requestWith(
                CustomHeader("Authorization", "   "),
                CustomHeader("x-goog-api-key", ""),
            ),
        )

        assertEquals(listOf("provider-key"), request.headers.values("x-goog-api-key"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `duplicate custom credentials normalize to one last value`() = runBlocking {
        val request = provider.transformRequest(
            ProviderSetting.Google(apiKey = "provider-key"),
            requestWith(
                CustomHeader("X-Goog-Api-Key", "first-custom"),
                CustomHeader("x-goog-api-key", "last-custom"),
            ),
        )

        assertEquals(listOf("last-custom"), request.headers.values("x-goog-api-key"))
    }

    @Test
    fun `custom authorization overrides adapter authentication`() = runBlocking {
        val request = provider.transformRequest(
            ProviderSetting.Google(apiKey = "provider-key", vertexAI = true),
            requestWith(CustomHeader("Authorization", "Bearer gateway-token")),
        )

        assertEquals(listOf("Bearer gateway-token"), request.headers.values("Authorization"))
        assertNull(request.header("x-goog-api-key"))
        assertNull(request.url.queryParameter("key"))
    }

    private fun requestWith(vararg customHeaders: CustomHeader): Request = Request.Builder()
        .url("https://example.test/models/gemini-test:generateContent")
        .headers(providerAuthHeaders(customHeaders.toList()))
        .build()
}

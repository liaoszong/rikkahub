package me.rerere.ai.provider.providers

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIProviderImageMutationSafetyTest {
    @Test
    fun `paid image client never transparently retries a post`() {
        val shared = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()

        val paid = shared.newPaidImageMutationClient()

        assertFalse(paid.retryOnConnectionFailure)
    }

    @Test
    fun `stable attempt identity is sent as idempotency metadata`() {
        val request = Request.Builder()
            .url("https://example.com/v1/images/edits")
            .withImageRequestIdentity("request-1:0")
            .build()

        assertEquals("request-1:0", request.header("Idempotency-Key"))
        assertEquals("request-1:0", request.header("X-RikkaHub-Request-Id"))
    }

    @Test
    fun `blank request identity does not create transport metadata`() {
        val request = Request.Builder()
            .url("https://example.com/v1/images/generations")
            .withImageRequestIdentity("  ")
            .build()

        assertNull(request.header("Idempotency-Key"))
        assertNull(request.header("X-RikkaHub-Request-Id"))
    }
}

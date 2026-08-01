package me.rerere.ai.provider.providers

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class GeneratedImageDownloadPolicyTest {
    @Test
    fun `public downloads require https`() {
        expectFailure {
            GeneratedImageDownloadPolicy.validateUrl("http://images.example.com/output.png", false)
        }
        assertEquals(
            "https",
            GeneratedImageDownloadPolicy.validateUrl("https://images.example.com/output.png", false).scheme,
        )
    }

    @Test
    fun `localhost http exception only follows local provider configuration`() {
        expectFailure {
            GeneratedImageDownloadPolicy.validateUrl("http://127.0.0.1/output.png", false)
        }
        assertTrue(
            GeneratedImageDownloadPolicy.validateUrl("http://127.0.0.1/output.png", true).isHttps.not(),
        )
    }

    @Test
    fun `private metadata and unique local destinations are rejected`() {
        listOf("10.0.0.1", "100.64.0.1", "169.254.169.254", "192.168.1.1", "fc00::1").forEach { host ->
            expectFailure {
                GeneratedImageDownloadPolicy.validateResolvedAddresses(
                    url = "https://example.com/image.png".toHttpUrl(),
                    addresses = listOf(InetAddress.getByName(host)),
                    allowLocalDevelopment = false,
                )
            }
        }
    }

    @Test
    fun `mime type and streaming byte ceiling are enforced`() {
        assertEquals("image/png", GeneratedImageDownloadPolicy.validateMimeType("image/png; charset=binary"))
        expectFailure { GeneratedImageDownloadPolicy.validateMimeType("text/html") }
        expectFailure {
            GeneratedImageDownloadPolicy.readLimited(
                ByteArrayInputStream(ByteArray((GeneratedImageDownloadPolicy.MAX_IMAGE_BYTES + 1).toInt())),
            )
        }
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected validation failure", failed)
    }
}

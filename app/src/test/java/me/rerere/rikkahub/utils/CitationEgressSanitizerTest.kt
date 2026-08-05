package me.rerere.rikkahub.utils

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationEgressSanitizerTest {
    @Test
    fun `metadata sanitizer removes direct semantic and encoded credentials`() {
        val metadata = buildJsonObject {
            put("legacyShortId", "abc123")
            put("Authorization", "Bearer direct-secret")
            put("requestUrl", "https://example.com/source?api_key=url-secret&lang=zh#token")
            put("embeddedHeader", """{"name":"Authorization","value":"Bearer embedded-secret"}""")
            put("headers", buildJsonArray {
                add(buildJsonObject {
                    put("Name", "Authorization")
                    put("Value", "Bearer semantic-secret")
                })
                add(buildJsonObject {
                    put("name", "X-Tenant")
                    put("value", "opaque-custom-header-secret")
                })
                add(buildJsonObject {
                    put("name", "Accept")
                    put("value", "application/json")
                })
                add(buildJsonObject {
                    put("Accept", "application/problem+json")
                    put("X-Private", "opaque-array-header-map-secret")
                })
            })
            put("requestHeaders", buildJsonObject {
                put("Accept", "text/plain")
                put("X-Private", "opaque-header-map-secret")
            })
        }

        val sanitized = CitationEgressSanitizer.sanitizeMetadata(metadata).toString()

        assertTrue(sanitized.contains("legacyShortId"))
        assertTrue(sanitized.contains("abc123"))
        assertTrue(sanitized.contains("lang=zh"))
        assertTrue(sanitized.contains("application/json"))
        assertTrue(sanitized.contains("application/problem+json"))
        assertTrue(sanitized.contains("text/plain"))
        assertFalse(sanitized.contains("Authorization", ignoreCase = true))
        assertFalse(sanitized.contains("Bearer", ignoreCase = true))
        assertFalse(sanitized.contains("direct-secret"))
        assertFalse(sanitized.contains("url-secret"))
        assertFalse(sanitized.contains("embedded-secret"))
        assertFalse(sanitized.contains("semantic-secret"))
        assertFalse(sanitized.contains("opaque-custom-header-secret"))
        assertFalse(sanitized.contains("opaque-header-map-secret"))
        assertFalse(sanitized.contains("opaque-array-header-map-secret"))
        assertFalse(sanitized.contains("api_key", ignoreCase = true))
    }

    @Test
    fun `citation sanitizer keeps safe display data and disables unsafe navigation`() {
        val sanitized = CitationEgressSanitizer.sanitize(
            UIMessageAnnotation.UrlCitation(
                title = "Authorization%3A%20Bearer%20title-secret",
                url = "https://example.com/source?signature=signed-secret&lang=en",
                sourceId = "source-1",
                citationId = "citation-1",
                publisher = "Example Publisher",
                quote = "Safe quote",
                providerMetadata = buildJsonObject {
                    put("token", "metadata-secret")
                    put("legacyShortId", "abc123")
                },
            ),
        )

        assertEquals("", sanitized.title)
        assertEquals("Example Publisher", sanitized.publisher)
        assertEquals("Safe quote", sanitized.quote)
        assertEquals("https://example.com/source?lang=en", sanitized.url)
        assertEquals("source-1", sanitized.sourceId)
        assertEquals("citation-1", sanitized.citationId)
        assertEquals("abc123", sanitized.providerMetadata?.get("legacyShortId")?.toString()?.trim('"'))
        assertNull(sanitized.providerMetadata?.get("token"))
        assertTrue(sanitized.isAvailable)

        val unsafe = CitationEgressSanitizer.sanitize(
            UIMessageAnnotation.UrlCitation(
                title = "Unsafe",
                url = "https://user:password@example.com/private",
            ),
        )
        assertEquals("", unsafe.url)
        assertTrue(unsafe.isAvailable)
    }

    @Test
    fun `bearer valued query parameter is removed even with an innocent name`() {
        assertEquals(
            "https://example.com/source?lang=zh",
            CitationEgressSanitizer.sanitizeUrl(
                "https://example.com/source?continuation=Bearer%20query-secret&lang=zh",
            ),
        )
    }

    @Test
    fun `part metadata tool payload and diagnostics share the egress boundary`() {
        val sanitized = CitationEgressSanitizer.sanitizeMessageParts(
            listOf(
                UIMessagePart.Text(
                    text = "The word Bearer is intentional conversation content",
                    metadata = buildJsonObject {
                        put("thoughtSignature", "part-signature-secret")
                        put("safeIndex", 2)
                    },
                ),
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "custom_request",
                    input = """{"headers":[{"name":"Authorization","value":"Basic tool-secret"}],"lang":"zh"}""",
                    output = listOf(UIMessagePart.Text("Authorization: Bearer output-secret")),
                ),
                UIMessagePart.Image("https://example.com/image.png?signature=image-secret&size=large"),
            ),
        )
        val serialized = sanitized.toString()

        assertTrue(serialized.contains("The word Bearer is intentional conversation content"))
        assertTrue(serialized.contains("safeIndex"))
        assertTrue(serialized.contains("lang"))
        assertTrue(serialized.contains("size=large"))
        assertFalse(serialized.contains("thoughtSignature"))
        assertFalse(serialized.contains("part-signature-secret"))
        assertFalse(serialized.contains("Authorization", ignoreCase = true))
        assertFalse(serialized.contains("tool-secret"))
        assertFalse(serialized.contains("output-secret"))
        assertFalse(serialized.contains("image-secret"))
        assertEquals(
            "Generation failed",
            CitationEgressSanitizer.sanitizeDiagnosticText(
                "Generation failed: Authorization: Bearer error-secret",
                "Generation failed",
            ),
        )
    }
}

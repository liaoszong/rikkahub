package me.rerere.rikkahub.utils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilCitationTest {
    @Test
    fun `portable text appends distinct citation urls`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation("One", "https://example.com/one"),
                UIMessageAnnotation.UrlCitation("Duplicate", "https://example.com/one"),
                UIMessageAnnotation.UrlCitation("Two", "https://example.com/two"),
            ),
        )

        val result = message.toPortableText()

        assertTrue(result.startsWith("Answer\n\nSources:"))
        assertEquals(1, "https://example.com/one".toRegex().findAll(result).count())
        assertEquals(1, "https://example.com/two".toRegex().findAll(result).count())
    }

    @Test
    fun `portable text never exposes active or credential bearing citation urls`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation("Script", "javascript:alert(1)"),
                UIMessageAnnotation.UrlCitation("Credentials", "https://user:secret@example.com/private"),
                UIMessageAnnotation.UrlCitation("Signed", "https://example.com/private?key=secret&lang=zh#token"),
            ),
        )

        val result = message.toPortableText()

        assertTrue(result.contains("Script"))
        assertTrue(result.contains("Credentials"))
        assertTrue(result.contains("Signed"))
        assertTrue(!result.contains("javascript:"))
        assertTrue(!result.contains("secret"))
        assertTrue(result.contains("https://example.com/private?lang=zh"))
        assertNull("javascript:alert(1)".safeHttpUrlOrNull())
        assertNull("https://user:secret@example.com/private".safeHttpUrlOrNull())
        assertEquals(
            "https://example.com/private?lang=zh",
            "https://example.com/private?key=secret&lang=zh#token".safeHttpUrlOrNull(),
        )
    }

    @Test
    fun `portable text strips citation display and metadata credentials`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Authorization: Bearer title-secret",
                    publisher = "Safe Publisher",
                    url = "https://example.com/source?continuation=Bearer%20url-secret&lang=zh",
                    providerMetadata = buildJsonObject {
                        put("api_key", "metadata-secret")
                    },
                ),
            ),
        )

        val result = message.toPortableText()

        assertTrue(result.contains("Safe Publisher"))
        assertTrue(result.contains("https://example.com/source?lang=zh"))
        assertTrue(!result.contains("Authorization", ignoreCase = true))
        assertTrue(!result.contains("Bearer", ignoreCase = true))
        assertTrue(!result.contains("title-secret"))
        assertTrue(!result.contains("url-secret"))
        assertTrue(!result.contains("metadata-secret"))
    }

    @Test
    fun `legacy search citation resolver rejects unsafe schemes`() {
        val unsafeTool = UIMessagePart.Tool(
            toolCallId = "tool-unsafe",
            toolName = "search_web",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"items":[{"id":"abc123","url":"intent://open#Intent;end"}]}""",
                ),
            ),
            executionState = ToolExecutionState.SUCCEEDED,
        )
        val safeTool = unsafeTool.copy(
            toolCallId = "tool-safe",
            output = listOf(
                UIMessagePart.Text(
                    """{"items":[{"id":"def456","url":"https://example.com/source"}]}""",
                ),
            ),
        )

        assertNull(resolveMessageCitationUrl("abc123", emptyList(), listOf(unsafeTool)))
        assertEquals(
            "https://example.com/source",
            resolveMessageCitationUrl("def456", emptyList(), listOf(safeTool)),
        )
    }

    @Test
    fun `stable citation authority prevents legacy tool fallback resurrection`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tool-stable",
            toolName = "search_web",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"items":[{"id":"abc123","url":"https://example.com/legacy"}]}""",
                ),
            ),
            executionState = ToolExecutionState.SUCCEEDED,
        )
        val unavailable = UIMessageAnnotation.UrlCitation(
            title = "Unavailable",
            url = "https://example.com/canonical",
            sourceId = "stable-source",
            citationId = "stable-citation",
            isAvailable = false,
            providerMetadata = buildJsonObject { put("legacyShortId", "abc123") },
        )

        assertNull(resolveMessageCitationUrl("abc123", listOf(unavailable), listOf(tool)))
        assertNull(resolveMessageCitationUrl("unknown", listOf(unavailable), listOf(tool)))
        assertEquals(
            "https://example.com/canonical",
            resolveMessageCitationUrl(
                "abc123",
                listOf(unavailable.copy(isAvailable = true)),
                listOf(tool),
            ),
        )
    }

    @Test
    fun `malformed legacy citation metadata and search items fail closed`() {
        val malformedAuthority = UIMessageAnnotation.UrlCitation(
            title = "Malformed",
            url = "https://example.com/canonical",
            sourceId = "stable-source",
            providerMetadata = buildJsonObject {
                put("legacyShortId", buildJsonObject { put("unexpected", true) })
            },
        )
        val malformedTool = UIMessagePart.Tool(
            toolCallId = "tool-malformed",
            toolName = "search_web",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"items":[1,{"id":{},"url":"https://example.com/wrong"},{"id":"target","url":{}}]}""",
                ),
            ),
            executionState = ToolExecutionState.SUCCEEDED,
        )

        assertNull(resolveMessageCitationUrl("unknown", listOf(malformedAuthority), emptyList()))
        assertNull(resolveMessageCitationUrl("target", emptyList(), listOf(malformedTool)))
    }
}

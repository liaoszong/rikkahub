package me.rerere.rikkahub.web.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDtoCitationEgressTest {
    @Test
    fun `rest and sse dto serialization share the citation secret boundary`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "Safe answer",
                    metadata = buildJsonObject {
                        put("thoughtSignature", "part-signature-secret")
                        put("safeIndex", 2)
                    },
                ),
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "custom_request",
                    input = """{"headers":[{"name":"Authorization","value":"Bearer tool-secret"}]}""",
                    output = listOf(UIMessagePart.Text("api_key=output-secret")),
                ),
            ),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Safe source",
                    url = "https://example.com/source?key=url-secret&lang=zh",
                    sourceId = "source-1",
                    citationId = "citation-1",
                    providerMetadata = buildJsonObject {
                        put("legacyShortId", "abc123")
                        put("Authorization", "Bearer direct-secret")
                        put("headers", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "Authorization")
                                put("value", "Bearer semantic-secret")
                            })
                        })
                    },
                ),
            ),
        )
        val dto = message.toDto()
        val restJson = JsonInstant.encodeToString(dto)
        val sseJson = JsonInstant.encodeToString(
            ConversationNodeUpdateEvent(
                seq = 1,
                conversationId = "conversation-1",
                nodeId = "node-1",
                nodeIndex = 0,
                node = MessageNodeDto(id = "node-1", messages = listOf(dto), selectIndex = 0),
                updateAt = 1,
                isGenerating = true,
            ),
        )

        listOf(restJson, sseJson).forEach { outbound ->
            assertTrue(outbound.contains("Safe source"))
            assertTrue(outbound.contains("https://example.com/source?lang=zh"))
            assertTrue(outbound.contains("legacyShortId"))
            assertTrue(outbound.contains("abc123"))
            assertFalse(outbound.contains("Authorization", ignoreCase = true))
            assertFalse(outbound.contains("Bearer", ignoreCase = true))
            assertFalse(outbound.contains("url-secret"))
            assertFalse(outbound.contains("direct-secret"))
            assertFalse(outbound.contains("semantic-secret"))
            assertFalse(outbound.contains("part-signature-secret"))
            assertFalse(outbound.contains("tool-secret"))
            assertFalse(outbound.contains("output-secret"))
            assertTrue(outbound.contains("safeIndex"))
        }
    }
}

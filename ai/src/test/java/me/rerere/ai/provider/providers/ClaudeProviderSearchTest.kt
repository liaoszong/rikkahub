package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProviderSearchTest {
    private val provider = ClaudeProvider(OkHttpClient())

    @Test
    fun `modern claude declares dynamic web search and respects deny list`() {
        val enabled = buildRequest("claude-opus-4-8")
        assertEquals(
            "web_search_20260209",
            enabled["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content,
        )

        val disabled = buildRequest(
            modelId = "claude-opus-4-8",
            disabledBuiltInTools = setOf(BuiltInTools.Search),
        )
        assertTrue("deny-listed search must not leak into request", disabled["tools"] == null)
    }

    @Test
    fun `legacy claude uses basic web search variant`() {
        assertEquals("web_search_20250305", claudeWebSearchToolType("claude-3-5-sonnet-latest"))
    }

    @Test
    fun `search result is losslessly replayable and emits bounded evidence`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "srv_1")
                put("name", "web_search")
                put("input", buildJsonObject { put("query", "current event") })
            })
            add(buildJsonObject {
                put("type", "web_search_tool_result")
                put("tool_use_id", "srv_1")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search_result")
                        put("url", "https://example.com/source")
                        put("title", "Source")
                        put("encrypted_content", "opaque-provider-field")
                    })
                })
            })
        }
        val parsed = parseMessage(content)

        assertEquals(2, parsed.parts.filterIsInstance<UIMessagePart.ProviderOpaque>().size)
        assertTrue(parsed.parts.filterIsInstance<UIMessagePart.ProviderOpaque>()[1].payloadJson.contains("opaque-provider-field"))
        assertTrue(parsed.annotations.any {
            it is UIMessageAnnotation.ProviderToolEvent && it.status == "completed"
        })
        assertTrue(parsed.annotations.any {
            it is UIMessageAnnotation.UrlCitation && it.url == "https://example.com/source"
        })

        val replay = buildMessages(listOf(UIMessage.user("search"), parsed))
        val assistantBlocks = replay.last().jsonObject["content"]!!.jsonArray
        assertEquals("server_tool_use", assistantBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("web_search_tool_result", assistantBlocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `server search error is a failed event not an exception-shaped result`() {
        val parsed = parseMessage(buildJsonArray {
            add(buildJsonObject {
                put("type", "web_search_tool_result")
                put("tool_use_id", "srv_2")
                put("content", buildJsonObject { put("error_code", "max_uses_exceeded") })
            })
        })
        val event = parsed.annotations.filterIsInstance<UIMessageAnnotation.ProviderToolEvent>().single()
        assertEquals("failed", event.status)
        assertEquals("max_uses_exceeded", event.providerMetadata!!["error_code"]!!.jsonPrimitive.content)
    }

    private fun buildRequest(
        modelId: String,
        disabledBuiltInTools: Set<BuiltInTools> = emptySet(),
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        return method.invoke(
            provider,
            ProviderSetting.Claude(promptCaching = false),
            listOf(UIMessage.user("search")),
            TextGenerationParams(
                model = Model(modelId = modelId, tools = setOf(BuiltInTools.Search)),
                disabledBuiltInTools = disabledBuiltInTools,
            ),
            false,
        ) as JsonObject
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val method = ClaudeProvider::class.java.getDeclaredMethod("parseMessage", JsonArray::class.java)
            .apply { isAccessible = true }
        return method.invoke(provider, content) as UIMessage
    }

    private fun buildMessages(messages: List<UIMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            me.rerere.ai.provider.ClaudePromptCacheTtl::class.java,
        ).apply { isAccessible = true }
        return method.invoke(
            provider,
            messages,
            false,
            me.rerere.ai.provider.ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray
    }
}

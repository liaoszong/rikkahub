package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseAPIToolCallStreamTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `semantic call id survives item id based argument events`() {
        val tracker = ResponseToolCallTracker()
        val citationTracker = ResponseTextPartOrdinalTracker()
        val added = api.parseResponseDelta(
            functionCallAdded(
                itemId = "item_1",
                callId = "call_1",
                name = "search",
            ),
            citationTracker,
            tracker,
        ) ?: error("missing output_item added chunk")
        val done = api.parseResponseDelta(
            functionCallArgumentsDone(
                itemId = "item_1",
                arguments = """{"query":"rikkahub"}""",
            ),
            citationTracker,
            tracker,
        ) ?: error("missing arguments done chunk")

        assertEquals("call_1", added.singleTool().toolCallId)
        assertEquals("call_1", done.singleTool().toolCallId)

        val merged = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) + added + done
        assertEquals(1, merged.getTools().size)
        assertEquals("call_1", merged.getTools().single().toolCallId)
        assertEquals("search", merged.getTools().single().toolName)
        assertEquals("""{"query":"rikkahub"}""", merged.getTools().single().input)
    }

    @Test
    fun `parallel argument completion cannot cross tool identities`() {
        val tracker = ResponseToolCallTracker()
        val citationTracker = ResponseTextPartOrdinalTracker()
        val chunks = listOf(
            api.parseResponseDelta(
                functionCallAdded("item_a", "call_a", "tool_a"),
                citationTracker,
                tracker,
            ),
            api.parseResponseDelta(
                functionCallAdded("item_b", "call_b", "tool_b"),
                citationTracker,
                tracker,
            ),
            api.parseResponseDelta(
                functionCallArgumentsDone("item_b", """{"value":"B"}"""),
                citationTracker,
                tracker,
            ),
            api.parseResponseDelta(
                functionCallArgumentsDone("item_a", """{"value":"A"}"""),
                citationTracker,
                tracker,
            ),
        ).map { it ?: error("missing tool stream chunk") }

        val merged = chunks.fold(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())) { message, chunk ->
            message + chunk
        }
        val tools = merged.getTools().associateBy(UIMessagePart.Tool::toolCallId)

        assertEquals(setOf("call_a", "call_b"), tools.keys)
        assertEquals("tool_a", tools.getValue("call_a").toolName)
        assertEquals("""{"value":"A"}""", tools.getValue("call_a").input)
        assertEquals("tool_b", tools.getValue("call_b").toolName)
        assertEquals("""{"value":"B"}""", tools.getValue("call_b").input)
    }

    @Test
    fun `missing call id falls back to stable item id`() {
        val tracker = ResponseToolCallTracker()
        val chunk = api.parseResponseDelta(
            functionCallAdded(itemId = "item_only", callId = null, name = "fallback"),
            ResponseTextPartOrdinalTracker(),
            tracker,
        ) ?: error("missing fallback chunk")

        assertEquals("item_only", chunk.singleTool().toolCallId)
        assertEquals("item_only", tracker.resolve("item_only"))
    }

    @Test
    fun `duplicate added event cannot downgrade a semantic call id`() {
        val tracker = ResponseToolCallTracker()

        assertEquals("call_stable", tracker.register("item_1", "call_stable"))
        assertEquals("call_stable", tracker.register("item_1", null))
        assertEquals("call_stable", tracker.resolve("item_1"))
    }

    @Test
    fun `arguments event without preceding item fails closed to its own item id`() {
        val chunk = api.parseResponseDelta(
            functionCallArgumentsDone(itemId = "orphan_item", arguments = "{}"),
            ResponseTextPartOrdinalTracker(),
            ResponseToolCallTracker(),
        ) ?: error("missing orphan arguments chunk")

        assertEquals("orphan_item", chunk.singleTool().toolCallId)
    }

    private fun functionCallAdded(
        itemId: String,
        callId: String?,
        name: String,
    ) = buildJsonObject {
        put("type", "response.output_item.added")
        put("item", buildJsonObject {
            put("type", "function_call")
            put("id", itemId)
            callId?.let { put("call_id", it) }
            put("name", name)
            put("arguments", "")
        })
    }

    private fun functionCallArgumentsDone(
        itemId: String,
        arguments: String,
    ) = buildJsonObject {
        put("type", "response.function_call_arguments.done")
        put("item_id", itemId)
        put("arguments", arguments)
    }

    private fun MessageChunk.singleTool(): UIMessagePart.Tool = choices.single()
        .delta
        ?.parts
        ?.filterIsInstance<UIMessagePart.Tool>()
        ?.single()
        ?: error("chunk has no tool")
}

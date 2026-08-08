package me.rerere.ai.search

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTurnContractTest {
    @Test
    fun `successful tool output without later text is results ready`() {
        val projection = SearchTurnContract.project(
            listOf(UIMessage.user("latest"), searchTool(ToolExecutionState.SUCCEEDED, "{evidence}")),
        )
        assertEquals(SearchTurnState.RESULTS_READY, projection.state)
    }

    @Test
    fun `later assistant text closes the search turn`() {
        val projection = SearchTurnContract.project(
            listOf(
                UIMessage.user("latest"),
                searchTool(ToolExecutionState.SUCCEEDED, "{evidence}"),
                UIMessage.assistant("Final cited answer"),
            ),
        )
        assertEquals(SearchTurnState.ANSWER_READY, projection.state)
    }

    @Test
    fun `provider native cited response is answer ready`() {
        val cited = UIMessage.assistant("Final answer").copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation("Example", "https://example.com")),
        )
        assertEquals(SearchTurnState.ANSWER_READY, SearchTurnContract.project(listOf(cited)).state)
    }

    @Test
    fun `provider tool metadata alone is results ready not a visible answer`() {
        val eventOnly = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()).copy(
            annotations = listOf(
                UIMessageAnnotation.ProviderToolEvent(
                    provider = "openai",
                    toolType = "web_search",
                    callId = "ws1",
                    status = "completed",
                    payloadDigest = "digest",
                )
            ),
        )
        assertEquals(SearchTurnState.RESULTS_READY, SearchTurnContract.project(listOf(eventOnly)).state)
    }

    @Test
    fun `provider failed event is explicit failure`() {
        val failed = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()).copy(
            annotations = listOf(
                UIMessageAnnotation.ProviderToolEvent(
                    provider = "google",
                    toolType = "google_search",
                    callId = "gs1",
                    status = "failed",
                    payloadDigest = "digest",
                )
            ),
        )
        assertEquals(SearchTurnState.FAILED, SearchTurnContract.project(listOf(failed)).state)
    }

    private fun searchTool(state: ToolExecutionState, output: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "search-1",
                toolName = "search_web",
                input = "{}",
                output = output.takeIf(String::isNotEmpty)?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
                executionState = state,
            ),
        ),
    )
}

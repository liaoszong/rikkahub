package me.rerere.ai.search

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

enum class SearchTurnState { NOT_USED, SEARCH_PENDING, RESULTS_READY, ANSWER_READY, FAILED }

data class SearchTurnProjection(
    val state: SearchTurnState,
    val evidenceToolCount: Int,
    val failedToolCount: Int,
    val hasVisibleAnswer: Boolean,
)

/** Pure terminal projection shared by generation, recovery and future task UI. */
object SearchTurnContract {
    private val searchToolNames = setOf("search_web", "scrape_web")

    fun project(messages: List<UIMessage>): SearchTurnProjection {
        val searchTools = messages.flatMap(UIMessage::getTools).filter { it.toolName in searchToolNames }
        if (searchTools.isEmpty()) {
            val providerSearchEvents = messages.flatMap(UIMessage::annotations)
                .filterIsInstance<UIMessageAnnotation.ProviderToolEvent>()
                .filter { it.toolType == "web_search" || it.toolType == "google_search" }
            val citedAnswer = messages.any { message ->
                message.role == MessageRole.ASSISTANT &&
                    message.annotations.any { it is UIMessageAnnotation.UrlCitation } &&
                    message.hasVisibleText()
            }
            val completedEvents = providerSearchEvents.filter { it.status in setOf("completed", "succeeded") }
            val failedEvents = providerSearchEvents.filter {
                it.status in setOf("failed", "error", "cancelled", "incomplete")
            }
            return SearchTurnProjection(
                state = when {
                    citedAnswer -> SearchTurnState.ANSWER_READY
                    completedEvents.isNotEmpty() -> SearchTurnState.RESULTS_READY
                    failedEvents.size == providerSearchEvents.size && failedEvents.isNotEmpty() -> SearchTurnState.FAILED
                    providerSearchEvents.isNotEmpty() -> SearchTurnState.SEARCH_PENDING
                    else -> SearchTurnState.NOT_USED
                },
                evidenceToolCount = completedEvents.size,
                failedToolCount = failedEvents.size,
                hasVisibleAnswer = citedAnswer,
            )
        }
        val latestSearchMessageIndex = messages.indexOfLast { message ->
            message.getTools().any { it.toolName in searchToolNames }
        }
        val evidenceCount = searchTools.count { tool ->
            tool.executionState == ToolExecutionState.SUCCEEDED && tool.output.isNotEmpty()
        }
        val failedCount = searchTools.count { tool ->
            tool.executionState == ToolExecutionState.FAILED ||
                tool.executionState == ToolExecutionState.INTERRUPTED
        }
        val pending = searchTools.any { !it.isExecuted || it.isRunning || it.isPending }
        val visibleAnswer = messages.drop((latestSearchMessageIndex + 1).coerceAtMost(messages.size))
            .any { it.role == MessageRole.ASSISTANT && it.hasVisibleText() }
        val state = when {
            visibleAnswer && evidenceCount > 0 -> SearchTurnState.ANSWER_READY
            pending -> SearchTurnState.SEARCH_PENDING
            evidenceCount > 0 -> SearchTurnState.RESULTS_READY
            failedCount > 0 -> SearchTurnState.FAILED
            else -> SearchTurnState.SEARCH_PENDING
        }
        return SearchTurnProjection(state, evidenceCount, failedCount, visibleAnswer)
    }

    private fun UIMessage.hasVisibleText(): Boolean = parts
        .filterIsInstance<UIMessagePart.Text>()
        .any { it.text.isNotBlank() }
}

class SearchTerminalContractViolation(
    val projection: SearchTurnProjection,
    message: String,
) : IllegalStateException(message)

class GenerationStepLimitExceeded(val maxSteps: Int) : IllegalStateException(
    "Generation reached the configured step limit ($maxSteps) before a terminal answer",
)

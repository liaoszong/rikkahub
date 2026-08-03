package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ToolApprovalIdentityTest {
    @Test
    fun `approval updates only matching host request when provider call ids repeat`() {
        val first = pendingTool("request-1", "provider-call")
        val second = pendingTool("request-2", "provider-call")
        val conversation = conversation(first, second)

        val updated = conversation.withToolApproval(
            requestId = "request-2",
            toolCallId = "provider-call",
            approvalState = ToolApprovalState.Approved,
        )

        val tools = updated.messageNodes.flatMap { node ->
            node.messages.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
        }
        assertEquals(ToolApprovalState.Pending, tools.single { it.requestId == "request-1" }.approvalState)
        assertEquals(ToolApprovalState.Approved, tools.single { it.requestId == "request-2" }.approvalState)
    }

    @Test
    fun `duplicate host invocation identity fails closed`() {
        val duplicate = pendingTool("request-1", "provider-call")
        val conversation = conversation(duplicate, duplicate)

        assertThrows(IllegalStateException::class.java) {
            conversation.withToolApproval(
                requestId = "request-1",
                toolCallId = "provider-call",
                approvalState = ToolApprovalState.Approved,
            )
        }
    }

    private fun conversation(vararg tools: UIMessagePart.Tool) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = tools.map { tool ->
            MessageNode.of(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool),
                ),
            )
        },
    )

    private fun pendingTool(requestId: String, toolCallId: String) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = "test_tool",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
        requestId = requestId,
    )
}

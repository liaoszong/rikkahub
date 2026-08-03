package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskPhase
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageGenerationStateRecoveryTest {
    @Test
    fun `durable interruption closes a stale running gallery without retry`() {
        val recovered = runningState().reconcileTerminalTask(
            task(ChatImageGenerationTaskPhase.INTERRUPTED),
        )

        assertTrue(recovered.isTerminal)
        assertEquals(ChatImageSlotStatus.INTERRUPTED, recovered.slots.single().status)
        assertEquals(ImageGenerationFailureKind.PROCESS_INTERRUPTED, recovered.slots.single().failureKind)
        assertEquals(2_000L, recovered.finishedAtEpochMillis)
    }

    @Test
    fun `already terminal conversation checkpoint remains authoritative`() {
        val completed = runningState().copy(
            finishedAtEpochMillis = 1_500L,
            slots = listOf(
                runningState().slots.single().copy(status = ChatImageSlotStatus.SUCCEEDED),
            ),
        )

        val recovered = completed.reconcileTerminalTask(
            task(ChatImageGenerationTaskPhase.INTERRUPTED),
        )

        assertEquals(completed, recovered)
    }

    private fun runningState() = ChatImageGenerationState(
        requestId = "tool-1",
        prompt = "draw",
        model = "image-model",
        size = "1024x1024",
        startedAtEpochMillis = 1_000L,
        slots = listOf(
            ChatImageGenerationSlot(
                index = 0,
                status = ChatImageSlotStatus.RUNNING,
                requestId = "tool-1:0",
            ),
        ),
    )

    private fun task(phase: ChatImageGenerationTaskPhase) = ChatImageGenerationTaskRecord(
        taskId = "tool-1",
        conversationId = "conversation-1",
        toolCallId = "tool-1",
        requestId = "tool-1",
        attempt = 1,
        modelName = "image-model",
        requestedImageCount = 1,
        reservedOutputAssetIds = listOf("asset-1"),
        startedAtEpochMillis = 1_000L,
        finishedAtEpochMillis = 2_000L,
        phase = phase,
    )
}

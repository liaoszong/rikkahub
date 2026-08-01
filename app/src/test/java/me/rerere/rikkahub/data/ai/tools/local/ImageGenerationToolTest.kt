package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.findChatImageGenerationState
import me.rerere.rikkahub.data.imggen.toStatusPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolTest {
    @Test
    fun `chat image state round trips all slot states`() {
        val state = ChatImageGenerationState(
            prompt = "cat",
            model = "gpt-image-2",
            size = "1024x1024",
            startedAtEpochMillis = 100,
            finishedAtEpochMillis = 200,
            slots = listOf(
                ChatImageGenerationSlot(0, ChatImageSlotStatus.SUCCEEDED, "file:///one.png"),
                ChatImageGenerationSlot(1, ChatImageSlotStatus.FAILED, error = "failed"),
            ),
        )

        val decoded = listOf(state.toStatusPart()).findChatImageGenerationState()

        assertEquals(state, decoded)
        assertEquals(1, decoded?.succeededCount)
        assertEquals(1, decoded?.failedCount)
        assertTrue(decoded?.isTerminal == true)
    }

    @Test
    fun `queued or running slots keep group non terminal`() {
        val state = ChatImageGenerationState(
            prompt = "cat",
            model = "gpt-image-2",
            size = "auto",
            startedAtEpochMillis = 100,
            slots = listOf(
                ChatImageGenerationSlot(0, ChatImageSlotStatus.RUNNING),
                ChatImageGenerationSlot(1, ChatImageSlotStatus.QUEUED),
            ),
        )

        assertFalse(state.isTerminal)
    }
}

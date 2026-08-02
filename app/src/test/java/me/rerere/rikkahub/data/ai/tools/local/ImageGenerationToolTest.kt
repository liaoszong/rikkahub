package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.findChatImageGenerationState
import me.rerere.rikkahub.data.imggen.toStatusPart
import me.rerere.rikkahub.data.imggen.withFallbackImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolTest {
    @Test
    fun `chat image state round trips all slot states`() {
        val state = ChatImageGenerationState(
            requestId = "tool-call-1",
            attempt = 1,
            prompt = "cat",
            model = "gpt-image-2",
            size = "1024x1024",
            startedAtEpochMillis = 100,
            finishedAtEpochMillis = 200,
            slots = listOf(
                ChatImageGenerationSlot(
                    index = 0,
                    status = ChatImageSlotStatus.SUCCEEDED,
                    imageUrl = "file:///one.png",
                    requestId = "tool-call-1:0",
                    attempt = 1,
                ),
                ChatImageGenerationSlot(
                    index = 1,
                    status = ChatImageSlotStatus.FAILED,
                    error = "failed",
                    requestId = "tool-call-1:1",
                    attempt = 1,
                ),
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

    @Test
    fun `legacy final image becomes a terminal successful state`() {
        val recovered = null.withFallbackImages(
            toolCallId = "legacy-tool",
            images = listOf(UIMessagePart.Image("file:///legacy.png")),
        )

        assertTrue(recovered?.isTerminal == true)
        assertEquals(1, recovered?.succeededCount)
        assertEquals("file:///legacy.png", recovered?.slots?.single()?.imageUrl)
    }

    @Test
    fun `durable image output wins over stale failed slot`() {
        val stale = ChatImageGenerationState(
            requestId = "tool-call",
            prompt = "cat",
            model = "gpt-image-2",
            size = "1024x1024",
            startedAtEpochMillis = 100,
            finishedAtEpochMillis = 200,
            slots = listOf(
                ChatImageGenerationSlot(
                    index = 0,
                    status = ChatImageSlotStatus.FAILED,
                    error = "checkpoint failed",
                ),
            ),
        )

        val recovered = stale.withFallbackImages(
            toolCallId = "tool-call",
            images = listOf(UIMessagePart.Image("file:///committed.png")),
        )

        assertEquals(ChatImageSlotStatus.SUCCEEDED, recovered?.slots?.single()?.status)
        assertEquals("file:///committed.png", recovered?.slots?.single()?.imageUrl)
        assertEquals(null, recovered?.slots?.single()?.error)
    }

    @Test
    fun `library indexing failure keeps the already committed paid asset successful`() = runBlocking {
        var deferredMessage: String? = null

        val assetId = registerCommittedImageOrDefer(
            reservedAssetId = "reserved-asset",
            register = { error("room unavailable") },
            onDeferred = { deferredMessage = it.message },
        )

        assertEquals("reserved-asset", assetId)
        assertEquals("room unavailable", deferredMessage)
    }

    @Test
    fun `resolved provider references preserve ordered deduplicated media identities`() {
        val inputs = buildMediaReferenceInputs(
            listOf(
                ResolvedImageGenerationReference(
                    localPath = "/files/images/b.png",
                    assetId = "asset-b",
                    managedSourcePath = "images/b.png",
                ),
                ResolvedImageGenerationReference(
                    localPath = "/files/images/a.png",
                    assetId = "asset-a",
                    managedSourcePath = "images/a.png",
                ),
                ResolvedImageGenerationReference(
                    localPath = "/files/duplicate-b.png",
                    assetId = "asset-b",
                    managedSourcePath = "images/duplicate-b.png",
                ),
                ResolvedImageGenerationReference(
                    localPath = "/files/images/legacy.png",
                    managedSourcePath = "images/legacy.png",
                ),
            ),
        )

        assertEquals(listOf("asset-b", "asset-a", null), inputs.map { it.assetId })
        assertEquals(
            listOf("images/b.png", "images/a.png", "images/legacy.png"),
            inputs.map { it.sourcePath },
        )
    }
}

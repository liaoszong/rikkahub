package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.toStatusPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `single user image keeps existing individual attachment rendering`() {
        val blocks = listOf(
            UIMessagePart.Image("file:///one.png"),
        ).groupMessageParts(groupImages = true, groupSingleImages = false)

        assertEquals(1, blocks.size)
        assertTrue(blocks.all { it is MessagePartBlock.ContentBlock })
    }

    @Test
    fun `multiple user images are grouped into one compact gallery`() {
        val blocks = listOf(
            UIMessagePart.Image("file:///one.png"),
            UIMessagePart.Image("file:///two.png"),
        ).groupMessageParts(groupImages = true, groupSingleImages = false)

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MessagePartBlock.ImageBlock)
    }

    @Test
    fun `assistant images are grouped into one gallery`() {
        val blocks = listOf(
            UIMessagePart.Image("file:///one.png"),
            UIMessagePart.Image("file:///two.png"),
        ).groupMessageParts(groupImages = true)

        assertEquals(1, blocks.size)
        val gallery = blocks.single() as MessagePartBlock.ImageBlock
        assertEquals(2, gallery.images.size)
    }

    @Test
    fun `image generation tool is hoisted out of thinking card`() {
        val state = ChatImageGenerationState(
            prompt = "cat",
            model = "gpt-image-2",
            size = "1024x1024",
            startedAtEpochMillis = 100,
            slots = listOf(ChatImageGenerationSlot(0, ChatImageSlotStatus.RUNNING)),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
            progress = listOf(state.toStatusPart()),
        )

        val blocks = listOf<UIMessagePart>(tool).groupMessageParts(groupImages = true)

        assertEquals(1, blocks.size)
        val gallery = blocks.single() as MessagePartBlock.ImageGenerationBlock
        assertEquals(state, gallery.state)
    }

    @Test
    fun `legacy image tool output is preserved when status payload is absent`() {
        val image = UIMessagePart.Image("file:///legacy-generated.png")
        val tool = UIMessagePart.Tool(
            toolCallId = "legacy-call",
            toolName = "generate_image",
            input = "{}",
            output = listOf(image),
        )

        val gallery = listOf<UIMessagePart>(tool)
            .groupMessageParts(groupImages = true)
            .single() as MessagePartBlock.ImageGenerationBlock

        assertEquals(null, gallery.state)
        assertEquals(listOf(image), gallery.fallbackImages)
    }

    @Test
    fun `status and final images are recovered across output and progress`() {
        val state = ChatImageGenerationState(
            prompt = "cat",
            model = "gpt-image-2",
            size = "1024x1024",
            startedAtEpochMillis = 100,
            slots = listOf(ChatImageGenerationSlot(0, ChatImageSlotStatus.RUNNING)),
        )
        val image = UIMessagePart.Image("file:///final.png")
        val tool = UIMessagePart.Tool(
            toolCallId = "call-split",
            toolName = "generate_image",
            input = "{}",
            output = listOf(image),
            progress = listOf(state.toStatusPart()),
        )

        val gallery = listOf<UIMessagePart>(tool)
            .groupMessageParts(groupImages = true)
            .single() as MessagePartBlock.ImageGenerationBlock

        assertEquals(state, gallery.state)
        assertEquals(listOf(image), gallery.fallbackImages)
    }
}

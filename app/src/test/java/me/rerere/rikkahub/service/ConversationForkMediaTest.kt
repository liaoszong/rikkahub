package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationForkMediaTest {
    @Test
    fun `fork shares stable gallery assets and recursively copies ordinary attachments`() = runBlocking {
        val shared = UIMessagePart.Image(
            url = "file:///files/chat_generated_images/result.png",
            assetId = "asset-result",
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "tool-1",
            toolName = "render",
            input = "{}",
            output = listOf(
                shared,
                UIMessagePart.Document("file:///files/upload/source.pdf", "source.pdf"),
            ),
            progress = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-2",
                    toolName = "preview",
                    input = "{}",
                    output = listOf(UIMessagePart.Image("file:///files/upload/preview.png")),
                ),
            ),
        )
        val copiedUrls = mutableListOf<String>()

        val forked = tool.copyForConversationFork { url ->
            copiedUrls += url
            "$url.fork"
        } as UIMessagePart.Tool

        assertSame(shared, forked.output[0])
        assertEquals("file:///files/upload/source.pdf.fork", (forked.output[1] as UIMessagePart.Document).url)
        val nested = forked.progress.single() as UIMessagePart.Tool
        assertEquals("file:///files/upload/preview.png.fork", (nested.output.single() as UIMessagePart.Image).url)
        assertEquals(
            listOf("file:///files/upload/source.pdf", "file:///files/upload/preview.png"),
            copiedUrls,
        )
    }

    @Test
    fun `fork fails closed when an ordinary attachment cannot be copied`() {
        val attachment = UIMessagePart.Document(
            url = "file:///files/upload/source.pdf",
            fileName = "source.pdf",
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                attachment.copyForConversationFork {
                    error("copy failed")
                }
            }
        }
    }
}

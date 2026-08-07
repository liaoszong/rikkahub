package me.rerere.rikkahub.data.db.media

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class ConversationMediaReferenceProjectorTest {
    @Test
    fun `typed projector recursively keeps top level tool output and progress image ownership`() = runBlocking {
        val root = Files.createTempDirectory("conversation-media-projector").toFile()
        try {
            val historical = root.resolve("images/historical.png").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val message = UIMessage(
                id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image(historical.toURI().toString()),
                    UIMessagePart.Tool(
                        toolCallId = "outer-tool",
                        toolName = "render",
                        input = "{}",
                        output = listOf(UIMessagePart.Image("https://example.invalid/a.png", assetId = "asset-a")),
                        progress = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "inner-tool",
                                toolName = "preview",
                                input = "{}",
                                output = listOf(
                                    UIMessagePart.Image("https://example.invalid/b.png", assetId = "asset-b"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val nodes = listOf(
                MessageNode(
                    id = Uuid.parse("20000000-0000-0000-0000-000000000001"),
                    messages = listOf(message),
                ),
            )
            val resolver = FilesDirManagedMediaPathResolver(root)

            val first = projectTypedConversation("conversation-a", nodes, JsonInstant, resolver)
            val replay = projectTypedConversation("conversation-a", nodes, JsonInstant, resolver)

            assertEquals(first, replay)
            assertEquals(3, first.images.size)
            assertEquals("images/historical.png", first.images[0].managedRelativePath)
            assertNull(first.images[0].toolCallId)
            assertEquals("asset-a", first.images[1].assetId)
            assertEquals("outer-tool", first.images[1].toolCallId)
            assertEquals("top/output/0", first.images[1].nestedLocation)
            assertEquals("asset-b", first.images[2].assetId)
            assertEquals("inner-tool", first.images[2].toolCallId)
            assertEquals("top/progress/0/output/0", first.images[2].nestedLocation)
            assertEquals(first.images[1].partId, first.images[2].partId)
            assertNotEquals(first.images[0].partId, first.images[1].partId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `managed path resolver classifies only explicit http and https as ignorable remote`() {
        val root = Files.createTempDirectory("conversation-media-root").toFile()
        val external = Files.createTempFile("conversation-media-external", ".png").toFile()
        try {
            val resolver = FilesDirManagedMediaPathResolver(root)

            assertTrue(resolver.resolve(external.toURI().toString()) is ManagedMediaLocation.InvalidLocal)
            assertEquals(
                ManagedMediaLocation.Managed("images/relative.png"),
                resolver.resolve("images/relative.png"),
            )
            assertEquals(ManagedMediaLocation.ExplicitRemote, resolver.resolve("https://example.test/a.png"))
            assertEquals(ManagedMediaLocation.ExplicitRemote, resolver.resolve("http://example.test/a.png"))
            assertTrue(resolver.resolve("images/../secret.png") is ManagedMediaLocation.InvalidLocal)
            assertTrue(resolver.resolve("file:///%zz") is ManagedMediaLocation.InvalidLocal)
            assertTrue(resolver.resolve("content://provider/image/1") is ManagedMediaLocation.InvalidLocal)
        } finally {
            root.deleteRecursively()
            external.delete()
        }
    }

    @Test
    fun `typed projector includes stable image video audio and document assets`() {
        val root = Files.createTempDirectory("conversation-all-assets").toFile()
        try {
            val message = UIMessage(
                id = Uuid.parse("10000000-0000-0000-0000-000000000009"),
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image("library_attachments/a.png", assetId = "asset-image"),
                    UIMessagePart.Video("library_attachments/b.mp4", assetId = "asset-video"),
                    UIMessagePart.Audio("library_attachments/c.mp3", assetId = "asset-audio"),
                    UIMessagePart.Document(
                        url = "library_attachments/d.pdf",
                        fileName = "d.pdf",
                        mime = "application/pdf",
                        assetId = "asset-document",
                    ),
                ),
            )
            val projection = projectTypedConversation(
                conversationId = "conversation-assets",
                nodes = listOf(MessageNode(messages = listOf(message))),
                json = JsonInstant,
                pathResolver = FilesDirManagedMediaPathResolver(root),
            )

            assertEquals(4, projection.images.size)
            assertEquals(
                listOf("asset-image", "asset-video", "asset-audio", "asset-document"),
                projection.images.map { it.assetId },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

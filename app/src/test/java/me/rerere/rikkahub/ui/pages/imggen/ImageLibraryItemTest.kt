package me.rerere.rikkahub.ui.pages.imggen

import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ImageLibraryItemTest {
    @Test
    fun `maps legacy gallery rows into stable asset-backed items`() {
        val filesDir = File("build/test-files")
        val entity = MediaAssetEntity(
            id = 42,
            assetId = "asset-42",
            path = "images/generated.png",
            modelId = "model-id",
            modelDisplayName = "Model Name",
            providerId = "provider-id",
            prompt = "paint a quiet harbor",
            createAt = 1234L,
            mimeType = "image/png",
            conversationId = "conversation-id",
            messageNodeId = "message-id",
            toolCallId = "tool-call-id",
            parentAssetId = "parent-asset-id",
        )

        val item = entity.toImageLibraryItem { relativePath -> File(filesDir, relativePath) }

        assertEquals("asset-42", item.assetId)
        assertEquals(42, item.legacyId)
        assertEquals(File(filesDir, "images/generated.png").absolutePath, item.filePath)
        assertEquals("Model Name", item.model)
        assertEquals("image/png", item.mimeType)
        assertEquals("conversation-id", item.conversationId)
        assertEquals("parent-asset-id", item.parentAssetId)
    }

    @Test
    fun `keeps chat generated folder when resolving library path`() {
        val filesDir = File("build/test-files")
        val entity = MediaAssetEntity(
            assetId = "asset-chat",
            path = "chat_generated_images/generated.png",
            modelId = "model-id",
            prompt = "draw",
            createAt = 1234L,
        )

        val item = entity.toImageLibraryItem { relativePath -> File(filesDir, relativePath) }

        assertEquals(
            File(filesDir, "chat_generated_images/generated.png").absolutePath,
            item.filePath,
        )
    }
}

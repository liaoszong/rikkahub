package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaAssetMessageTest {
    @Test
    fun `image part persists stable media identity`() {
        val encoded = Json.encodeToString<UIMessagePart>(
            UIMessagePart.Image(
                url = "file:///generated.png",
                assetId = "asset-1",
            ),
        )

        val decoded = Json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Image

        assertEquals("file:///generated.png", decoded.url)
        assertEquals("asset-1", decoded.assetId)
    }

    @Test
    fun `legacy image part remains readable without asset identity`() {
        val decoded = Json.decodeFromString<UIMessagePart>(
            """{"type":"image","url":"file:///legacy.png"}""",
        ) as UIMessagePart.Image

        assertEquals("file:///legacy.png", decoded.url)
        assertNull(decoded.assetId)
    }
}

package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIImageRequestSafetyTest {
    @Test
    fun `paid image slot fields cannot be overridden by custom body`() {
        listOf(
            "model",
            "prompt",
            "n",
            "size",
            "image",
            "image[]",
            "mask",
            "stream",
            "partial_images",
        ).forEach { key ->
            val failure = runCatching {
                listOf(CustomBody("  ${key.uppercase()}  ", JsonPrimitive("override")))
                    .requireSafeOpenAiImageCustomBody()
            }.exceptionOrNull()

            assertTrue("reserved key $key was accepted", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `non-envelope image options remain customizable`() {
        val custom = listOf(
            CustomBody("quality", JsonPrimitive("high")),
            CustomBody("background", JsonPrimitive("opaque")),
            CustomBody("output_format", JsonPrimitive("webp")),
            CustomBody("output_compression", JsonPrimitive(80)),
        )

        assertEquals(custom, custom.requireSafeOpenAiImageCustomBody())
    }
}

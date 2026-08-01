package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GoogleProviderMediaRequestTest {
    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    @Test
    fun `request preserves wav m4a and webm mime types`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Audio("data:audio/wav;base64,UklGRg=="),
                    UIMessagePart.Audio(
                        url = "data:application/octet-stream;base64,AAAA",
                        metadata = buildJsonObject { put("mimeType", "audio/mp4") },
                    ),
                    UIMessagePart.Video("data:video/webm;base64,GkXf"),
                ),
            )
        )

        val body = invokeBuildCompletionRequestBody(messages)
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray

        assertEquals(
            listOf("audio/wav", "audio/mp4", "video/webm"),
            parts.map { it.jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `system text parts use explicit paragraph separators`() {
        val body = invokeBuildCompletionRequestBody(
            listOf(
                UIMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(
                        UIMessagePart.Text("First instruction"),
                        UIMessagePart.Text("Second instruction"),
                        UIMessagePart.Text("Third instruction"),
                    ),
                ),
                UIMessage.user("hello"),
            )
        )

        val text = body["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("First instruction\n\nSecond instruction\n\nThird instruction", text)
    }

    @Test
    fun `url extension probing and unknown fallback are deterministic`() {
        assertEquals(
            "audio/wav",
            resolveGoogleMediaMimeType("file:///tmp/voice.WAV", null, GoogleMediaKind.AUDIO).mimeType,
        )
        assertEquals(
            "audio/mp4",
            resolveGoogleMediaMimeType("file:///tmp/voice.m4a", null, GoogleMediaKind.AUDIO).mimeType,
        )
        assertEquals(
            "video/webm",
            resolveGoogleMediaMimeType("https://example.test/video.webm?token=hidden", null, GoogleMediaKind.VIDEO)
                .mimeType,
        )

        val fallback = resolveGoogleMediaMimeType("file:///tmp/no-extension", null, GoogleMediaKind.AUDIO)
        assertEquals("audio/mpeg", fallback.mimeType)
        assertNotNull(fallback.diagnostic)
        assertTrue(fallback.diagnostic.orEmpty().contains("fallback=audio/mpeg"))
        assertTrue(!fallback.diagnostic.orEmpty().contains("/tmp/"))
    }

    @Test
    fun `actual media signature wins over conflicting metadata and extension`() {
        val wavHeader = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVEfmt ".encodeToByteArray()
        val encoded = Base64.getEncoder().encodeToString(wavHeader)
        val resolution = resolveGoogleMediaMimeType(
            url = "file:///tmp/not-really-an-mp3.mp3",
            metadata = buildJsonObject { put("mimeType", "audio/mpeg") },
            kind = GoogleMediaKind.AUDIO,
            encodedBase64 = encoded,
        )

        assertEquals("audio/wav", resolution.mimeType)
    }

    private fun invokeBuildCompletionRequestBody(messages: List<UIMessage>): JsonObject =
        GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).run {
            isAccessible = true
            invoke(provider, messages, TextGenerationParams(model = Model(modelId = "gemini-test"))) as JsonObject
        }
}

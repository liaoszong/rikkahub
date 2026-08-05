package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAnnotationMergeTest {
    @Test
    fun streamedSpanReplacesSourcePlaceholderAndDistinctSpansSurvive() {
        val placeholder = citation()
        val firstSpan = citation(start = 0, end = 5, quote = "first")
        val secondSpan = citation(start = 10, end = 16, quote = "second")

        val merged = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) +
            chunk(placeholder) + chunk(firstSpan) + chunk(secondSpan) + chunk(placeholder)
        val citations = merged.annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()

        assertEquals(2, citations.size)
        assertEquals(listOf(0, 10), citations.map(UIMessageAnnotation.UrlCitation::startIndex))
        assertEquals(listOf("first", "second"), citations.map(UIMessageAnnotation.UrlCitation::quote))
    }

    @Test
    fun streamedTextPartsMergeOnlyWithinStableProviderPart() {
        val merged = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) +
            textChunk("first-", "part-0") +
            textChunk("continued", "part-0") +
            textChunk("second", "part-1")

        assertEquals(
            listOf("first-continued", "second"),
            merged.parts.filterIsInstance<UIMessagePart.Text>().map(UIMessagePart.Text::text),
        )
    }

    @Test
    fun malformedImportedStreamPartMetadataCannotAbortChunkMerge() {
        val imported = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "imported",
                    metadata = buildJsonObject {
                        put(STREAM_PART_ID_METADATA_KEY, buildJsonObject { put("unexpected", true) })
                    },
                ),
            ),
        )

        val merged = imported + textChunk("streamed", "part-0")

        assertEquals(
            listOf("imported", "streamed"),
            merged.parts.filterIsInstance<UIMessagePart.Text>().map(UIMessagePart.Text::text),
        )
    }

    private fun citation(
        start: Int? = null,
        end: Int? = null,
        quote: String? = null,
    ) = UIMessageAnnotation.UrlCitation(
        title = "Example",
        url = "https://example.com/source",
        startIndex = start,
        endIndex = end,
        textPartOrdinal = if (start != null || end != null) 0 else null,
        offsetUnit = if (start != null || end != null) "utf8_byte" else null,
        quote = quote,
    )

    private fun chunk(annotation: UIMessageAnnotation.UrlCitation) = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                    annotations = listOf(annotation),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )

    private fun textChunk(text: String, streamPartId: String) = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text(
                            text = text,
                            metadata = buildJsonObject { put(STREAM_PART_ID_METADATA_KEY, streamPartId) },
                        ),
                    ),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )
}

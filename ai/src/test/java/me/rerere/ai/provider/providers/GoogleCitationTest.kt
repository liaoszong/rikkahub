package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleCitationTest {
    @Test
    fun groundingSupportUsesProviderIndexWithoutCompactingInvalidChunks() {
        val metadata = Json.parseToJsonElement(
            """
            {
              "groundingChunks": [
                {"unsupported": {}},
                {"web": {"uri": "https://example.com/right", "title": "Right source"}}
              ],
              "groundingSupports": [
                {
                  "segment": {"partIndex": 2, "startIndex": 7, "endIndex": 12, "text": "猫🙂"},
                  "groundingChunkIndices": [1]
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val citation = parseGoogleSearchGroundingMetadata(metadata, mapOf(2 to 0)).single()
            as UIMessageAnnotation.UrlCitation

        assertEquals("https://example.com/right", citation.url)
        assertEquals(7, citation.startIndex)
        assertEquals(12, citation.endIndex)
        assertEquals("猫🙂", citation.quote)
        assertEquals(0, citation.textPartOrdinal)
        assertEquals("utf8_byte", citation.offsetUnit)
    }

    @Test
    fun groundingUtf8OffsetsNormalizeToUtf16ForPersistedText() {
        val metadata = Json.parseToJsonElement(
            """{"groundingChunks":[{"web":{"uri":"https://example.com","title":"S"}}],"groundingSupports":[{"segment":{"partIndex":0,"startIndex":4,"endIndex":8,"text":"🙂"},"groundingChunkIndices":[0]}]}"""
        ).jsonObject

        val citation = parseGoogleSearchGroundingMetadata(
            metadata,
            textPartOrdinals = mapOf(0 to 0),
            textByProviderPartIndex = mapOf(0 to "a猫🙂b"),
        ).single() as UIMessageAnnotation.UrlCitation

        assertEquals(2, citation.startIndex)
        assertEquals(4, citation.endIndex)
        assertEquals("utf16_code_unit", citation.offsetUnit)
    }

    @Test
    fun streamingGroundingIndicesResolveAcrossEventsForSameCandidate() {
        val accumulator = GoogleGroundingAccumulator()
        accumulator.accumulate(
            0,
            Json.parseToJsonElement(
                """{"groundingChunks":[{"web":{"uri":"https://example.com/first","title":"First"}}]}""",
            ).jsonObject,
        )
        val second = accumulator.accumulate(
            0,
            Json.parseToJsonElement(
                """
                {
                  "groundingChunks": [
                    {"web": {"uri": "https://example.com/second", "title": "Second"}}
                  ],
                  "groundingSupports": [
                    {"segment": {"startIndex": 2, "endIndex": 8}, "groundingChunkIndices": [1]}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        val citation = parseGoogleSearchGroundingMetadata(second).single()
            as UIMessageAnnotation.UrlCitation

        assertEquals("https://example.com/second", citation.url)
        assertEquals(2, citation.startIndex)
        assertEquals(8, citation.endIndex)
    }

    @Test
    fun annotationOnlyStreamingChunkReusesObservedTextPartMapping() {
        val accumulator = GoogleGroundingAccumulator()
        val content = Json.parseToJsonElement(
            """{"parts":[{"text":"thinking","thought":true},{"text":"answer"}]}""",
        ).jsonObject
        accumulator.observeTextParts(0, content)
        val mapping = accumulator.observeTextParts(0, null)
        val metadata = Json.parseToJsonElement(
            """
            {
              "groundingChunks": [{"web": {"uri": "https://example.com/source", "title": "Source"}}],
              "groundingSupports": [
                {"segment": {"partIndex": 1, "startIndex": 0, "endIndex": 6}, "groundingChunkIndices": [0]}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val citation = parseGoogleSearchGroundingMetadata(metadata, mapping).single()
            as UIMessageAnnotation.UrlCitation

        assertEquals(0, citation.textPartOrdinal)
        assertEquals(mapOf(1 to 0), mapping)
    }

    @Test
    fun malformedGroundingSideChannelDoesNotThrowOrCreateCitation() {
        val metadata = Json.parseToJsonElement(
            """
            {
              "groundingChunks": {"unexpected": true},
              "groundingSupports": [
                1,
                {"segment": [], "groundingChunkIndices": [{}, 0]}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals(emptyList<UIMessageAnnotation>(), parseGoogleSearchGroundingMetadata(metadata))
    }

    @Test
    fun malformedStreamingPartDoesNotCompactProviderPartIndex() {
        val accumulator = GoogleGroundingAccumulator()
        val mapping = accumulator.observeTextParts(
            0,
            Json.parseToJsonElement(
                """{"parts":[{"text":"thinking","thought":true},1,{"text":"answer"}]}""",
            ).jsonObject,
        )

        assertEquals(mapOf(2 to 0), mapping)
    }
}

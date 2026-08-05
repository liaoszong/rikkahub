package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCitationTest {
    @Test
    fun `responses url citation preserves range and raw metadata`() {
        val raw = buildJsonObject {
            put("type", "url_citation")
            put("title", "Source")
            put("url", "https://example.com/source")
            put("start_index", 5)
            put("end_index", 11)
        }

        val citation = requireNotNull(parseResponseUrlCitationAnnotation(raw))

        assertEquals("Source", citation.title)
        assertEquals("https://example.com/source", citation.url)
        assertEquals(5, citation.startIndex)
        assertEquals(11, citation.endIndex)
        assertEquals("provider", citation.provenance)
        assertEquals(raw, citation.providerMetadata)
    }

    @Test
    fun `unknown or empty response annotation fails closed`() {
        assertNull(parseResponseUrlCitationAnnotation(buildJsonObject { put("type", "file_citation") }))
        assertNull(
            parseResponseUrlCitationAnnotation(
                buildJsonObject {
                    put("type", "url_citation")
                    put("url", "")
                },
            ),
        )
    }

    @Test
    fun `responses citation carries ui text ordinal and provider coordinates`() {
        val raw = buildJsonObject {
            put("type", "url_citation")
            put("title", "Source")
            put("url", "https://example.com/source")
            put("start_index", 1)
            put("end_index", 4)
        }

        val citation = requireNotNull(
            parseResponseUrlCitationAnnotation(
                annotation = raw,
                textPartOrdinal = 2,
                outputIndex = 3,
                contentIndex = 4,
            ),
        )

        assertEquals(2, citation.textPartOrdinal)
        assertEquals(3, citation.providerMetadata?.get("responseOutputIndex")?.toString()?.toInt())
        assertEquals(4, citation.providerMetadata?.get("responseContentIndex")?.toString()?.toInt())
    }

    @Test
    fun `stream response tracker reuses ordinal for annotations on same text part`() {
        val tracker = ResponseTextPartOrdinalTracker()

        assertEquals(0, tracker.ordinalFor(outputIndex = 0, contentIndex = 0, itemId = "a"))
        assertEquals(0, tracker.ordinalFor(outputIndex = 0, contentIndex = 0, itemId = "a"))
        assertEquals(1, tracker.ordinalFor(outputIndex = 0, contentIndex = 1, itemId = "a"))
        assertNull(tracker.ordinalFor(outputIndex = 0, contentIndex = null, itemId = "a"))
    }

    @Test
    fun `malformed annotations cannot discard valid response text`() {
        val part = Json.parseToJsonElement(
            """
            {
              "type":"output_text",
              "text":"正文保留",
              "annotations":[
                1,
                {"type":{"unexpected":true}},
                {"type":"url_citation","url":{"unexpected":true}}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val parsed = requireNotNull(
            parseResponseOutputTextWithCitations(
                part = part,
                textPartOrdinal = 0,
                outputIndex = 0,
                contentIndex = 0,
            ),
        )

        assertEquals("正文保留", parsed.text)
        assertTrue(parsed.annotations.isEmpty())
    }

    @Test
    fun `non-array annotation side channel cannot discard valid response text`() {
        val part = Json.parseToJsonElement(
            """{"type":"output_text","text":"still here","annotations":{"unexpected":true}}""",
        ).jsonObject

        val parsed = requireNotNull(
            parseResponseOutputTextWithCitations(
                part = part,
                textPartOrdinal = 0,
                outputIndex = 0,
                contentIndex = 0,
            ),
        )

        assertEquals("still here", parsed.text)
        assertTrue(parsed.annotations.isEmpty())
    }
}

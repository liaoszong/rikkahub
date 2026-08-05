package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionCitationTest {
    @Test
    fun urlCitationReadsSpanFromNestedPayload() {
        val annotation = buildJsonObject {
            put("type", "url_citation")
            put("url_citation", buildJsonObject {
                put("title", "Example")
                put("url", "https://example.com/source")
                put("start_index", 4)
                put("end_index", 12)
            })
        }

        val citation = requireNotNull(parseChatCompletionUrlCitationAnnotation(annotation))

        assertEquals(4, citation.startIndex)
        assertEquals(12, citation.endIndex)
    }

    @Test
    fun malformedAndUnknownAnnotationsAreIsolatedFromValidCitation() {
        val annotations = Json.parseToJsonElement(
            """
            [
              1,
              {"type":{"unexpected":true}},
              {"type":"file_citation"},
              {"type":"url_citation","url_citation":{"url":{"unexpected":true}}},
              {"type":"url_citation","url_citation":{"title":"Safe","url":"https://example.com/safe"}}
            ]
            """.trimIndent(),
        ).jsonArray

        val citation = parseChatCompletionAnnotations(annotations).single()

        assertEquals("Safe", citation.title)
        assertEquals("https://example.com/safe", citation.url)
    }
}

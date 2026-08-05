package me.rerere.rikkahub.data.db.conversation

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.CitationValues
import me.rerere.rikkahub.data.db.entity.CitationSourceEntity
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CitationProjectorTest {
    private val projector = CitationProjector(JsonInstant)

    @Test
    fun `provider citation receives stable identities and survives reprojection`() {
        val original = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000101"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Example",
                    url = "HTTPS://Example.COM:443/a/../source?q=1#fragment",
                    startIndex = 0,
                    endIndex = 6,
                ),
            ),
        )

        val first = projector.project(CONVERSATION_ID, original)
        val normalized = first.message.annotations.single() as UIMessageAnnotation.UrlCitation
        val second = projector.project(CONVERSATION_ID, first.message)

        assertEquals("https://example.com/source?q=1", normalized.url)
        assertEquals(CitationValues.PROVENANCE_PROVIDER, normalized.provenance)
        assertNotNull(normalized.sourceId)
        assertNotNull(normalized.citationId)
        assertEquals(first.sources, second.sources)
        assertEquals(first.citations, second.citations)
    }

    @Test
    fun `self consistent digest cannot bind a canonical url to a foreign source id`() {
        val projection = projector.project(
            CONVERSATION_ID,
            UIMessage(
                id = Uuid.parse("00000000-0000-0000-0000-000000000109"),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Answer")),
                annotations = listOf(
                    UIMessageAnnotation.UrlCitation(
                        title = "Example",
                        url = "https://example.com/source",
                    ),
                ),
            ),
        )
        val foreignSourceId = "00000000-0000-0000-0000-000000000999"
        val forgedSource = projection.sources.single().copy(
            sourceId = foreignSourceId,
            recordDigest = "",
        ).let { it.copy(recordDigest = citationSourceRecordDigest(it)) }
        val forgedCitation = projection.citations.single().copy(
            sourceId = foreignSourceId,
            recordDigest = "",
        ).let { it.copy(recordDigest = messageCitationRecordDigest(it)) }

        val result = runCatching {
            requireValidCitationProjection(CONVERSATION_ID, listOf(forgedSource), listOf(forgedCitation))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("canonical URL"))
    }

    @Test
    fun `self consistent occurrence digest cannot retain a foreign citation id`() {
        val projection = projector.project(
            CONVERSATION_ID,
            UIMessage(
                id = Uuid.parse("00000000-0000-0000-0000-000000000110"),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Answer")),
                annotations = listOf(
                    UIMessageAnnotation.UrlCitation(
                        title = "Example",
                        url = "https://example.com/source",
                        startIndex = 0,
                        endIndex = 6,
                    ),
                ),
            ),
        )
        val forgedCitation = projection.citations.single().copy(
            citationId = "00000000-0000-0000-0000-000000000998",
        )
        assertEquals(messageCitationRecordDigest(forgedCitation), forgedCitation.recordDigest)

        val result = runCatching {
            requireValidCitationProjection(CONVERSATION_ID, projection.sources, listOf(forgedCitation))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("occurrence identity"))
    }

    @Test
    fun `forked message rebuilds conversation scoped citation identity`() {
        val original = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000104"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Example",
                    url = "https://example.com/source",
                ),
            ),
        )
        val source = projector.project(CONVERSATION_ID, original)
        val fork = projector.project(FORK_CONVERSATION_ID, source.message)

        assertEquals(source.sources.single().sourceId, fork.sources.single().sourceId)
        assertTrue(source.citations.single().citationId != fork.citations.single().citationId)
        assertEquals(CONVERSATION_ID, source.citations.single().conversationId)
        assertEquals(FORK_CONVERSATION_ID, fork.citations.single().conversationId)
        assertEquals(source.citations, projector.project(CONVERSATION_ID, source.message).citations)
    }

    @Test
    fun `inserting a different citation before an occurrence does not renumber its identity`() {
        val original = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000106"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Stable",
                    url = "https://example.com/stable",
                    startIndex = 10,
                    endIndex = 20,
                    offsetUnit = "provider_character",
                ),
            ),
        )
        val first = projector.project(CONVERSATION_ID, original)
        val stable = first.message.annotations.single() as UIMessageAnnotation.UrlCitation
        val insertedBefore = first.message.copy(
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "New",
                    url = "https://example.com/new",
                    startIndex = 0,
                    endIndex = 5,
                    offsetUnit = "provider_character",
                ),
                stable,
            ),
        )

        val second = projector.project(CONVERSATION_ID, insertedBefore)

        assertEquals(stable.citationId, second.citations.single { it.sourceId == stable.sourceId }.citationId)
    }

    @Test
    fun `structured search results remain citations even when marker is omitted`() {
        val output = """
            {
              "items": [
                {"id":"abc123","title":"Used","url":"https://example.com/used","text":"used snippet"},
                {"id":"def456","title":"Unused","url":"https://example.com/unused","text":"unused snippet"}
              ],
              "images": []
            }
        """.trimIndent()
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000102"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text(output)),
                    executionState = ToolExecutionState.SUCCEEDED,
                ),
                UIMessagePart.Text("Claim [citation,example.com](abc123)"),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)

        assertEquals(2, projection.sources.size)
        assertEquals(
            setOf("https://example.com/used", "https://example.com/unused"),
            projection.sources.map(CitationSourceEntity::canonicalUrl).toSet(),
        )
        assertEquals(
            "used snippet",
            projection.sources.single { it.canonicalUrl.endsWith("/used") }.snippet,
        )
        assertTrue(projection.citations.all { it.provenance == CitationValues.PROVENANCE_SEARCH_TOOL })
        assertTrue(projection.citations.single { it.displayTitle == "Used" }.textStart != null)
        assertEquals(null, projection.citations.single { it.displayTitle == "Unused" }.textStart)
        val replay = projector.project(CONVERSATION_ID, projection.message)
        assertEquals(projection.sources, replay.sources)
        assertEquals(projection.citations, replay.citations)
    }

    @Test
    fun `credential bearing citation url fails closed before metadata persistence`() {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000107"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Secret",
                    url = "https://user:secret@example.com/source",
                    providerMetadata = buildJsonObject {
                        put("url", "https://user:secret@example.com/source")
                    },
                ),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)

        assertTrue(projection.sources.isEmpty())
        assertTrue(projection.citations.isEmpty())
        assertTrue(projection.message.annotations.isEmpty())
    }

    @Test
    fun `sensitive url parameters and nested provider credentials are redacted`() {
        val rawUrl = "https://example.com/source?key=secret-key&lang=zh#access_token=fragment-secret"
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000108"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Source",
                    url = rawUrl,
                    providerMetadata = buildJsonObject {
                        put("url", rawUrl)
                        put(
                            "continuationUrl",
                            "https://example.com/source?continuation=Bearer%20query-secret&lang=zh",
                        )
                        put("authorization", "Bearer provider-secret")
                        put("semanticHeader", buildJsonObject {
                            put("name", "Authorization")
                            put("value", "Basic semantic-secret")
                        })
                        put("embeddedHeader", """{"name":"Authorization","value":"Bearer embedded-secret"}""")
                        put("requestHeaders", buildJsonObject {
                            put("Accept", "application/json")
                            put("X-Private", "opaque-header-secret")
                        })
                        put("nested", buildJsonObject {
                            put("custom_access_token", "nested-secret")
                            put("client_secret", "client-secret")
                            put("safe", "kept")
                        })
                    },
                ),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)
        val persistedMetadata = projection.citations.single().providerMetadataJson

        assertEquals("https://example.com/source?lang=zh", projection.sources.single().canonicalUrl)
        assertFalse(persistedMetadata.contains("secret-key"))
        assertFalse(persistedMetadata.contains("fragment-secret"))
        assertFalse(persistedMetadata.contains("provider-secret"))
        assertFalse(persistedMetadata.contains("nested-secret"))
        assertFalse(persistedMetadata.contains("client-secret"))
        assertFalse(persistedMetadata.contains("query-secret"))
        assertFalse(persistedMetadata.contains("semantic-secret"))
        assertFalse(persistedMetadata.contains("embedded-secret"))
        assertFalse(persistedMetadata.contains("opaque-header-secret"))
        assertTrue(persistedMetadata.contains("[redacted]"))
        assertTrue(persistedMetadata.contains("https://example.com/source?lang=zh"))
        assertTrue(persistedMetadata.contains("application/json"))
        assertTrue(persistedMetadata.contains("kept"))
    }

    @Test
    fun `provider controlled persisted strings redact encoded and semantic credentials`() {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000111"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                searchTool(
                    "tool-sensitive-fields",
                    """
                        {"items":[{
                          "id":"abc123",
                          "title":"{\"name\":\"Authorization\",\"value\":\"Basic search-secret\"}",
                          "url":"https://search.example/source",
                          "text":"token%253Dsnippet-secret"
                        }]}
                    """.trimIndent(),
                ),
            ),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Authorization%253A%2520Bearer%2520title-secret",
                    url = "https://provider.example/source",
                    publisher = "%7B%22name%22%3A%22Authorization%22%2C%22value%22%3A%22Basic%20publisher-secret%22%7D",
                    quote = "api_key%253Dquote-secret",
                ),
                UIMessageAnnotation.UrlCitation(
                    title = "普通标题 100%25 保真",
                    url = "https://safe.example/source",
                    publisher = "研究机构",
                    quote = "这是普通引用，token budget 只是术语。",
                ),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)
        val provider = projection.citations.single {
            projection.sources.single { source -> source.sourceId == it.sourceId }.canonicalUrl.contains("provider")
        }
        val safe = projection.citations.single {
            projection.sources.single { source -> source.sourceId == it.sourceId }.canonicalUrl.contains("safe")
        }
        val search = projection.citations.single { it.provenance == CitationValues.PROVENANCE_SEARCH_TOOL }

        assertEquals("[redacted]", provider.displayTitle)
        assertEquals("[redacted]", provider.displayPublisher)
        assertEquals("[redacted]", provider.quote)
        assertEquals("[redacted]", search.displayTitle)
        assertEquals("[redacted]", projection.sources.single { it.sourceId == search.sourceId }.snippet)
        assertEquals("普通标题 100%25 保真", safe.displayTitle)
        assertEquals("研究机构", safe.displayPublisher)
        assertEquals("这是普通引用，token budget 只是术语。", safe.quote)
    }

    @Test
    fun `canonical url preserves encoded and unicode components without percent inflation`() {
        val encoded = canonicalizeCitationUrl(
            "HTTPS://Example.COM:443/%E4%B8%AD/%7Euser/../%E6%96%87%E6%A1%A3" +
                "?q=%E4%B8%AD%E6%96%87&api%255Fkey=secret" +
                "&continuation=Authorization%253A%2520Bearer%2520query-secret#fragment",
        )
        val unicode = canonicalizeCitationUrl("https://example.com/资料/图像?q=中文")

        assertEquals(
            "https://example.com/%E4%B8%AD/%E6%96%87%E6%A1%A3?q=%E4%B8%AD%E6%96%87",
            encoded,
        )
        assertEquals(encoded, canonicalizeCitationUrl(requireNotNull(encoded)))
        assertEquals(unicode, canonicalizeCitationUrl(requireNotNull(unicode)))
        assertFalse(encoded.contains("%25E4"))
        assertFalse(unicode.contains("%25E8"))
    }

    @Test
    fun `unicode url expansion beyond canonical persistence budget is rejected`() {
        val raw = "https://example.com/" + "资料".repeat(1_000)

        assertTrue(raw.length < 8 * 1024)
        assertEquals(null, canonicalizeCitationUrl(raw))
    }

    @Test
    fun `citation projection truncates combined provider and search candidates to message budget`() {
        val providerAnnotations = (0 until 100).map { index ->
            UIMessageAnnotation.UrlCitation(
                title = "Provider $index",
                url = "https://provider.example/$index",
            )
        }
        val searchItems = (0 until 100).joinToString(",") { index ->
            val shortId = "s${index.toString().padStart(5, '0')}"
            """{"id":"$shortId","title":"Search $index","url":"https://search.example/$index"}"""
        }
        val projection = projector.project(
            CONVERSATION_ID,
            UIMessage(
                id = Uuid.parse("00000000-0000-0000-0000-000000000112"),
                role = MessageRole.ASSISTANT,
                parts = listOf(searchTool("tool-budget", """{"items":[$searchItems]}""")),
                annotations = providerAnnotations,
            ),
        )

        assertEquals(MAX_CITATIONS_PER_MESSAGE, projection.citations.size)
        assertEquals(100, projection.citations.count { it.provenance == CitationValues.PROVENANCE_PROVIDER })
        assertEquals(28, projection.citations.count { it.provenance == CitationValues.PROVENANCE_SEARCH_TOOL })
        assertEquals((0 until MAX_CITATIONS_PER_MESSAGE).toList(), projection.citations.map { it.ordinal })
    }

    @Test
    fun `valid projection gate rejects more than message citation budget`() {
        val messageId = "00000000-0000-0000-0000-000000000113"
        val annotations = (0..MAX_CITATIONS_PER_MESSAGE).map { index ->
            UIMessageAnnotation.UrlCitation(
                title = "Citation $index",
                url = "https://same.example/source",
                startIndex = index,
                endIndex = index + 1,
            )
        }
        val projection = projector.project(
            CONVERSATION_ID,
            UIMessage(
                id = Uuid.parse(messageId),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Answer")),
                annotations = annotations,
            ),
        )
        val template = projection.citations.last()
        val sourceId = template.sourceId
        val occurrenceKey = listOf(
            sourceId,
            template.provenance,
            "",
            MAX_CITATIONS_PER_MESSAGE.toString(),
            (MAX_CITATIONS_PER_MESSAGE + 1).toString(),
            template.offsetUnit,
        ).joinToString("\u0000")
        val extraCitationId = deterministicConversationV2Id(
            "message-citation-v1",
            CONVERSATION_ID,
            messageId,
            sourceId,
            occurrenceKey,
            "0",
        )
        val extra = template.copy(
            citationId = extraCitationId,
            ordinal = MAX_CITATIONS_PER_MESSAGE,
            textStart = MAX_CITATIONS_PER_MESSAGE,
            textEnd = MAX_CITATIONS_PER_MESSAGE + 1,
            recordDigest = "",
        ).let { it.copy(recordDigest = messageCitationRecordDigest(it)) }

        val result = runCatching {
            requireValidCitationProjection(
                CONVERSATION_ID,
                projection.sources,
                projection.citations + extra,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("citation persistence budget"))
    }

    @Test
    fun `ambiguous short search id fails closed`() {
        val first = """{"items":[{"id":"abc123","title":"One","url":"https://one.example/"}]}"""
        val second = """{"items":[{"id":"abc123","title":"Two","url":"https://two.example/"}]}"""
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000103"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                searchTool("tool-1", first),
                searchTool("tool-2", second),
                UIMessagePart.Text("Claim [citation,example.com](abc123)"),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)

        assertTrue(projection.sources.isEmpty())
        assertTrue(projection.citations.isEmpty())
    }

    @Test
    fun `malformed search side channel cannot abort valid citation projection`() {
        val output =
            """{"items":[1,{"id":{},"url":"https://wrong.example/"},{"id":"bad123","url":{}},""" +
                """{"id":"def456","url":"https://safe.example/source","title":{},"text":[]}]}"""
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000109"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                searchTool("tool-malformed", output),
                UIMessagePart.Text("Claim [citation,safe.example](def456)"),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)

        assertEquals("https://safe.example/source", projection.sources.single().canonicalUrl)
        assertEquals("safe.example", projection.citations.single().displayTitle)
    }

    @Test
    fun `oversized search side channel is not parsed into citation authority`() {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000110"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                searchTool("tool-oversized", "x".repeat(512 * 1024 + 1)),
                UIMessagePart.Text("Claim [citation,example.com](abc123)"),
            ),
        )

        val projection = projector.project(CONVERSATION_ID, message)

        assertTrue(projection.sources.isEmpty())
        assertTrue(projection.citations.isEmpty())
    }

    @Test
    fun `richer snippet and its content hash are selected atomically`() {
        val rich = CitationSourceEntity(
            sourceId = "source",
            canonicalUrl = "https://example.com/source",
            title = "Example",
            snippet = "the richer existing snippet",
            contentHash = sha256Hex("the richer existing snippet"),
            recordDigest = "old",
        )
        val poor = CitationSourceEntity(
            sourceId = "source",
            canonicalUrl = "https://example.com/source",
            title = "Example",
            snippet = "short",
            contentHash = sha256Hex("short"),
            recordDigest = "incoming",
        )

        val merged = rich.mergePreferRicher(poor)

        assertEquals("the richer existing snippet", merged.snippet)
        assertEquals(sha256Hex("the richer existing snippet"), merged.contentHash)
        assertEquals(citationSourceRecordDigest(merged), merged.recordDigest)
    }

    @Test
    fun `oversized provider metadata is replaced by bounded verifiable envelope`() {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000105"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "Example",
                    url = "https://example.com/source",
                    providerMetadata = buildJsonObject {
                        put("type", "url_citation")
                        put("oversized", "x".repeat(9 * 1024))
                    },
                ),
            ),
        )

        val metadata = JsonInstant.parseToJsonElement(
            projector.project(CONVERSATION_ID, message).citations.single().providerMetadataJson,
        ).jsonObject

        val persistedBytes = metadata.toString().toByteArray(Charsets.UTF_8).size

        assertTrue(metadata["_rikkahubTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            metadata["originalUtf8Bytes"]!!.jsonPrimitive.content.toInt() >
                MAX_CITATION_PROVIDER_METADATA_BYTES,
        )
        assertEquals(64, metadata["originalSha256"]!!.jsonPrimitive.content.length)
        assertEquals("url_citation", metadata["type"]!!.jsonPrimitive.content)
        assertTrue(persistedBytes <= MAX_CITATION_PROVIDER_METADATA_BYTES)
    }

    @Test
    fun `per message citation payloads are fairly bounded and deterministic`() {
        val unicodeQuote = "🐱资料".repeat(3_000)
        val original = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000111"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = List(40) { index ->
                UIMessageAnnotation.UrlCitation(
                    title = "Source $index",
                    url = "https://example.com/source/$index",
                    quote = unicodeQuote,
                    providerMetadata = buildJsonObject {
                        put("type", "url_citation")
                        put("payload", "资料🐱".repeat(3_000))
                    },
                )
            },
        )

        val first = projector.project(CONVERSATION_ID, original)
        val second = projector.project(CONVERSATION_ID, first.message)
        val metadataBytes = first.citations.sumOf {
            it.providerMetadataJson.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val quoteBytes = first.citations.sumOf {
            it.quote?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }

        assertEquals(40, first.citations.size)
        assertTrue(
            first.citations.all {
                it.providerMetadataJson.toByteArray(Charsets.UTF_8).size <=
                    MAX_CITATION_PROVIDER_METADATA_BYTES
            },
        )
        assertTrue(first.citations.all { (it.quote?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_CITATION_QUOTE_BYTES })
        assertTrue(metadataBytes <= MAX_CITATION_PROVIDER_METADATA_BYTES_PER_MESSAGE)
        assertTrue(quoteBytes <= MAX_CITATION_QUOTE_BYTES_PER_MESSAGE)
        assertTrue(first.citations.all { citation ->
            citation.quote?.let { quote ->
                quote == quote.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
            } ?: true
        })
        assertEquals(first.citations.map { it.citationId }, second.citations.map { it.citationId })
        assertEquals(first.citations.map { it.providerMetadataJson }, second.citations.map { it.providerMetadataJson })
        assertEquals(first.citations.map { it.quote }, second.citations.map { it.quote })
    }

    @Test
    fun `unicode citation field limits use code points instead of utf16 units`() {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-0000-0000-000000000112"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Answer")),
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(
                    title = "🐱".repeat(600),
                    publisher = "出版🐱".repeat(300),
                    url = "https://example.com/unicode",
                    offsetUnit = "🧭".repeat(80),
                    provenance = "来源🐱".repeat(80),
                ),
            ),
        )

        val citation = projector.project(CONVERSATION_ID, message).citations.single()

        assertEquals(512, citation.displayTitle.codePointCount(0, citation.displayTitle.length))
        assertEquals(512, citation.displayPublisher!!.codePointCount(0, citation.displayPublisher.length))
        assertEquals(64, citation.offsetUnit.codePointCount(0, citation.offsetUnit.length))
        assertEquals(64, citation.provenance.codePointCount(0, citation.provenance.length))
    }

    private fun searchTool(id: String, output: String) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "search_web",
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
        executionState = ToolExecutionState.SUCCEEDED,
    )

    private companion object {
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000001"
        const val FORK_CONVERSATION_ID = "00000000-0000-0000-0000-000000000002"
    }
}

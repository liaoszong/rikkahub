package me.rerere.rikkahub.data.db.conversation

import me.rerere.rikkahub.data.db.dao.MessageCitationCount
import me.rerere.rikkahub.data.db.entity.CitationSourceEntity
import me.rerere.rikkahub.data.db.entity.CitationValues
import me.rerere.rikkahub.data.db.entity.MessageCitationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationHydrationBatchPlannerTest {
    @Test
    fun `planner enforces both message and occurrence windows`() {
        val counts = List(70) { index ->
            MessageCitationCount(messageId = "%03d".format(69 - index), citationCount = 4)
        }

        val batches = planCitationHydrationBatches(counts)

        assertEquals(listOf(64, 6), batches.map { it.messageIds.size })
        assertEquals(listOf(256, 24), batches.map { it.citationCount })
        assertEquals((0..69).map { "%03d".format(it) }, batches.flatMap { it.messageIds })
    }

    @Test
    fun `planner never splits one message across occurrence windows`() {
        val batches = planCitationHydrationBatches(
            listOf(
                MessageCitationCount("c", 100),
                MessageCitationCount("a", 100),
                MessageCitationCount("b", 100),
            ),
        )

        assertEquals(listOf(listOf("a", "b"), listOf("c")), batches.map { it.messageIds })
        assertEquals(listOf(200, 100), batches.map { it.citationCount })
    }

    @Test
    fun `planner rejects corrupt duplicate or oversized message counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            planCitationHydrationBatches(
                listOf(MessageCitationCount("a", 1), MessageCitationCount("a", 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            planCitationHydrationBatches(listOf(MessageCitationCount("a", MAX_CITATIONS_PER_MESSAGE + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            planCitationHydrationBatches(
                listOf(MessageCitationCount("a", 5)),
                maxOccurrences = 4,
            )
        }
    }

    @Test
    fun `streaming digest remains byte compatible with persisted projection digest`() {
        val sources = listOf(source("source-a", "https://example.com/a"), source("source-b", "https://example.com/b"))
        val citations = listOf(
            citation("citation-b", "message-b", "source-b", 0),
            citation("citation-a", "message-a", "source-a", 0),
        )
        val sourcesById = sources.associateBy(CitationSourceEntity::sourceId)
        val streaming = CitationProjectionDigestAccumulator()
        citations.sortedWith(
            compareBy<MessageCitationEntity>(MessageCitationEntity::messageId)
                .thenBy(MessageCitationEntity::ordinal)
                .thenBy(MessageCitationEntity::citationId),
        ).forEach { citation -> streaming.add(citation, sourcesById.getValue(citation.sourceId)) }

        assertEquals(digestCitationProjection(sources, citations), streaming.finish())
        assertTrue(planCitationHydrationBatches(emptyList()).isEmpty())
    }

    private fun source(id: String, url: String): CitationSourceEntity {
        val source = CitationSourceEntity(
            sourceId = id,
            canonicalUrl = url,
            title = id,
            recordDigest = "record-$id",
        )
        return source
    }

    private fun citation(
        id: String,
        messageId: String,
        sourceId: String,
        ordinal: Int,
    ) = MessageCitationEntity(
        citationId = id,
        conversationId = "conversation",
        messageId = messageId,
        sourceId = sourceId,
        ordinal = ordinal,
        displayTitle = id,
        provenance = CitationValues.PROVENANCE_PROVIDER,
        recordDigest = "record-$id",
    )
}

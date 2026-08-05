package me.rerere.rikkahub.data.db.conversation

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.CitationDAO
import me.rerere.rikkahub.data.db.dao.ConversationGraphDAO
import me.rerere.rikkahub.data.db.dao.ConversationMigrationDAO
import me.rerere.rikkahub.data.db.entity.CitationSourceEntity
import me.rerere.rikkahub.data.db.entity.CitationValues
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageCitationEntity
import java.util.UUID

/** Bounded, restartable conversion of Room30 annotations/tool results into Room31 citation authority. */
class CitationBackfillCoordinator internal constructor(
    private val database: AppDatabase,
    private val graphDAO: ConversationGraphDAO,
    private val migrationDAO: ConversationMigrationDAO,
    private val citationDAO: CitationDAO,
    private val shadowProjector: ConversationV2ShadowProjector,
    private val citationProjector: CitationProjector,
    private val scrubProjectedConversation: suspend (String) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun backfillBatch(limit: Int = DEFAULT_BATCH_SIZE): CitationBackfillResult {
        require(limit in 1..MAX_BATCH_SIZE)
        val seedTime = nowMillis()
        citationDAO.seedMissingJournals(seedTime)
        citationDAO.invalidateStaleReadyJournals(seedTime)
        val owner = UUID.randomUUID().toString()
        val candidateTime = nowMillis()
        val projectedCandidates = citationDAO.getProjectedLeaseCandidates(
            now = candidateTime,
            retryBefore = candidateTime - RETRY_BACKOFF_MILLIS,
            limit = limit,
        )
        var migrated = 0
        var quarantined = 0
        var deferred = 0
        projectedCandidates.forEach { conversationId ->
            val claimed = database.withTransaction {
                val now = nowMillis()
                citationDAO.claimProjected(
                    conversationId = conversationId,
                    owner = owner,
                    now = now,
                    leaseUntil = now + LEASE_MILLIS,
                    retryBefore = citationRetryBefore(now),
                ) == 1
            }
            if (!claimed) return@forEach
            try {
                completeProjectedConversation(conversationId)
                migrated++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                releaseProjectedForRetry(conversationId, owner, error)
                deferred++
            }
        }

        val remaining = limit - projectedCandidates.size
        val candidates = if (remaining > 0) {
            citationDAO.getLeaseCandidates(
                now = candidateTime,
                retryBefore = candidateTime - RETRY_BACKOFF_MILLIS,
                limit = remaining,
            )
        } else {
            emptyList()
        }
        candidates.forEach { conversationId ->
            val claimed = database.withTransaction {
                val now = nowMillis()
                citationDAO.claim(
                    conversationId = conversationId,
                    owner = owner,
                    now = now,
                    leaseUntil = now + LEASE_MILLIS,
                    retryBefore = citationRetryBefore(now),
                ) == 1
            }
            if (!claimed) return@forEach
            try {
                database.withTransaction {
                    val state = migrationDAO.getConversationState(conversationId)
                        ?: error("Owning conversation disappeared")
                    if (state.storageVersion != ConversationV2Values.STORAGE_VERSION_V2) {
                        throw CitationBackfillDeterministicException(
                            "Conversation is not ready for citation backfill",
                        )
                    }
                    val messages = graphDAO.getMessages(conversationId)
                    val partsByMessage = graphDAO.getAllParts(conversationId).groupBy { it.messageId }
                    val sourcesById = linkedMapOf<String, CitationSourceEntity>()
                    val citations = mutableListOf<MessageCitationEntity>()
                    messages.filter { it.deletedAt == null }.forEach { message ->
                        val projection = deterministicCitationData {
                            val decoded = shadowProjector.decodeMessage(
                                message = message,
                                parts = partsByMessage[message.messageId].orEmpty(),
                                authoritativeAnnotations = null,
                            )
                            citationProjector.project(conversationId, decoded)
                        }
                        projection.sources.forEach { source ->
                            sourcesById[source.sourceId] = sourcesById[source.sourceId]
                                ?.mergePreferRicher(source) ?: source
                        }
                        citations += projection.citations
                    }

                    val existingSources = if (sourcesById.isEmpty()) emptyMap() else {
                        citationDAO.getSources(sourcesById.keys.toList()).associateBy(CitationSourceEntity::sourceId)
                    }
                    val sources = deterministicCitationData {
                        sourcesById.values.map { incoming ->
                            val old = existingSources[incoming.sourceId]
                            old ?: incoming
                        }
                    }
                    deterministicCitationData {
                        requireValidCitationProjection(conversationId, sources, citations)
                    }
                    citationDAO.deleteConversationCitations(conversationId)
                    if (sources.isNotEmpty()) citationDAO.upsertSources(sources)
                    if (citations.isNotEmpty()) citationDAO.upsertCitations(citations)
                    val digest = deterministicCitationData { digestCitationProjection(sources, citations) }
                    if (
                        citationDAO.markProjected(
                            conversationId = conversationId,
                            owner = owner,
                            sourceRevision = state.revision,
                            digest = digest,
                            citationCount = citations.size,
                            leaseUntil = nowMillis() + LEASE_MILLIS,
                            now = nowMillis(),
                        ) != 1
                    ) {
                        throw CitationBackfillLeaseLostException()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val errorCode = error::class.simpleName.orEmpty().take(80)
                if (isDeterministicCitationBackfillFailure(error)) {
                    database.withTransaction {
                        citationDAO.quarantine(
                            conversationId = conversationId,
                            owner = owner,
                            error = errorCode,
                            now = nowMillis(),
                        )
                    }
                    quarantined++
                } else {
                    database.withTransaction {
                        citationDAO.releaseForRetry(
                            conversationId = conversationId,
                            owner = owner,
                            error = errorCode,
                            now = nowMillis(),
                        )
                    }
                    deferred++
                }
                return@forEach
            }

            try {
                completeProjectedConversation(conversationId)
                migrated++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                releaseProjectedForRetry(conversationId, owner, error)
                deferred++
            }
        }
        val attempted = projectedCandidates.size + candidates.size
        val nextEligibleAtMillis = citationDAO.getNextEligibleAt(RETRY_BACKOFF_MILLIS)
        return CitationBackfillResult(
            attempted = attempted,
            migrated = migrated,
            quarantined = quarantined,
            deferred = deferred,
            // Retryable rows are no longer eligible for 30 seconds, so another batch can safely
            // advance unrelated conversations without spinning on the same failure.
            hasMore = attempted == limit,
            nextEligibleAtMillis = nextEligibleAtMillis,
        )
    }

    private suspend fun completeProjectedConversation(conversationId: String) {
        check(scrubProjectedConversation(conversationId)) {
            "Projected citation payload scrub was not accepted"
        }
        val journal = citationDAO.getJournal(conversationId)
        check(journal == null || journal.phase == CitationValues.MIGRATION_READY) {
            "Projected citation payload scrub did not reach READY"
        }
    }

    private suspend fun releaseProjectedForRetry(
        conversationId: String,
        owner: String,
        error: Exception,
    ) {
        database.withTransaction {
            citationDAO.releaseProjectedForRetry(
                conversationId = conversationId,
                owner = owner,
                error = error::class.simpleName.orEmpty().take(80),
                now = nowMillis(),
            )
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 12
        const val MAX_BATCH_SIZE = 50
        private const val LEASE_MILLIS = 2 * 60 * 1000L
        internal const val RETRY_BACKOFF_MILLIS = 30 * 1000L
    }
}

data class CitationBackfillResult(
    val attempted: Int,
    val migrated: Int,
    val quarantined: Int,
    val deferred: Int = 0,
    val hasMore: Boolean,
    /** Earliest safe claim time for unfinished work, including both lease and retry fences. */
    val nextEligibleAtMillis: Long?,
)

/**
 * Keeps the restart recovery alive until every retryable journal reaches READY or QUARANTINED.
 *
 * The minimum delay is intentional: an immediately eligible page yields instead of creating a
 * database busy loop. Delayed rows sleep until their lease/backoff fence and are then re-read from
 * Room, so a fast process restart cannot reclaim work using stale in-memory state.
 */
internal suspend fun runCitationBackfillSchedule(
    runBatch: suspend () -> CitationBackfillResult,
    nowMillis: () -> Long = System::currentTimeMillis,
    delayMillis: suspend (Long) -> Unit = { delay(it) },
    onBatch: (CitationBackfillResult) -> Unit = {},
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val batch = runBatch()
        onBatch(batch)
        val nextEligibleAt = batch.nextEligibleAtMillis ?: return
        delayMillis(citationBackfillDelayMillis(nowMillis(), nextEligibleAt))
    }
}

internal fun citationBackfillDelayMillis(nowMillis: Long, nextEligibleAtMillis: Long): Long =
    if (nextEligibleAtMillis <= nowMillis) {
        MIN_SCHEDULER_DELAY_MILLIS
    } else {
        (nextEligibleAtMillis - nowMillis).coerceAtLeast(MIN_SCHEDULER_DELAY_MILLIS)
    }

internal fun citationRetryBefore(nowMillis: Long): Long = nowMillis - CitationBackfillCoordinator.RETRY_BACKOFF_MILLIS

private const val MIN_SCHEDULER_DELAY_MILLIS = 25L

internal fun isDeterministicCitationBackfillFailure(error: Exception): Boolean = when (error) {
    is CitationBackfillDeterministicException,
    is SerializationException,
    is SQLiteConstraintException,
    is IllegalArgumentException,
    is ArithmeticException -> true
    else -> false
}

internal class CitationBackfillDeterministicException : Exception {
    constructor(message: String) : super(message)
    constructor(cause: Exception) : super(cause)
}

internal class CitationBackfillLeaseLostException : Exception("Citation migration lease was lost")

private inline fun <T> deterministicCitationData(block: () -> T): T = try {
    block()
} catch (error: CitationBackfillDeterministicException) {
    throw error
} catch (error: Exception) {
    throw CitationBackfillDeterministicException(error)
}

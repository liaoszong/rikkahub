package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.conversation.ConversationV2IntegrityException
import me.rerere.rikkahub.data.db.conversation.ConversationV2ShadowProjector
import me.rerere.rikkahub.data.db.conversation.deterministicConversationV2Id
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageFtsOutboxDAO
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageFtsOutboxEntity

private const val TAG = "MessageFtsOutbox"

/**
 * Applies the durable Room outbox to the FTS external projection.
 *
 * FTS writes and the matching outbox acknowledgement share one SQLite transaction. A process
 * crash therefore leaves either both committed or neither committed. The singleton runner is an
 * optimization only: leases and compare-and-set state transitions remain authoritative.
 */
class MessageFtsOutboxProcessor(
    private val database: AppDatabase,
    private val outboxDAO: MessageFtsOutboxDAO,
    private val conversationDAO: ConversationDAO,
    private val projector: ConversationV2ShadowProjector,
    private val ftsManager: MessageFtsManager,
    private val appScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    workerId: String = UUID.randomUUID().toString(),
) {
    private val workerId = "message-fts-$workerId"
    private val started = AtomicBoolean(false)
    private val drainMutex = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO + CoroutineName("MessageFtsOutbox")) {
            wakeups.trySend(Unit)
            runLoop()
        }
    }

    /** Never runs FTS work on the caller and never turns a committed conversation into an error. */
    fun requestDrain() {
        start()
        wakeups.trySend(Unit)
    }

    suspend fun rebuildAll(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        try {
            drainMutex.withLock {
                val events = database.withTransaction {
                    ftsManager.deleteAllInTransaction()
                    conversationDAO.getAllIds().mapNotNull { conversationId ->
                        val entity = conversationDAO.getConversationById(conversationId)
                            ?: return@mapNotNull null
                        enqueueRebuildEvent(conversationId, entity.revision)
                    }
                }
                events.forEachIndexed { index, event ->
                    processCandidate(event)
                    onProgress(index + 1, events.size)
                }
            }
        } finally {
            requestDrain()
        }
    }

    internal suspend fun drainReady(limit: Int = DEFAULT_BATCH_SIZE): MessageFtsDrainResult =
        drainMutex.withLock {
            require(limit in 1..MAX_BATCH_SIZE) { "Invalid FTS outbox batch size" }
            val candidates = outboxDAO.getClaimCandidates(nowMillis(), limit)
            var claimed = 0
            var succeeded = 0
            var failed = 0
            candidates.forEach { candidate ->
                when (processCandidate(candidate)) {
                    CandidateResult.SKIPPED -> Unit
                    CandidateResult.SUCCEEDED -> {
                        claimed += 1
                        succeeded += 1
                    }

                    CandidateResult.FAILED -> {
                        claimed += 1
                        failed += 1
                    }
                }
            }
            MessageFtsDrainResult(claimed = claimed, succeeded = succeeded, failed = failed)
        }

    private suspend fun processCandidate(candidate: MessageFtsOutboxEntity): CandidateResult {
        val claimTime = nowMillis()
        val didClaim = outboxDAO.claim(
            eventId = candidate.eventId,
            owner = workerId,
            now = claimTime,
            leaseUntil = Math.addExact(claimTime, LEASE_DURATION_MS),
        ) == 1
        if (!didClaim) return CandidateResult.SKIPPED
        return try {
            applyClaimed(candidate.eventId)
            CandidateResult.SUCCEEDED
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val retryTime = nowMillis()
            outboxDAO.retry(
                eventId = candidate.eventId,
                owner = workerId,
                retryAt = Math.addExact(retryTime, retryDelayMillis(candidate.attempts)),
                errorCode = error.errorCode(),
                now = retryTime,
            )
            Log.w(
                TAG,
                "Projection retry scheduled for ${candidate.conversationId}: ${error.errorCode()}",
            )
            CandidateResult.FAILED
        }
    }

    private suspend fun enqueueRebuildEvent(
        conversationId: String,
        targetRevision: Long,
    ): MessageFtsOutboxEntity {
        val now = nowMillis()
        val previousOrder = outboxDAO.getMaxEventOrder(conversationId)
        val eventOrder = previousOrder?.let { maxOf(now, Math.addExact(it, 1L)) } ?: now
        val event = MessageFtsOutboxEntity(
            eventId = deterministicConversationV2Id(
                "fts-rebuild",
                conversationId,
                eventOrder.toString(),
            ),
            conversationId = conversationId,
            targetRevision = targetRevision,
            operation = ConversationV2Values.OUTBOX_REBUILD,
            createdAt = eventOrder,
            updatedAt = eventOrder,
        )
        check(outboxDAO.enqueue(event) != -1L) {
            "Unable to enqueue FTS rebuild for $conversationId"
        }
        return event
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                wakeups.receive()
                while (currentCoroutineContext().isActive) {
                    val result = drainReady()
                    if (result.claimed > 0) continue
                    val now = nowMillis()
                    val nextWakeAt = outboxDAO.getNextWakeAt(now) ?: break
                    val waitMillis = (nextWakeAt - now).coerceAtLeast(MIN_WAKE_DELAY_MS)
                    withTimeoutOrNull(waitMillis) {
                        wakeups.receive()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Outbox runner recovered from ${error.errorCode()}")
                delay(RUNNER_RECOVERY_DELAY_MS)
                wakeups.trySend(Unit)
            }
        }
    }

    private suspend fun applyClaimed(eventId: String) {
        database.withTransaction {
            val event = outboxDAO.getEvent(eventId)
                ?.takeIf {
                    it.state == ConversationV2Values.OUTBOX_PROCESSING && it.leaseOwner == workerId
                }
                ?: throw FtsOutboxLeaseLostException(eventId)
            when (event.operation) {
                ConversationV2Values.OUTBOX_DELETE -> {
                    ftsManager.deleteConversationInTransaction(event.conversationId)
                }

                ConversationV2Values.OUTBOX_UPSERT,
                ConversationV2Values.OUTBOX_REBUILD
                -> applyUpsert(event)

                else -> throw IllegalStateException("Unsupported FTS outbox operation")
            }
            val completedAt = nowMillis()
            if (outboxDAO.completeClaim(event.eventId, workerId, completedAt) != 1) {
                throw FtsOutboxLeaseLostException(event.eventId)
            }
            outboxDAO.deleteSuperseded(
                conversationId = event.conversationId,
                completedEventOrder = event.createdAt,
                completedEventId = event.eventId,
            )
        }
    }

    private suspend fun applyUpsert(event: MessageFtsOutboxEntity) {
        val entity = conversationDAO.getConversationById(event.conversationId)
        if (entity == null) {
            ftsManager.deleteConversationInTransaction(event.conversationId)
            return
        }
        if (entity.revision < event.targetRevision) {
            throw ConversationV2IntegrityException(
                event.conversationId,
                "FTS event targets a future conversation revision",
            )
        }
        val projection = projector.loadReady(event.conversationId)
            ?: throw ConversationV2IntegrityException(
                event.conversationId,
                "FTS event cannot read a READY conversation",
            )
        ftsManager.replaceConversationInTransaction(
            conversationId = event.conversationId,
            title = entity.title,
            updateAtMillis = entity.updateAt,
            nodes = projection.asLegacyMessageNodes(),
        )
    }

    private fun retryDelayMillis(previousAttempts: Int): Long {
        val shift = previousAttempts.coerceIn(0, MAX_BACKOFF_SHIFT)
        return min(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS * (1L shl shift))
    }

    private fun Throwable.errorCode(): String =
        (this::class.qualifiedName ?: this::class.simpleName ?: "Throwable").take(MAX_ERROR_CODE_LENGTH)

    private companion object {
        const val DEFAULT_BATCH_SIZE = 16
        const val MAX_BATCH_SIZE = 64
        const val LEASE_DURATION_MS = 60_000L
        const val MIN_WAKE_DELAY_MS = 250L
        const val RUNNER_RECOVERY_DELAY_MS = 5_000L
        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 5 * 60_000L
        const val MAX_BACKOFF_SHIFT = 8
        const val MAX_ERROR_CODE_LENGTH = 160
    }
}

internal data class MessageFtsDrainResult(
    val claimed: Int,
    val succeeded: Int,
    val failed: Int,
)

private class FtsOutboxLeaseLostException(eventId: String) :
    IllegalStateException("FTS outbox lease was lost for $eventId")

private enum class CandidateResult {
    SKIPPED,
    SUCCEEDED,
    FAILED,
}

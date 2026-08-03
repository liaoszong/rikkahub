package me.rerere.rikkahub.data.db.media

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
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

private const val TAG = "ConversationMediaRefs"

fun interface MediaReferenceBackfillScheduler {
    fun requestBackfill()
}

/**
 * Coalesces durable media-reference verification outside ConversationStore transactions.
 *
 * Live writes already maintain one conversation's exact references atomically. The expensive
 * all-conversation pass therefore runs only at process start, while a migration/new asset is
 * pending, or after a fail-closed validation result. The Room journal remains the authority;
 * this singleton is only a liveness mechanism.
 */
class ConversationMediaReferenceBackfillProcessor(
    private val indexer: ConversationMediaReferenceIndexer,
    private val appScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MS,
) : MediaReferenceBackfillScheduler {
    private val started = AtomicBoolean(false)
    private val forceVerification = AtomicBoolean(false)
    private val drainMutex = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        forceVerification.set(true)
        appScope.launch(Dispatchers.IO + CoroutineName("ConversationMediaReferenceBackfill")) {
            wakeups.trySend(Unit)
            runLoop()
        }
    }

    override fun requestBackfill() {
        start()
        wakeups.trySend(Unit)
    }

    /** Used after restore/startup even when the imported journal claims to be complete. */
    fun requestFullVerification() {
        forceVerification.set(true)
        requestBackfill()
    }

    internal suspend fun drainIfNeeded(force: Boolean = false): ConversationMediaBackfillResult? =
        drainMutex.withLock {
            if (!force && !indexer.requiresGlobalBackfill()) return@withLock null
            indexer.backfillReadyConversations(now = nowMillis())
        }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            wakeups.receive()
            if (settleDelayMillis > 0) delay(settleDelayMillis)
            var transientRetries = 0
            while (currentCoroutineContext().isActive) {
                val force = forceVerification.getAndSet(false)
                val result = try {
                    drainIfNeeded(force)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Reference backfill paused after ${error::class.java.simpleName}")
                    null
                }
                if (result == null) break
                Log.i(
                    TAG,
                    "Reference backfill ${result.status}: conversations=${result.indexedConversations}/" +
                        "${result.readyConversations} refs=${result.referenceCount} " +
                        "unresolved=${result.unresolvedImages} failures=${result.failures.size}",
                )
                if (
                    result.status in TRANSIENT_RESULTS &&
                    transientRetries < MAX_TRANSIENT_RETRIES
                ) {
                    transientRetries++
                    delay(TRANSIENT_RETRY_DELAY_MS * transientRetries)
                    continue
                }
                break
            }
        }
    }

    private companion object {
        const val DEFAULT_SETTLE_DELAY_MS = 750L
        const val TRANSIENT_RETRY_DELAY_MS = 500L
        const val MAX_TRANSIENT_RETRIES = 3
        val TRANSIENT_RESULTS = setOf(
            ConversationMediaBackfillStatus.SOURCE_CHANGED,
            ConversationMediaBackfillStatus.REFERENCE_MISMATCH,
        )
    }
}

package me.rerere.rikkahub.data.db.conversation

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationBackfillPolicyTest {
    @Test
    fun deterministicDataFailureIsQuarantinedButLeaseAndIoFailuresRetry() {
        assertTrue(
            isDeterministicCitationBackfillFailure(
                CitationBackfillDeterministicException("bad citation payload"),
            ),
        )
        assertFalse(isDeterministicCitationBackfillFailure(CitationBackfillLeaseLostException()))
        assertFalse(isDeterministicCitationBackfillFailure(IOException("database temporarily unavailable")))
    }

    @Test
    fun staleCandidateCannotCrossAConcurrentReleaseBackoffFence() {
        val candidateScanAt = 100_000L
        val previouslyEligibleUpdatedAt = 70_000L
        assertTrue(previouslyEligibleUpdatedAt <= citationRetryBefore(candidateScanAt))

        // Scheduler A has already selected the row. Scheduler B then releases the same row for a
        // retry before A executes its UPDATE. The claim-time retryBefore predicate must use A's
        // current transaction time, not the old candidate snapshot.
        val concurrentReleaseAt = candidateScanAt + 1
        val staleClaimAt = candidateScanAt + 2
        assertTrue(concurrentReleaseAt > citationRetryBefore(staleClaimAt))

        val firstSafeClaimAt = concurrentReleaseAt + CitationBackfillCoordinator.RETRY_BACKOFF_MILLIS
        assertEquals(concurrentReleaseAt, citationRetryBefore(firstSafeClaimAt))
    }

    @Test
    fun deferredRowAutomaticallyResumesAtItsNextEligibleBoundary() = runBlocking {
        var now = 1_000L
        var batches = 0
        val waits = mutableListOf<Long>()

        runCitationBackfillSchedule(
            runBatch = {
                batches++
                if (batches == 1) {
                    result(deferred = 1, nextEligibleAtMillis = 31_000L)
                } else {
                    result(migrated = 1, nextEligibleAtMillis = null)
                }
            },
            nowMillis = { now },
            delayMillis = { wait ->
                // No second batch (and therefore no second claim attempt) happens before this
                // lease/backoff fence has been awaited.
                assertEquals(1, batches)
                waits += wait
                now += wait
            },
        )

        assertEquals(2, batches)
        assertEquals(listOf(30_000L), waits)
    }

    @Test
    fun fastRestartRecomputesTheRemainingLeaseInsteadOfClaimingEarly() = runBlocking {
        var now = 5_000L
        var firstProcessBatches = 0
        val firstProcess = runCatching {
            runCitationBackfillSchedule(
                runBatch = {
                    firstProcessBatches++
                    result(nextEligibleAtMillis = 125_000L)
                },
                nowMillis = { now },
                delayMillis = { wait ->
                    assertEquals(120_000L, wait)
                    throw CancellationException("process stopped")
                },
            )
        }
        assertTrue(firstProcess.exceptionOrNull() is CancellationException)
        assertEquals(1, firstProcessBatches)

        // A quick restart sees the persisted lease, sleeps only its remaining duration, and then
        // re-reads Room. It does not retain or reuse the first process' claim state.
        now = 65_000L
        var restartedBatches = 0
        val restartWaits = mutableListOf<Long>()
        runCitationBackfillSchedule(
            runBatch = {
                restartedBatches++
                if (restartedBatches == 1) {
                    result(nextEligibleAtMillis = 125_000L)
                } else {
                    result(migrated = 1, nextEligibleAtMillis = null)
                }
            },
            nowMillis = { now },
            delayMillis = { wait ->
                restartWaits += wait
                now += wait
            },
        )

        assertEquals(2, restartedBatches)
        assertEquals(listOf(60_000L), restartWaits)
    }

    @Test
    fun immediatelyEligiblePagesAlwaysYieldAndTerminateWhenSettled() = runBlocking {
        var now = 10_000L
        var batches = 0
        val waits = mutableListOf<Long>()

        runCitationBackfillSchedule(
            runBatch = {
                batches++
                if (batches < 3) {
                    result(attempted = 12, hasMore = true, nextEligibleAtMillis = now)
                } else {
                    result(nextEligibleAtMillis = null)
                }
            },
            nowMillis = { now },
            delayMillis = { wait ->
                waits += wait
                now += wait
            },
        )

        assertEquals(3, batches)
        assertEquals(listOf(25L, 25L), waits)
        assertTrue(waits.all { it > 0 })
    }

    private fun result(
        attempted: Int = 0,
        migrated: Int = 0,
        deferred: Int = 0,
        hasMore: Boolean = false,
        nextEligibleAtMillis: Long?,
    ) = CitationBackfillResult(
        attempted = attempted,
        migrated = migrated,
        quarantined = 0,
        deferred = deferred,
        hasMore = hasMore,
        nextEligibleAtMillis = nextEligibleAtMillis,
    )
}

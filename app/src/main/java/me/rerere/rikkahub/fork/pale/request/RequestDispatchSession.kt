package me.rerere.rikkahub.fork.pale.request

import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.ProviderDispatchObserver
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptState

/**
 * One fenced provider dispatch owned by Room's RequestLedger.
 *
 * This class deliberately keeps no lifecycle snapshot. Every callback reloads the current attempt
 * from Room and lets [RequestLedgerRepository] validate the transition. The mutex only serializes
 * callbacks from transports that may report their first response and terminal result concurrently.
 */
class RequestDispatchSession private constructor(
    private val repository: RequestLedgerRepository,
    initialLease: RequestLease,
    val attemptId: RequestAttemptId,
    private val actor: AuditActor,
    private val leaseDurationMillis: Long,
) {
    private val callbackMutex = Mutex()
    private var leaseReleased = false

    var lease: RequestLease = initialLease
        private set

    /** Called by a provider immediately before OkHttp/SSE takes ownership of the request. */
    val dispatchObserver = ProviderDispatchObserver {
        callbackMutex.withLock {
            prepareDispatchLocked()
            advanceIfBehind(
                nextState = RequestAttemptState.RUNNING,
                nextBoundary = BillableBoundary.SENT,
            )
        }
    }

    suspend fun prepareDispatch() = callbackMutex.withLock {
        prepareDispatchLocked()
    }

    suspend fun markResponseStarted() = callbackMutex.withLock {
        val attempt = requireAttempt()
        if (attempt.billableBoundary().isAtLeast(BillableBoundary.RESPONSE_STARTED)) return@withLock
        check(attempt.attemptState() == RequestAttemptState.RUNNING) {
            "A response cannot start from ${attempt.attemptState()}"
        }
        repository.advanceAttempt(
            AdvanceAttemptCommand(
                lease = lease,
                attemptId = attemptId,
                nextState = RequestAttemptState.RUNNING,
                nextBoundary = BillableBoundary.RESPONSE_STARTED,
                actor = actor,
            ),
        )
    }

    suspend fun markResultReceived() = callbackMutex.withLock {
        markResultReceivedLocked()
    }

    /**
     * Commits one durable output and only then advances the request to SUCCEEDED.
     * If output persistence fails, the attempt intentionally remains COMMITTING for local repair.
     */
    suspend fun commitOutputAndSucceed(command: CommitRequestOutputCommand) = callbackMutex.withLock {
        require(command.lease.sameFenceAs(lease)) { "Output command belongs to a different request fence" }
        require(command.attemptId == attemptId) { "Output command belongs to a different attempt" }
        markResultReceivedLocked()
        val output = repository.commitOutput(command.copy(lease = lease))
        repository.advanceAttempt(
            AdvanceAttemptCommand(
                lease = lease,
                attemptId = attemptId,
                nextState = RequestAttemptState.SUCCEEDED,
                nextBoundary = BillableBoundary.RESULT_COMMITTED,
                actor = actor,
            ),
        )
        releaseLeaseLocked()
        output
    }

    /** A provider response proved the request failed; retry policy still inspects the boundary. */
    suspend fun markKnownFailure() = callbackMutex.withLock {
        finishTerminalLocked(RequestAttemptState.FAILED, requireAttempt().billableBoundary())
    }

    /** Incomplete response with known progress. It is never considered a NOT_SENT safe retry. */
    suspend fun markInterrupted() = callbackMutex.withLock {
        finishTerminalLocked(RequestAttemptState.INTERRUPTED, requireAttempt().billableBoundary())
    }

    /** Transport ownership changed but no authoritative outcome can be proved. */
    suspend fun markUnknownOutcome() = callbackMutex.withLock {
        val attempt = requireAttempt()
        check(attempt.billableBoundary() != BillableBoundary.NOT_SENT) {
            "An undispatched attempt cannot have an unknown provider outcome"
        }
        finishTerminalLocked(RequestAttemptState.UNKNOWN_OUTCOME, BillableBoundary.UNKNOWN)
    }

    /** Cancellation before dispatch is safe; after SENT it is conservatively an unknown outcome. */
    suspend fun cancel() = callbackMutex.withLock {
        val attempt = requireAttempt()
        if (attempt.billableBoundary() == BillableBoundary.NOT_SENT) {
            finishTerminalLocked(RequestAttemptState.CANCELLED, BillableBoundary.NOT_SENT)
        } else {
            finishTerminalLocked(RequestAttemptState.UNKNOWN_OUTCOME, BillableBoundary.UNKNOWN)
        }
    }

    suspend fun releaseLease() = callbackMutex.withLock {
        releaseLeaseLocked()
    }

    suspend fun renewLease() = callbackMutex.withLock {
        check(!leaseReleased) { "Cannot renew a released request lease" }
        lease = repository.renewRequestLease(lease, leaseDurationMillis)
        lease
    }

    /** Keeps long provider streams fenced without turning crash recovery into a long fixed timeout. */
    suspend fun <T> withLeaseHeartbeat(
        intervalMillis: Long = (leaseDurationMillis / 3).coerceAtLeast(1L),
        block: suspend () -> T,
    ): T = coroutineScope {
        require(intervalMillis in 1 until leaseDurationMillis) {
            "heartbeat interval must be shorter than the lease duration"
        }
        val heartbeat = launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(intervalMillis)
                if (!renewLeaseForHeartbeat()) break
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private suspend fun prepareDispatchLocked() {
        val attempt = requireAttempt()
        when (attempt.attemptState()) {
            RequestAttemptState.PREPARED -> repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = RequestAttemptState.DISPATCHING,
                    nextBoundary = BillableBoundary.NOT_SENT,
                    actor = actor,
                ),
            )

            RequestAttemptState.DISPATCHING -> check(
                attempt.billableBoundary() == BillableBoundary.NOT_SENT,
            ) { "A SENT dispatch cannot be prepared again" }

            else -> check(false) { "Attempt cannot prepare dispatch from ${attempt.attemptState()}" }
        }
    }

    private suspend fun renewLeaseForHeartbeat(): Boolean = callbackMutex.withLock {
        if (leaseReleased) return@withLock false
        lease = repository.renewRequestLease(lease, leaseDurationMillis)
        true
    }

    private suspend fun markResultReceivedLocked() {
        var attempt = requireAttempt()
        if (attempt.billableBoundary().isAtLeast(BillableBoundary.RESULT_RECEIVED)) return
        if (!attempt.billableBoundary().isAtLeast(BillableBoundary.RESPONSE_STARTED)) {
            check(attempt.attemptState() == RequestAttemptState.RUNNING) {
                "A result cannot arrive from ${attempt.attemptState()}"
            }
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = RequestAttemptState.RUNNING,
                    nextBoundary = BillableBoundary.RESPONSE_STARTED,
                    actor = actor,
                ),
            )
            attempt = requireAttempt()
        }
        check(attempt.attemptState() == RequestAttemptState.RUNNING)
        repository.advanceAttempt(
            AdvanceAttemptCommand(
                lease = lease,
                attemptId = attemptId,
                nextState = RequestAttemptState.COMMITTING,
                nextBoundary = BillableBoundary.RESULT_RECEIVED,
                actor = actor,
            ),
        )
    }

    private suspend fun advanceIfBehind(
        nextState: RequestAttemptState,
        nextBoundary: BillableBoundary,
    ) {
        val attempt = requireAttempt()
        if (attempt.billableBoundary().isAtLeast(nextBoundary)) return
        repository.advanceAttempt(
            AdvanceAttemptCommand(
                lease = lease,
                attemptId = attemptId,
                nextState = nextState,
                nextBoundary = nextBoundary,
                actor = actor,
            ),
        )
    }

    private suspend fun finishTerminalLocked(
        state: RequestAttemptState,
        boundary: BillableBoundary,
    ) {
        val attempt = requireAttempt()
        if (attempt.attemptState().isTerminal()) {
            check(attempt.attemptState() == state && attempt.billableBoundary() == boundary) {
                "Attempt already finished as ${attempt.attemptState()}/${attempt.billableBoundary()}"
            }
        } else {
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = state,
                    nextBoundary = boundary,
                    actor = actor,
                ),
            )
        }
        releaseLeaseLocked()
    }

    private suspend fun releaseLeaseLocked() {
        if (leaseReleased) return
        repository.releaseRequest(lease)
        leaseReleased = true
    }

    private suspend fun requireAttempt(): RequestAttemptEntity =
        repository.getAttempt(attemptId) ?: throw RequestLedgerMissing(attemptId.value)

    companion object {
        suspend fun open(
            repository: RequestLedgerRepository,
            request: NewRequestSpec,
            owner: String,
            leaseDurationMillis: Long,
            attemptId: RequestAttemptId,
            idempotencyKey: String,
            requestFingerprint: String,
            actor: AuditActor = AuditActor.system(owner),
            transportKind: String? = null,
            ownerReplicaId: String? = null,
            foregroundTaskId: String? = null,
            providerGuaranteesIdempotency: Boolean = false,
            acceptsPossibleCharge: Boolean = false,
        ): RequestDispatchSession {
            val created = repository.createRequest(request)
            val lease = repository.claimRequest(RequestId(created.requestId), owner, leaseDurationMillis)
            try {
                val current = repository.getRequest(RequestId(created.requestId))
                    ?: throw RequestLedgerMissing(created.requestId)
                val activeAttempt = current.activeAttemptId?.let { activeId ->
                    repository.getAttempt(RequestAttemptId(activeId))
                        ?: throw RequestLedgerMissing(activeId)
                }
                val attempt = if (activeAttempt != null) {
                    check(!activeAttempt.attemptState().isTerminal()) {
                        "Terminal attempt remained active for request ${created.requestId}"
                    }
                    check(activeAttempt.idempotencyKey == idempotencyKey) {
                        "Active attempt has a different idempotency identity"
                    }
                    check(activeAttempt.requestFingerprint == requestFingerprint) {
                        "Active attempt has a different request fingerprint"
                    }
                    activeAttempt
                } else {
                    repository.beginAttempt(
                        BeginAttemptCommand(
                            lease = lease,
                            attemptId = attemptId,
                            idempotencyKey = idempotencyKey,
                            requestFingerprint = requestFingerprint,
                            actor = actor,
                            transportKind = transportKind,
                            ownerReplicaId = ownerReplicaId,
                            foregroundTaskId = foregroundTaskId,
                            providerGuaranteesIdempotency = providerGuaranteesIdempotency,
                            acceptsPossibleCharge = acceptsPossibleCharge,
                        ),
                    )
                }
                return RequestDispatchSession(
                    repository = repository,
                    initialLease = lease,
                    attemptId = RequestAttemptId(attempt.attemptId),
                    actor = actor,
                    leaseDurationMillis = leaseDurationMillis,
                )
            } catch (failure: Throwable) {
                runCatching { repository.releaseRequest(lease) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

private fun RequestAttemptEntity.attemptState(): RequestAttemptState =
    RequestAttemptState.valueOf(attemptState.uppercase(Locale.ROOT))

private fun RequestAttemptEntity.billableBoundary(): BillableBoundary =
    BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

private fun RequestAttemptState.isTerminal(): Boolean = when (this) {
    RequestAttemptState.SUCCEEDED,
    RequestAttemptState.FAILED,
    RequestAttemptState.CANCELLED,
    RequestAttemptState.INTERRUPTED,
    RequestAttemptState.UNKNOWN_OUTCOME,
    -> true

    else -> false
}

private fun BillableBoundary.isAtLeast(other: BillableBoundary): Boolean {
    if (this == BillableBoundary.UNKNOWN) return true
    if (other == BillableBoundary.UNKNOWN) return this == BillableBoundary.UNKNOWN
    return rank() >= other.rank()
}

private fun BillableBoundary.rank(): Int = when (this) {
    BillableBoundary.NOT_SENT -> 0
    BillableBoundary.SENT -> 1
    BillableBoundary.RESPONSE_STARTED -> 2
    BillableBoundary.RESULT_RECEIVED -> 3
    BillableBoundary.RESULT_COMMITTED -> 4
    BillableBoundary.UNKNOWN -> Int.MAX_VALUE
}

private fun RequestLease.sameFenceAs(other: RequestLease): Boolean =
    requestId == other.requestId && owner == other.owner && fencingEpoch == other.fencingEpoch

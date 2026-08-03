package me.rerere.rikkahub.fork.pale.request

import java.util.Locale
import java.util.UUID
import me.rerere.ai.ui.UIMessage
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptState
import me.rerere.pale.request.RequestState

data class ChatRequestReconcileReport(
    val inspected: Int,
    val committed: Int,
    val unknown: Int,
    val interrupted: Int,
    val failed: Int,
    val failures: List<String>,
)

/**
 * Resolves orphaned paid chat attempts at process start. It never opens provider transport:
 * SENT becomes UNKNOWN, RESPONSE_STARTED becomes INTERRUPTED, and RESULT_RECEIVED is committed
 * only when its exact durable message checkpoint still exists.
 */
class ChatRequestReconciler(
    private val requestRepository: RequestLedgerRepository,
    private val coordinator: ChatProviderStepCoordinator,
    private val loadDurableMessage: suspend (conversationId: String, messageId: String) -> UIMessage?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ownerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
) {
    suspend fun reconcilePending(limit: Int = 500): ChatRequestReconcileReport {
        val candidates = requestRepository.getRequestsByState(
            listOf(RequestState.DISPATCHING, RequestState.RUNNING, RequestState.COMMITTING),
            limit,
        ).filter { request ->
            request.requestKind == "chat_generation" &&
                (request.leaseUntil == null || request.leaseUntil <= nowMillis())
        }
        var committed = 0
        var unknown = 0
        var interrupted = 0
        var failed = 0
        val failures = mutableListOf<String>()

        candidates.forEach { request ->
            runCatching {
                val attemptId = request.activeAttemptId?.let(::RequestAttemptId)
                    ?: return@runCatching
                val attempt = requestRepository.getAttempt(attemptId)
                    ?: throw RequestLedgerMissing(attemptId.value)
                when (attempt.billableBoundary()) {
                    BillableBoundary.NOT_SENT -> Unit
                    BillableBoundary.SENT -> {
                        finishOrphan(
                            requestId = RequestId(request.requestId),
                            attemptId = attemptId,
                            state = RequestAttemptState.UNKNOWN_OUTCOME,
                            boundary = BillableBoundary.UNKNOWN,
                        )
                        unknown++
                    }

                    BillableBoundary.RESPONSE_STARTED -> {
                        finishOrphan(
                            requestId = RequestId(request.requestId),
                            attemptId = attemptId,
                            state = RequestAttemptState.INTERRUPTED,
                            boundary = BillableBoundary.RESPONSE_STARTED,
                        )
                        interrupted++
                    }

                    BillableBoundary.RESULT_RECEIVED -> {
                        if (repairCommittedResult(request, attemptId, attempt.checkpointDigest)) {
                            committed++
                        } else {
                            finishOrphan(
                                requestId = RequestId(request.requestId),
                                attemptId = attemptId,
                                state = RequestAttemptState.FAILED,
                                boundary = BillableBoundary.RESULT_RECEIVED,
                            )
                            failed++
                        }
                    }

                    BillableBoundary.RESULT_COMMITTED -> {
                        // A crash between output insertion and SUCCEEDED is repaired idempotently
                        // through the same checkpoint path.
                        if (repairCommittedResult(request, attemptId, attempt.checkpointDigest)) {
                            committed++
                        } else {
                            throw RequestLedgerConflict(
                                "Committed chat request ${request.requestId} has no valid durable checkpoint",
                            )
                        }
                    }

                    BillableBoundary.UNKNOWN -> Unit
                }
            }.onFailure { failure ->
                failures += "${request.requestId}:${failure.javaClass.simpleName}"
            }
        }
        return ChatRequestReconcileReport(
            inspected = candidates.size,
            committed = committed,
            unknown = unknown,
            interrupted = interrupted,
            failed = failed,
            failures = failures,
        )
    }

    private suspend fun repairCommittedResult(
        request: RequestLedgerEntity,
        attemptId: RequestAttemptId,
        checkpointDigest: String?,
    ): Boolean {
        val conversationId = request.conversationId ?: return false
        val messageId = request.messageId ?: return false
        val message = loadDurableMessage(conversationId, messageId) ?: return false
        if (message.parts.isEmpty() || checkpointDigest == null ||
            coordinator.digestOutput(message) != checkpointDigest
        ) {
            return false
        }
        val requestId = RequestId(request.requestId)
        val lease = requestRepository.claimRequest(requestId, owner(requestId), LEASE_MILLIS)
        try {
            requestRepository.commitOutput(
                CommitRequestOutputCommand(
                    lease = lease,
                    attemptId = attemptId,
                    outputId = chatOutputId(requestId),
                    outputKind = "chat_step",
                    ordinal = 0,
                    contentDigest = coordinator.digestOutput(message),
                    actor = AuditActor.system(owner(requestId)),
                    conversationId = request.conversationId,
                    messageId = request.messageId,
                ),
            )
            requestRepository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = RequestAttemptState.SUCCEEDED,
                    nextBoundary = BillableBoundary.RESULT_COMMITTED,
                    actor = AuditActor.system(owner(requestId)),
                    checkpointDigest = checkpointDigest,
                ),
            )
        } finally {
            runCatching { requestRepository.releaseRequest(lease) }
        }
        return true
    }

    private suspend fun finishOrphan(
        requestId: RequestId,
        attemptId: RequestAttemptId,
        state: RequestAttemptState,
        boundary: BillableBoundary,
    ) {
        val lease = requestRepository.claimRequest(requestId, owner(requestId), LEASE_MILLIS)
        try {
            requestRepository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = state,
                    nextBoundary = boundary,
                    actor = AuditActor.system(owner(requestId)),
                ),
            )
        } finally {
            runCatching { requestRepository.releaseRequest(lease) }
        }
    }

    private fun owner(requestId: RequestId) = "chat-reconcile:$ownerId:${requestId.value}"

    private fun RequestAttemptEntity.billableBoundary(): BillableBoundary =
        BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

    private companion object {
        const val LEASE_MILLIS = 30_000L
    }
}

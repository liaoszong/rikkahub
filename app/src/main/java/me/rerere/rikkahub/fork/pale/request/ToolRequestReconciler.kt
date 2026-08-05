package me.rerere.rikkahub.fork.pale.request

import java.util.Locale
import java.util.UUID
import me.rerere.ai.ui.ToolExecutionState as UiToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.ToolInvocationId
import me.rerere.pale.id.ToolPermissionId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptState
import me.rerere.pale.request.RequestState
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.ToolApprovalState
import me.rerere.pale.request.ToolExecutionState

data class ToolRequestReconcileReport(
    val inspected: Int,
    val committed: Int,
    val unknown: Int,
    val cancelled: Int,
    val failed: Int,
    val failures: List<String>,
    val deferred: Int = 0,
)

/** Repairs only durable local tool results; it never executes a tool during startup. */
class ToolRequestReconciler(
    private val repository: RequestLedgerRepository,
    private val coordinator: ToolExecutionLedgerCoordinator,
    private val loadDurableMessage: suspend (conversationId: String, messageId: String) -> UIMessage?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ownerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
) {
    suspend fun reconcilePending(limit: Int = 500): ToolRequestReconcileReport {
        var committed = 0
        var unknown = 0
        var cancelled = 0
        var failed = 0
        var deferred = 0
        val failures = mutableListOf<String>()
        val inspected = repository.forEachRecoverableRequestByKindAndState(
            kinds = listOf(RequestKind.TOOL_CALL, RequestKind.MCP_TOOL_CALL, RequestKind.WORKSPACE_TOOL),
            states = listOf(RequestState.DISPATCHING, RequestState.RUNNING, RequestState.COMMITTING),
            recoveryBefore = nowMillis(),
            pageSize = limit,
        ) { request ->
            runCatching {
                val attemptId = request.activeAttemptId?.let(::RequestAttemptId)
                if (attemptId == null) {
                    settleMissingAttempt(request)
                    failed++
                    return@runCatching
                }
                val attempt = repository.getAttempt(attemptId)
                    ?: throw RequestLedgerMissing(attemptId.value)
                val invocation = repository.getInvocations(RequestId(request.requestId)).singleOrNull()
                if (invocation == null) {
                    failAttemptWithoutInvocation(request, attempt)
                    failed++
                    return@runCatching
                }
                val durableTool = durableTool(request, invocation)
                if (durableTool == null && invocation.toolName == "generate_image" &&
                    repository.getImageRequestsByParent(RequestId(request.requestId)).isNotEmpty()
                ) {
                    // ImageTaskRecoveryCoordinator exclusively owns this local aggregate until
                    // its exact conversation result is durable. Cancelling it here would sever
                    // already-paid child evidence from the parent forever.
                    deferred++
                    return@runCatching
                }
                if (durableTool != null) {
                    repairDurableResult(request, attempt, invocation, durableTool)
                    committed++
                } else if (invocation.executionState in TERMINAL_INVOCATION_STATES) {
                    when (invocation.executionState.toExecutionState()) {
                        ToolExecutionState.UNKNOWN_OUTCOME -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.UNKNOWN_OUTCOME,
                                BillableBoundary.UNKNOWN,
                                ToolExecutionState.UNKNOWN_OUTCOME,
                            )
                            unknown++
                        }

                        ToolExecutionState.CANCELLED -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.CANCELLED,
                                BillableBoundary.NOT_SENT,
                                ToolExecutionState.CANCELLED,
                            )
                            cancelled++
                        }

                        ToolExecutionState.SUCCEEDED,
                        ToolExecutionState.FAILED,
                        -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.FAILED,
                                attempt.billableBoundary(),
                                invocation.executionState.toExecutionState(),
                            )
                            failed++
                        }

                        else -> error("Non-terminal tool invocation reached terminal recovery branch")
                    }
                } else {
                    when (attempt.billableBoundary()) {
                        BillableBoundary.SENT,
                        BillableBoundary.RESPONSE_STARTED,
                        -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.UNKNOWN_OUTCOME,
                                BillableBoundary.UNKNOWN,
                                ToolExecutionState.UNKNOWN_OUTCOME,
                            )
                            unknown++
                        }

                        BillableBoundary.NOT_SENT -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.CANCELLED,
                                BillableBoundary.NOT_SENT,
                                ToolExecutionState.CANCELLED,
                            )
                            cancelled++
                        }

                        BillableBoundary.RESULT_RECEIVED,
                        BillableBoundary.RESULT_COMMITTED,
                        BillableBoundary.UNKNOWN,
                        -> {
                            finishOrphan(
                                request,
                                attempt,
                                invocation,
                                RequestAttemptState.FAILED,
                                attempt.billableBoundary(),
                                ToolExecutionState.FAILED,
                            )
                            failed++
                        }
                    }
                }
            }.onFailure { failure ->
                failures += "${request.requestId}:${failure.javaClass.simpleName}"
            }
        }
        return ToolRequestReconcileReport(
            inspected = inspected,
            committed = committed,
            unknown = unknown,
            cancelled = cancelled,
            failed = failed,
            failures = failures,
            deferred = deferred,
        )
    }

    private suspend fun settleMissingAttempt(request: RequestLedgerEntity) {
        val requestId = RequestId(request.requestId)
        val recoveryOwner = owner(requestId)
        val lease = repository.claimRequest(requestId, recoveryOwner, LEASE_MILLIS)
        try {
            repository.settleOrphanedRequestWithoutAttempt(
                lease = lease,
                actor = AuditActor.system(recoveryOwner),
                reason = "missing_active_attempt",
            )
        } finally {
            runCatching { repository.releaseRequest(lease) }
        }
    }

    private suspend fun failAttemptWithoutInvocation(
        request: RequestLedgerEntity,
        attempt: RequestAttemptEntity,
    ) {
        val requestId = RequestId(request.requestId)
        val recoveryOwner = owner(requestId)
        val lease = repository.claimRequest(requestId, recoveryOwner, LEASE_MILLIS)
        try {
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = RequestAttemptId(attempt.attemptId),
                    nextState = RequestAttemptState.FAILED,
                    nextBoundary = attempt.billableBoundary(),
                    actor = AuditActor.system(recoveryOwner),
                ),
            )
        } finally {
            runCatching { repository.releaseRequest(lease) }
        }
    }

    private suspend fun durableTool(
        request: RequestLedgerEntity,
        invocation: ToolInvocationEntity,
    ): UIMessagePart.Tool? {
        val conversationId = request.conversationId ?: return null
        val messageId = request.messageId ?: return null
        val message = loadDurableMessage(conversationId, messageId) ?: return null
        return message.parts.filterIsInstance<UIMessagePart.Tool>().singleOrNull { tool ->
            tool.requestId == request.requestId &&
                tool.toolCallId == invocation.providerToolCallId &&
                (tool.executionState == UiToolExecutionState.SUCCEEDED ||
                    tool.executionState == UiToolExecutionState.FAILED)
        }
    }

    private suspend fun repairDurableResult(
        request: RequestLedgerEntity,
        attempt: RequestAttemptEntity,
        invocation: ToolInvocationEntity,
        result: UIMessagePart.Tool,
    ) {
        val requestId = RequestId(request.requestId)
        val attemptId = RequestAttemptId(attempt.attemptId)
        val invocationId = ToolInvocationId(invocation.invocationId)
        val permissionId = invocation.permissionId?.let(::ToolPermissionId)
            ?: throw RequestLedgerConflict("Tool invocation lost permission evidence")
        val actor = AuditActor.system(owner(requestId))
        val digest = coordinator.digestOutput(result)
        if (attempt.billableBoundary() == BillableBoundary.RESULT_RECEIVED ||
            attempt.billableBoundary() == BillableBoundary.RESULT_COMMITTED
        ) {
            check(attempt.checkpointDigest == digest) {
                "Durable tool result no longer matches its received-result checkpoint"
            }
        }
        validateInvocationResult(invocation, result)
        val lease = repository.claimRequest(requestId, owner(requestId), LEASE_MILLIS)
        try {
            var currentAttempt = repository.getAttempt(attemptId) ?: throw RequestLedgerMissing(attemptId.value)
            if (currentAttempt.attemptState() == RequestAttemptState.DISPATCHING) {
                repository.advanceAttempt(
                    AdvanceAttemptCommand(
                        lease = lease,
                        attemptId = attemptId,
                        nextState = RequestAttemptState.RUNNING,
                        nextBoundary = currentAttempt.billableBoundary(),
                        actor = actor,
                    ),
                )
                currentAttempt = repository.getAttempt(attemptId)!!
            }
            advanceInvocationForDurableResult(
                lease = lease,
                invocationId = invocationId,
                permissionId = permissionId,
                result = result,
                resultDigest = digest,
                actor = actor,
            )
            if (currentAttempt.attemptState() == RequestAttemptState.RUNNING) {
                repository.advanceAttempt(
                    AdvanceAttemptCommand(
                        lease = lease,
                        attemptId = attemptId,
                        nextState = RequestAttemptState.COMMITTING,
                        nextBoundary = BillableBoundary.RESULT_RECEIVED,
                        checkpointDigest = digest,
                        actor = actor,
                    ),
                )
            }
            repository.commitOutput(
                CommitRequestOutputCommand(
                    lease = lease,
                    attemptId = attemptId,
                    outputId = toolOutputId(requestId),
                    outputKind = "tool_result",
                    ordinal = 0,
                    contentDigest = digest,
                    actor = actor,
                    conversationId = request.conversationId,
                    messageId = request.messageId,
                    partId = invocation.providerToolCallId,
                ),
            )
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = RequestAttemptState.SUCCEEDED,
                    nextBoundary = BillableBoundary.RESULT_COMMITTED,
                    checkpointDigest = digest,
                    actor = actor,
                ),
            )
        } finally {
            runCatching { repository.releaseRequest(lease) }
        }
    }

    private suspend fun advanceInvocationForDurableResult(
        lease: RequestLease,
        invocationId: ToolInvocationId,
        permissionId: ToolPermissionId,
        result: UIMessagePart.Tool,
        resultDigest: String,
        actor: AuditActor,
    ) {
        var invocation = repository.getInvocation(invocationId) ?: throw RequestLedgerMissing(invocationId.value)
        val approval = invocation.approvalState.toApprovalState()
        if (invocation.executionState == "ready") {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = lease,
                    invocationId = invocationId,
                    nextApprovalState = approval,
                    nextExecutionState = ToolExecutionState.RUNNING,
                    permissionId = permissionId,
                    actor = actor,
                ),
            )
            invocation = repository.getInvocation(invocationId)!!
        }
        if (result.executionState == UiToolExecutionState.SUCCEEDED) {
            if (invocation.executionState == "running") {
                repository.advanceInvocation(
                    AdvanceToolInvocationCommand(
                        lease = lease,
                        invocationId = invocationId,
                        nextApprovalState = approval,
                        nextExecutionState = ToolExecutionState.COMMITTING,
                        permissionId = permissionId,
                        actor = actor,
                    ),
                )
                invocation = repository.getInvocation(invocationId)!!
            }
            if (invocation.executionState == "committing") {
                repository.advanceInvocation(
                    AdvanceToolInvocationCommand(
                        lease = lease,
                        invocationId = invocationId,
                        nextApprovalState = approval,
                        nextExecutionState = ToolExecutionState.SUCCEEDED,
                        permissionId = permissionId,
                        resultDigest = resultDigest,
                        actor = actor,
                    ),
                )
            }
        } else if (invocation.executionState == "running") {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = lease,
                    invocationId = invocationId,
                    nextApprovalState = approval,
                    nextExecutionState = ToolExecutionState.FAILED,
                    permissionId = permissionId,
                    errorKind = "tool_error",
                    actor = actor,
                ),
            )
        }
    }

    private suspend fun finishOrphan(
        request: RequestLedgerEntity,
        attempt: RequestAttemptEntity,
        invocation: ToolInvocationEntity,
        attemptState: RequestAttemptState,
        boundary: BillableBoundary,
        invocationState: ToolExecutionState,
    ) {
        val requestId = RequestId(request.requestId)
        val lease = repository.claimRequest(requestId, owner(requestId), LEASE_MILLIS)
        try {
            val currentInvocation = repository.getInvocation(ToolInvocationId(invocation.invocationId))!!
            val currentState = currentInvocation.executionState.toExecutionState()
            val nextInvocationState = when {
                currentState == ToolExecutionState.COMMITTING -> ToolExecutionState.FAILED
                currentState == ToolExecutionState.SUCCEEDED -> null
                currentState == ToolExecutionState.FAILED -> null
                currentState == ToolExecutionState.CANCELLED -> null
                currentState == ToolExecutionState.UNKNOWN_OUTCOME -> null
                else -> invocationState
            }
            if (nextInvocationState != null) {
                repository.advanceInvocation(
                    AdvanceToolInvocationCommand(
                        lease = lease,
                        invocationId = ToolInvocationId(currentInvocation.invocationId),
                        nextApprovalState = currentInvocation.approvalState.toApprovalState(),
                        nextExecutionState = nextInvocationState,
                        permissionId = currentInvocation.permissionId?.let(::ToolPermissionId),
                        errorKind = "startup_orphan",
                        actor = AuditActor.system(owner(requestId)),
                    ),
                )
            }
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = RequestAttemptId(attempt.attemptId),
                    nextState = attemptState,
                    nextBoundary = boundary,
                    actor = AuditActor.system(owner(requestId)),
                ),
            )
        } finally {
            runCatching { repository.releaseRequest(lease) }
        }
    }

    private fun owner(requestId: RequestId) = "tool-reconcile:$ownerId:${requestId.value}"

    private fun validateInvocationResult(
        invocation: ToolInvocationEntity,
        result: UIMessagePart.Tool,
    ) {
        val state = invocation.executionState.toExecutionState()
        if (state == ToolExecutionState.SUCCEEDED) {
            check(invocation.resultDigest == coordinator.digestOutput(result)) {
                "Durable tool result no longer matches the invocation result digest"
            }
        }
        val allowed = when (result.executionState) {
            UiToolExecutionState.SUCCEEDED -> state == ToolExecutionState.READY ||
                state == ToolExecutionState.RUNNING ||
                state == ToolExecutionState.COMMITTING ||
                state == ToolExecutionState.SUCCEEDED

            UiToolExecutionState.FAILED -> state == ToolExecutionState.READY ||
                state == ToolExecutionState.RUNNING ||
                state == ToolExecutionState.FAILED

            else -> false
        }
        check(allowed) {
            "Durable ${result.executionState} result conflicts with invocation $state"
        }
    }

    private fun RequestAttemptEntity.attemptState() =
        RequestAttemptState.valueOf(attemptState.uppercase(Locale.ROOT))

    private fun RequestAttemptEntity.billableBoundary() =
        BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

    private fun String.toApprovalState() = ToolApprovalState.valueOf(uppercase(Locale.ROOT))

    private fun String.toExecutionState() = ToolExecutionState.valueOf(uppercase(Locale.ROOT))

    private companion object {
        const val LEASE_MILLIS = 30_000L
        val TERMINAL_INVOCATION_STATES = setOf(
            "succeeded", "failed", "cancelled", "unknown_outcome",
        )
    }
}

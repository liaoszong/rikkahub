package me.rerere.rikkahub.fork.pale.request

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestAuditEventId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.id.ToolAuditEventId
import me.rerere.pale.id.ToolInvocationId
import me.rerere.pale.id.ToolPermissionId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptLifecycle
import me.rerere.pale.request.RequestAttemptState
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.RequestLifecycle
import me.rerere.pale.request.RequestRetryPolicy
import me.rerere.pale.request.RequestState
import me.rerere.pale.request.ToolApprovalState
import me.rerere.pale.request.ToolExecutionState
import me.rerere.pale.request.ToolPermissionDecision
import me.rerere.pale.request.ToolPermissionScope
import me.rerere.pale.request.ToolSideEffectClass
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * The only write authority for Room 29 request, attempt, output, permission, and tool evidence.
 *
 * Every public mutation is a single SQLite transaction. Provider adapters receive a [RequestLease]
 * and cannot advance state after another owner has reclaimed the request. UI models are projections
 * of this repository; they are never accepted as persistence authority.
 */
class RequestLedgerRepository(
    private val database: AppDatabase,
    private val dao: RequestLedgerDAO = database.requestLedgerDao(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val requestAuditId: () -> String = { RequestAuditEventId.random().value },
    private val toolAuditId: () -> String = { ToolAuditEventId.random().value },
    private val faultInjector: RequestLedgerFaultInjector = RequestLedgerFaultInjector.NONE,
) {
    suspend fun createRequest(spec: NewRequestSpec): RequestLedgerEntity = database.withTransaction {
        require(spec.intentKey.isNotBlank()) { "intentKey must not be blank" }
        require(spec.inputDigest.isNotBlank()) { "inputDigest must not be blank" }
        require(spec.capabilitySnapshotJson.isNotBlank()) { "capability snapshot must not be blank" }
        dao.getRequestByIntentKey(spec.intentKey)?.let { existing ->
            if (existing.matches(spec)) return@withTransaction existing
            throw RequestLedgerIdentityConflict("Intent key already belongs to a different request")
        }

        val now = nowMillis()
        val entity = RequestLedgerEntity(
            requestId = spec.requestId.value,
            intentKey = spec.intentKey,
            parentRequestId = spec.parentRequestId?.value,
            requestKind = spec.kind.dbValue(),
            conversationId = spec.conversationId,
            assistantId = spec.assistantId,
            messageId = spec.messageId,
            partId = spec.partId,
            workspaceId = spec.workspaceId,
            mcpServerId = spec.mcpServerId,
            credentialRefId = spec.credentialRefId,
            providerKind = spec.providerKind,
            providerId = spec.providerId,
            modelId = spec.modelId,
            apiSurface = spec.apiSurface,
            inputDigest = spec.inputDigest,
            capabilitySnapshotJson = spec.capabilitySnapshotJson,
            resolverVersion = spec.resolverVersion,
            toolCatalogDigest = spec.toolCatalogDigest,
            approvalState = spec.approvalState.dbValue(),
            requestState = RequestState.CREATED.dbValue(),
            billableBoundary = BillableBoundary.NOT_SENT.dbValue(),
            createdAt = now,
            updatedAt = now,
        )
        dao.insertRequest(entity)
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_REQUEST_INSERT)
        appendRequestAudit(
            requestId = entity.requestId,
            eventKind = "request_created",
            actor = spec.actor,
            payload = buildJsonObject {
                put("request_kind", entity.requestKind)
                put("intent_key_digest", sha256(spec.intentKey))
            },
            now = now,
        )
        entity
    }

    suspend fun claimRequest(requestId: RequestId, owner: String, leaseDurationMillis: Long): RequestLease =
        database.withTransaction {
            require(owner.isNotBlank()) { "lease owner must not be blank" }
            require(leaseDurationMillis > 0) { "lease duration must be positive" }
            val now = nowMillis()
            val leaseUntil = Math.addExact(now, leaseDurationMillis)
            if (dao.claimRequest(requestId.value, owner, now, leaseUntil) != 1) {
                throw RequestLedgerLeaseConflict(requestId.value)
            }
            faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_LEASE_CLAIM)
            val claimed = dao.getRequest(requestId.value) ?: throw RequestLedgerMissing(requestId.value)
            appendRequestAudit(
                requestId = requestId.value,
                eventKind = "lease_claimed",
                actor = AuditActor.system(owner),
                payload = buildJsonObject {
                    put("fencing_epoch", claimed.fencingEpoch)
                    put("lease_until", leaseUntil)
                },
                now = now,
            )
            RequestLease(requestId, owner, claimed.fencingEpoch, leaseUntil)
        }

    suspend fun releaseRequest(lease: RequestLease) = database.withTransaction {
        val now = nowMillis()
        requireLease(lease, now)
        if (dao.releaseRequest(lease.requestId.value, lease.owner, lease.fencingEpoch, now) != 1) {
            throw RequestLedgerLeaseConflict(lease.requestId.value)
        }
        appendRequestAudit(
            requestId = lease.requestId.value,
            eventKind = "lease_released",
            actor = AuditActor.system(lease.owner),
            payload = buildJsonObject { put("fencing_epoch", lease.fencingEpoch) },
            now = now,
        )
    }

    suspend fun beginAttempt(command: BeginAttemptCommand): RequestAttemptEntity = database.withTransaction {
        val now = nowMillis()
        var request = requireLease(command.lease, now)
        check(request.activeAttemptId == null) { "Request already has an active attempt" }
        val currentState = request.requestState.asRequestState()
        val currentBoundary = request.billableBoundary.asBoundary()

        request = when {
            currentState.isTerminal -> {
                if (!RequestRetryPolicy.canCreateAttempt(
                        state = currentState,
                        boundary = currentBoundary,
                        providerGuaranteesIdempotency = command.providerGuaranteesIdempotency,
                        acceptsPossibleCharge = command.acceptsPossibleCharge,
                    )
                ) {
                    throw RequestLedgerRetryRejected(request.requestId)
                }
                transitionRequestInTransaction(
                    request = request,
                    nextState = RequestState.QUEUED,
                    nextBoundary = currentBoundary,
                    lease = command.lease,
                    actor = command.actor,
                    now = now,
                    explicitRetry = true,
                    providerGuaranteesIdempotency = command.providerGuaranteesIdempotency,
                    acceptsPossibleCharge = command.acceptsPossibleCharge,
                )
            }

            currentState == RequestState.CREATED -> transitionRequestInTransaction(
                request = request,
                nextState = RequestState.QUEUED,
                nextBoundary = currentBoundary,
                lease = command.lease,
                actor = command.actor,
                now = now,
            )

            currentState == RequestState.QUEUED || currentState == RequestState.WAITING_RUNTIME -> request
            else -> throw RequestLedgerConflict("Cannot prepare an attempt from $currentState")
        }

        require(command.idempotencyKey.isNotBlank()) { "idempotency key must not be blank" }
        require(command.requestFingerprint.isNotBlank()) { "request fingerprint must not be blank" }
        val attempt = RequestAttemptEntity(
            attemptId = command.attemptId.value,
            requestId = request.requestId,
            attemptOrdinal = Math.addExact(request.attemptCount, 1),
            idempotencyKey = command.idempotencyKey,
            attemptState = RequestAttemptState.PREPARED.dbValue(),
            billableBoundary = BillableBoundary.NOT_SENT.dbValue(),
            transportKind = command.transportKind,
            requestFingerprint = command.requestFingerprint,
            ownerReplicaId = command.ownerReplicaId,
            foregroundTaskId = command.foregroundTaskId,
            preparedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertAttempt(attempt)
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_ATTEMPT_INSERT)
        if (dao.activateAttempt(
                request.requestId,
                attempt.attemptId,
                command.lease.owner,
                command.lease.fencingEpoch,
                now,
            ) != 1
        ) {
            throw RequestLedgerConflict("Attempt activation lost its request fence")
        }
        appendRequestAudit(
            requestId = request.requestId,
            attemptId = attempt.attemptId,
            eventKind = "attempt_prepared",
            actor = command.actor,
            payload = buildJsonObject {
                put("attempt_ordinal", attempt.attemptOrdinal)
                put("request_fingerprint", attempt.requestFingerprint)
            },
            now = now,
        )
        attempt
    }

    suspend fun advanceAttempt(command: AdvanceAttemptCommand): RequestAttemptEntity = database.withTransaction {
        val now = nowMillis()
        val request = requireLease(command.lease, now)
        val attempt = dao.getAttempt(command.attemptId.value)
            ?: throw RequestLedgerMissing(command.attemptId.value)
        check(attempt.requestId == request.requestId && request.activeAttemptId == attempt.attemptId) {
            "Attempt is not active for the leased request"
        }
        val currentAttemptState = attempt.attemptState.asAttemptState()
        val currentAttemptBoundary = attempt.billableBoundary.asBoundary()
        check(RequestAttemptLifecycle.canTransition(currentAttemptState, command.nextState)) {
            "Illegal attempt transition: $currentAttemptState -> ${command.nextState}"
        }
        check(currentAttemptBoundary.canAdvanceTo(command.nextBoundary)) {
            "Attempt billing boundary cannot move backwards"
        }
        if (command.nextState == RequestAttemptState.COMMITTING) {
            check(
                command.nextBoundary == BillableBoundary.RESULT_RECEIVED ||
                    command.nextBoundary == BillableBoundary.RESULT_COMMITTED,
            ) { "COMMITTING requires a received result" }
        }
        if (command.nextState == RequestAttemptState.SUCCEEDED) {
            check(command.nextBoundary == BillableBoundary.RESULT_COMMITTED) {
                "SUCCEEDED requires RESULT_COMMITTED"
            }
            check(dao.getOutputs(request.requestId).any { it.attemptId == attempt.attemptId }) {
                "SUCCEEDED requires at least one explicit request output"
            }
        }
        if (currentAttemptState == command.nextState && currentAttemptBoundary == command.nextBoundary) {
            return@withTransaction attempt
        }

        val terminal = command.nextState.isTerminal()
        if (dao.transitionAttempt(
                attemptId = attempt.attemptId,
                requestId = request.requestId,
                expectedState = attempt.attemptState,
                nextState = command.nextState.dbValue(),
                expectedBoundary = attempt.billableBoundary,
                nextBoundary = command.nextBoundary.dbValue(),
                expectedStateRevision = attempt.stateRevision,
                owner = command.lease.owner,
                fencingEpoch = command.lease.fencingEpoch,
                sentAt = now.takeIf { command.nextBoundary.isAtLeast(BillableBoundary.SENT) },
                acknowledgedAt = now.takeIf {
                    command.nextBoundary.isAtLeast(BillableBoundary.RESPONSE_STARTED)
                },
                firstByteAt = now.takeIf {
                    command.nextBoundary.isAtLeast(BillableBoundary.RESPONSE_STARTED)
                },
                resultReceivedAt = now.takeIf {
                    command.nextBoundary.isAtLeast(BillableBoundary.RESULT_RECEIVED)
                },
                commitStartedAt = now.takeIf {
                    command.nextState == RequestAttemptState.COMMITTING ||
                        command.nextState == RequestAttemptState.SUCCEEDED
                },
                finishedAt = now.takeIf { terminal },
                now = now,
            ) != 1
        ) {
            throw RequestLedgerConflict("Attempt state changed concurrently")
        }
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_ATTEMPT_STATE_CAS)

        val requestBoundary = request.billableBoundary.asBoundary()
        val aggregateBoundary = requestBoundary.aggregate(command.nextBoundary)
        transitionRequestInTransaction(
            request = request,
            nextState = command.nextState.toRequestState(),
            nextBoundary = aggregateBoundary,
            lease = command.lease,
            actor = command.actor,
            now = now,
        )
        if (terminal && dao.clearActiveAttempt(
                request.requestId,
                attempt.attemptId,
                command.lease.owner,
                command.lease.fencingEpoch,
                now,
            ) != 1
        ) {
            throw RequestLedgerConflict("Terminal attempt could not release active ownership")
        }
        appendRequestAudit(
            requestId = request.requestId,
            attemptId = attempt.attemptId,
            eventKind = "attempt_${command.nextState.dbValue()}",
            actor = command.actor,
            payload = buildJsonObject { put("billable_boundary", command.nextBoundary.dbValue()) },
            now = now,
        )
        dao.getAttempt(attempt.attemptId) ?: throw RequestLedgerMissing(attempt.attemptId)
    }

    suspend fun commitOutput(command: CommitRequestOutputCommand): RequestOutputEntity =
        database.withTransaction {
            val now = nowMillis()
            val request = requireLease(command.lease, now)
            val attempt = dao.getAttempt(command.attemptId.value)
                ?: throw RequestLedgerMissing(command.attemptId.value)
            check(request.activeAttemptId == attempt.attemptId && attempt.requestId == request.requestId) {
                "Output attempt is not active for the leased request"
            }
            check(attempt.attemptState == RequestAttemptState.COMMITTING.dbValue()) {
                "Outputs can only be committed while the attempt is COMMITTING"
            }
            dao.getOutputBySlot(request.requestId, command.outputKind, command.ordinal)?.let { existing ->
                if (existing.matches(command, request.requestId, attempt.attemptId)) {
                    return@withTransaction existing
                }
                throw RequestLedgerIdentityConflict("Output slot already contains different evidence")
            }
            val output = RequestOutputEntity(
                outputId = command.outputId.value,
                requestId = request.requestId,
                attemptId = attempt.attemptId,
                outputKind = command.outputKind,
                ordinal = command.ordinal,
                conversationId = command.conversationId,
                messageId = command.messageId,
                partId = command.partId,
                assetId = command.assetId,
                sourceId = command.sourceId,
                contentDigest = command.contentDigest,
                committedAt = now,
            )
            dao.insertOutput(output)
            faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_OUTPUT_INSERT)
            appendRequestAudit(
                requestId = request.requestId,
                attemptId = attempt.attemptId,
                eventKind = "output_committed",
                actor = command.actor,
                payload = buildJsonObject {
                    put("output_id", output.outputId)
                    put("output_kind", output.outputKind)
                    put("ordinal", output.ordinal)
                    put("content_digest", output.contentDigest)
                },
                now = now,
            )
            output
        }

    suspend fun createPermission(spec: NewToolPermissionSpec): ToolPermissionEntity = database.withTransaction {
        dao.getPermission(spec.permissionKey)?.let { existing ->
            if (existing.matches(spec)) return@withTransaction existing
            throw RequestLedgerIdentityConflict("Permission key already contains different policy")
        }
        val now = nowMillis()
        val permission = ToolPermissionEntity(
            permissionId = spec.permissionId.value,
            permissionKey = spec.permissionKey,
            sourceRequestId = spec.sourceRequestId?.value,
            principalKind = spec.principalKind,
            principalId = spec.principalId,
            serverId = spec.serverId,
            toolName = spec.toolName,
            action = spec.action,
            schemaDigest = spec.schemaDigest,
            decision = spec.decision.dbValue(),
            scopeKind = spec.scope.dbValue(),
            scopeId = spec.scopeId,
            constraintsJson = spec.constraintsJson,
            capabilitySnapshotJson = spec.capabilitySnapshotJson,
            policyVersion = spec.policyVersion,
            reason = spec.reason,
            decidedAt = now,
            expiresAt = spec.expiresAt,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertPermission(permission)
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_PERMISSION_INSERT)
        appendToolAudit(
            requestId = permission.sourceRequestId,
            permissionId = permission.permissionId,
            eventKind = "permission_created",
            actor = spec.actor,
            summary = "Tool permission created",
            payload = buildJsonObject {
                put("decision", permission.decision)
                put("scope", permission.scopeKind)
                put("schema_digest", permission.schemaDigest)
            },
            now = now,
        )
        permission
    }

    suspend fun decidePermission(command: DecideToolPermissionCommand): ToolPermissionEntity =
        database.withTransaction {
            val current = dao.getPermissionById(command.permissionId.value)
                ?: throw RequestLedgerMissing(command.permissionId.value)
            val now = nowMillis()
            val currentDecision = current.decision.asPermissionDecision()
            if (currentDecision == command.decision) return@withTransaction current
            check(ToolPermissionLifecycle.canTransition(currentDecision, command.decision)) {
                "Illegal permission transition: $currentDecision -> ${command.decision}"
            }
            if (command.decision == ToolPermissionDecision.ALLOW) {
                check(current.expiresAt == null || current.expiresAt > now) {
                    "Expired permission cannot be allowed"
                }
            }
            if (dao.updatePermissionDecision(
                    permissionId = current.permissionId,
                    expectedDecision = current.decision,
                    expectedStateRevision = current.stateRevision,
                    decision = command.decision.dbValue(),
                    reason = command.reason,
                    revokedAt = now.takeIf { command.decision == ToolPermissionDecision.REVOKED },
                    now = now,
                ) != 1
            ) {
                throw RequestLedgerConflict("Permission decision changed concurrently")
            }
            faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_PERMISSION_STATE_CAS)
            appendToolAudit(
                requestId = current.sourceRequestId,
                permissionId = current.permissionId,
                eventKind = "permission_${command.decision.dbValue()}",
                actor = command.actor,
                summary = "Tool permission decision changed",
                payload = buildJsonObject {
                    put("previous_decision", current.decision)
                    put("decision", command.decision.dbValue())
                    put("state_revision", current.stateRevision + 1)
                },
                now = now,
            )
            dao.getPermissionById(current.permissionId) ?: throw RequestLedgerMissing(current.permissionId)
        }

    suspend fun createInvocation(spec: NewToolInvocationSpec): ToolInvocationEntity =
        database.withTransaction {
            val now = nowMillis()
            val request = requireLease(spec.lease, now)
            check(request.activeAttemptId == spec.attemptId.value) {
                "Tool invocation must belong to the active request attempt"
            }
            val permissionId = spec.permissionId
                ?: throw RequestLedgerConflict("Every tool invocation requires policy evidence")
            val permission = dao.getPermissionById(permissionId.value)
                ?: throw RequestLedgerMissing(permissionId.value)
            validatePermissionBinding(
                permission = permission,
                request = request,
                invocationId = spec.invocationId.value,
                serverId = spec.serverId,
                toolName = spec.toolName,
                principalKind = spec.principalKind,
                principalId = spec.principalId,
                action = spec.action,
                schemaDigest = spec.schemaDigest,
            )
            validateApprovalAgainstPermission(
                permission = permission,
                approval = spec.approvalState,
                now = now,
                executionRequiresAllow = false,
            )
            dao.getInvocationByProviderCall(
                request.requestId,
                spec.attemptId.value,
                spec.providerToolCallId,
            )?.let { existing ->
                if (existing.matches(spec, request.requestId, permission.permissionId)) {
                    return@withTransaction existing
                }
                throw RequestLedgerIdentityConflict(
                    "Provider tool call already belongs to a different invocation",
                )
            }
            val invocation = ToolInvocationEntity(
                invocationId = spec.invocationId.value,
                requestId = request.requestId,
                attemptId = spec.attemptId.value,
                providerToolCallId = spec.providerToolCallId,
                serverId = spec.serverId,
                toolName = spec.toolName,
                principalKind = spec.principalKind,
                principalId = spec.principalId,
                action = spec.action,
                schemaDigest = spec.schemaDigest,
                inputDigest = spec.inputDigest,
                sideEffectClass = spec.sideEffectClass.dbValue(),
                approvalState = spec.approvalState.dbValue(),
                executionState = ToolExecutionState.CREATED.dbValue(),
                permissionId = permission.permissionId,
                createdAt = now,
                updatedAt = now,
            )
            dao.insertInvocation(invocation)
            faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_INVOCATION_INSERT)
            appendToolAudit(
                requestId = request.requestId,
                invocationId = invocation.invocationId,
                permissionId = invocation.permissionId,
                eventKind = "invocation_created",
                actor = spec.actor,
                summary = "Tool invocation created",
                payload = buildJsonObject {
                    put("tool_name", invocation.toolName)
                    put("schema_digest", invocation.schemaDigest)
                    put("input_digest", invocation.inputDigest)
                    put("side_effect_class", invocation.sideEffectClass)
                },
                now = now,
            )
            invocation
        }

    suspend fun advanceInvocation(command: AdvanceToolInvocationCommand): ToolInvocationEntity =
        database.withTransaction {
            val now = nowMillis()
            val request = requireLease(command.lease, now)
            val current = dao.getInvocation(command.invocationId.value)
                ?: throw RequestLedgerMissing(command.invocationId.value)
            check(current.requestId == request.requestId && request.activeAttemptId == current.attemptId) {
                "Tool invocation is not owned by the active request attempt"
            }
            val currentExecution = current.executionState.asToolExecutionState()
            check(ToolInvocationLifecycle.canTransition(currentExecution, command.nextExecutionState)) {
                "Illegal tool invocation transition: $currentExecution -> ${command.nextExecutionState}"
            }
            validateApprovalForExecution(command.nextApprovalState, command.nextExecutionState)
            val permissionId = current.permissionId
                ?: throw RequestLedgerConflict("Tool invocation lost its policy evidence")
            check(command.permissionId == null || command.permissionId.value == permissionId) {
                "Tool invocation cannot switch permission evidence"
            }
            val permission = dao.getPermissionById(permissionId)
                ?: throw RequestLedgerMissing(permissionId)
            validatePermissionBinding(
                permission = permission,
                request = request,
                invocationId = current.invocationId,
                serverId = current.serverId,
                toolName = current.toolName,
                principalKind = current.principalKind,
                principalId = current.principalId,
                action = current.action,
                schemaDigest = current.schemaDigest,
            )
            validateApprovalAgainstPermission(
                permission = permission,
                approval = command.nextApprovalState,
                now = now,
                executionRequiresAllow = command.nextExecutionState.requiresAuthorization(),
            )
            if (command.nextExecutionState == ToolExecutionState.SUCCEEDED) {
                check(!command.resultDigest.isNullOrBlank()) {
                    "Successful tool invocation requires a durable result digest"
                }
            }
            if (currentExecution == command.nextExecutionState &&
                current.approvalState == command.nextApprovalState.dbValue() &&
                current.resultDigest == command.resultDigest
            ) {
                return@withTransaction current
            }
            if (dao.transitionInvocation(
                    invocationId = current.invocationId,
                    requestId = current.requestId,
                    expectedApprovalState = current.approvalState,
                    nextApprovalState = command.nextApprovalState.dbValue(),
                    expectedExecutionState = current.executionState,
                    nextExecutionState = command.nextExecutionState.dbValue(),
                    expectedStateRevision = current.stateRevision,
                    owner = command.lease.owner,
                    fencingEpoch = command.lease.fencingEpoch,
                    permissionId = permissionId,
                    resultDigest = command.resultDigest,
                    errorKind = command.errorKind,
                    errorCode = command.errorCode,
                    now = now,
                ) != 1
            ) {
                throw RequestLedgerConflict("Tool invocation changed concurrently")
            }
            faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_INVOCATION_STATE_CAS)
            appendToolAudit(
                requestId = current.requestId,
                invocationId = current.invocationId,
                permissionId = permissionId,
                eventKind = "invocation_${command.nextExecutionState.dbValue()}",
                actor = command.actor,
                summary = "Tool invocation state changed",
                payload = buildJsonObject {
                    put("approval_state", command.nextApprovalState.dbValue())
                    put("execution_state", command.nextExecutionState.dbValue())
                    command.resultDigest?.let { put("result_digest", it) }
                    command.errorKind?.let { put("error_kind", it) }
                    command.errorCode?.let { put("error_code", it) }
                },
                now = now,
            )
            dao.getInvocation(current.invocationId) ?: throw RequestLedgerMissing(current.invocationId)
        }

    private suspend fun transitionRequestInTransaction(
        request: RequestLedgerEntity,
        nextState: RequestState,
        nextBoundary: BillableBoundary,
        lease: RequestLease,
        actor: AuditActor,
        now: Long,
        explicitRetry: Boolean = false,
        providerGuaranteesIdempotency: Boolean = false,
        acceptsPossibleCharge: Boolean = false,
    ): RequestLedgerEntity {
        val currentState = request.requestState.asRequestState()
        val currentBoundary = request.billableBoundary.asBoundary()
        check(RequestLifecycle.canTransition(
            from = currentState,
            to = nextState,
            explicitRetry = explicitRetry,
            acceptsPossibleCharge = acceptsPossibleCharge,
        )) { "Illegal request transition: $currentState -> $nextState" }
        check(currentBoundary.canAdvanceTo(nextBoundary)) { "Request billing boundary cannot move backwards" }
        if (currentState == nextState && currentBoundary == nextBoundary) return request
        if (dao.transitionRequest(
                requestId = request.requestId,
                expectedState = request.requestState,
                nextState = nextState.dbValue(),
                expectedBoundary = request.billableBoundary,
                nextBoundary = nextBoundary.dbValue(),
                expectedStateRevision = request.stateRevision,
                owner = lease.owner,
                fencingEpoch = lease.fencingEpoch,
                now = now,
                explicitRetry = explicitRetry,
                providerGuaranteesIdempotency = providerGuaranteesIdempotency,
                acceptsPossibleCharge = acceptsPossibleCharge,
            ) != 1
        ) {
            throw RequestLedgerConflict("Request state changed concurrently")
        }
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_REQUEST_STATE_CAS)
        appendRequestAudit(
            requestId = request.requestId,
            eventKind = "request_${nextState.dbValue()}",
            actor = actor,
            payload = buildJsonObject {
                put("previous_state", request.requestState)
                put("request_state", nextState.dbValue())
                put("billable_boundary", nextBoundary.dbValue())
            },
            now = now,
        )
        return dao.getRequest(request.requestId) ?: throw RequestLedgerMissing(request.requestId)
    }

    private suspend fun requireLease(lease: RequestLease, now: Long): RequestLedgerEntity {
        val request = dao.getRequest(lease.requestId.value) ?: throw RequestLedgerMissing(lease.requestId.value)
        if (request.leaseOwner != lease.owner ||
            request.fencingEpoch != lease.fencingEpoch ||
            request.leaseUntil == null || request.leaseUntil <= now ||
            lease.leaseUntil <= now
        ) {
            throw RequestLedgerLeaseConflict(lease.requestId.value)
        }
        return request
    }

    private suspend fun appendRequestAudit(
        requestId: String,
        eventKind: String,
        actor: AuditActor,
        payload: JsonObject,
        now: Long,
        attemptId: String? = null,
        invocationId: String? = null,
        permissionId: String? = null,
    ) {
        val payloadJson = payload.toString()
        dao.appendRequestAudit(
            RequestAuditEventEntity(
                eventId = requestAuditId(),
                requestId = requestId,
                eventSeq = dao.nextRequestAuditSequence(requestId),
                attemptId = attemptId,
                invocationId = invocationId,
                permissionId = permissionId,
                eventKind = eventKind,
                actorKind = actor.kind,
                actorId = actor.id,
                payloadDigest = sha256(payloadJson),
                payloadJson = payloadJson,
                createdAt = now,
            ),
        )
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_REQUEST_AUDIT_INSERT)
    }

    private suspend fun appendToolAudit(
        requestId: String?,
        eventKind: String,
        actor: AuditActor,
        summary: String,
        payload: JsonObject,
        now: Long,
        invocationId: String? = null,
        permissionId: String? = null,
    ) {
        val payloadJson = payload.toString()
        dao.appendToolAudit(
            ToolAuditEventEntity(
                eventId = toolAuditId(),
                requestId = requestId,
                invocationId = invocationId,
                permissionId = permissionId,
                eventKind = eventKind,
                actorKind = actor.kind,
                actorId = actor.id,
                summary = summary,
                payloadDigest = sha256(payloadJson),
                createdAt = now,
            ),
        )
        faultInjector.checkpoint(RequestLedgerCheckpoint.AFTER_TOOL_AUDIT_INSERT)
    }
}

data class NewRequestSpec(
    val requestId: RequestId,
    val intentKey: String,
    val kind: RequestKind,
    val inputDigest: String,
    val capabilitySnapshotJson: String,
    val resolverVersion: Int,
    val actor: AuditActor,
    val parentRequestId: RequestId? = null,
    val conversationId: String? = null,
    val assistantId: String? = null,
    val messageId: String? = null,
    val partId: String? = null,
    val workspaceId: String? = null,
    val mcpServerId: String? = null,
    val credentialRefId: String? = null,
    val providerKind: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val apiSurface: String? = null,
    val toolCatalogDigest: String? = null,
    val approvalState: ToolApprovalState = ToolApprovalState.NOT_REQUIRED,
)

data class RequestLease(
    val requestId: RequestId,
    val owner: String,
    val fencingEpoch: Long,
    val leaseUntil: Long,
)

data class BeginAttemptCommand(
    val lease: RequestLease,
    val attemptId: RequestAttemptId,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val actor: AuditActor,
    val transportKind: String? = null,
    val ownerReplicaId: String? = null,
    val foregroundTaskId: String? = null,
    val providerGuaranteesIdempotency: Boolean = false,
    val acceptsPossibleCharge: Boolean = false,
)

data class AdvanceAttemptCommand(
    val lease: RequestLease,
    val attemptId: RequestAttemptId,
    val nextState: RequestAttemptState,
    val nextBoundary: BillableBoundary,
    val actor: AuditActor,
)

data class CommitRequestOutputCommand(
    val lease: RequestLease,
    val attemptId: RequestAttemptId,
    val outputId: RequestOutputId,
    val outputKind: String,
    val ordinal: Int,
    val contentDigest: String,
    val actor: AuditActor,
    val conversationId: String? = null,
    val messageId: String? = null,
    val partId: String? = null,
    val assetId: String? = null,
    val sourceId: String? = null,
)

data class NewToolPermissionSpec(
    val permissionId: ToolPermissionId,
    val permissionKey: String,
    val principalKind: String,
    val principalId: String,
    val toolName: String,
    val action: String,
    val schemaDigest: String,
    val decision: ToolPermissionDecision,
    val scope: ToolPermissionScope,
    val constraintsJson: String,
    val capabilitySnapshotJson: String,
    val policyVersion: Int,
    val actor: AuditActor,
    val sourceRequestId: RequestId? = null,
    val serverId: String? = null,
    val scopeId: String? = null,
    val reason: String? = null,
    val expiresAt: Long? = null,
)

data class DecideToolPermissionCommand(
    val permissionId: ToolPermissionId,
    val decision: ToolPermissionDecision,
    val actor: AuditActor,
    val reason: String? = null,
)

data class NewToolInvocationSpec(
    val lease: RequestLease,
    val attemptId: RequestAttemptId,
    val invocationId: ToolInvocationId,
    val providerToolCallId: String,
    val toolName: String,
    val principalKind: String,
    val principalId: String,
    val action: String,
    val schemaDigest: String,
    val inputDigest: String,
    val sideEffectClass: ToolSideEffectClass,
    val approvalState: ToolApprovalState,
    val actor: AuditActor,
    val serverId: String? = null,
    val permissionId: ToolPermissionId? = null,
)

data class AdvanceToolInvocationCommand(
    val lease: RequestLease,
    val invocationId: ToolInvocationId,
    val nextApprovalState: ToolApprovalState,
    val nextExecutionState: ToolExecutionState,
    val actor: AuditActor,
    val permissionId: ToolPermissionId? = null,
    val resultDigest: String? = null,
    val errorKind: String? = null,
    val errorCode: String? = null,
)

data class AuditActor(val kind: String, val id: String? = null) {
    init { require(kind.isNotBlank()) }

    companion object {
        fun system(id: String? = null) = AuditActor("system", id)
        fun user(id: String? = null) = AuditActor("user", id)
        fun provider(id: String? = null) = AuditActor("provider", id)
    }
}

enum class RequestLedgerCheckpoint {
    AFTER_REQUEST_INSERT,
    AFTER_LEASE_CLAIM,
    AFTER_REQUEST_STATE_CAS,
    AFTER_ATTEMPT_INSERT,
    AFTER_ATTEMPT_STATE_CAS,
    AFTER_OUTPUT_INSERT,
    AFTER_PERMISSION_INSERT,
    AFTER_PERMISSION_STATE_CAS,
    AFTER_INVOCATION_INSERT,
    AFTER_INVOCATION_STATE_CAS,
    AFTER_REQUEST_AUDIT_INSERT,
    AFTER_TOOL_AUDIT_INSERT,
}

fun interface RequestLedgerFaultInjector {
    fun checkpoint(point: RequestLedgerCheckpoint)

    companion object {
        val NONE = RequestLedgerFaultInjector { }
    }
}

open class RequestLedgerException(message: String) : IllegalStateException(message)
class RequestLedgerConflict(message: String) : RequestLedgerException(message)
class RequestLedgerIdentityConflict(message: String) : RequestLedgerException(message)
class RequestLedgerLeaseConflict(requestId: String) :
    RequestLedgerException("Request lease is stale or unavailable: $requestId")
class RequestLedgerRetryRejected(requestId: String) :
    RequestLedgerException("Retry requires idempotency proof or explicit charge acceptance: $requestId")
class RequestLedgerMissing(id: String) : RequestLedgerException("Request ledger evidence not found: $id")

private object ToolInvocationLifecycle {
    private val transitions = mapOf(
        ToolExecutionState.CREATED to setOf(
            ToolExecutionState.WAITING_APPROVAL,
            ToolExecutionState.READY,
            ToolExecutionState.CANCELLED,
        ),
        ToolExecutionState.WAITING_APPROVAL to setOf(
            ToolExecutionState.READY,
            ToolExecutionState.FAILED,
            ToolExecutionState.CANCELLED,
        ),
        ToolExecutionState.READY to setOf(ToolExecutionState.RUNNING, ToolExecutionState.CANCELLED),
        ToolExecutionState.RUNNING to setOf(
            ToolExecutionState.COMMITTING,
            ToolExecutionState.SUCCEEDED,
            ToolExecutionState.FAILED,
            ToolExecutionState.CANCELLED,
            ToolExecutionState.UNKNOWN_OUTCOME,
        ),
        ToolExecutionState.COMMITTING to setOf(ToolExecutionState.SUCCEEDED, ToolExecutionState.FAILED),
    )

    fun canTransition(from: ToolExecutionState, to: ToolExecutionState): Boolean =
        from == to || transitions[from]?.contains(to) == true
}

private object ToolPermissionLifecycle {
    private val transitions = mapOf(
        ToolPermissionDecision.ASK to setOf(
            ToolPermissionDecision.ALLOW,
            ToolPermissionDecision.DENY,
            ToolPermissionDecision.REVOKED,
            ToolPermissionDecision.EXPIRED,
        ),
        ToolPermissionDecision.ALLOW to setOf(
            ToolPermissionDecision.REVOKED,
            ToolPermissionDecision.EXPIRED,
        ),
    )

    fun canTransition(from: ToolPermissionDecision, to: ToolPermissionDecision): Boolean =
        transitions[from]?.contains(to) == true
}

private fun validateApprovalForExecution(approval: ToolApprovalState, execution: ToolExecutionState) {
    when (execution) {
        ToolExecutionState.WAITING_APPROVAL -> check(approval == ToolApprovalState.PENDING)
        ToolExecutionState.READY,
        ToolExecutionState.RUNNING,
        ToolExecutionState.COMMITTING,
        ToolExecutionState.SUCCEEDED,
        -> check(approval == ToolApprovalState.APPROVED || approval == ToolApprovalState.NOT_REQUIRED)

        else -> Unit
    }
}

private fun validatePermissionBinding(
    permission: ToolPermissionEntity,
    request: RequestLedgerEntity,
    invocationId: String,
    serverId: String?,
    toolName: String,
    principalKind: String,
    principalId: String,
    action: String,
    schemaDigest: String,
) {
    check(permission.serverId == serverId) { "Permission server does not match invocation" }
    check(permission.toolName == toolName) { "Permission tool does not match invocation" }
    check(permission.principalKind == principalKind && permission.principalId == principalId) {
        "Permission principal does not match invocation"
    }
    check(permission.action == action) { "Permission action does not match invocation" }
    check(permission.schemaDigest == schemaDigest) { "Permission schema is stale" }

    val scope = permission.scopeKind.asPermissionScope()
    val expectedScopeId = when (scope) {
        ToolPermissionScope.ONCE -> invocationId
        ToolPermissionScope.CONVERSATION -> request.conversationId
        ToolPermissionScope.ASSISTANT -> request.assistantId
        ToolPermissionScope.WORKSPACE -> request.workspaceId
        ToolPermissionScope.SERVER -> serverId
        ToolPermissionScope.GLOBAL -> null
    }
    if (scope != ToolPermissionScope.GLOBAL) {
        check(expectedScopeId != null) { "Request has no anchor for ${scope.dbValue()} permission" }
    }
    check(permission.scopeId == expectedScopeId) { "Permission scope does not match request" }
}

private fun validateApprovalAgainstPermission(
    permission: ToolPermissionEntity,
    approval: ToolApprovalState,
    now: Long,
    executionRequiresAllow: Boolean,
) {
    val expired = permission.expiresAt?.let { it <= now } == true
    val storedDecision = permission.decision.asPermissionDecision()
    val decision = when {
        permission.revokedAt != null || storedDecision == ToolPermissionDecision.REVOKED ->
            ToolPermissionDecision.REVOKED
        expired || storedDecision == ToolPermissionDecision.EXPIRED -> ToolPermissionDecision.EXPIRED
        else -> storedDecision
    }
    if (executionRequiresAllow) {
        check(decision == ToolPermissionDecision.ALLOW) { "Tool execution is not allowed" }
        check(approval == ToolApprovalState.APPROVED || approval == ToolApprovalState.NOT_REQUIRED) {
            "Tool execution lacks approval evidence"
        }
    } else {
        when (decision) {
            ToolPermissionDecision.ALLOW -> check(
                approval == ToolApprovalState.APPROVED || approval == ToolApprovalState.NOT_REQUIRED,
            )
            ToolPermissionDecision.ASK -> check(approval == ToolApprovalState.PENDING)
            ToolPermissionDecision.DENY -> check(approval == ToolApprovalState.DENIED)
            ToolPermissionDecision.REVOKED,
            ToolPermissionDecision.EXPIRED,
            -> check(approval == ToolApprovalState.DENIED)
        }
    }
}

private fun ToolExecutionState.requiresAuthorization(): Boolean = when (this) {
    ToolExecutionState.READY,
    ToolExecutionState.RUNNING,
    ToolExecutionState.COMMITTING,
    ToolExecutionState.SUCCEEDED,
    -> true
    else -> false
}

private fun RequestLedgerEntity.matches(spec: NewRequestSpec): Boolean =
    requestId == spec.requestId.value &&
        parentRequestId == spec.parentRequestId?.value &&
        requestKind == spec.kind.dbValue() &&
        conversationId == spec.conversationId &&
        assistantId == spec.assistantId &&
        messageId == spec.messageId &&
        partId == spec.partId &&
        workspaceId == spec.workspaceId &&
        mcpServerId == spec.mcpServerId &&
        credentialRefId == spec.credentialRefId &&
        providerKind == spec.providerKind &&
        providerId == spec.providerId &&
        modelId == spec.modelId &&
        apiSurface == spec.apiSurface &&
        inputDigest == spec.inputDigest &&
        capabilitySnapshotJson == spec.capabilitySnapshotJson &&
        resolverVersion == spec.resolverVersion &&
        toolCatalogDigest == spec.toolCatalogDigest &&
        approvalState == spec.approvalState.dbValue()

private fun RequestOutputEntity.matches(
    command: CommitRequestOutputCommand,
    expectedRequestId: String,
    expectedAttemptId: String,
): Boolean =
    outputId == command.outputId.value &&
        requestId == expectedRequestId &&
        attemptId == expectedAttemptId &&
        outputKind == command.outputKind &&
        ordinal == command.ordinal &&
        conversationId == command.conversationId &&
        messageId == command.messageId &&
        partId == command.partId &&
        assetId == command.assetId &&
        sourceId == command.sourceId &&
        contentDigest == command.contentDigest

private fun ToolPermissionEntity.matches(spec: NewToolPermissionSpec): Boolean =
    permissionId == spec.permissionId.value &&
        sourceRequestId == spec.sourceRequestId?.value &&
        principalKind == spec.principalKind && principalId == spec.principalId &&
        serverId == spec.serverId && toolName == spec.toolName && action == spec.action &&
        schemaDigest == spec.schemaDigest && scopeKind == spec.scope.dbValue() &&
        scopeId == spec.scopeId && constraintsJson == spec.constraintsJson &&
        capabilitySnapshotJson == spec.capabilitySnapshotJson && policyVersion == spec.policyVersion &&
        expiresAt == spec.expiresAt

private fun ToolInvocationEntity.matches(
    spec: NewToolInvocationSpec,
    expectedRequestId: String,
    expectedPermissionId: String,
): Boolean =
    invocationId == spec.invocationId.value &&
        requestId == expectedRequestId &&
        attemptId == spec.attemptId.value &&
        providerToolCallId == spec.providerToolCallId &&
        serverId == spec.serverId &&
        toolName == spec.toolName &&
        principalKind == spec.principalKind &&
        principalId == spec.principalId &&
        action == spec.action &&
        schemaDigest == spec.schemaDigest &&
        inputDigest == spec.inputDigest &&
        sideEffectClass == spec.sideEffectClass.dbValue() &&
        permissionId == expectedPermissionId

private fun RequestAttemptState.toRequestState(): RequestState = when (this) {
    RequestAttemptState.PREPARED -> RequestState.QUEUED
    RequestAttemptState.DISPATCHING -> RequestState.DISPATCHING
    RequestAttemptState.RUNNING -> RequestState.RUNNING
    RequestAttemptState.COMMITTING -> RequestState.COMMITTING
    RequestAttemptState.SUCCEEDED -> RequestState.SUCCEEDED
    RequestAttemptState.FAILED -> RequestState.FAILED
    RequestAttemptState.CANCELLED -> RequestState.CANCELLED
    RequestAttemptState.INTERRUPTED -> RequestState.INTERRUPTED
    RequestAttemptState.UNKNOWN_OUTCOME -> RequestState.UNKNOWN_OUTCOME
}

private fun RequestAttemptState.isTerminal(): Boolean = when (this) {
    RequestAttemptState.SUCCEEDED,
    RequestAttemptState.FAILED,
    RequestAttemptState.CANCELLED,
    RequestAttemptState.INTERRUPTED,
    RequestAttemptState.UNKNOWN_OUTCOME,
    -> true
    else -> false
}

private fun BillableBoundary.aggregate(next: BillableBoundary): BillableBoundary {
    if (this == BillableBoundary.UNKNOWN || next == BillableBoundary.UNKNOWN) return BillableBoundary.UNKNOWN
    return if (boundaryRank(this) >= boundaryRank(next)) this else next
}

private fun BillableBoundary.isAtLeast(minimum: BillableBoundary): Boolean =
    this != BillableBoundary.UNKNOWN && boundaryRank(this) >= boundaryRank(minimum)

private fun boundaryRank(boundary: BillableBoundary): Int = when (boundary) {
    BillableBoundary.NOT_SENT -> 0
    BillableBoundary.SENT -> 1
    BillableBoundary.RESPONSE_STARTED -> 2
    BillableBoundary.RESULT_RECEIVED -> 3
    BillableBoundary.RESULT_COMMITTED -> 4
    BillableBoundary.UNKNOWN -> Int.MAX_VALUE
}

private fun String.asRequestState(): RequestState =
    RequestState.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown request state: $this")

private fun String.asAttemptState(): RequestAttemptState =
    RequestAttemptState.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown attempt state: $this")

private fun String.asToolExecutionState(): ToolExecutionState =
    ToolExecutionState.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown tool execution state: $this")

private fun String.asPermissionDecision(): ToolPermissionDecision =
    ToolPermissionDecision.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown permission decision: $this")

private fun String.asPermissionScope(): ToolPermissionScope =
    ToolPermissionScope.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown permission scope: $this")

private fun String.asBoundary(): BillableBoundary =
    BillableBoundary.entries.firstOrNull { it.dbValue() == this }
        ?: throw RequestLedgerConflict("Unknown billable boundary: $this")

private fun Enum<*>.dbValue(): String = name.lowercase(Locale.ROOT)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

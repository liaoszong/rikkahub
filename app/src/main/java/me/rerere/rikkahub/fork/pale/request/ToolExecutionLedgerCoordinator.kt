package me.rerere.rikkahub.fork.pale.request

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState as UiToolApprovalState
import me.rerere.ai.ui.ToolExecutionState as UiToolExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.id.ToolInvocationId
import me.rerere.pale.id.ToolPermissionId
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.ToolApprovalState
import me.rerere.pale.request.ToolExecutionState
import me.rerere.pale.request.ToolPermissionDecision
import me.rerere.pale.request.ToolPermissionScope
import me.rerere.pale.request.ToolSideEffectClass

internal fun toolOutputId(requestId: RequestId): RequestOutputId = RequestOutputId(
    UUID.nameUUIDFromBytes(
        "pale.6:tool-output:v1:${requestId.value}:0".toByteArray(Charsets.UTF_8),
    ).toString().lowercase(Locale.ROOT),
)

/**
 * Durable authority for one model-requested tool call.
 *
 * A tool call is a child request of the provider step that produced it. It owns a separate
 * attempt because the parent provider attempt is already terminal before approval or execution.
 * The UI Tool part is only a projection; Room permission and invocation rows decide whether the
 * side effect may start or be resumed after process death.
 */
class ToolExecutionLedgerCoordinator(
    private val repository: RequestLedgerRepository,
    private val json: Json,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_MILLIS,
    private val processOwnerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
) {
    internal fun digestOutput(result: UIMessagePart.Tool): String = sha256(
        json.encodeToString(UIMessagePart.serializer(), result),
    )
    suspend fun prepare(
        parentRequestId: RequestId,
        context: ChatGenerationLedgerContext,
        tool: UIMessagePart.Tool,
        definition: Tool,
    ) {
        val descriptor = descriptor(context, tool, definition)
        repository.createRequest(descriptor.toRequestSpec(parentRequestId))
        val existing = repository.getRequest(descriptor.requestId)
        if (existing?.requestState == "succeeded") return
        check(existing == null || existing.requestState !in TERMINAL_REQUEST_STATES) {
            "Tool request ${descriptor.requestId.value} is ${existing?.requestState}; automatic recreation is forbidden"
        }
        val session = openDispatch(descriptor, parentRequestId)
        try {
            ensurePolicyAndInvocation(session, descriptor)
        } finally {
            withContext(NonCancellable) { session.releaseLease() }
        }
    }

    suspend fun openExecution(
        context: ChatGenerationLedgerContext,
        tool: UIMessagePart.Tool,
        definition: Tool,
    ): ToolExecutionLedgerSession {
        val descriptor = descriptor(context, tool, definition)
        val existing = repository.getRequest(descriptor.requestId)
            ?: throw RequestLedgerMissing(descriptor.requestId.value)
        check(existing.requestState !in TERMINAL_REQUEST_STATES) {
            "Tool request ${descriptor.requestId.value} is already ${existing.requestState}"
        }
        val parentRequestId = existing.parentRequestId?.let(::RequestId)
            ?: throw RequestLedgerIdentityConflict("Tool request lost its parent provider request")
        val dispatch = openDispatch(descriptor, parentRequestId)
        try {
            ensurePolicyAndInvocation(dispatch, descriptor)
            synchronizeApproval(dispatch, descriptor, tool.approvalState)
            return ToolExecutionLedgerSession(
                repository = repository,
                dispatch = dispatch,
                descriptor = descriptor,
                json = json,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { dispatch.releaseLease() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private suspend fun openDispatch(
        descriptor: ToolRequestDescriptor,
        parentRequestId: RequestId,
    ): RequestDispatchSession {
        val existing = repository.getRequest(descriptor.requestId)
        val activeAttempt = existing?.activeAttemptId?.let { repository.getAttempt(RequestAttemptId(it)) }
        val attemptId = activeAttempt?.let { RequestAttemptId(it.attemptId) } ?: descriptor.attemptId
        return RequestDispatchSession.open(
            repository = repository,
            request = descriptor.toRequestSpec(parentRequestId),
            owner = "tool:$processOwnerId:${descriptor.requestId.value}",
            leaseDurationMillis = leaseDurationMillis,
            attemptId = attemptId,
            idempotencyKey = activeAttempt?.idempotencyKey ?: "pale-tool-${attemptId.value}",
            requestFingerprint = activeAttempt?.requestFingerprint ?: descriptor.inputDigest,
            actor = descriptor.systemActor,
            transportKind = descriptor.kind.name.lowercase(Locale.ROOT),
        )
    }

    private suspend fun ensurePolicyAndInvocation(
        dispatch: RequestDispatchSession,
        descriptor: ToolRequestDescriptor,
    ) {
        repository.createPermission(
            NewToolPermissionSpec(
                permissionId = descriptor.permissionId,
                permissionKey = "tool-once:v1:${descriptor.invocationId.value}",
                principalKind = "assistant",
                principalId = descriptor.assistantId,
                serverId = descriptor.serverId,
                toolName = descriptor.toolName,
                action = descriptor.action,
                schemaDigest = descriptor.schemaDigest,
                decision = if (descriptor.requiresApproval) {
                    ToolPermissionDecision.ASK
                } else {
                    ToolPermissionDecision.ALLOW
                },
                scope = ToolPermissionScope.ONCE,
                scopeId = descriptor.invocationId.value,
                constraintsJson = "{}",
                capabilitySnapshotJson = descriptor.capabilitySnapshotJson,
                policyVersion = POLICY_VERSION,
                sourceRequestId = descriptor.requestId,
                actor = descriptor.systemActor,
            ),
        )
        repository.createInvocation(
            NewToolInvocationSpec(
                lease = dispatch.lease,
                attemptId = dispatch.attemptId,
                invocationId = descriptor.invocationId,
                providerToolCallId = descriptor.providerToolCallId,
                serverId = descriptor.serverId,
                toolName = descriptor.toolName,
                principalKind = "assistant",
                principalId = descriptor.assistantId,
                action = descriptor.action,
                schemaDigest = descriptor.schemaDigest,
                inputDigest = descriptor.inputDigest,
                sideEffectClass = descriptor.sideEffectClass,
                approvalState = descriptor.initialApproval,
                permissionId = descriptor.permissionId,
                actor = descriptor.systemActor,
            ),
        )
        val invocation = requireInvocation(descriptor.invocationId)
        if (invocation.executionState == "created") {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = dispatch.lease,
                    invocationId = descriptor.invocationId,
                    nextApprovalState = descriptor.initialApproval,
                    nextExecutionState = if (descriptor.requiresApproval) {
                        ToolExecutionState.WAITING_APPROVAL
                    } else {
                        ToolExecutionState.READY
                    },
                    permissionId = descriptor.permissionId,
                    actor = descriptor.systemActor,
                ),
            )
        }
    }

    private suspend fun synchronizeApproval(
        dispatch: RequestDispatchSession,
        descriptor: ToolRequestDescriptor,
        approval: UiToolApprovalState,
    ) {
        require(approval !is UiToolApprovalState.Answered || descriptor.action == "answer") {
            "Only an answerable tool can accept ANSWERED approval evidence"
        }
        val targetApproval = approval.toLedgerApproval(descriptor.requiresApproval)
        val targetDecision = when (targetApproval) {
            ToolApprovalState.PENDING -> ToolPermissionDecision.ASK
            ToolApprovalState.DENIED -> ToolPermissionDecision.DENY
            else -> ToolPermissionDecision.ALLOW
        }
        val permission = repository.getPermission(descriptor.permissionId)
            ?: throw RequestLedgerMissing(descriptor.permissionId.value)
        if (permission.decision != targetDecision.name.lowercase(Locale.ROOT)) {
            repository.decidePermission(
                DecideToolPermissionCommand(
                    permissionId = descriptor.permissionId,
                    decision = targetDecision,
                    reason = (approval as? UiToolApprovalState.Denied)?.reason,
                    actor = AuditActor.user("chat-approval"),
                ),
            )
        }
        val invocation = requireInvocation(descriptor.invocationId)
        if (invocation.executionState == "waiting_approval" && targetApproval != ToolApprovalState.PENDING) {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = dispatch.lease,
                    invocationId = descriptor.invocationId,
                    nextApprovalState = targetApproval,
                    nextExecutionState = if (targetApproval == ToolApprovalState.DENIED) {
                        ToolExecutionState.FAILED
                    } else {
                        ToolExecutionState.READY
                    },
                    permissionId = descriptor.permissionId,
                    errorKind = "user_denied".takeIf { targetApproval == ToolApprovalState.DENIED },
                    actor = AuditActor.user("chat-approval"),
                ),
            )
        }
    }

    private fun descriptor(
        context: ChatGenerationLedgerContext,
        tool: UIMessagePart.Tool,
        definition: Tool,
    ): ToolRequestDescriptor {
        require(tool.requestId.isNotBlank()) { "Tool request identity must be frozen before execution" }
        val requestId = RequestId(tool.requestId)
        val invocationId = stableInvocationId(requestId, tool.toolCallId)
        val schemaJson = definition.parameters()?.let { json.encodeToString(it) } ?: "null"
        val requiresApproval = definition.needsApproval(tool.inputAsJson())
        val kind = when {
            definition.name.startsWith("mcp__") -> RequestKind.MCP_TOOL_CALL
            definition.name.startsWith("workspace_") -> RequestKind.WORKSPACE_TOOL
            else -> RequestKind.TOOL_CALL
        }
        val serverId = definition.ledgerAuthorityId
            ?: definition.name.takeIf { it.startsWith("mcp__") }
                ?.removePrefix("mcp__")
                ?.substringBefore("__")
        val sideEffect = definition.ledgerSideEffectClass?.toSideEffectClass()
            ?: inferSideEffect(definition.name, kind)
        val action = if (definition.name == "ask_user") "answer" else "execute"
        val inputDescriptor = ToolInputDescriptor(
            requestId = requestId.value,
            providerToolCallId = tool.toolCallId,
            toolName = definition.name,
            action = action,
            serverId = serverId,
            assistantId = context.assistantId,
            conversationId = context.conversationId,
            messageId = context.responseMessageId,
            workspaceId = context.workspaceId,
            schemaDigest = sha256(schemaJson),
            input = tool.input,
            sideEffectClass = sideEffect.name.lowercase(Locale.ROOT),
            requiresApproval = requiresApproval,
        )
        val inputDigest = sha256(json.encodeToString(inputDescriptor))
        val capability = json.encodeToString(
            ToolCapabilitySnapshot(
                policyVersion = POLICY_VERSION,
                kind = kind.name.lowercase(Locale.ROOT),
                sideEffectClass = sideEffect.name.lowercase(Locale.ROOT),
                requiresApproval = requiresApproval,
                authorityId = serverId,
            ),
        )
        return ToolRequestDescriptor(
            requestId = requestId,
            attemptId = stableAttemptId(requestId),
            invocationId = invocationId,
            permissionId = stablePermissionId(invocationId),
            kind = kind,
            inputDigest = inputDigest,
            capabilitySnapshotJson = capability,
            schemaDigest = inputDescriptor.schemaDigest,
            providerToolCallId = tool.toolCallId,
            toolName = definition.name,
            action = action,
            serverId = serverId,
            assistantId = context.assistantId,
            conversationId = context.conversationId,
            messageId = context.responseMessageId,
            workspaceId = context.workspaceId,
            sideEffectClass = sideEffect,
            requiresApproval = requiresApproval,
            initialApproval = if (requiresApproval) ToolApprovalState.PENDING else ToolApprovalState.NOT_REQUIRED,
            systemActor = AuditActor.system("tool:$processOwnerId"),
        )
    }

    private suspend fun requireInvocation(id: ToolInvocationId): ToolInvocationEntity =
        repository.getInvocation(id) ?: throw RequestLedgerMissing(id.value)

    private fun stableAttemptId(requestId: RequestId) = RequestAttemptId(stableUuid("tool-attempt:v1:${requestId.value}:1"))

    private fun stableInvocationId(requestId: RequestId, providerToolCallId: String) =
        ToolInvocationId(stableUuid("tool-invocation:v1:${requestId.value}:$providerToolCallId"))

    private fun stablePermissionId(invocationId: ToolInvocationId) =
        ToolPermissionId(stableUuid("tool-permission:v1:${invocationId.value}"))

    private fun stableUuid(identity: String): String = UUID.nameUUIDFromBytes(
        "pale.6:$identity".toByteArray(Charsets.UTF_8),
    ).toString().lowercase(Locale.ROOT)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        private const val DEFAULT_LEASE_MILLIS = 120_000L
        private const val POLICY_VERSION = 1
        private val TERMINAL_REQUEST_STATES = setOf(
            "succeeded", "failed", "cancelled", "interrupted", "unknown_outcome",
        )
    }
}

class ToolExecutionLedgerSession internal constructor(
    private val repository: RequestLedgerRepository,
    private val dispatch: RequestDispatchSession,
    private val descriptor: ToolRequestDescriptor,
    private val json: Json,
) {
    suspend fun <T> withLeaseHeartbeat(block: suspend () -> T): T =
        dispatch.withLeaseHeartbeat(block = block)

    suspend fun startExternal() {
        try {
            withContext(NonCancellable) {
                dispatch.prepareDispatch()
                advanceInvocation(ToolExecutionState.RUNNING)
                dispatch.dispatchObserver.onDispatch()
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException) releaseForLocalRepair(failure)
            throw failure
        }
    }

    suspend fun startLocal() {
        try {
            withContext(NonCancellable) {
                dispatch.markLocalExecutionStarted()
                val invocation = requireInvocation()
                if (invocation.executionState == "ready") {
                    advanceInvocation(ToolExecutionState.RUNNING)
                }
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException) releaseForLocalRepair(failure)
            throw failure
        }
    }

    suspend fun commitDurableResult(
        result: UIMessagePart.Tool,
        persistConversation: suspend () -> Unit,
    ) {
        val digest = digest(result)
        val invocation = requireInvocation()
        when {
            result.executionState == UiToolExecutionState.SUCCEEDED && invocation.executionState == "running" ->
                advanceInvocation(ToolExecutionState.COMMITTING)

            result.executionState == UiToolExecutionState.FAILED && invocation.executionState == "running" ->
                advanceInvocation(ToolExecutionState.FAILED, errorKind = "tool_error")
        }
        try {
            persistConversation()
            if (result.executionState == UiToolExecutionState.SUCCEEDED &&
                requireInvocation().executionState == "committing"
            ) {
                advanceInvocation(ToolExecutionState.SUCCEEDED, resultDigest = digest)
            }
            dispatch.commitOutputAndSucceed(
                CommitRequestOutputCommand(
                    lease = dispatch.lease,
                    attemptId = dispatch.attemptId,
                    outputId = toolOutputId(descriptor.requestId),
                    outputKind = "tool_result",
                    ordinal = 0,
                    contentDigest = digest,
                    actor = descriptor.systemActor,
                    conversationId = descriptor.conversationId,
                    messageId = descriptor.messageId,
                    partId = descriptor.providerToolCallId,
                ),
            )
        } catch (failure: Throwable) {
            runCatching { dispatch.releaseLease() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun finishCancellation(externalBoundaryCrossed: Boolean) = withContext(NonCancellable) {
        val attemptCrossedBoundary = repository.getAttempt(dispatch.attemptId)
            ?.billableBoundary
            ?.let { it != "not_sent" }
            ?: externalBoundaryCrossed
        val invocation = requireInvocation()
        if (invocation.executionState == "running") {
            advanceInvocation(
                if (attemptCrossedBoundary) ToolExecutionState.UNKNOWN_OUTCOME else ToolExecutionState.CANCELLED,
                errorKind = "cancelled",
            )
        }
        dispatch.finishTransportFailure(cancelled = true)
    }

    suspend fun releaseForLocalRepair(failure: Throwable) = withContext(NonCancellable) {
        runCatching { dispatch.releaseLease() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
    }

    private suspend fun advanceInvocation(
        state: ToolExecutionState,
        resultDigest: String? = null,
        errorKind: String? = null,
    ) {
        val current = requireInvocation()
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = dispatch.lease,
                invocationId = descriptor.invocationId,
                nextApprovalState = current.approvalState.toApprovalState(),
                nextExecutionState = state,
                permissionId = descriptor.permissionId,
                resultDigest = resultDigest,
                errorKind = errorKind,
                actor = descriptor.systemActor,
            ),
        )
    }

    private suspend fun requireInvocation(): ToolInvocationEntity =
        repository.getInvocation(descriptor.invocationId)
            ?: throw RequestLedgerMissing(descriptor.invocationId.value)

    private fun digest(result: UIMessagePart.Tool): String = sha256(
        json.encodeToString(UIMessagePart.serializer(), result),
    )

    private fun stableUuid(identity: String): String = UUID.nameUUIDFromBytes(
        "pale.6:$identity".toByteArray(Charsets.UTF_8),
    ).toString().lowercase(Locale.ROOT)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}

internal data class ToolRequestDescriptor(
    val requestId: RequestId,
    val attemptId: RequestAttemptId,
    val invocationId: ToolInvocationId,
    val permissionId: ToolPermissionId,
    val kind: RequestKind,
    val inputDigest: String,
    val capabilitySnapshotJson: String,
    val schemaDigest: String,
    val providerToolCallId: String,
    val toolName: String,
    val action: String,
    val serverId: String?,
    val assistantId: String,
    val conversationId: String,
    val messageId: String,
    val workspaceId: String?,
    val sideEffectClass: ToolSideEffectClass,
    val requiresApproval: Boolean,
    val initialApproval: ToolApprovalState,
    val systemActor: AuditActor,
) {
    fun toRequestSpec(parentRequestId: RequestId) = NewRequestSpec(
        requestId = requestId,
        intentKey = "tool-call:v1:${requestId.value}",
        kind = kind,
        inputDigest = inputDigest,
        capabilitySnapshotJson = capabilitySnapshotJson,
        resolverVersion = 1,
        actor = systemActor,
        parentRequestId = parentRequestId,
        conversationId = conversationId,
        assistantId = assistantId,
        messageId = messageId,
        partId = providerToolCallId,
        workspaceId = workspaceId,
        mcpServerId = serverId,
        apiSurface = "tool",
        approvalState = initialApproval,
    )
}

@Serializable
private data class ToolInputDescriptor(
    val requestId: String,
    val providerToolCallId: String,
    val toolName: String,
    val action: String,
    val serverId: String?,
    val assistantId: String,
    val conversationId: String,
    val messageId: String,
    val workspaceId: String?,
    val schemaDigest: String,
    val input: String,
    val sideEffectClass: String,
    val requiresApproval: Boolean,
)

@Serializable
private data class ToolCapabilitySnapshot(
    val policyVersion: Int,
    val kind: String,
    val sideEffectClass: String,
    val requiresApproval: Boolean,
    val authorityId: String?,
)

private fun UiToolApprovalState.toLedgerApproval(requiresApproval: Boolean): ToolApprovalState = when (this) {
    UiToolApprovalState.Auto -> if (requiresApproval) ToolApprovalState.PENDING else ToolApprovalState.NOT_REQUIRED
    UiToolApprovalState.Pending -> ToolApprovalState.PENDING
    UiToolApprovalState.Approved -> ToolApprovalState.APPROVED
    is UiToolApprovalState.Denied -> ToolApprovalState.DENIED
    is UiToolApprovalState.Answered -> ToolApprovalState.ANSWERED
}

private fun String.toApprovalState(): ToolApprovalState =
    ToolApprovalState.valueOf(uppercase(Locale.ROOT))

private fun String.toSideEffectClass(): ToolSideEffectClass = runCatching {
    ToolSideEffectClass.valueOf(uppercase(Locale.ROOT))
}.getOrDefault(ToolSideEffectClass.UNKNOWN)

private fun inferSideEffect(name: String, kind: RequestKind): ToolSideEffectClass = when {
    kind == RequestKind.MCP_TOOL_CALL -> ToolSideEffectClass.UNKNOWN
    name == "generate_image" -> ToolSideEffectClass.IRREVERSIBLE
    name == "workspace_read_file" -> ToolSideEffectClass.READ_ONLY
    name == "workspace_write_file" || name == "workspace_edit_file" -> ToolSideEffectClass.REVERSIBLE_WRITE
    name == "workspace_shell" -> ToolSideEffectClass.UNKNOWN
    name.startsWith("search_") || name.contains("conversation", ignoreCase = true) -> ToolSideEffectClass.READ_ONLY
    name.contains("memory", ignoreCase = true) -> ToolSideEffectClass.REVERSIBLE_WRITE
    name == "time" || name.startsWith("get_") || name.startsWith("list_") -> ToolSideEffectClass.READ_ONLY
    else -> ToolSideEffectClass.UNKNOWN
}

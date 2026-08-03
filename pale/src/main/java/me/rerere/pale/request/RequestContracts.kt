package me.rerere.pale.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RequestKind {
    @SerialName("chat_generation") CHAT_GENERATION,
    @SerialName("image_generation_group") IMAGE_GENERATION_GROUP,
    @SerialName("image_generation") IMAGE_GENERATION,
    @SerialName("title_generation") TITLE_GENERATION,
    @SerialName("suggestion_generation") SUGGESTION_GENERATION,
    @SerialName("translation") TRANSLATION,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("mcp_tool_call") MCP_TOOL_CALL,
    @SerialName("workspace_tool") WORKSPACE_TOOL,
    @SerialName("sync") SYNC,
    @SerialName("credential_auth") CREDENTIAL_AUTH,
    @SerialName("legacy_unmapped") LEGACY_UNMAPPED,
}

@Serializable
enum class RequestAttemptState {
    @SerialName("prepared") PREPARED,
    @SerialName("dispatching") DISPATCHING,
    @SerialName("running") RUNNING,
    @SerialName("committing") COMMITTING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("unknown_outcome") UNKNOWN_OUTCOME,
}

object RequestAttemptLifecycle {
    private val transitions = mapOf(
        RequestAttemptState.PREPARED to setOf(
            RequestAttemptState.DISPATCHING,
            RequestAttemptState.FAILED,
            RequestAttemptState.CANCELLED,
            RequestAttemptState.INTERRUPTED,
        ),
        RequestAttemptState.DISPATCHING to setOf(
            RequestAttemptState.RUNNING,
            RequestAttemptState.FAILED,
            RequestAttemptState.CANCELLED,
            RequestAttemptState.INTERRUPTED,
            RequestAttemptState.UNKNOWN_OUTCOME,
        ),
        RequestAttemptState.RUNNING to setOf(
            RequestAttemptState.COMMITTING,
            RequestAttemptState.FAILED,
            RequestAttemptState.CANCELLED,
            RequestAttemptState.INTERRUPTED,
            RequestAttemptState.UNKNOWN_OUTCOME,
        ),
        RequestAttemptState.COMMITTING to setOf(
            RequestAttemptState.SUCCEEDED,
            RequestAttemptState.FAILED,
        ),
    )

    fun canTransition(from: RequestAttemptState, to: RequestAttemptState): Boolean =
        from == to || transitions[from]?.contains(to) == true
}

@Serializable
enum class ToolApprovalState {
    @SerialName("not_required") NOT_REQUIRED,
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("answered") ANSWERED,
}

@Serializable
enum class ToolExecutionState {
    @SerialName("created") CREATED,
    @SerialName("waiting_approval") WAITING_APPROVAL,
    @SerialName("ready") READY,
    @SerialName("running") RUNNING,
    @SerialName("committing") COMMITTING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("unknown_outcome") UNKNOWN_OUTCOME,
}

@Serializable
enum class ToolPermissionDecision {
    @SerialName("allow") ALLOW,
    @SerialName("ask") ASK,
    @SerialName("deny") DENY,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED,
}

@Serializable
enum class ToolPermissionScope {
    @SerialName("once") ONCE,
    @SerialName("conversation") CONVERSATION,
    @SerialName("assistant") ASSISTANT,
    @SerialName("workspace") WORKSPACE,
    @SerialName("server") SERVER,
    @SerialName("global") GLOBAL,
}

@Serializable
enum class ToolSideEffectClass {
    @SerialName("none") NONE,
    @SerialName("read_only") READ_ONLY,
    @SerialName("reversible_write") REVERSIBLE_WRITE,
    @SerialName("irreversible") IRREVERSIBLE,
    @SerialName("unknown") UNKNOWN,
}

object RequestRetryPolicy {
    fun requiresPossibleChargeConfirmation(boundary: BillableBoundary): Boolean =
        boundary != BillableBoundary.NOT_SENT

    fun canCreateAttempt(
        state: RequestState,
        boundary: BillableBoundary,
        providerGuaranteesIdempotency: Boolean,
        acceptsPossibleCharge: Boolean,
    ): Boolean {
        if (state != RequestState.FAILED &&
            state != RequestState.INTERRUPTED &&
            state != RequestState.UNKNOWN_OUTCOME
        ) {
            return false
        }
        if (state == RequestState.UNKNOWN_OUTCOME) {
            return acceptsPossibleCharge
        }
        return boundary == BillableBoundary.NOT_SENT ||
            providerGuaranteesIdempotency ||
            acceptsPossibleCharge
    }
}

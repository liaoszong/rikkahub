package me.rerere.pale.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Read-only projection over RequestLedger state; RequestLedger remains the transition authority. */
@Serializable
data class TurnOutcome(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val terminal: TurnTerminalOutcome,
    val billableBoundary: BillableBoundary,
    val localCommit: LocalCommitState,
    val cancellation: CancellationState = CancellationState.NONE,
    val errorKind: String? = null,
    val errorCode: String? = null,
    val unknownOutcomeReason: String? = null,
) {
    init {
        require(schemaVersion > 0)
        if (terminal == TurnTerminalOutcome.SUCCEEDED) {
            require(billableBoundary == BillableBoundary.RESULT_COMMITTED)
            require(localCommit == LocalCommitState.COMMITTED)
        }
        if (terminal == TurnTerminalOutcome.UNKNOWN) {
            require(billableBoundary == BillableBoundary.UNKNOWN || unknownOutcomeReason != null)
        }
        if (terminal == TurnTerminalOutcome.CANCELLED) {
            require(cancellation != CancellationState.NONE)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
enum class TurnTerminalOutcome {
    @SerialName("active") ACTIVE,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class LocalCommitState {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("committing") COMMITTING,
    @SerialName("committed") COMMITTED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class CancellationState {
    @SerialName("none") NONE,
    @SerialName("local_wait_stopped") LOCAL_WAIT_STOPPED,
    @SerialName("remote_requested") REMOTE_REQUESTED,
    @SerialName("remote_confirmed") REMOTE_CONFIRMED,
}

object TurnOutcomeReducer {
    fun project(
        requestState: RequestState,
        billableBoundary: BillableBoundary,
        hasCommittedOutput: Boolean,
        cancellation: CancellationState = CancellationState.NONE,
        errorKind: String? = null,
        errorCode: String? = null,
        unknownOutcomeReason: String? = null,
    ): TurnOutcome {
        val terminal = when (requestState) {
            RequestState.SUCCEEDED -> TurnTerminalOutcome.SUCCEEDED
            RequestState.FAILED -> TurnTerminalOutcome.FAILED
            RequestState.CANCELLED -> TurnTerminalOutcome.CANCELLED
            RequestState.INTERRUPTED -> TurnTerminalOutcome.INTERRUPTED
            RequestState.UNKNOWN_OUTCOME -> TurnTerminalOutcome.UNKNOWN
            else -> TurnTerminalOutcome.ACTIVE
        }
        val localCommit = when {
            hasCommittedOutput -> LocalCommitState.COMMITTED
            requestState == RequestState.COMMITTING -> LocalCommitState.COMMITTING
            requestState == RequestState.FAILED && billableBoundary == BillableBoundary.RESULT_RECEIVED ->
                LocalCommitState.FAILED
            else -> LocalCommitState.NOT_STARTED
        }
        require(requestState != RequestState.SUCCEEDED || hasCommittedOutput) {
            "A succeeded request must have a durable canonical output"
        }
        require(!hasCommittedOutput || billableBoundary == BillableBoundary.RESULT_COMMITTED) {
            "Committed output requires RESULT_COMMITTED boundary"
        }
        return TurnOutcome(
            terminal = terminal,
            billableBoundary = billableBoundary,
            localCommit = localCommit,
            cancellation = cancellation,
            errorKind = errorKind,
            errorCode = errorCode,
            unknownOutcomeReason = unknownOutcomeReason,
        )
    }
}

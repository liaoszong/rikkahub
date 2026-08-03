package me.rerere.pale.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RequestState {
    @SerialName("created") CREATED,
    @SerialName("awaiting_approval") AWAITING_APPROVAL,
    @SerialName("queued") QUEUED,
    @SerialName("waiting_runtime") WAITING_RUNTIME,
    @SerialName("dispatching") DISPATCHING,
    @SerialName("running") RUNNING,
    @SerialName("waiting_user") WAITING_USER,
    @SerialName("committing") COMMITTING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("unknown_outcome") UNKNOWN_OUTCOME;

    val isTerminal: Boolean
        get() = this == SUCCEEDED ||
            this == FAILED ||
            this == CANCELLED ||
            this == INTERRUPTED ||
            this == UNKNOWN_OUTCOME
}

@Serializable
enum class BillableBoundary {
    @SerialName("not_sent") NOT_SENT,
    @SerialName("sent") SENT,
    @SerialName("response_started") RESPONSE_STARTED,
    @SerialName("result_received") RESULT_RECEIVED,
    @SerialName("result_committed") RESULT_COMMITTED,
    @SerialName("unknown") UNKNOWN,

    ;

    fun canAdvanceTo(next: BillableBoundary): Boolean {
        if (this == next) return true
        if (this == UNKNOWN) return false
        if (next == UNKNOWN) return true
        return knownRank(next) > knownRank(this)
    }

    private fun knownRank(boundary: BillableBoundary): Int = when (boundary) {
        NOT_SENT -> 0
        SENT -> 1
        RESPONSE_STARTED -> 2
        RESULT_RECEIVED -> 3
        RESULT_COMMITTED -> 4
        UNKNOWN -> error("UNKNOWN has no known billing rank")
    }
}

/** Pure transition authority shared by UI, services, persistence, and recovery. */
object RequestLifecycle {
    private val transitions = mapOf(
        RequestState.CREATED to setOf(
            RequestState.AWAITING_APPROVAL,
            RequestState.QUEUED,
            RequestState.CANCELLED,
        ),
        RequestState.AWAITING_APPROVAL to setOf(
            RequestState.QUEUED,
            RequestState.FAILED,
            RequestState.CANCELLED,
        ),
        RequestState.QUEUED to setOf(
            RequestState.WAITING_RUNTIME,
            RequestState.DISPATCHING,
            RequestState.CANCELLED,
        ),
        RequestState.WAITING_RUNTIME to setOf(
            RequestState.DISPATCHING,
            RequestState.FAILED,
            RequestState.CANCELLED,
            RequestState.INTERRUPTED,
        ),
        RequestState.DISPATCHING to setOf(
            RequestState.RUNNING,
            RequestState.FAILED,
            RequestState.CANCELLED,
            RequestState.INTERRUPTED,
            RequestState.UNKNOWN_OUTCOME,
        ),
        RequestState.RUNNING to setOf(
            RequestState.WAITING_USER,
            RequestState.COMMITTING,
            RequestState.FAILED,
            RequestState.CANCELLED,
            RequestState.INTERRUPTED,
            RequestState.UNKNOWN_OUTCOME,
        ),
        RequestState.WAITING_USER to setOf(
            RequestState.RUNNING,
            RequestState.FAILED,
            RequestState.CANCELLED,
            RequestState.INTERRUPTED,
        ),
        RequestState.COMMITTING to setOf(
            RequestState.SUCCEEDED,
            RequestState.FAILED,
        ),
        RequestState.UNKNOWN_OUTCOME to setOf(
            RequestState.COMMITTING,
        ),
    )

    fun canTransition(
        from: RequestState,
        to: RequestState,
        explicitRetry: Boolean = false,
        acceptsPossibleCharge: Boolean = false,
    ): Boolean {
        if (from == to) return true
        if (to == RequestState.QUEUED && explicitRetry) {
            return from == RequestState.FAILED ||
                from == RequestState.INTERRUPTED ||
                (from == RequestState.UNKNOWN_OUTCOME && acceptsPossibleCharge)
        }
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(
        from: RequestState,
        to: RequestState,
        explicitRetry: Boolean = false,
        acceptsPossibleCharge: Boolean = false,
    ) {
        require(canTransition(from, to, explicitRetry, acceptsPossibleCharge)) {
            "Illegal request transition: $from -> $to"
        }
    }
}

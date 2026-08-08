package me.rerere.pale.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnOutcomeTest {
    @Test
    fun `succeeded request projects only after durable commit`() {
        val outcome = TurnOutcomeReducer.project(
            requestState = RequestState.SUCCEEDED,
            billableBoundary = BillableBoundary.RESULT_COMMITTED,
            hasCommittedOutput = true,
        )

        assertEquals(TurnTerminalOutcome.SUCCEEDED, outcome.terminal)
        assertEquals(LocalCommitState.COMMITTED, outcome.localCommit)
    }

    @Test
    fun `succeeded request without canonical output is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TurnOutcomeReducer.project(
                requestState = RequestState.SUCCEEDED,
                billableBoundary = BillableBoundary.RESULT_RECEIVED,
                hasCommittedOutput = false,
            )
        }
    }

    @Test
    fun `cancel distinguishes local stop from remote confirmation`() {
        val outcome = TurnOutcomeReducer.project(
            requestState = RequestState.CANCELLED,
            billableBoundary = BillableBoundary.SENT,
            hasCommittedOutput = false,
            cancellation = CancellationState.LOCAL_WAIT_STOPPED,
        )

        assertEquals(CancellationState.LOCAL_WAIT_STOPPED, outcome.cancellation)
    }

    @Test
    fun `unknown outcome preserves billing uncertainty`() {
        val outcome = TurnOutcomeReducer.project(
            requestState = RequestState.UNKNOWN_OUTCOME,
            billableBoundary = BillableBoundary.UNKNOWN,
            hasCommittedOutput = false,
            unknownOutcomeReason = "transport_lost_after_dispatch",
        )

        assertEquals(TurnTerminalOutcome.UNKNOWN, outcome.terminal)
    }
}

package me.rerere.pale.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRetryPolicyTest {
    @Test
    fun `not sent failure can retry without charge confirmation`() {
        assertTrue(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.FAILED,
                boundary = BillableBoundary.NOT_SENT,
                providerGuaranteesIdempotency = false,
                acceptsPossibleCharge = false,
            ),
        )
    }

    @Test
    fun `sent unknown outcome cannot retry silently`() {
        assertFalse(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.UNKNOWN_OUTCOME,
                boundary = BillableBoundary.SENT,
                providerGuaranteesIdempotency = false,
                acceptsPossibleCharge = false,
            ),
        )
    }

    @Test
    fun `provider idempotency or explicit charge acceptance allows retry`() {
        assertTrue(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.UNKNOWN_OUTCOME,
                boundary = BillableBoundary.UNKNOWN,
                providerGuaranteesIdempotency = true,
                acceptsPossibleCharge = false,
            ),
        )
        assertTrue(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.INTERRUPTED,
                boundary = BillableBoundary.RESPONSE_STARTED,
                providerGuaranteesIdempotency = false,
                acceptsPossibleCharge = true,
            ),
        )
    }

    @Test
    fun `active and succeeded requests cannot create retry attempts`() {
        assertFalse(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.RUNNING,
                boundary = BillableBoundary.NOT_SENT,
                providerGuaranteesIdempotency = true,
                acceptsPossibleCharge = true,
            ),
        )
        assertFalse(
            RequestRetryPolicy.canCreateAttempt(
                state = RequestState.SUCCEEDED,
                boundary = BillableBoundary.RESULT_COMMITTED,
                providerGuaranteesIdempotency = true,
                acceptsPossibleCharge = true,
            ),
        )
    }
}

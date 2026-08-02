package me.rerere.pale.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLifecycleTest {
    @Test
    fun `normal request reaches durable success`() {
        assertTrue(RequestLifecycle.canTransition(RequestState.CREATED, RequestState.QUEUED))
        assertTrue(RequestLifecycle.canTransition(RequestState.QUEUED, RequestState.WAITING_RUNTIME))
        assertTrue(RequestLifecycle.canTransition(RequestState.WAITING_RUNTIME, RequestState.DISPATCHING))
        assertTrue(RequestLifecycle.canTransition(RequestState.DISPATCHING, RequestState.RUNNING))
        assertTrue(RequestLifecycle.canTransition(RequestState.RUNNING, RequestState.COMMITTING))
        assertTrue(RequestLifecycle.canTransition(RequestState.COMMITTING, RequestState.SUCCEEDED))
    }

    @Test
    fun `unknown outcome requires explicit possible charge acceptance`() {
        assertFalse(RequestLifecycle.canTransition(RequestState.UNKNOWN_OUTCOME, RequestState.QUEUED))
        assertFalse(
            RequestLifecycle.canTransition(
                RequestState.UNKNOWN_OUTCOME,
                RequestState.QUEUED,
                explicitRetry = true,
            ),
        )
        assertTrue(
            RequestLifecycle.canTransition(
                RequestState.UNKNOWN_OUTCOME,
                RequestState.QUEUED,
                explicitRetry = true,
                acceptsPossibleCharge = true,
            ),
        )
    }

    @Test
    fun `paid result cannot be cancelled or dispatched again while committing`() {
        assertFalse(RequestLifecycle.canTransition(RequestState.COMMITTING, RequestState.CANCELLED))
        assertFalse(RequestLifecycle.canTransition(RequestState.COMMITTING, RequestState.DISPATCHING))
    }

    @Test
    fun `recovery never silently requeues interrupted work`() {
        assertFalse(RequestLifecycle.canTransition(RequestState.INTERRUPTED, RequestState.QUEUED))
        assertTrue(
            RequestLifecycle.canTransition(
                RequestState.INTERRUPTED,
                RequestState.QUEUED,
                explicitRetry = true,
            ),
        )
    }

    @Test
    fun `terminal success cannot be replayed`() {
        assertFalse(RequestLifecycle.canTransition(RequestState.SUCCEEDED, RequestState.QUEUED))
        assertFalse(
            RequestLifecycle.canTransition(
                RequestState.SUCCEEDED,
                RequestState.QUEUED,
                explicitRetry = true,
            ),
        )
    }
}

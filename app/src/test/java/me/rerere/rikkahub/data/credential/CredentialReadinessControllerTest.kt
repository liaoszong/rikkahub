package me.rerere.rikkahub.data.credential

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialReadinessControllerTest {
    @Test
    fun `initializing gate suspends dispatch until credential bootstrap is ready`() = runBlocking {
        val controller = CredentialReadinessController()
        var dispatched = false
        val request = async {
            controller.awaitReady()
            dispatched = true
        }

        kotlinx.coroutines.yield()
        assertFalse(dispatched)
        controller.ready()
        withTimeout(1_000) { request.await() }
        assertEquals(CredentialReadiness.Ready, controller.state.value)
        assertEquals(true, dispatched)
    }

    @Test
    fun `unavailable gate fails closed before request dispatch`() = runBlocking {
        val controller = CredentialReadinessController()
        controller.unavailable(CredentialUnavailableReason.CORRUPT_ENTRY, retryable = false)
        var dispatched = false

        val failure = runCatching {
            controller.awaitReady()
            dispatched = true
        }.exceptionOrNull()

        assertFalse(dispatched)
        assertEquals(
            CredentialReadiness.Unavailable(CredentialUnavailableReason.CORRUPT_ENTRY, retryable = false),
            (failure as CredentialNetworkUnavailableException).readiness,
        )
    }

    @Test
    fun `synchronous entry point rejects initializing state`() {
        val controller = CredentialReadinessController()
        assertThrows(CredentialNetworkUnavailableException::class.java) {
            controller.requireReady()
        }
    }
}

package me.rerere.rikkahub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryPipelineTest {
    @Test
    fun `one failed recovery domain cannot block later domains`() = runBlocking {
        val executed = mutableListOf<String>()
        val failures = mutableListOf<String>()

        runIndependentStartupRecoveryDomains(
            domains = listOf(
                StartupRecoveryDomain("request") { executed += "request" },
                StartupRecoveryDomain("citation") {
                    executed += "citation"
                    error("legacy payload must not escape into logs")
                },
                StartupRecoveryDomain("cleanup") { executed += "cleanup" },
            ),
            onFailure = { domain, _ -> failures += domain },
        )

        assertEquals(listOf("request", "citation", "cleanup"), executed)
        assertEquals(listOf("citation"), failures)
    }

    @Test
    fun `scope cancellation stops recovery instead of being downgraded to a domain failure`() = runBlocking {
        var laterDomainRan = false
        val result = runCatching {
            runIndependentStartupRecoveryDomains(
                domains = listOf(
                    StartupRecoveryDomain("cancelled") { throw CancellationException("stop") },
                    StartupRecoveryDomain("later") { laterDomainRan = true },
                ),
                onFailure = { _, _ -> error("Cancellation must not be reported as a domain failure") },
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(!laterDomainRan)
    }

    @Test
    fun `fatal vm errors are not swallowed as recoverable domain failures`() = runBlocking {
        var failureReported = false
        var laterDomainRan = false

        val result = runCatching {
            runIndependentStartupRecoveryDomains(
                domains = listOf(
                    StartupRecoveryDomain("fatal") { throw AssertionError("invariant") },
                    StartupRecoveryDomain("later") { laterDomainRan = true },
                ),
                onFailure = { _, _ -> failureReported = true },
            )
        }

        assertTrue(result.exceptionOrNull() is AssertionError)
        assertTrue(!failureReported)
        assertTrue(!laterDomainRan)
    }
}

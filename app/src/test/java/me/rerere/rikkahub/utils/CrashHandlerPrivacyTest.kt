package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashHandlerPrivacyTest {
    @Test
    fun `legacy crash payload is rejected without retaining secrets or stack`() {
        val raw = "java.io.IOException: Authorization: Bearer crash-secret " +
            "content://private/report?api_key=query-secret\n" +
            "\tat SecretFrame.call(secret.kt:42)"

        val sanitized = sanitizeStoredCrashReport(raw)

        assertEquals(
            "event=operation domain=crash operation=legacy_report outcome=rejected",
            sanitized,
        )
        listOf("Bearer", "crash-secret", "content://", "api_key", "SecretFrame", "\n").forEach {
            assertFalse(sanitized.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `structured crash report is preserved`() {
        val safe = "event=operation domain=crash operation=uncaught_exception outcome=failed " +
            "errorClass=IOException causeClass=SocketException"

        assertEquals(safe, sanitizeStoredCrashReport(safe))
    }

    @Test
    fun `forged structured prefix cannot append a diagnostic payload`() {
        val forged = "event=operation domain=crash operation=uncaught_exception outcome=failed " +
            "errorClass=IOException Authorization=Bearer-secret"

        assertEquals(
            "event=operation domain=crash operation=legacy_report outcome=rejected",
            sanitizeStoredCrashReport(forged),
        )
    }
}

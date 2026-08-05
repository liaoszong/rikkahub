package me.rerere.common.android

import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LoggingPrivacyTest {
    @After
    fun tearDown() {
        Logging.setRequestLoggingEnabled(false)
        Logging.clear()
    }

    @Test
    fun `structured errors retain type and correlation fields without throwable payload`() {
        val cause = IllegalStateException(
            "content://documents/private/report?token=uri-secret Authorization: Bearer cause-secret"
        )
        val error = IOException(
            "Authorization: Bearer top-secret api_key=message-secret https://api.example/private?key=query-secret",
            cause,
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "BearerSecretStackFrame",
                    "api_key_body_secret",
                    "content-uri-secret.kt",
                    42,
                )
            )
        }

        val logcatMessage = Logging.logError(
            tag = "ChatService",
            domain = "chat",
            operation = "complete_generation",
            error = error,
            requestId = "request-123",
            httpStatus = 502,
        )

        val expected = "event=operation domain=chat operation=complete_generation outcome=failed " +
            "requestId=request-123 httpStatus=502 errorClass=IOException causeClass=IllegalStateException"
        assertEquals(expected, logcatMessage)
        assertEquals(expected, Logging.getTextLogs().single().message)
        assertCanariesAbsent(logcatMessage)
    }

    @Test
    fun `legacy free form text is never retained`() {
        val raw = "Authorization: Bearer legacy-secret api_key=body-secret " +
            "content://documents/private?id=query-secret\n" +
            "\tat SecretStack.call(secret.kt:9)"

        Logging.log(tag = "CropLauncher", message = raw)

        val retained = Logging.getTextLogs().single()
        assertEquals("CropLauncher", retained.tag)
        assertEquals("event=legacy_text_redacted inputLength=${raw.length}", retained.message)
        assertCanariesAbsent(retained.message)
    }

    @Test
    fun `request persistence applies a second privacy boundary`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = "https://user:password@example.com/v1/chat/completions" +
                    "?api_key=query-secret#Bearer-fragment-secret",
                method = "POST",
                requestHeaders = mapOf(
                    "Authorization" to "Bearer header-secret",
                    "X-Api-Key" to "request-header-secret",
                    "Content-Type" to "application/json; charset=utf-8",
                    "User-Agent" to "device-secret",
                ),
                requestBody = "{\"prompt\":\"private-body\",\"api_key\":\"body-secret\"}",
                responseCode = 502,
                responseHeaders = mapOf(
                    "Set-Cookie" to "session=response-secret",
                    "Content-Length" to "123",
                ),
                error = "Authorization: Bearer error-secret",
            )
        )

        val retained = Logging.getRequestLogs().single()
        assertEquals("https://example.com/<redacted>?<redacted>", retained.url)
        assertEquals("POST", retained.method)
        assertEquals("<redacted>", retained.requestHeaders["Authorization"])
        assertEquals("<redacted>", retained.requestHeaders["X-Api-Key"])
        assertEquals("application/json; charset=utf-8", retained.requestHeaders["Content-Type"])
        assertEquals("<present>", retained.requestHeaders["User-Agent"])
        assertEquals("<redacted>", retained.requestBody)
        assertEquals("<redacted>", retained.responseHeaders["Set-Cookie"])
        assertEquals("123", retained.responseHeaders["Content-Length"])
        assertEquals("<redacted>", retained.error)
        assertEquals(502, retained.responseCode)
        assertRequestCanariesAbsent(retained)
    }

    @Test
    fun `already sanitized request URLs remain useful without restoring query values`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = "https://api.example.com/v1/responses?<redacted>",
                method = "POST",
            )
        )

        val retained = Logging.getRequestLogs().single()
        assertEquals("https://api.example.com/<redacted>?<redacted>", retained.url)
        assertNull(retained.requestBody)
    }

    @Test
    fun `untrusted correlation fields are omitted rather than copied`() {
        val logcatMessage = Logging.logOperation(
            tag = "Authorization-Bearer-secret",
            domain = "files",
            operation = "copy_chat_attachment",
            outcome = SafeLogOutcome.REJECTED,
            requestId = "Bearer-request-secret",
        )

        assertEquals(
            "event=operation domain=files operation=copy_chat_attachment outcome=rejected",
            logcatMessage,
        )
        assertEquals("unknown", Logging.getTextLogs().single().tag)
        assertCanariesAbsent(logcatMessage)
    }

    private fun assertCanariesAbsent(value: String) {
        listOf(
            "Authorization",
            "Bearer",
            "top-secret",
            "message-secret",
            "cause-secret",
            "api_key",
            "content://",
            "query-secret",
            "SecretStack",
            ".kt:42",
            "\n",
        ).forEach { canary ->
            assertFalse("Leaked canary: $canary in $value", value.contains(canary, ignoreCase = true))
        }
    }

    private fun assertRequestCanariesAbsent(entry: LogEntry.RequestLog) {
        val retained = buildString {
            append(entry.url)
            append(entry.requestHeaders.values.joinToString())
            append(entry.requestBody)
            append(entry.responseHeaders.values.joinToString())
            append(entry.error)
        }
        listOf(
            "password",
            "query-secret",
            "fragment-secret",
            "header-secret",
            "request-header-secret",
            "device-secret",
            "private-body",
            "body-secret",
            "response-secret",
            "error-secret",
        ).forEach { canary ->
            assertFalse("Leaked request canary: $canary in $retained", retained.contains(canary, ignoreCase = true))
        }
    }
}

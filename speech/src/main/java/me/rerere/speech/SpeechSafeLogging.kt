package me.rerere.speech

import me.rerere.common.android.Logging
import me.rerere.common.android.SafeLogLevel
import me.rerere.common.android.SafeLogOutcome

internal fun logSpeechStarted(tag: String, operation: String) {
    Logging.logOperationToLogcat(
        tag = tag,
        domain = "speech",
        operation = operation,
        outcome = SafeLogOutcome.STARTED,
        level = SafeLogLevel.INFO,
        persist = false,
    )
}

internal fun logSpeechFailure(
    tag: String,
    operation: String,
    httpStatus: Int? = null,
    warning: Boolean = false,
) {
    Logging.logOperationToLogcat(
        tag = tag,
        domain = "speech",
        operation = operation,
        outcome = SafeLogOutcome.FAILED,
        level = if (warning) SafeLogLevel.WARN else SafeLogLevel.ERROR,
        httpStatus = httpStatus,
    )
}

internal fun logSpeechError(tag: String, operation: String, error: Throwable, warning: Boolean = false) {
    Logging.logErrorToLogcat(
        tag = tag,
        domain = "speech",
        operation = operation,
        error = error,
        level = if (warning) SafeLogLevel.WARN else SafeLogLevel.ERROR,
    )
}

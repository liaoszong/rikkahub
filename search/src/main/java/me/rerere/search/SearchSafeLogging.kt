package me.rerere.search

import me.rerere.common.android.Logging
import me.rerere.common.android.SafeLogLevel
import me.rerere.common.android.SafeLogOutcome

internal fun logSearchStarted(tag: String, operation: String = "search_request") {
    Logging.logOperationToLogcat(
        tag = tag,
        domain = "search",
        operation = operation,
        outcome = SafeLogOutcome.STARTED,
        level = SafeLogLevel.INFO,
        persist = false,
    )
}

internal fun logSearchHttpFailure(tag: String, operation: String, httpStatus: Int) {
    Logging.logOperationToLogcat(
        tag = tag,
        domain = "search",
        operation = operation,
        outcome = SafeLogOutcome.FAILED,
        level = SafeLogLevel.WARN,
        httpStatus = httpStatus,
    )
}

internal fun logSearchError(tag: String, operation: String, error: Throwable) {
    Logging.logErrorToLogcat(
        tag = tag,
        domain = "search",
        operation = operation,
        error = error,
        level = SafeLogLevel.WARN,
    )
}

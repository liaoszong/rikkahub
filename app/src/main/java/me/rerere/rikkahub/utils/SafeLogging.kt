package me.rerere.rikkahub.utils

import me.rerere.common.android.Logging
import me.rerere.common.android.SafeLogLevel
import me.rerere.common.android.SafeLogOutcome

internal fun logSafeError(
    tag: String,
    domain: String,
    operation: String,
    error: Throwable,
    warning: Boolean = false,
    requestId: String? = null,
    httpStatus: Int? = null,
    persist: Boolean = true,
): String = Logging.logErrorToLogcat(
    tag = tag,
    domain = domain,
    operation = operation,
    error = error,
    level = if (warning) SafeLogLevel.WARN else SafeLogLevel.ERROR,
    requestId = requestId,
    httpStatus = httpStatus,
    persist = persist,
)

internal fun logSafeFailure(
    tag: String,
    domain: String,
    operation: String,
    warning: Boolean = false,
    requestId: String? = null,
    httpStatus: Int? = null,
    itemCount: Int? = null,
    persist: Boolean = true,
): String = Logging.logOperationToLogcat(
    tag = tag,
    domain = domain,
    operation = operation,
    outcome = SafeLogOutcome.FAILED,
    level = if (warning) SafeLogLevel.WARN else SafeLogLevel.ERROR,
    requestId = requestId,
    httpStatus = httpStatus,
    itemCount = itemCount,
    persist = persist,
)

internal fun logSafeStarted(
    tag: String,
    domain: String,
    operation: String,
    persist: Boolean = false,
): String = Logging.logOperationToLogcat(
    tag = tag,
    domain = domain,
    operation = operation,
    outcome = SafeLogOutcome.STARTED,
    level = SafeLogLevel.INFO,
    persist = persist,
)

internal fun logSafeSuccess(
    tag: String,
    domain: String,
    operation: String,
    itemCount: Int? = null,
    persist: Boolean = false,
): String = Logging.logOperationToLogcat(
    tag = tag,
    domain = domain,
    operation = operation,
    outcome = SafeLogOutcome.SUCCEEDED,
    level = SafeLogLevel.DEBUG,
    itemCount = itemCount,
    persist = persist,
)

package me.rerere.rikkahub.utils

import android.content.Context
import androidx.core.content.edit
import me.rerere.common.android.Logging

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val SAFE_CRASH_PREFIX =
    "event=operation domain=crash operation=uncaught_exception outcome=failed errorClass="
private const val LEGACY_CRASH_REDACTED =
    "event=operation domain=crash operation=legacy_report outcome=rejected"
private val SAFE_CRASH_REPORT_PATTERN = Regex(
    "^event=operation domain=crash operation=uncaught_exception outcome=failed " +
        "errorClass=[A-Za-z_][A-Za-z0-9_.$]{0,127}" +
        "(?: causeClass=[A-Za-z_][A-Za-z0-9_.$]{0,127})?$",
)
private val SENSITIVE_CRASH_FIELD_PATTERN = Regex(
    "authorization|bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|cookie",
    RegexOption.IGNORE_CASE,
)

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val safeReport = Logging.logErrorToLogcat(
                tag = TAG,
                domain = "crash",
                operation = "uncaught_exception",
                error = throwable,
                persist = false,
            )
            markCrashed(appContext, safeReport)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
            ?: return null
        return sanitizeStoredCrashReport(stored)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, safeReport: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, safeReport)
            } // commit() 同步写入，确保崩溃前写完
    }
}

internal fun sanitizeStoredCrashReport(stored: String): String =
    stored.takeIf {
        it.startsWith(SAFE_CRASH_PREFIX) &&
            SAFE_CRASH_REPORT_PATTERN.matches(it) &&
            !SENSITIVE_CRASH_FIELD_PATTERN.containsMatchIn(it)
    } ?: LEGACY_CRASH_REDACTED

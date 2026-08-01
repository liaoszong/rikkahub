package me.rerere.rikkahub.utils

import android.os.Bundle

/** App-owned analytics boundary so debug builds never require production Firebase. */
interface AppAnalytics {
    fun logEvent(name: String, params: Bundle? = null)
}

object NoOpAppAnalytics : AppAnalytics {
    override fun logEvent(name: String, params: Bundle?) = Unit
}

package me.rerere.rikkahub.utils

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/** App-owned analytics boundary so debug builds never require production Firebase. */
interface AppAnalytics {
    fun logEvent(name: String, params: Bundle? = null)
}

class FirebaseAppAnalytics(
    private val firebaseAnalytics: FirebaseAnalytics,
) : AppAnalytics {
    override fun logEvent(name: String, params: Bundle?) {
        firebaseAnalytics.logEvent(name, params)
    }
}

object NoOpAppAnalytics : AppAnalytics {
    override fun logEvent(name: String, params: Bundle?) = Unit
}

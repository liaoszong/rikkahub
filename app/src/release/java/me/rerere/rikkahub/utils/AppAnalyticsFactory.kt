package me.rerere.rikkahub.utils

import android.os.Bundle
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

internal class FirebaseAppAnalytics(
    private val firebaseAnalytics: FirebaseAnalytics,
) : AppAnalytics {
    override fun logEvent(name: String, params: Bundle?) {
        firebaseAnalytics.logEvent(name, params)
    }
}

/** Release builds retain the production Firebase analytics implementation. */
fun createAppAnalytics(): AppAnalytics = FirebaseAppAnalytics(Firebase.analytics)

package me.rerere.rikkahub.utils

/** Debug builds never initialize or upload Firebase telemetry. */
fun createAppAnalytics(): AppAnalytics = NoOpAppAnalytics

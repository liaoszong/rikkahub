package me.rerere.rikkahub.data.quality

import android.content.Context
import androidx.core.content.edit
import me.rerere.pale.product.QualityEvent

/**
 * Local anonymous aggregate sink. Keys are closed enums/diagnostic codes; conversational content,
 * URLs, attachment names and provider payloads cannot enter this storage surface.
 */
class QualityMetricsRecorder(context: Context) {
    private val preferences = context.getSharedPreferences("agent_quality_metrics_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun record(event: QualityEvent, enabled: Boolean) {
        if (!enabled) return
        val key = buildString {
            append(event.metric.name)
            append('|').append(event.providerKind?.sanitize().orEmpty())
            append('|').append(event.modelFamily?.sanitize().orEmpty())
            append('|').append(event.diagnosticCode?.sanitize().orEmpty())
        }
        preferences.edit {
            putLong("count:$key", preferences.getLong("count:$key", 0L) + 1L)
            putLong("last:$key", event.occurredAt)
        }
    }

    fun clear() = preferences.edit { clear() }

    private fun String.sanitize(): String = lowercase()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(64)
}

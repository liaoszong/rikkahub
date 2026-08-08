package me.rerere.pale.product

import kotlinx.serialization.Serializable
import me.rerere.pale.continuity.ResumeAction
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState

@Serializable
data class TaskProjection(
    val requestId: String,
    val conversationId: String? = null,
    val title: String,
    val state: TaskDisplayState,
    val startedAt: Long?,
    val updatedAt: Long,
    val elapsedMillis: Long,
    val cost: CostSummary?,
    val resumeAction: ResumeAction?,
    val canCancelLocalWait: Boolean,
    val remoteCancellationConfirmed: Boolean,
    val diagnosticCode: String?,
)

@Serializable
enum class TaskDisplayState {
    QUEUED, SEARCHING, SYNTHESIZING, WAITING_USER, BACKGROUND, COMMITTING, SUCCEEDED, FAILED, CANCELLED, NEEDS_DECISION
}

@Serializable
data class CostSummary(
    val currency: String,
    val estimatedMicros: Long,
    val actualMicros: Long? = null,
    val mayDuplicateOnRetry: Boolean = false,
)

object TaskProjector {
    fun project(
        requestId: String,
        title: String,
        requestState: RequestState,
        boundary: BillableBoundary,
        nowMillis: Long,
        startedAt: Long?,
        updatedAt: Long,
        resumeAction: ResumeAction? = null,
        cost: CostSummary? = null,
        errorCode: String? = null,
        conversationId: String? = null,
    ): TaskProjection {
        val display = when {
            resumeAction == ResumeAction.REQUIRE_DUPLICATE_COST_CONFIRMATION -> TaskDisplayState.NEEDS_DECISION
            requestState == RequestState.WAITING_USER -> TaskDisplayState.WAITING_USER
            requestState == RequestState.CREATED || requestState == RequestState.QUEUED -> TaskDisplayState.QUEUED
            requestState == RequestState.DISPATCHING || requestState == RequestState.RUNNING -> TaskDisplayState.SYNTHESIZING
            requestState == RequestState.WAITING_RUNTIME -> TaskDisplayState.BACKGROUND
            requestState == RequestState.COMMITTING -> TaskDisplayState.COMMITTING
            requestState == RequestState.SUCCEEDED -> TaskDisplayState.SUCCEEDED
            requestState == RequestState.CANCELLED -> TaskDisplayState.CANCELLED
            else -> TaskDisplayState.FAILED
        }
        return TaskProjection(
            requestId = requestId,
            conversationId = conversationId,
            title = title,
            state = display,
            startedAt = startedAt,
            updatedAt = updatedAt,
            elapsedMillis = startedAt?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0,
            cost = cost?.copy(mayDuplicateOnRetry = boundary != BillableBoundary.NOT_SENT),
            resumeAction = resumeAction,
            canCancelLocalWait = !requestState.isTerminal,
            remoteCancellationConfirmed = requestState == RequestState.CANCELLED,
            diagnosticCode = errorCode,
        )
    }
}

@Serializable
data class PrivacyPolicy(
    val networkEnabled: Boolean = true,
    val localOnly: Boolean = false,
    val memoryEnabled: Boolean = true,
    val persistSensitiveContent: Boolean = false,
    val rawPayloadRetention: RawPayloadRetention = RawPayloadRetention.SHORT_LIVED_PLATFORM_MANAGED,
    val anonymousMetricsEnabled: Boolean = true,
) {
    init { require(!(localOnly && networkEnabled)) { "localOnly and networkEnabled cannot both be true" } }
}

@Serializable
enum class RawPayloadRetention { NONE, SHORT_LIVED_PLATFORM_MANAGED, USER_MANAGED }

@Serializable
enum class QualityMetric {
    SEARCH_TERMINAL_SUCCESS,
    SEARCH_TERMINAL_FAILURE,
    CONTEXT_OVERFLOW,
    MEMORY_CORRECTION,
    RESUME_SUCCESS,
    DUPLICATE_CHARGE_BLOCKED,
    MIGRATION_SUCCESS,
    MIGRATION_FAILURE,
}

@Serializable
data class QualityEvent(
    val metric: QualityMetric,
    val occurredAt: Long,
    val providerKind: String? = null,
    val modelFamily: String? = null,
    val diagnosticCode: String? = null,
) {
    /** Deliberately has no prompt, response, URL, attachment name, or raw payload field. */
    init { require(occurredAt >= 0) }
}

@Serializable
data class QualityAggregate(
    val metric: QualityMetric,
    val providerKind: String? = null,
    val modelFamily: String? = null,
    val diagnosticCode: String? = null,
    val count: Long,
    val lastOccurredAt: Long,
)

object QualityAggregator {
    fun reduce(events: List<QualityEvent>): List<QualityAggregate> = events
        .groupBy { listOf(it.metric.name, it.providerKind, it.modelFamily, it.diagnosticCode) }
        .map { (_, grouped) ->
            val first = grouped.first()
            QualityAggregate(
                metric = first.metric,
                providerKind = first.providerKind,
                modelFamily = first.modelFamily,
                diagnosticCode = first.diagnosticCode,
                count = grouped.size.toLong(),
                lastOccurredAt = grouped.maxOf(QualityEvent::occurredAt),
            )
        }
        .sortedWith(compareBy(QualityAggregate::metric, QualityAggregate::providerKind, QualityAggregate::modelFamily))
}

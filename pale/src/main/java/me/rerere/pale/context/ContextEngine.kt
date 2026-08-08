package me.rerere.pale.context

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider-neutral, content-free input to the authoritative context selector. */
@Serializable
data class ContextSource(
    val sourceRef: String,
    val sourceDigest: String,
    val kind: ContextSourceKind,
    val estimatedTokens: Int,
    val semanticUnitId: String = sourceRef,
    val priority: Int = 0,
    val required: Boolean = false,
    val retrievable: Boolean = false,
    val active: Boolean = true,
) {
    init {
        require(sourceRef.isNotBlank())
        require(sourceDigest.isNotBlank())
        require(semanticUnitId.isNotBlank())
        require(estimatedTokens >= 0)
    }
}

@Serializable
enum class ContextSourceKind {
    @SerialName("system") SYSTEM,
    @SerialName("current_user") CURRENT_USER,
    @SerialName("provider_replay") PROVIDER_REPLAY,
    @SerialName("tool_pair") TOOL_PAIR,
    @SerialName("recent_dialogue") RECENT_DIALOGUE,
    @SerialName("older_dialogue") OLDER_DIALOGUE,
    @SerialName("episodic_summary") EPISODIC_SUMMARY,
    @SerialName("memory") MEMORY,
    @SerialName("evidence") EVIDENCE,
    @SerialName("attachment") ATTACHMENT,
}

@Serializable
data class ContextBudgetPolicy(
    val modelWindowTokens: Int,
    val reservedOutputTokens: Int,
    val reservedRepairTokens: Int = 0,
    val safetyMarginTokens: Int,
) {
    init {
        require(modelWindowTokens > 0)
        require(reservedOutputTokens >= 0)
        require(reservedRepairTokens >= 0)
        require(safetyMarginTokens >= 0)
        require(availableInputTokens >= 0) { "Context reserves exceed model window" }
    }

    val availableInputTokens: Int
        get() = modelWindowTokens - reservedOutputTokens - reservedRepairTokens - safetyMarginTokens
}

@Serializable
data class ContextSelection(
    val compilerVersion: String,
    val availableInputTokens: Int,
    val included: List<ContextSelectionEntry>,
    val excluded: List<ContextSelectionEntry>,
    val includedTokens: Int,
) {
    init {
        require(includedTokens == included.sumOf(ContextSelectionEntry::estimatedTokens))
        require(includedTokens <= availableInputTokens)
    }
}

@Serializable
data class ContextSelectionEntry(
    val sourceRef: String,
    val sourceDigest: String,
    val semanticUnitId: String,
    val estimatedTokens: Int,
    val disposition: ContextSelectionDisposition,
    val reason: ContextSelectionReason,
)

@Serializable
enum class ContextSelectionDisposition { INCLUDED, EXCLUDED }

@Serializable
enum class ContextSelectionReason {
    REQUIRED,
    PRIORITY_SELECTED,
    INACTIVE,
    BUDGET_EXHAUSTED,
    RETRIEVAL_SUBSTITUTED,
}

class RequiredContextDoesNotFitException(
    val requiredTokens: Int,
    val availableTokens: Int,
) : IllegalStateException("Required context needs $requiredTokens tokens; only $availableTokens available")

/**
 * Deterministic, semantic-unit-safe selector. A semantic unit is all-or-nothing, preventing orphaned
 * tool results, provider continuation blocks, citation pairs, and partially retained attachments.
 */
object ContextBudgetPlanner {
    const val COMPILER_VERSION = "authoritative-context-v1"

    fun plan(sources: List<ContextSource>, policy: ContextBudgetPolicy): ContextSelection {
        val activeGroups = sources.filter(ContextSource::active).groupBy(ContextSource::semanticUnitId)
        val inactive = sources.filterNot(ContextSource::active)
        val requiredGroups = activeGroups.values.filter { group -> group.any(ContextSource::required) }
        val requiredTokens = requiredGroups.sumOf { group -> group.sumOf(ContextSource::estimatedTokens) }
        if (requiredTokens > policy.availableInputTokens) {
            throw RequiredContextDoesNotFitException(requiredTokens, policy.availableInputTokens)
        }

        val selectedUnitIds = linkedSetOf<String>()
        requiredGroups.forEach { selectedUnitIds += it.first().semanticUnitId }
        var remaining = policy.availableInputTokens - requiredTokens

        activeGroups.values
            .filterNot { it.first().semanticUnitId in selectedUnitIds }
            .sortedWith(
                compareByDescending<List<ContextSource>> { group -> group.maxOf(::tier) }
                    .thenByDescending { group -> group.maxOf(ContextSource::priority) }
                    .thenBy { group -> group.minOf(ContextSource::sourceRef) }
            )
            .forEach { group ->
                val cost = group.sumOf(ContextSource::estimatedTokens)
                if (cost <= remaining) {
                    selectedUnitIds += group.first().semanticUnitId
                    remaining -= cost
                }
            }

        val included = sources.filter { it.active && it.semanticUnitId in selectedUnitIds }.map { source ->
            source.toEntry(
                ContextSelectionDisposition.INCLUDED,
                if (source.required) ContextSelectionReason.REQUIRED else ContextSelectionReason.PRIORITY_SELECTED,
            )
        }
        val excluded = buildList {
            inactive.forEach { add(it.toEntry(ContextSelectionDisposition.EXCLUDED, ContextSelectionReason.INACTIVE)) }
            sources.filter { it.active && it.semanticUnitId !in selectedUnitIds }.forEach { source ->
                add(
                    source.toEntry(
                        ContextSelectionDisposition.EXCLUDED,
                        if (source.retrievable) {
                            ContextSelectionReason.RETRIEVAL_SUBSTITUTED
                        } else {
                            ContextSelectionReason.BUDGET_EXHAUSTED
                        },
                    )
                )
            }
        }
        return ContextSelection(
            compilerVersion = COMPILER_VERSION,
            availableInputTokens = policy.availableInputTokens,
            included = included,
            excluded = excluded,
            includedTokens = included.sumOf(ContextSelectionEntry::estimatedTokens),
        )
    }

    private fun tier(source: ContextSource): Int = when (source.kind) {
        ContextSourceKind.SYSTEM -> 700
        ContextSourceKind.CURRENT_USER -> 690
        ContextSourceKind.PROVIDER_REPLAY -> 680
        ContextSourceKind.TOOL_PAIR -> 670
        ContextSourceKind.RECENT_DIALOGUE -> 500
        ContextSourceKind.EPISODIC_SUMMARY -> 400
        ContextSourceKind.MEMORY -> 390
        ContextSourceKind.EVIDENCE -> 380
        ContextSourceKind.ATTACHMENT -> 200
        ContextSourceKind.OLDER_DIALOGUE -> 300
    }

    private fun ContextSource.toEntry(
        disposition: ContextSelectionDisposition,
        reason: ContextSelectionReason,
    ) = ContextSelectionEntry(
        sourceRef = sourceRef,
        sourceDigest = sourceDigest,
        semanticUnitId = semanticUnitId,
        estimatedTokens = estimatedTokens,
        disposition = disposition,
        reason = reason,
    )
}

@Serializable
data class EpisodicSummary(
    val summaryId: String,
    val sourceRefs: List<String>,
    val decisions: List<String>,
    val openQuestions: List<String>,
    val evidenceRefs: List<String>,
    val provenance: SummaryProvenance,
    val active: Boolean = true,
    val revision: Int = 1,
) {
    init {
        require(summaryId.isNotBlank())
        require(sourceRefs.isNotEmpty()) { "A summary cannot exist without original sources" }
        require(revision > 0)
    }
}

@Serializable
enum class SummaryProvenance { STRUCTURED_COMPACTION, LEGACY_SUMMARY }

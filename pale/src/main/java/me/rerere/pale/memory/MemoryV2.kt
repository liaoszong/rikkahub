package me.rerere.pale.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryType { PROFILE, PREFERENCE, FACT, EPISODIC, PROJECT_SPACE, PROHIBITION }

@Serializable
enum class MemoryScopeKind { USER, ASSISTANT, SPACE, CONVERSATION }

@Serializable
data class MemoryScope(val kind: MemoryScopeKind, val id: String) {
    init { require(id.isNotBlank()) }
}

@Serializable
enum class MemorySensitivity { NORMAL, SENSITIVE, HIGHLY_SENSITIVE }

@Serializable
enum class MemoryStatus { CANDIDATE, ACTIVE, DISABLED, SUPERSEDED, CONFLICTED, DELETED }

@Serializable
enum class MemorySourceTrust {
    @SerialName("explicit_user") EXPLICIT_USER,
    @SerialName("conversation_user") CONVERSATION_USER,
    @SerialName("model_inference") MODEL_INFERENCE,
    @SerialName("external_untrusted") EXTERNAL_UNTRUSTED,
    @SerialName("legacy_manual") LEGACY_MANUAL,
}

@Serializable
data class MemoryRecord(
    val memoryId: String,
    val type: MemoryType,
    val scope: MemoryScope,
    val canonicalStatement: String,
    val sourceRefs: List<String>,
    val sourceTrust: MemorySourceTrust,
    val createdAt: Long,
    val confirmedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val expiresAt: Long? = null,
    val confidence: Double,
    val sensitivity: MemorySensitivity,
    val status: MemoryStatus,
    val revision: Int,
    val supersedes: List<String> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
    val extractionPolicyVersion: Int,
) {
    init {
        require(memoryId.isNotBlank())
        require(canonicalStatement.isNotBlank())
        require(sourceRefs.isNotEmpty()) { "Memory provenance is required" }
        require(confidence in 0.0..1.0)
        require(revision > 0)
        require(extractionPolicyVersion > 0)
    }
}

@Serializable
data class MemoryCandidate(
    val memoryId: String,
    val type: MemoryType,
    val scope: MemoryScope,
    val statement: String,
    val sourceRefs: List<String>,
    val sourceTrust: MemorySourceTrust,
    val confidence: Double,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val explicitUserMutation: Boolean = false,
)

@Serializable
enum class MemoryCandidateDecision {
    ACTIVATE,
    HOLD_FOR_CONFIRMATION,
    REJECT_EXTERNAL_FACT,
    REJECT_LOW_VALUE,
}

object MemoryWritePolicy {
    fun evaluate(candidate: MemoryCandidate): MemoryCandidateDecision = when {
        candidate.statement.isBlank() || candidate.sourceRefs.isEmpty() -> MemoryCandidateDecision.REJECT_LOW_VALUE
        candidate.sourceTrust == MemorySourceTrust.EXTERNAL_UNTRUSTED -> MemoryCandidateDecision.REJECT_EXTERNAL_FACT
        candidate.explicitUserMutation -> MemoryCandidateDecision.ACTIVATE
        candidate.sensitivity != MemorySensitivity.NORMAL -> MemoryCandidateDecision.HOLD_FOR_CONFIRMATION
        candidate.confidence < 0.85 -> MemoryCandidateDecision.HOLD_FOR_CONFIRMATION
        else -> MemoryCandidateDecision.ACTIVATE
    }
}

@Serializable
data class MemorySelectionPolicy(
    val scopes: Set<MemoryScope>,
    val tokenBudget: Int,
    val memoryEnabled: Boolean = true,
    val nowMillis: Long,
    val maxItems: Int = 16,
) {
    init {
        require(tokenBudget >= 0)
        require(maxItems >= 0)
    }
}

@Serializable
data class MemorySelectionEntry(
    val memoryId: String,
    val sourceRefs: List<String>,
    val statement: String,
    val estimatedTokens: Int,
)

@Serializable
data class MemorySelection(
    val entries: List<MemorySelectionEntry>,
    val excluded: Map<String, MemoryExclusionReason>,
    val estimatedTokens: Int,
)

@Serializable
enum class MemoryExclusionReason { MEMORY_OFF, SCOPE, STATUS, EXPIRED, CONFLICT, BUDGET, ITEM_LIMIT }

object MemorySelector {
    fun select(
        records: List<MemoryRecord>,
        policy: MemorySelectionPolicy,
        relevance: Map<String, Double> = emptyMap(),
    ): MemorySelection {
        val excluded = linkedMapOf<String, MemoryExclusionReason>()
        if (!policy.memoryEnabled) {
            records.forEach { excluded[it.memoryId] = MemoryExclusionReason.MEMORY_OFF }
            return MemorySelection(emptyList(), excluded, 0)
        }

        val eligible = records.filter { record ->
            when {
                record.scope !in policy.scopes -> MemoryExclusionReason.SCOPE
                record.status != MemoryStatus.ACTIVE -> MemoryExclusionReason.STATUS
                record.expiresAt?.let { it <= policy.nowMillis } == true -> MemoryExclusionReason.EXPIRED
                record.conflictsWith.isNotEmpty() -> MemoryExclusionReason.CONFLICT
                else -> null
            }?.let { reason -> excluded[record.memoryId] = reason; false } ?: true
        }.sortedWith(
            compareByDescending<MemoryRecord> { it.type == MemoryType.PROHIBITION }
                .thenByDescending { relevance[it.memoryId] ?: 0.0 }
                .thenByDescending(MemoryRecord::confidence)
                .thenBy(MemoryRecord::memoryId)
        )

        val entries = mutableListOf<MemorySelectionEntry>()
        var tokens = 0
        eligible.forEach { record ->
            val estimate = estimateTokens(record.canonicalStatement)
            val reason = when {
                entries.size >= policy.maxItems -> MemoryExclusionReason.ITEM_LIMIT
                tokens + estimate > policy.tokenBudget -> MemoryExclusionReason.BUDGET
                else -> null
            }
            if (reason != null) {
                excluded[record.memoryId] = reason
            } else {
                entries += MemorySelectionEntry(record.memoryId, record.sourceRefs, record.canonicalStatement, estimate)
                tokens += estimate
            }
        }
        return MemorySelection(entries, excluded, tokens)
    }

    private fun estimateTokens(text: String): Int = 4 + (text.sumOf { if (it.code <= 0x7f) 1L else 4L } + 3L).div(4L).toInt()
}

@Serializable
sealed interface MemoryMutation {
    val memoryId: String

    @Serializable
    data class Disable(override val memoryId: String) : MemoryMutation

    @Serializable
    data class Delete(override val memoryId: String) : MemoryMutation

    @Serializable
    data class Confirm(override val memoryId: String, val at: Long) : MemoryMutation
}

object MemoryReducer {
    fun reduce(record: MemoryRecord, mutation: MemoryMutation): MemoryRecord {
        require(record.memoryId == mutation.memoryId)
        return when (mutation) {
            is MemoryMutation.Disable -> record.copy(status = MemoryStatus.DISABLED, revision = record.revision + 1)
            is MemoryMutation.Delete -> record.copy(status = MemoryStatus.DELETED, revision = record.revision + 1)
            is MemoryMutation.Confirm -> record.copy(
                status = MemoryStatus.ACTIVE,
                confirmedAt = mutation.at,
                confidence = 1.0,
                revision = record.revision + 1,
            )
        }
    }
}

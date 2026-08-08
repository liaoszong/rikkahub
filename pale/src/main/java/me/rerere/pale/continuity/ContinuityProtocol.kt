package me.rerere.pale.continuity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState

@Serializable
enum class CheckpointKind {
    BEFORE_DISPATCH,
    RESPONSE_STARTED,
    RESULT_RECEIVED,
    DURABLE_RESULT_COMMITTED,
    PROVIDER_PAUSED,
    CONTEXT_RECOMPILED,
    TERMINAL,
}

@Serializable
data class ContinuityCheckpoint(
    val checkpointId: String,
    val requestId: String,
    val attemptId: String?,
    val kind: CheckpointKind,
    val requestState: RequestState,
    val billableBoundary: BillableBoundary,
    val committedOutputRefs: List<String>,
    val contextManifestRef: String?,
    val replayEnvelopeRef: String?,
    val pendingApprovalRefs: List<String>,
    val createdAt: Long,
    val stateRevision: Long,
) {
    init {
        require(checkpointId.isNotBlank())
        require(requestId.isNotBlank())
        require(stateRevision >= 0)
        if (kind == CheckpointKind.PROVIDER_PAUSED) require(!replayEnvelopeRef.isNullOrBlank())
    }
}

@Serializable
enum class ResumeAction {
    CONTINUE_LOCAL_COMMIT,
    CONTINUE_PROVIDER_WITH_REPLAY,
    RECOMPILE_AND_SYNTHESIZE_FROM_COMMITTED_INPUTS,
    WAIT_FOR_USER_OR_PERMISSION,
    RETRY_SAFE_NOT_SENT,
    REQUIRE_DUPLICATE_COST_CONFIRMATION,
    CANNOT_RESUME_EXPLICIT_FAILURE,
}

@Serializable
data class ResumeInput(
    val requestState: RequestState,
    val billableBoundary: BillableBoundary,
    val hasCommittedInputs: Boolean,
    val hasUncommittedDurableOutputs: Boolean,
    val hasProviderReplay: Boolean,
    val waitingForUserOrPermission: Boolean,
    val providerGuaranteesIdempotency: Boolean = false,
)

object ResumePlanner {
    fun plan(input: ResumeInput): ResumeAction = when {
        input.waitingForUserOrPermission || input.requestState == RequestState.WAITING_USER ->
            ResumeAction.WAIT_FOR_USER_OR_PERMISSION

        input.hasUncommittedDurableOutputs -> ResumeAction.CONTINUE_LOCAL_COMMIT
        input.hasProviderReplay -> ResumeAction.CONTINUE_PROVIDER_WITH_REPLAY
        input.hasCommittedInputs && input.billableBoundary >= BillableBoundary.RESULT_RECEIVED ->
            ResumeAction.RECOMPILE_AND_SYNTHESIZE_FROM_COMMITTED_INPUTS

        input.requestState == RequestState.FAILED || input.requestState == RequestState.CANCELLED ->
            ResumeAction.CANNOT_RESUME_EXPLICIT_FAILURE

        input.billableBoundary == BillableBoundary.NOT_SENT -> ResumeAction.RETRY_SAFE_NOT_SENT
        input.providerGuaranteesIdempotency -> ResumeAction.RETRY_SAFE_NOT_SENT
        else -> ResumeAction.REQUIRE_DUPLICATE_COST_CONFIRMATION
    }
}

@Serializable
data class ProviderReplayEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val provider: String,
    val apiSurface: String,
    val blocks: List<ProviderReplayBlock>,
    val envelopeDigest: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unknown replay schema must fail closed" }
        require(provider.isNotBlank())
        require(apiSurface.isNotBlank())
        require(envelopeDigest.isNotBlank())
    }

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

@Serializable
data class ProviderReplayBlock(
    val ordinal: Int,
    val messageBoundary: Int,
    val type: String,
    /** Complete provider JSON, including unknown and encrypted fields. */
    val opaquePayloadJson: String,
    val payloadDigest: String,
    val toolPairingRef: String? = null,
) {
    init {
        require(ordinal >= 0)
        require(messageBoundary >= 0)
        require(type.isNotBlank())
        require(opaquePayloadJson.isNotBlank())
        require(payloadDigest.isNotBlank())
    }
}

@Serializable
data class HandoffCapsule(
    val capsuleId: String,
    val sourceConversationId: String,
    val target: String,
    val decisionSourceRefs: List<String>,
    val constraintSourceRefs: List<String>,
    val evidenceRefs: List<String>,
    val memoryIds: List<String>,
    val openQuestions: List<String>,
    val createdAt: Long,
) {
    init {
        require(capsuleId.isNotBlank())
        require(sourceConversationId.isNotBlank())
        require(target.isNotBlank())
    }
}

@Serializable
data class HandoffCapsuleDraft(
    val sourceConversationId: String,
    val target: String,
    val decisions: List<HandoffItem> = emptyList(),
    val constraints: List<HandoffItem> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
    val memoryIds: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
)

@Serializable
data class HandoffItem(val sourceRef: String, val selected: Boolean = true)

object HandoffCapsuleCompiler {
    fun compile(draft: HandoffCapsuleDraft, nowMillis: Long): HandoffCapsule {
        require(draft.sourceConversationId.isNotBlank())
        require(draft.target.isNotBlank())
        val decisions = draft.decisions.filter(HandoffItem::selected).map(HandoffItem::sourceRef).distinct()
        val constraints = draft.constraints.filter(HandoffItem::selected).map(HandoffItem::sourceRef).distinct()
        val identity = listOf(
            draft.sourceConversationId,
            draft.target,
            decisions.joinToString(","),
            constraints.joinToString(","),
            draft.evidenceRefs.distinct().joinToString(","),
            draft.memoryIds.distinct().joinToString(","),
            draft.openQuestions.joinToString("\u0000"),
        ).joinToString("|")
        return HandoffCapsule(
            capsuleId = "handoff:" + sha256(identity).take(24),
            sourceConversationId = draft.sourceConversationId,
            target = draft.target,
            decisionSourceRefs = decisions,
            constraintSourceRefs = constraints,
            evidenceRefs = draft.evidenceRefs.filter(String::isNotBlank).distinct(),
            memoryIds = draft.memoryIds.filter(String::isNotBlank).distinct(),
            openQuestions = draft.openQuestions.filter(String::isNotBlank).distinct().take(20),
            createdAt = nowMillis,
        )
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

package me.rerere.rikkahub.fork.pale.request

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptState
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.RequestState
import me.rerere.pale.request.ToolApprovalState

data class ImageSlotRecoveryCandidate(
    val requestId: RequestId,
    val attemptId: RequestAttemptId,
    val expectedAssetId: String,
    val expectedSourceId: String,
    /** Null at RESPONSE_STARTED, where the resolver must compute the durable file digest. */
    val checkpointDigest: String?,
    val conversationId: String?,
    val messageId: String?,
)

/**
 * Resolves only the reserved asset/source pair and must hash the on-disk file itself. A database
 * row, URI, or filename without readable-file and SHA-256 verification is not durable evidence.
 */
fun interface DurableImageSlotResolver {
    suspend fun resolve(candidate: ImageSlotRecoveryCandidate): DurableImageSlotOutput?

    companion object {
        val NONE = DurableImageSlotResolver { null }
    }
}

data class ImageRequestReconcileReport(
    val inspected: Int,
    val committed: Int,
    val cancelled: Int,
    val unknown: Int,
    val interrupted: Int,
    val failed: Int,
    val failures: List<String>,
)

/**
 * Cold-start authority for paid image slots. It only repairs durable local evidence; it never calls
 * a provider. NOT_SENT is cancelled, SENT is unknown, a partial response is interrupted, and a
 * received result is committed only when its exact file digest can still be proved.
 */
class ImageRequestReconciler(
    private val repository: RequestLedgerRepository,
    private val durableOutputResolver: DurableImageSlotResolver = DurableImageSlotResolver.NONE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ownerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
    private val leaseDurationMillis: Long = 30_000L,
) {
    suspend fun reconcilePending(limit: Int = 500): ImageRequestReconcileReport {
        val candidates = repository.getRequestsByKindAndState(
            kinds = listOf(RequestKind.IMAGE_GENERATION),
            states = listOf(
                RequestState.CREATED,
                RequestState.QUEUED,
                RequestState.WAITING_RUNTIME,
                RequestState.DISPATCHING,
                RequestState.RUNNING,
                RequestState.COMMITTING,
            ),
            limit = limit,
        ).filter { it.leaseUntil == null || it.leaseUntil <= nowMillis() }
        var committed = 0
        var cancelled = 0
        var unknown = 0
        var interrupted = 0
        var failed = 0
        val failures = mutableListOf<String>()

        candidates.forEach { request ->
            runCatching {
                val attempt = request.activeAttemptId
                    ?.let(::RequestAttemptId)
                    ?.let { repository.getAttempt(it) ?: throw RequestLedgerMissing(it.value) }
                val boundary = attempt?.billableBoundary() ?: request.billableBoundary()
                when (boundary) {
                    BillableBoundary.NOT_SENT -> {
                        cancelUndispatched(request, attempt)
                        cancelled++
                    }

                    BillableBoundary.SENT -> {
                        val exactAttempt = attempt ?: throw RequestLedgerConflict(
                            "SENT image request is missing its active attempt",
                        )
                        finishOrphan(
                            requestId = RequestId(request.requestId),
                            attemptId = RequestAttemptId(exactAttempt.attemptId),
                            state = RequestAttemptState.UNKNOWN_OUTCOME,
                            boundary = BillableBoundary.UNKNOWN,
                        )
                        unknown++
                    }

                    BillableBoundary.RESPONSE_STARTED -> {
                        val exactAttempt = attempt ?: throw RequestLedgerConflict(
                            "Started image response is missing its active attempt",
                        )
                        if (repairStartedResponse(request, exactAttempt)) {
                            committed++
                        } else {
                            finishOrphan(
                                requestId = RequestId(request.requestId),
                                attemptId = RequestAttemptId(exactAttempt.attemptId),
                                state = RequestAttemptState.INTERRUPTED,
                                boundary = BillableBoundary.RESPONSE_STARTED,
                            )
                            interrupted++
                        }
                    }

                    BillableBoundary.RESULT_RECEIVED,
                    BillableBoundary.RESULT_COMMITTED,
                    -> {
                        val exactAttempt = attempt ?: throw RequestLedgerConflict(
                            "Committing image request is missing its active attempt",
                        )
                        if (repairDurableOutput(request, exactAttempt)) {
                            committed++
                        } else if (boundary == BillableBoundary.RESULT_RECEIVED) {
                            finishOrphan(
                                requestId = RequestId(request.requestId),
                                attemptId = RequestAttemptId(exactAttempt.attemptId),
                                state = RequestAttemptState.FAILED,
                                boundary = BillableBoundary.RESULT_RECEIVED,
                            )
                            failed++
                        } else {
                            throw RequestLedgerConflict(
                                "Committed image request ${request.requestId} has no matching durable file",
                            )
                        }
                    }

                    BillableBoundary.UNKNOWN -> Unit
                }
            }.onFailure { failure ->
                failures += "${request.requestId}:${failure.javaClass.simpleName}"
            }
        }
        return ImageRequestReconcileReport(
            inspected = candidates.size,
            committed = committed,
            cancelled = cancelled,
            unknown = unknown,
            interrupted = interrupted,
            failed = failed,
            failures = failures,
        )
    }

    private suspend fun cancelUndispatched(
        request: RequestLedgerEntity,
        activeAttempt: RequestAttemptEntity?,
    ) {
        val requestId = RequestId(request.requestId)
        val attemptId = activeAttempt?.let { RequestAttemptId(it.attemptId) }
            ?: ImageGenerationLedgerCoordinator.stableAttemptId(requestId)
        val session = RequestDispatchSession.open(
            repository = repository,
            request = request.toSpec(actor(requestId)),
            owner = owner(requestId),
            leaseDurationMillis = leaseDurationMillis,
            attemptId = attemptId,
            idempotencyKey = activeAttempt?.idempotencyKey ?: "pale-image-${attemptId.value}",
            requestFingerprint = activeAttempt?.requestFingerprint ?: request.inputDigest,
            actor = actor(requestId),
            transportKind = activeAttempt?.transportKind ?: request.apiSurface,
            foregroundTaskId = activeAttempt?.foregroundTaskId,
        )
        withContext(NonCancellable) { session.cancel() }
    }

    private suspend fun repairDurableOutput(
        request: RequestLedgerEntity,
        attempt: RequestAttemptEntity,
    ): Boolean {
        val requestId = RequestId(request.requestId)
        val attemptId = RequestAttemptId(attempt.attemptId)
        val checkpoint = attempt.checkpointDigest ?: return false
        if (!checkpoint.isSha256Hex()) return false
        val assetId = request.partId ?: return false
        val expectedSourceId = request.expectedSourceId() ?: return false
        val existing = repository.getOutputs(requestId).singleOrNull {
            it.outputKind == IMAGE_SLOT_OUTPUT_KIND_VALUE && it.ordinal == 0
        }
        if (existing != null && (existing.contentDigest != checkpoint || existing.assetId != assetId ||
                existing.sourceId != expectedSourceId)
        ) {
            return false
        }
        // An output row proves metadata commit, not that the authoritative file still exists.
        // Resolve and hash the file even when the output row is already present.
        val durable = durableOutputResolver.resolve(
            ImageSlotRecoveryCandidate(
                requestId = requestId,
                attemptId = attemptId,
                expectedAssetId = assetId,
                expectedSourceId = expectedSourceId,
                checkpointDigest = checkpoint,
                conversationId = request.conversationId,
                messageId = request.messageId,
            ),
        ) ?: return false
        if (durable.contentDigest != checkpoint || durable.assetId != assetId ||
            durable.sourceId != expectedSourceId
        ) {
            return false
        }
        commitRecoveredOutput(
            request = request,
            attemptId = attemptId,
            assetId = assetId,
            expectedSourceId = expectedSourceId,
            checkpoint = checkpoint,
            durable = durable,
        )
        return true
    }

    private suspend fun repairStartedResponse(
        request: RequestLedgerEntity,
        attempt: RequestAttemptEntity,
    ): Boolean {
        val requestId = RequestId(request.requestId)
        val attemptId = RequestAttemptId(attempt.attemptId)
        val assetId = request.partId ?: return false
        val expectedSourceId = request.expectedSourceId() ?: return false
        val durable = durableOutputResolver.resolve(
            ImageSlotRecoveryCandidate(
                requestId = requestId,
                attemptId = attemptId,
                expectedAssetId = assetId,
                expectedSourceId = expectedSourceId,
                checkpointDigest = null,
                conversationId = request.conversationId,
                messageId = request.messageId,
            ),
        ) ?: return false
        if (durable.assetId != assetId || durable.sourceId != expectedSourceId ||
            !durable.contentDigest.isSha256Hex()
        ) {
            return false
        }
        commitRecoveredOutput(
            request = request,
            attemptId = attemptId,
            assetId = assetId,
            expectedSourceId = expectedSourceId,
            checkpoint = durable.contentDigest,
            durable = durable,
        )
        return true
    }

    private suspend fun commitRecoveredOutput(
        request: RequestLedgerEntity,
        attemptId: RequestAttemptId,
        assetId: String,
        expectedSourceId: String,
        checkpoint: String,
        durable: DurableImageSlotOutput?,
    ) {
        val requestId = RequestId(request.requestId)
        val lease = repository.claimRequest(requestId, owner(requestId), leaseDurationMillis)
        try {
            val currentAttempt = repository.getAttempt(attemptId)
                ?: throw RequestLedgerMissing(attemptId.value)
            if (currentAttempt.billableBoundary() == BillableBoundary.RESPONSE_STARTED) {
                repository.advanceAttempt(
                    AdvanceAttemptCommand(
                        lease = lease,
                        attemptId = attemptId,
                        nextState = RequestAttemptState.COMMITTING,
                        nextBoundary = BillableBoundary.RESULT_RECEIVED,
                        actor = actor(requestId),
                        checkpointDigest = checkpoint,
                    ),
                )
            }
            repository.commitOutput(
                CommitRequestOutputCommand(
                    lease = lease,
                    attemptId = attemptId,
                    outputId = ImageGenerationLedgerCoordinator.stableOutputId(requestId),
                    outputKind = IMAGE_SLOT_OUTPUT_KIND_VALUE,
                    ordinal = 0,
                    contentDigest = durable?.contentDigest ?: checkpoint,
                    actor = actor(requestId),
                    conversationId = request.conversationId,
                    messageId = request.messageId,
                    partId = assetId,
                    assetId = durable?.assetId ?: assetId,
                    sourceId = durable?.sourceId ?: expectedSourceId,
                ),
            )
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = RequestAttemptState.SUCCEEDED,
                    nextBoundary = BillableBoundary.RESULT_COMMITTED,
                    actor = actor(requestId),
                    checkpointDigest = checkpoint,
                ),
            )
        } finally {
            runCatching { withContext(NonCancellable) { repository.releaseRequest(lease) } }
        }
    }

    private suspend fun finishOrphan(
        requestId: RequestId,
        attemptId: RequestAttemptId,
        state: RequestAttemptState,
        boundary: BillableBoundary,
    ) {
        val lease = repository.claimRequest(requestId, owner(requestId), leaseDurationMillis)
        try {
            repository.advanceAttempt(
                AdvanceAttemptCommand(
                    lease = lease,
                    attemptId = attemptId,
                    nextState = state,
                    nextBoundary = boundary,
                    actor = actor(requestId),
                ),
            )
        } finally {
            runCatching { withContext(NonCancellable) { repository.releaseRequest(lease) } }
        }
    }

    private fun owner(requestId: RequestId) = "image-reconcile:$ownerId:${requestId.value}"

    private fun actor(requestId: RequestId) = AuditActor.system(owner(requestId))

    private fun RequestLedgerEntity.toSpec(actor: AuditActor) = NewRequestSpec(
        requestId = RequestId(requestId),
        intentKey = intentKey,
        kind = RequestKind.IMAGE_GENERATION,
        inputDigest = inputDigest,
        capabilitySnapshotJson = capabilitySnapshotJson,
        resolverVersion = resolverVersion,
        actor = actor,
        parentRequestId = parentRequestId?.let(::RequestId),
        conversationId = conversationId,
        assistantId = assistantId,
        messageId = messageId,
        partId = partId,
        workspaceId = workspaceId,
        mcpServerId = mcpServerId,
        credentialRefId = credentialRefId,
        providerKind = providerKind,
        providerId = providerId,
        modelId = modelId,
        apiSurface = apiSurface,
        toolCatalogDigest = toolCatalogDigest,
        approvalState = ToolApprovalState.valueOf(approvalState.uppercase(Locale.ROOT)),
    )

    private fun RequestLedgerEntity.expectedSourceId(): String? {
        val prefix = "image-slot:v1:${parentRequestId ?: return null}:"
        if (!intentKey.startsWith(prefix)) return null
        return intentKey.removePrefix(prefix).substringBeforeLast(':').takeIf(String::isNotBlank)
    }
}

private const val IMAGE_SLOT_OUTPUT_KIND_VALUE = "image_generation_slot"

private fun RequestLedgerEntity.billableBoundary() =
    BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

private fun RequestAttemptEntity.billableBoundary() =
    BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

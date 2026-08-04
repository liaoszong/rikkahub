package me.rerere.rikkahub.fork.pale.request

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ProviderDispatchObserver
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.RequestState

private const val IMAGE_SLOT_OUTPUT_KIND = "image_generation_slot"
private const val IMAGE_LEDGER_RESOLVER_VERSION = 1
private const val DEFAULT_IMAGE_LEASE_MILLIS = 90_000L

/** Frozen input shared by the independently billed slots of one chat image task. */
data class ImageGenerationRequestDescriptor(
    val parentRequestId: RequestId,
    val taskId: String,
    val toolCallId: String,
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val providerId: String?,
    val providerKind: String?,
    val size: String,
    val referenceImageDigests: List<String>,
    val referenceAssetIds: List<String> = emptyList(),
    val referenceSourcePaths: List<String> = emptyList(),
    val parentAssetId: String? = null,
    val capabilitySnapshotJson: String,
    /** SHA-256 of provider route, credential values, custom headers, and custom body. */
    val transportConfigurationDigest: String,
    val requestedImageCount: Int,
    val reservedOutputAssetIds: List<String>,
    val credentialRefId: String? = null,
    val apiSurface: String = "image_generations",
) {
    init {
        require(taskId.isNotBlank())
        require(toolCallId.isNotBlank())
        require(prompt.isNotBlank())
        require(modelId.isNotBlank())
        require(modelName.isNotBlank())
        require(size.isNotBlank())
        require(capabilitySnapshotJson.isNotBlank())
        require(transportConfigurationDigest.isSha256Hex())
        require(requestedImageCount > 0)
        require(reservedOutputAssetIds.size == requestedImageCount) {
            "Every paid image slot must reserve exactly one stable MediaAsset identity"
        }
        require(reservedOutputAssetIds.none(String::isBlank))
        require(reservedOutputAssetIds.distinct().size == reservedOutputAssetIds.size)
        require(referenceImageDigests.none(String::isBlank))
        require(referenceAssetIds.none(String::isBlank))
        require(referenceSourcePaths.none(String::isBlank))
    }
}

data class ImageGenerationSlotPlan(
    val requestId: RequestId,
    val attemptId: RequestAttemptId,
    val outputId: RequestOutputId,
    val providerRequestId: String,
    val slotOrdinal: Int,
    val assetId: String,
    val inputDigest: String,
    internal val requestSpec: NewRequestSpec,
    internal val taskId: String,
)

sealed interface ImageGenerationSlotOpenResult {
    data class Dispatch(val session: ImageGenerationLedgerSession) : ImageGenerationSlotOpenResult
    data class AlreadySucceeded(val output: RequestOutputEntity) : ImageGenerationSlotOpenResult
}

data class ImageGenerationSlotLedgerStatus(
    val state: RequestState,
    val boundary: BillableBoundary,
)

class ImageGenerationSlotBlocked(message: String) : IllegalStateException(message)

/**
 * Reserves one durable child request per paid image slot before the foreground runtime is claimed.
 * A slot is never automatically re-dispatched after the provider transport boundary is crossed.
 */
class ImageGenerationLedgerCoordinator(
    private val repository: RequestLedgerRepository,
    private val leaseDurationMillis: Long = DEFAULT_IMAGE_LEASE_MILLIS,
    private val processOwnerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
) {
    suspend fun prepareSlots(descriptor: ImageGenerationRequestDescriptor): List<ImageGenerationSlotPlan> {
        val parent = repository.getRequest(descriptor.parentRequestId)
            ?: throw RequestLedgerMissing(descriptor.parentRequestId.value)
        require(parent.requestKind in setOf("tool_call", "mcp_tool_call", "workspace_tool")) {
            "Image slot parent must be the exact chat tool request"
        }
        val actor = actor()
        val plans = descriptor.reservedOutputAssetIds.mapIndexed { ordinal, assetId ->
            val inputDigest = digestSlot(descriptor, ordinal, assetId)
            val requestId = stableRequestId(descriptor.parentRequestId, descriptor.taskId, ordinal)
            val attemptId = stableAttemptId(requestId)
            val plan = ImageGenerationSlotPlan(
                requestId = requestId,
                attemptId = attemptId,
                outputId = stableOutputId(requestId),
                providerRequestId = "pale-image-${attemptId.value}",
                slotOrdinal = ordinal,
                assetId = assetId,
                inputDigest = inputDigest,
                taskId = descriptor.taskId,
                requestSpec = NewRequestSpec(
                    requestId = requestId,
                    intentKey = "image-slot:v1:${descriptor.parentRequestId.value}:${descriptor.taskId}:$ordinal",
                    kind = RequestKind.IMAGE_GENERATION,
                    inputDigest = inputDigest,
                    capabilitySnapshotJson = descriptor.capabilitySnapshotJson,
                    resolverVersion = IMAGE_LEDGER_RESOLVER_VERSION,
                    actor = actor,
                    parentRequestId = descriptor.parentRequestId,
                    conversationId = parent.conversationId,
                    assistantId = parent.assistantId,
                    messageId = parent.messageId,
                    partId = assetId,
                    workspaceId = parent.workspaceId,
                    // Image generation may use a different provider (or a local no-auth one) than
                    // the parent chat request. Never inherit the parent's credential evidence.
                    credentialRefId = descriptor.credentialRefId,
                    providerKind = descriptor.providerKind,
                    providerId = descriptor.providerId,
                    modelId = descriptor.modelId,
                    apiSurface = descriptor.apiSurface,
                ),
            )
            // Reservation is intentionally lease-free: FGS readiness remains outside the paid fence.
            repository.createRequest(plan.requestSpec)
            plan
        }
        // This privacy-minimal descriptor contains no prompt or credential values. It exists so
        // lineage and display metadata survive loss of the bounded SharedPreferences task cache.
        repository.recordImageTaskDescriptor(
            parentRequestId = descriptor.parentRequestId,
            actor = actor,
            payload = buildJsonObject {
                put("task_id", descriptor.taskId)
                put("tool_call_id", descriptor.toolCallId)
                put("model_name", descriptor.modelName)
                put("size", descriptor.size)
                put("reference_image_count", descriptor.referenceImageDigests.size)
                put("media_origin", if (descriptor.referenceImageDigests.isEmpty()) "ai_generated" else "ai_edited")
                descriptor.parentAssetId?.let { put("parent_asset_id", it) }
                put("reference_asset_ids", buildJsonArray {
                    descriptor.referenceAssetIds.forEach { add(JsonPrimitive(it)) }
                })
                put("reference_source_paths", buildJsonArray {
                    descriptor.referenceSourcePaths.forEach { add(JsonPrimitive(it)) }
                })
                put("requested_image_count", descriptor.requestedImageCount)
            },
        )
        return plans
    }

    suspend fun openSlot(plan: ImageGenerationSlotPlan): ImageGenerationSlotOpenResult {
        // Re-applying the full spec rejects a plan whose stable ID was rebound to different input.
        val request = repository.createRequest(plan.requestSpec)
        val state = request.requestState()
        val boundary = request.billableBoundary()
        if (state == RequestState.SUCCEEDED) {
            check(boundary == BillableBoundary.RESULT_COMMITTED)
            val output = canonicalOutput(plan.requestId)
                ?: throw RequestLedgerConflict("Succeeded image slot is missing its canonical output")
            requireOutputIdentity(output, plan)
            return ImageGenerationSlotOpenResult.AlreadySucceeded(output)
        }
        if (boundary != BillableBoundary.NOT_SENT || state !in SAFE_OPEN_STATES) {
            throw ImageGenerationSlotBlocked(
                "Image slot ${plan.requestId.value} is $state/$boundary; automatic redispatch is forbidden",
            )
        }
        val activeAttempt = request.activeAttemptId?.let { repository.getAttempt(RequestAttemptId(it)) }
        if (activeAttempt != null && activeAttempt.attemptId != plan.attemptId.value) {
            throw RequestLedgerIdentityConflict("Image slot has a different active attempt identity")
        }
        val session = RequestDispatchSession.open(
            repository = repository,
            request = plan.requestSpec,
            owner = owner(plan.requestId),
            leaseDurationMillis = leaseDurationMillis,
            attemptId = plan.attemptId,
            idempotencyKey = plan.providerRequestId,
            requestFingerprint = plan.inputDigest,
            actor = actor(),
            transportKind = plan.requestSpec.apiSurface,
            foregroundTaskId = plan.taskId,
        )
        return ImageGenerationSlotOpenResult.Dispatch(
            ImageGenerationLedgerSession(repository, plan, session, actor()),
        )
    }

    /**
     * Settles a slot that was reserved but cancelled while waiting for the local concurrency gate.
     * Once a transport boundary exists this method only reports the durable state; it never reopens
     * or mutates a possibly billed request.
     */
    suspend fun cancelBeforeDispatch(plan: ImageGenerationSlotPlan): RequestState {
        val request = repository.getRequest(plan.requestId)
            ?: throw RequestLedgerMissing(plan.requestId.value)
        val state = request.requestState()
        if (state.isTerminal) return state
        if (request.billableBoundary() != BillableBoundary.NOT_SENT) return state
        return when (val opened = openSlot(plan)) {
            is ImageGenerationSlotOpenResult.AlreadySucceeded -> RequestState.SUCCEEDED
            is ImageGenerationSlotOpenResult.Dispatch -> opened.session.finishCancellation()
        }
    }

    suspend fun inspectSlot(plan: ImageGenerationSlotPlan): ImageGenerationSlotLedgerStatus {
        val request = repository.getRequest(plan.requestId)
            ?: throw RequestLedgerMissing(plan.requestId.value)
        return ImageGenerationSlotLedgerStatus(request.requestState(), request.billableBoundary())
    }

    internal suspend fun canonicalOutput(requestId: RequestId): RequestOutputEntity? =
        repository.getOutputs(requestId).singleOrNull {
            it.outputKind == IMAGE_SLOT_OUTPUT_KIND && it.ordinal == 0
        }

    internal fun actor() = AuditActor.system("image:$processOwnerId")

    private fun owner(requestId: RequestId) = "image:$processOwnerId:${requestId.value}"

    private fun requireOutputIdentity(output: RequestOutputEntity, plan: ImageGenerationSlotPlan) {
        check(output.outputId == plan.outputId.value)
        check(output.attemptId == plan.attemptId.value)
        check(output.assetId == plan.assetId)
        check(output.sourceId == plan.taskId)
    }

    private fun digestSlot(
        descriptor: ImageGenerationRequestDescriptor,
        ordinal: Int,
        assetId: String,
    ): String = sha256(
        buildString {
            appendField(descriptor.parentRequestId.value)
            appendField(descriptor.taskId)
            appendField(descriptor.toolCallId)
            appendField(descriptor.prompt)
            appendField(descriptor.modelId)
            appendField(descriptor.modelName)
            appendField(descriptor.providerId.orEmpty())
            appendField(descriptor.providerKind.orEmpty())
            appendField(descriptor.size)
            appendField(descriptor.apiSurface)
            appendField(descriptor.capabilitySnapshotJson)
            appendField(descriptor.transportConfigurationDigest)
            appendField(descriptor.credentialRefId.orEmpty())
            appendField(descriptor.parentAssetId.orEmpty())
            appendField(ordinal.toString())
            appendField(assetId)
            descriptor.referenceImageDigests.forEach { appendField(it) }
            descriptor.referenceAssetIds.forEach { appendField(it) }
            descriptor.referenceSourcePaths.forEach { appendField(it) }
        },
    )

    companion object {
        internal fun stableRequestId(parentRequestId: RequestId, taskId: String, ordinal: Int) = RequestId(
            stableUuid("image-slot:v1:${parentRequestId.value}:$taskId:$ordinal"),
        )

        internal fun stableAttemptId(requestId: RequestId) = RequestAttemptId(
            stableUuid("image-attempt:v1:${requestId.value}:1"),
        )

        internal fun stableOutputId(requestId: RequestId) = RequestOutputId(
            stableUuid("image-output:v1:${requestId.value}:0"),
        )

        private val SAFE_OPEN_STATES = setOf(
            RequestState.CREATED,
            RequestState.QUEUED,
            RequestState.WAITING_RUNTIME,
            RequestState.DISPATCHING,
        )
    }
}

data class DurableImageSlotOutput(
    val contentDigest: String,
    val assetId: String,
    val sourceId: String,
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
) {
    init {
        require(contentDigest.isSha256Hex()) { "contentDigest must be a SHA-256 hex digest" }
        require(assetId.isNotBlank())
        require(sourceId.isNotBlank())
        require(relativePath.isNotBlank())
        require(mimeType.startsWith("image/"))
        require(byteSize > 0L)
    }
}

class ImageGenerationLedgerSession internal constructor(
    private val repository: RequestLedgerRepository,
    val plan: ImageGenerationSlotPlan,
    private val dispatch: RequestDispatchSession,
    private val actor: AuditActor,
) {
    val requestId: RequestId get() = plan.requestId
    val attemptId: RequestAttemptId get() = plan.attemptId
    val outputId: RequestOutputId get() = plan.outputId
    val providerRequestId: String get() = plan.providerRequestId
    val credentialRefId: String? get() = plan.requestSpec.credentialRefId
    val slotOrdinal: Int get() = plan.slotOrdinal
    val dispatchObserver: ProviderDispatchObserver get() = dispatch.dispatchObserver

    suspend fun prepareDispatch() = dispatch.prepareDispatch()

    suspend fun markResponseStarted() = dispatch.markResponseStarted()

    /** Records the exact fsync/rename result before the fallible MediaAsset registration step. */
    suspend fun markDurableFileReceived(contentDigest: String) = withContext(NonCancellable) {
        require(contentDigest.isSha256Hex()) { "contentDigest must be a SHA-256 hex digest" }
        dispatch.markResultReceived(contentDigest)
    }

    suspend fun <T> withLeaseHeartbeat(block: suspend () -> T): T = dispatch.withLeaseHeartbeat(block = block)

    /** The caller must only invoke this after the final file and MediaAsset row are durable. */
    suspend fun commitDurableOutput(output: DurableImageSlotOutput): RequestOutputEntity =
        withContext(NonCancellable) {
            check(output.assetId == plan.assetId) { "Durable image belongs to a different reserved slot" }
            check(output.sourceId == plan.taskId) { "Durable image belongs to a different task" }
            var lastFailure: Throwable? = null
            repeat(3) { retry ->
                try {
                    return@withContext dispatch.commitOutputAndSucceed(
                        CommitRequestOutputCommand(
                            lease = dispatch.lease,
                            attemptId = attemptId,
                            outputId = outputId,
                            outputKind = IMAGE_SLOT_OUTPUT_KIND,
                            ordinal = 0,
                            contentDigest = output.contentDigest,
                            actor = actor,
                            conversationId = plan.requestSpec.conversationId,
                            messageId = plan.requestSpec.messageId,
                            partId = plan.assetId,
                            assetId = output.assetId,
                            sourceId = output.sourceId,
                        ),
                    )
                } catch (failure: Throwable) {
                    lastFailure = failure
                    val terminal = repository.getRequest(requestId)?.requestState()
                    if (terminal == RequestState.SUCCEEDED) {
                        return@withContext checkNotNull(
                            repository.getOutputs(requestId).singleOrNull {
                                it.outputKind == IMAGE_SLOT_OUTPUT_KIND && it.ordinal == 0
                            },
                        ) { "Succeeded image slot lost its canonical output" }
                    }
                    if (retry < 2) delay(100L * (retry + 1))
                }
            }
            throw checkNotNull(lastFailure)
        }

    suspend fun finishFailure(responseProvedFailure: Boolean = false): RequestState = withContext(NonCancellable) {
        if (responseProvedFailure) dispatch.markKnownFailure() else dispatch.finishTransportFailure(cancelled = false)
        requireTerminalState()
    }

    suspend fun finishCancellation(): RequestState = withContext(NonCancellable) {
        dispatch.finishTransportFailure(cancelled = true)
        requireTerminalState()
    }

    suspend fun terminalState(): RequestState? = repository.getRequest(requestId)
        ?.requestState()
        ?.takeIf { it.isTerminal }

    suspend fun releaseForLocalRepair(failure: Throwable) = withContext(NonCancellable) {
        runCatching { dispatch.releaseLease() }.exceptionOrNull()?.let(failure::addSuppressed)
    }

    private suspend fun requireTerminalState(): RequestState = checkNotNull(terminalState()) {
        "Image slot did not reach a terminal ledger state"
    }
}

private fun stableUuid(identity: String): String = UUID.nameUUIDFromBytes(
    "pale.6:$identity".toByteArray(Charsets.UTF_8),
).toString().lowercase(Locale.ROOT)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun StringBuilder.appendField(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(';')
}

private fun RequestLedgerEntity.requestState() = RequestState.valueOf(requestState.uppercase(Locale.ROOT))

private fun RequestLedgerEntity.billableBoundary() =
    BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

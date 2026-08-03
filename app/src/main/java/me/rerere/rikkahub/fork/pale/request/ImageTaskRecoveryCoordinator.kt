package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.core.net.toUri
import java.util.Locale
import kotlin.uuid.Uuid
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.RequestState
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskPhase
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import me.rerere.rikkahub.data.imggen.findChatImageGenerationState
import me.rerere.rikkahub.data.imggen.findCommittedGeneratedImage
import me.rerere.rikkahub.data.imggen.toStatusPart
import me.rerere.rikkahub.data.repository.ConversationRepository

data class ImageTaskRecoveryReport(
    val inspected: Int,
    val projected: Int,
    val conversationResultsRepaired: Int,
    val pending: Int,
    val failures: List<String>,
)

fun interface DurableImageToolResultWriter {
    suspend fun write(
        task: ChatImageGenerationTaskRecord,
        state: ChatImageGenerationState,
        images: List<UIMessagePart.Image>,
        parentState: RequestState?,
    ): Boolean
}

fun interface DurableImageTaskSource {
    suspend fun load(
        parent: RequestLedgerEntity,
        invocation: ToolInvocationEntity,
        children: List<RequestLedgerEntity>,
    ): ChatImageGenerationTaskRecord?
}

class ConversationImageToolResultWriter(
    private val context: Context,
    private val conversationRepository: ConversationRepository,
    private val requestRepository: RequestLedgerRepository,
) : DurableImageToolResultWriter, DurableImageTaskSource {
    override suspend fun write(
        task: ChatImageGenerationTaskRecord,
        state: ChatImageGenerationState,
        images: List<UIMessagePart.Image>,
        parentState: RequestState?,
    ): Boolean {
        val conversationId = runCatching { Uuid.parse(task.conversationId) }.getOrNull() ?: return false
        return conversationRepository.updateToolResult(
            conversationId = conversationId,
            requestId = task.requestId,
            toolCallId = task.toolCallId,
        ) { current ->
            val previousState = (current.output + current.progress).findChatImageGenerationState()
            val recoveredState = state.copy(
                size = previousState?.size ?: state.size,
                referenceImageCount = previousState?.referenceImageCount ?: state.referenceImageCount,
            )
            val executionState = parentState.toRecoveredToolExecutionState()
            val previousImages = current.output.filterIsInstance<UIMessagePart.Image>()
            if (previousState?.sameDurableProjection(recoveredState) == true &&
                previousImages == images && current.progress.isEmpty() &&
                current.executionState == executionState
            ) {
                return@updateToolResult current
            }
            current.copy(
                output = buildList {
                    add(recoveredState.toStatusPart())
                    addAll(images)
                },
                progress = emptyList(),
                // generate_image is a local aggregate tool. A valid per-slot terminal payload is
                // a successful tool result even when individual provider slots failed or are
                // unknown; those distinctions remain inside ChatImageGenerationState.
                executionState = executionState,
            )
        }
    }


    override suspend fun load(
        parent: RequestLedgerEntity,
        invocation: ToolInvocationEntity,
        children: List<RequestLedgerEntity>,
    ): ChatImageGenerationTaskRecord? {
        val conversationId = parent.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return null
        val conversation = conversationRepository.getConversationById(conversationId)
            ?: return settleMissingProjection(parent, invocation, children, "conversation")
        val tool = conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .singleOrNull { part ->
                part.requestId == parent.requestId &&
                    part.toolCallId == invocation.providerToolCallId &&
                    part.toolName == "generate_image"
            } ?: return settleMissingProjection(parent, invocation, children, "tool")
        val existingState = (tool.output + tool.progress).findChatImageGenerationState()
        if (parent.requestState().isTerminal && tool.executionState in setOf(
                ToolExecutionState.SUCCEEDED,
                ToolExecutionState.FAILED,
                ToolExecutionState.INTERRUPTED,
            ) && existingState != null && hasExactImageLedgerProjection(
                context = context,
                repository = requestRepository,
                parent = parent,
                children = children,
                tool = tool,
                state = existingState,
            )
        ) {
            // Historical completed image parents are intentionally not rehydrated into the
            // bounded task cache on every startup. Terminal parents with a stale RUNNING tool
            // still fall through and are repaired below.
            return null
        }
        val descriptor = requestRepository.getImageTaskDescriptor(RequestId(parent.requestId))
            ?.let(::decodeImageTaskDescriptor)
        return reconstructImageTask(parent, invocation, children, tool, conversationId, descriptor)
    }

    private suspend fun settleMissingProjection(
        parent: RequestLedgerEntity,
        invocation: ToolInvocationEntity,
        children: List<RequestLedgerEntity>,
        missingKind: String,
    ): ChatImageGenerationTaskRecord? {
        if (parent.requestState().isTerminal) return null
        if (children.isNotEmpty() && children.all { it.requestState().isTerminal }) {
            requestRepository.settleOrphanedImageParent(parent, invocation, children)
            return null
        }
        throw RequestLedgerConflict("Active image parent $missingKind projection is missing")
    }
}

internal suspend fun hasExactImageLedgerProjection(
    context: Context,
    repository: RequestLedgerRepository,
    parent: RequestLedgerEntity,
    children: List<RequestLedgerEntity>,
    tool: UIMessagePart.Tool,
    state: ChatImageGenerationState,
): Boolean {
    if (!state.isTerminal || state.requestId != parent.requestId || state.slots.size != children.size) return false
    val orderedChildren = runCatching { children.sortedBy(RequestLedgerEntity::imageSlotOrdinal) }
        .getOrNull() ?: return false
    val toolImages = tool.output.filterIsInstance<UIMessagePart.Image>()
    if (toolImages.any { it.assetId.isNullOrBlank() }) return false
    val imagesByAssetId = toolImages.associateBy { checkNotNull(it.assetId) }
    if (imagesByAssetId.size != toolImages.size) return false
    val successfulAssetIds = orderedChildren.mapNotNull { child ->
        child.partId?.takeIf { child.requestState() == RequestState.SUCCEEDED }
    }.toSet()
    if (imagesByAssetId.keys != successfulAssetIds) return false
    return orderedChildren.indices.all { index ->
        val child = orderedChildren[index]
        val childState = child.requestState()
        if (!childState.isTerminal) return@all false
        val slot = state.slots.getOrNull(index) ?: return@all false
        if (slot.index != index || slot.requestId != child.requestId ||
            slot.status != childState.toSlotStatus()
        ) {
            return@all false
        }
        if (childState != RequestState.SUCCEEDED) return@all slot.imageUrl == null
        val output = repository.getOutputs(RequestId(child.requestId)).singleOrNull {
            it.outputKind == "image_generation_slot" && it.ordinal == 0
        } ?: return@all false
        val assetId = child.partId ?: return@all false
        if (output.assetId != assetId || output.sourceId != parent.requestId) return@all false
        val committed = findCommittedGeneratedImage(context, assetId) ?: return@all false
        val expectedUrl = committed.file.toUri().toString()
        committed.sha256 == output.contentDigest && slot.imageUrl == expectedUrl &&
            imagesByAssetId[assetId]?.url == expectedUrl
    }
}

internal fun reconstructImageTask(
    parent: RequestLedgerEntity,
    invocation: ToolInvocationEntity,
    children: List<RequestLedgerEntity>,
    tool: UIMessagePart.Tool,
    conversationId: Uuid,
    descriptor: DurableImageTaskDescriptor? = null,
): ChatImageGenerationTaskRecord? {
        val state = (tool.output + tool.progress).findChatImageGenerationState()
        val input = runCatching { tool.inputAsJson().jsonObject }.getOrNull() ?: return null
        val orderedChildren = children.sortedBy(RequestLedgerEntity::imageSlotOrdinal)
        val reservedAssetIds = orderedChildren.map { child -> child.partId ?: return null }
        if (reservedAssetIds.isEmpty()) return null
        if (descriptor != null) {
            require(descriptor.taskId == parent.requestId)
            require(descriptor.toolCallId == invocation.providerToolCallId)
            require(descriptor.requestedImageCount == reservedAssetIds.size)
        }
        val prompt = state?.prompt?.takeIf(String::isNotBlank)
            ?: input["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isBlank()) return null
        val firstChild = orderedChildren.first()
        val explicitReferenceCount = runCatching {
            input["reference_image_ids"]?.jsonArray?.size ?: 0
        }.getOrDefault(0)
        val referenceImageCount = state?.referenceImageCount ?: descriptor?.referenceImageCount ?: when {
            explicitReferenceCount > 0 -> explicitReferenceCount
            firstChild.apiSurface == "image_edits" -> 1
            else -> 0
        }
        val requestedSize = input["size"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: "auto"
        return ChatImageGenerationTaskRecord(
            taskId = parent.requestId,
            conversationId = conversationId.toString(),
            toolCallId = invocation.providerToolCallId,
            requestId = parent.requestId,
            attempt = 1,
            modelId = firstChild.modelId.orEmpty(),
            modelName = state?.model?.takeIf(String::isNotBlank)
                ?: descriptor?.modelName
                ?: firstChild.modelId.orEmpty(),
            providerId = firstChild.providerId,
            size = state?.size?.takeIf(String::isNotBlank) ?: descriptor?.size ?: requestedSize,
            referenceImageCount = referenceImageCount,
            prompt = prompt,
            mediaOrigin = descriptor?.mediaOrigin
                ?: if (referenceImageCount > 0) "ai_edited" else "ai_generated",
            parentAssetId = descriptor?.parentAssetId,
            referenceAssetIds = descriptor?.referenceAssetIds.orEmpty(),
            referenceSourcePaths = descriptor?.referenceSourcePaths.orEmpty(),
            requestedImageCount = reservedAssetIds.size,
            reservedOutputAssetIds = reservedAssetIds,
            outputAssetIds = tool.output.filterIsInstance<UIMessagePart.Image>()
                .mapNotNull(UIMessagePart.Image::assetId),
            startedAtEpochMillis = state?.startedAtEpochMillis
                ?: invocation.startedAt
                ?: parent.createdAt,
            phase = ChatImageGenerationTaskPhase.RECOVERING,
        )
}

internal data class DurableImageTaskDescriptor(
    val taskId: String,
    val toolCallId: String,
    val modelName: String,
    val size: String,
    val referenceImageCount: Int,
    val mediaOrigin: String,
    val parentAssetId: String?,
    val referenceAssetIds: List<String>,
    val referenceSourcePaths: List<String>,
    val requestedImageCount: Int,
)

internal fun decodeImageTaskDescriptor(payload: JsonObject): DurableImageTaskDescriptor? = runCatching {
    DurableImageTaskDescriptor(
        taskId = payload.getValue("task_id").jsonPrimitive.content,
        toolCallId = payload.getValue("tool_call_id").jsonPrimitive.content,
        modelName = payload.getValue("model_name").jsonPrimitive.content,
        size = payload.getValue("size").jsonPrimitive.content,
        referenceImageCount = payload.getValue("reference_image_count").jsonPrimitive.intOrNull
            ?: error("Invalid reference image count"),
        mediaOrigin = payload.getValue("media_origin").jsonPrimitive.content,
        parentAssetId = payload["parent_asset_id"]?.jsonPrimitive?.contentOrNull,
        referenceAssetIds = payload.getValue("reference_asset_ids").jsonArray.map {
            it.jsonPrimitive.content
        },
        referenceSourcePaths = payload.getValue("reference_source_paths").jsonArray.map {
            it.jsonPrimitive.content
        },
        requestedImageCount = payload.getValue("requested_image_count").jsonPrimitive.intOrNull
            ?: error("Invalid requested image count"),
    )
}.getOrNull()

/**
 * Projects authoritative per-slot request evidence back into the durable task and conversation.
 * This class deliberately has no provider dependency: cold start can repair local evidence but can
 * never cross the paid boundary.
 */
class ImageTaskRecoveryCoordinator(
    private val context: Context,
    private val requestRepository: RequestLedgerRepository,
    private val taskController: ChatImageGenerationTaskController,
    private val toolResultWriter: DurableImageToolResultWriter,
    private val taskSource: DurableImageTaskSource,
) {
    suspend fun reconcilePending(): ImageTaskRecoveryReport {
        val taskCandidates = taskController.tasks.value.toMutableMap()
        // Include terminal parents: a process can durably cancel/fail the parent before its
        // Conversation Tool projection is written, while the bounded task cache is also lost.
        val parentCandidates = requestRepository.getAllImageParentRequests()
        var projected = 0
        var conversationResultsRepaired = 0
        var pending = 0
        val failures = mutableListOf<String>()

        parentCandidates.forEach { parent ->
            if (taskCandidates.containsKey(parent.requestId)) return@forEach
            runCatching {
                val parentId = RequestId(parent.requestId)
                val children = requestRepository.getImageRequestsByParent(parentId)
                val invocation = requestRepository.getInvocations(parentId).singleOrNull()
                    ?: throw RequestLedgerConflict("Image parent must own exactly one invocation")
                val restored = taskSource.load(parent, invocation, children)
                if (restored != null) {
                    taskController.attachRecoveryTask(restored)
                    taskCandidates[parent.requestId] = restored
                }
            }.onFailure { failure ->
                pending++
                failures += "${parent.requestId}:${failure.javaClass.simpleName}"
            }
        }

        val tasks = taskCandidates.values.toList()

        tasks.forEach { task ->
            runCatching {
                val parentRequestId = runCatching { RequestId(task.requestId) }.getOrNull()
                val children = parentRequestId
                    ?.let { requestRepository.getImageRequestsByParent(it) }
                    .orEmpty()
                if (children.isEmpty()) {
                    if (task.phase == ChatImageGenerationTaskPhase.RECOVERING) {
                        val legacy = projectLegacyInterruptedTask(task)
                        val parentState = parentRequestId
                            ?.let { requestRepository.getRequest(it) }
                            ?.requestState()
                        if (!toolResultWriter.write(task, legacy.state, legacy.images, parentState)) {
                            pending++
                            return@runCatching
                        }
                        conversationResultsRepaired++
                        applyTaskProjection(task, legacy)
                        projected++
                    }
                    return@runCatching
                }

                val byAssetId = children.associateBy { child -> child.partId }
                check(byAssetId.size == children.size) { "Image child asset identities are not unique" }
                check(task.reservedOutputAssetIds.size == task.requestedImageCount) {
                    "Image task lost its reserved output identities"
                }
                val orderedChildren = task.reservedOutputAssetIds.map { assetId ->
                    byAssetId[assetId] ?: error("Image task is missing child request for $assetId")
                }
                check(orderedChildren.size == children.size) {
                    "Image task contains detached child requests"
                }
                val states = orderedChildren.map { child -> child.requestState() }
                if (states.any { !it.isTerminal }) {
                    pending++
                    return@runCatching
                }

                val projection = projectLedgerTask(task, orderedChildren, states)
                val parentState = parentRequestId
                    ?.let { requestRepository.getRequest(it) }
                    ?.requestState()
                if (!toolResultWriter.write(task, projection.state, projection.images, parentState)) {
                    pending++
                    return@runCatching
                }
                conversationResultsRepaired++
                applyTaskProjection(task, projection)
                projected++
            }.onFailure { failure ->
                pending++
                failures += "${task.taskId}:${failure.javaClass.simpleName}"
            }
        }
        return ImageTaskRecoveryReport(
            inspected = tasks.size,
            projected = projected,
            conversationResultsRepaired = conversationResultsRepaired,
            pending = pending,
            failures = failures,
        )
    }

    private suspend fun projectLedgerTask(
        task: ChatImageGenerationTaskRecord,
        children: List<RequestLedgerEntity>,
        states: List<RequestState>,
    ): RecoveredImageTask {
        val slots = mutableListOf<ChatImageGenerationSlot>()
        val images = mutableListOf<UIMessagePart.Image>()
        val outputAssetIds = mutableListOf<String>()
        children.forEachIndexed { index, child ->
            val state = states[index]
            val assetId = checkNotNull(child.partId)
            if (state == RequestState.SUCCEEDED) {
                val output = requestRepository.getOutputs(RequestId(child.requestId)).singleOrNull {
                    it.outputKind == "image_generation_slot" && it.ordinal == 0
                } ?: error("Succeeded image child has no canonical output")
                check(output.assetId == assetId && output.sourceId == task.requestId) {
                    "Succeeded image child output identity changed"
                }
                val committed = checkNotNull(findCommittedGeneratedImage(context, assetId)) {
                    "Succeeded image child has no committed file"
                }
                check(committed.sha256 == output.contentDigest) {
                    "Succeeded image child file digest changed"
                }
                val image = UIMessagePart.Image(
                    url = committed.file.toUri().toString(),
                    assetId = committed.assetId,
                )
                images += image
                outputAssetIds += committed.assetId
                slots += task.slot(index, child.requestId, ChatImageSlotStatus.SUCCEEDED, image.url)
            } else {
                slots += task.slot(index, child.requestId, state.toSlotStatus(), null)
            }
        }
        return RecoveredImageTask(task.state(slots), images, outputAssetIds)
    }

    private fun projectLegacyInterruptedTask(task: ChatImageGenerationTaskRecord): RecoveredImageTask {
        val identities = if (task.reservedOutputAssetIds.isNotEmpty()) {
            task.reservedOutputAssetIds
        } else {
            task.outputAssetIds
        }
        val images = mutableListOf<UIMessagePart.Image>()
        val outputAssetIds = mutableListOf<String>()
        val slots = List(task.requestedImageCount) { index ->
            val committed = identities.getOrNull(index)
                ?.let { assetId -> findCommittedGeneratedImage(context, assetId) }
            if (committed != null) {
                val image = UIMessagePart.Image(committed.file.toUri().toString(), assetId = committed.assetId)
                images += image
                outputAssetIds += committed.assetId
                task.slot(index, "${task.requestId}:legacy:$index", ChatImageSlotStatus.SUCCEEDED, image.url)
            } else {
                task.slot(index, "${task.requestId}:legacy:$index", ChatImageSlotStatus.INTERRUPTED, null)
            }
        }
        return RecoveredImageTask(task.state(slots), images, outputAssetIds)
    }

    private fun applyTaskProjection(task: ChatImageGenerationTaskRecord, recovered: RecoveredImageTask) {
        val phase = recovered.state.toTaskPhase()
        val issue = recovered.state.slots.firstOrNull { it.status != ChatImageSlotStatus.SUCCEEDED }
        taskController.applyRecoveredState(
            taskId = task.taskId,
            phase = phase,
            completedImageCount = recovered.state.succeededCount,
            failedImageCount = recovered.state.failedCount,
            outputAssetIds = recovered.outputAssetIds,
            slotStatuses = recovered.state.slots.map(ChatImageGenerationSlot::status),
            errorKind = issue?.failureKind ?: phase.failureKind(),
            errorMessage = issue?.error,
        )
    }

    private data class RecoveredImageTask(
        val state: ChatImageGenerationState,
        val images: List<UIMessagePart.Image>,
        val outputAssetIds: List<String>,
    )
}

private fun ChatImageGenerationTaskRecord.slot(
    index: Int,
    childRequestId: String,
    status: ChatImageSlotStatus,
    imageUrl: String?,
) = ChatImageGenerationSlot(
    index = index,
    status = status,
    imageUrl = imageUrl,
    error = status.defaultMessage(),
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = System.currentTimeMillis(),
    requestId = childRequestId,
    attempt = 1,
    failureKind = status.failureKind(),
)

private fun ChatImageGenerationTaskRecord.state(slots: List<ChatImageGenerationSlot>) = ChatImageGenerationState(
    requestId = requestId,
    attempt = attempt,
    prompt = prompt,
    model = modelName,
    size = size,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = System.currentTimeMillis(),
    referenceImageCount = referenceImageCount,
    slots = slots,
)

private fun RequestState.toSlotStatus(): ChatImageSlotStatus = when (this) {
    RequestState.SUCCEEDED -> ChatImageSlotStatus.SUCCEEDED
    RequestState.CANCELLED -> ChatImageSlotStatus.CANCELLED
    RequestState.INTERRUPTED -> ChatImageSlotStatus.INTERRUPTED
    RequestState.UNKNOWN_OUTCOME -> ChatImageSlotStatus.UNKNOWN_OUTCOME
    RequestState.FAILED -> ChatImageSlotStatus.FAILED
    else -> error("Non-terminal image child cannot be projected")
}

private fun ChatImageGenerationState.toTaskPhase(): ChatImageGenerationTaskPhase = when {
    slots.any { it.status == ChatImageSlotStatus.UNKNOWN_OUTCOME } -> ChatImageGenerationTaskPhase.UNKNOWN_OUTCOME
    slots.any { it.status == ChatImageSlotStatus.INTERRUPTED } -> ChatImageGenerationTaskPhase.INTERRUPTED
    slots.any { it.status == ChatImageSlotStatus.SUCCEEDED } -> ChatImageGenerationTaskPhase.COMPLETED
    slots.all { it.status == ChatImageSlotStatus.CANCELLED } -> ChatImageGenerationTaskPhase.CANCELLED
    else -> ChatImageGenerationTaskPhase.FAILED
}

private fun ChatImageGenerationTaskPhase.failureKind(): ImageGenerationFailureKind? = when (this) {
    ChatImageGenerationTaskPhase.CANCELLED -> ImageGenerationFailureKind.USER_CANCELLED
    ChatImageGenerationTaskPhase.INTERRUPTED -> ImageGenerationFailureKind.PROCESS_INTERRUPTED
    ChatImageGenerationTaskPhase.UNKNOWN_OUTCOME -> ImageGenerationFailureKind.UNKNOWN
    ChatImageGenerationTaskPhase.FAILED -> ImageGenerationFailureKind.UNKNOWN
    else -> null
}

private fun ChatImageSlotStatus.failureKind(): ImageGenerationFailureKind? = when (this) {
    ChatImageSlotStatus.CANCELLED -> ImageGenerationFailureKind.USER_CANCELLED
    ChatImageSlotStatus.INTERRUPTED -> ImageGenerationFailureKind.PROCESS_INTERRUPTED
    ChatImageSlotStatus.UNKNOWN_OUTCOME,
    ChatImageSlotStatus.FAILED,
    -> ImageGenerationFailureKind.UNKNOWN
    else -> null
}

private fun ChatImageSlotStatus.defaultMessage(): String? = when (this) {
    ChatImageSlotStatus.CANCELLED -> "Image generation was cancelled before dispatch."
    ChatImageSlotStatus.INTERRUPTED -> "Image response was interrupted and was not replayed."
    ChatImageSlotStatus.UNKNOWN_OUTCOME -> "The provider may have accepted this paid request; it was not replayed."
    ChatImageSlotStatus.FAILED -> "Image generation failed."
    else -> null
}

private fun RequestLedgerEntity.requestState(): RequestState =
    RequestState.valueOf(requestState.uppercase(Locale.ROOT))

private fun RequestLedgerEntity.imageSlotOrdinal(): Int =
    intentKey.substringAfterLast(':').toIntOrNull()
        ?: throw RequestLedgerConflict("Image child intent key lost its slot ordinal")

private fun ChatImageGenerationState.sameDurableProjection(other: ChatImageGenerationState): Boolean =
    requestId == other.requestId && attempt == other.attempt && prompt == other.prompt &&
        model == other.model && size == other.size && referenceImageCount == other.referenceImageCount &&
        slots.map { Triple(it.requestId, it.status, it.imageUrl) } ==
        other.slots.map { Triple(it.requestId, it.status, it.imageUrl) }

internal fun RequestState?.toRecoveredToolExecutionState(): ToolExecutionState = when (this) {
    RequestState.CANCELLED,
    RequestState.INTERRUPTED,
    RequestState.UNKNOWN_OUTCOME,
    -> ToolExecutionState.INTERRUPTED
    RequestState.FAILED -> ToolExecutionState.FAILED
    else -> ToolExecutionState.SUCCEEDED
}

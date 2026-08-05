package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.model.effectiveCapabilitySnapshot
import me.rerere.ai.provider.ProviderSetting
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.RequestState
import me.rerere.pale.request.BillableBoundary
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveImageGenerationModel
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.imggen.ImageGenerationGateway
import me.rerere.rikkahub.data.imggen.ImageGenerationExecution
import me.rerere.rikkahub.data.imggen.ImageGenerationExecutionEvent
import me.rerere.rikkahub.data.imggen.ImageGenerationException
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import me.rerere.rikkahub.data.imggen.ImageGenerationRequest
import me.rerere.rikkahub.data.imggen.freezeImageGenerationCredential
import me.rerere.rikkahub.data.imggen.imageTransportConfigurationDigest
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskExecutor
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskPhase
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.toStatusPart
import me.rerere.rikkahub.data.imggen.decodeValidatedImage
import me.rerere.rikkahub.data.imggen.isAndroidDecodableImage
import me.rerere.rikkahub.data.imggen.findCommittedGeneratedImage
import me.rerere.rikkahub.data.repository.GeneratedMediaAssetRegistration
import me.rerere.rikkahub.data.repository.MediaAssetReferenceInput
import me.rerere.rikkahub.data.repository.MediaAssetIds
import me.rerere.rikkahub.data.repository.MediaAssetRepository
import me.rerere.rikkahub.fork.pale.request.DurableImageSlotOutput
import me.rerere.rikkahub.fork.pale.request.ImageGenerationLedgerCoordinator
import me.rerere.rikkahub.fork.pale.request.ImageGenerationRequestDescriptor
import me.rerere.rikkahub.fork.pale.request.ImageGenerationSlotOpenResult
import me.rerere.rikkahub.fork.pale.request.ImageGenerationSlotLedgerStatus
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeFailure
import java.io.File
import java.security.MessageDigest

private const val TAG = "ImageGenerationTool"
private const val MAX_IMAGE_GENERATION_COUNT = 8

/**
 * Each paid slot is a separate n=1 request. Keep small multi-image requests in one wave so later
 * images do not spend another entire provider-latency cycle queued behind an earlier wave.
 * The tool schema caps the user-requested batch at eight, which remains the hard socket/upload
 * bound; provider-side throttling is surfaced per slot without cancelling successful siblings.
 */
internal fun imageGenerationParallelism(slotCount: Int): Int {
    require(slotCount in 1..MAX_IMAGE_GENERATION_COUNT) {
        "Image generation count must be between 1 and $MAX_IMAGE_GENERATION_COUNT"
    }
    return slotCount
}

private data class ImageGenerationProgressProjection(
    val state: ChatImageGenerationState,
    val images: List<UIMessagePart.Image>,
    val outputAssetIds: List<String>,
)

internal fun buildImageGenerationTool(
    applicationContext: Context,
    settingsStore: SettingsStore,
    gateway: ImageGenerationGateway,
    filesManager: FilesManager,
    chatImageTaskController: ChatImageGenerationTaskController,
    mediaAssetRepository: MediaAssetRepository,
    imageGenerationLedgerCoordinator: ImageGenerationLedgerCoordinator,
    json: Json,
): Tool = Tool(
    name = "generate_image",
    description = """
        Generate an image in the current conversation with the image model selected in the app.
        Call this only when the user explicitly asks to draw, create, or generate an image.
        Do not call it merely to analyze or discuss an existing image.
        Write a complete standalone visual prompt for one variant; the returned image is shown directly to the user.
        Put the requested number of variants only in count. Do not repeat the quantity in prompt or ask the image model
        for a grid/collage unless the user explicitly requested one composite image.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, messages ->
        val availability = settingsStore.settingsFlow.value
            .resolveImageGenerationModel()
            ?.let { "Image generation is available through generate_image using ${it.displayName}." }
            .orEmpty()
        val references = buildReferenceImageCatalog(messages)
        buildString {
            append(availability)
            append(" Images attached to the latest user message are used as references automatically. ")
            append("To reference an earlier conversation image, pass its opaque ID in reference_image_ids; never invent file paths.")
            if (references.isNotEmpty()) {
                append(" Available reference image IDs: ")
                append(references.joinToString { it.id })
                append('.')
            }
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "A complete prompt for one image variant. Omit the variant count; use count for quantity.",
                    )
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("description", "Image size such as auto, 1024x1024, 1536x1024, or 1024x1536")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Number of image variants to generate, from 1 to $MAX_IMAGE_GENERATION_COUNT",
                    )
                    put("minimum", 1)
                    put("maximum", MAX_IMAGE_GENERATION_COUNT)
                })
                put("reference_image_ids", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional opaque IDs of earlier conversation images to use as visual references")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("prompt"),
        )
    },
    execute = { input ->
        error("generate_image requires the durable chat execution context")
    },
    executeWithContext = { input, executionContext ->
        executeImageGeneration(
            input = input,
            applicationContext = applicationContext,
            context = executionContext,
            settingsStore = settingsStore,
            gateway = gateway,
            filesManager = filesManager,
            chatImageTaskController = chatImageTaskController,
            mediaAssetRepository = mediaAssetRepository,
            imageGenerationLedgerCoordinator = imageGenerationLedgerCoordinator,
            json = json,
        )
    },
    // The outer tool is a local task/group projection. Each image slot owns the real provider
    // dispatch and billable boundary through its IMAGE_GENERATION child request.
    ledgerSideEffectClass = "irreversible",
    ledgerOwnsExternalDispatch = false,
)

private suspend fun executeImageGeneration(
    input: kotlinx.serialization.json.JsonElement,
    applicationContext: Context,
    context: ToolExecutionContext,
    settingsStore: SettingsStore,
    gateway: ImageGenerationGateway,
    filesManager: FilesManager,
    chatImageTaskController: ChatImageGenerationTaskController,
    mediaAssetRepository: MediaAssetRepository,
    imageGenerationLedgerCoordinator: ImageGenerationLedgerCoordinator,
    json: Json,
): List<UIMessagePart> {
        val arguments = input.jsonObject
        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("prompt is required")
        val requestedSize = arguments["size"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val size = requestedSize?.takeIf { candidate ->
            ImageGenSize.entries.any { it.value == candidate }
        } ?: ImageGenSize.AUTO.value
        val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 1
        require(count in 1..MAX_IMAGE_GENERATION_COUNT) {
            "generate_image count must be between 1 and $MAX_IMAGE_GENERATION_COUNT"
        }
        settingsStore.awaitCredentialReady()
        val settings = settingsStore.settingsFlow.value
        val model = settings.resolveImageGenerationModel()
            ?: error("No image generation model is selected in the app settings")

        val catalog = buildReferenceImageCatalog(context.messages)
        val requestedReferenceIds = arguments["reference_image_ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val selectedReferences = if (requestedReferenceIds.isNotEmpty()) {
            val byId = catalog.associateBy(ReferenceImage::id)
            requestedReferenceIds.mapNotNull(byId::get)
        } else {
            latestUserReferenceImages(context.messages)
        }
        val resolvedReferences = selectedReferences.mapNotNull { reference ->
            reference.url.toLocalImagePath()?.let { localPath ->
                ResolvedImageGenerationReference(
                    localPath = localPath,
                    assetId = reference.assetId,
                    managedSourcePath = filesManager.toManagedRelativePath(File(localPath)),
                )
            }
        }
        val referencePaths = resolvedReferences.map(ResolvedImageGenerationReference::localPath)
        val mediaReferenceInputs = buildMediaReferenceInputs(resolvedReferences)
        if (requestedReferenceIds.isNotEmpty() && referencePaths.isEmpty()) {
            error("The selected reference images are no longer available")
        }

        val startedAt = System.currentTimeMillis()
        val requestId = context.executionRequestId.takeIf(String::isNotBlank)
            ?: error("generate_image requires a stable RequestLedger identity")
        val parentRequestId = RequestId(requestId)
        val attempt = 1
        val provider = model.findProvider(settings.providers)
            ?: error("The selected image provider is not configured")
        val providerId = provider.id.toString()
        val credentialEvidence = settings.freezeImageGenerationCredential(provider, model)
        val transportConfigurationDigest = imageTransportConfigurationDigest(model, provider)
        val reservedAssetIds = List(count) { index ->
            MediaAssetIds.forChatToolOutput(requestId, index)
        }
        val referencedAssetId = resolvedReferences.singleOrNull()?.assetId?.takeIf(String::isNotBlank)
        val parentAssetId = if (
            referencedAssetId != null && mediaAssetRepository.getAsset(referencedAssetId) != null
        ) {
            referencedAssetId
        } else {
            null
        }
        val assetOrigin = if (referencePaths.isEmpty()) {
            MediaAssetEntity.ORIGIN_AI_GENERATED
        } else {
            MediaAssetEntity.ORIGIN_AI_EDITED
        }
        val capabilitySnapshotJson = json.encodeToString(model.effectiveCapabilitySnapshot(provider))
        val plans = imageGenerationLedgerCoordinator.prepareSlots(
            ImageGenerationRequestDescriptor(
                parentRequestId = parentRequestId,
                taskId = requestId,
                toolCallId = context.toolCallId.ifBlank { requestId },
                prompt = prompt,
                modelId = model.id.toString(),
                modelName = model.displayName,
                providerId = providerId,
                providerKind = provider.providerKind(),
                credentialRefId = credentialEvidence?.reference,
                size = size,
                referenceImageDigests = referencePaths.map(::sha256File),
                referenceAssetIds = resolvedReferences.mapNotNull(ResolvedImageGenerationReference::assetId),
                referenceSourcePaths = resolvedReferences.mapNotNull(
                    ResolvedImageGenerationReference::managedSourcePath,
                ),
                parentAssetId = parentAssetId,
                capabilitySnapshotJson = capabilitySnapshotJson,
                transportConfigurationDigest = transportConfigurationDigest,
                requestedImageCount = count,
                reservedOutputAssetIds = reservedAssetIds,
                apiSurface = if (referencePaths.isEmpty()) "image_generations" else "image_edits",
            ),
        )
        val slots = MutableList(count) { index ->
            ChatImageGenerationSlot(
                index = index,
                status = ChatImageSlotStatus.QUEUED,
                requestId = plans[index].requestId.value,
                attempt = attempt,
            )
        }
        val slotImages = MutableList<UIMessagePart.Image?>(count) { null }
        val committedAssetIds = MutableList<String?>(count) { null }
        val stateMutex = Mutex()
        context.contextId?.takeIf(String::isNotBlank)
            ?: error("generate_image requires a durable conversation identity")
        val durableTaskId = requestId

        fun snapshotLocked(finishedAt: Long? = null): ChatImageGenerationState =
            ChatImageGenerationState(
                requestId = requestId,
                attempt = attempt,
                prompt = prompt,
                model = model.displayName,
                size = size,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = finishedAt,
                referenceImageCount = referencePaths.size,
                slots = slots.toList(),
            )

        fun projectionLocked(finishedAt: Long? = null) = ImageGenerationProgressProjection(
            state = snapshotLocked(finishedAt),
            images = slotImages.filterNotNull(),
            outputAssetIds = committedAssetIds.filterNotNull(),
        )

        suspend fun snapshot(finishedAt: Long? = null): ChatImageGenerationState = stateMutex.withLock {
            snapshotLocked(finishedAt)
        }

        suspend fun settleRemainingSlots(fallback: ChatImageSlotStatus) = withContext(NonCancellable) {
            plans.indices.forEach { index ->
                val terminal = stateMutex.withLock { slots[index].status.isTerminalStatus() }
                if (terminal) return@forEach
                val ledgerState = runCatching {
                    imageGenerationLedgerCoordinator.cancelBeforeDispatch(plans[index])
                }.getOrNull()
                val inspected = runCatching {
                    imageGenerationLedgerCoordinator.inspectSlot(plans[index])
                }.getOrNull()
                stateMutex.withLock {
                    if (!slots[index].status.isTerminalStatus()) {
                        slots[index] = slots[index].copy(
                            status = ledgerState.toImageSlotStatus(inspected.toImageSlotStatus(fallback)),
                            finishedAtEpochMillis = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }

        fun settleDurableTask(finalState: ChatImageGenerationState, fallbackMessage: String? = null) {
            val taskId = durableTaskId
            val phase = finalState.aggregateTaskPhase()
            val firstIssue = finalState.slots.firstOrNull { it.status != ChatImageSlotStatus.SUCCEEDED }
            runCatching {
                chatImageTaskController.applyRecoveredState(
                    taskId = taskId,
                    phase = phase,
                    completedImageCount = finalState.succeededCount,
                    failedImageCount = finalState.failedCount,
                    outputAssetIds = committedAssetIds.filterNotNull(),
                    slotStatuses = finalState.slots.map(ChatImageGenerationSlot::status),
                    errorKind = firstIssue?.failureKind ?: phase.defaultFailureKind(),
                    errorMessage = firstIssue?.error ?: fallbackMessage,
                )
            }.onFailure { error ->
                logSafeError(
                    tag = TAG,
                    domain = "image_generation",
                    operation = "project_terminal_task",
                    error = error,
                    requestId = taskId,
                )
            }
        }

        try {
            // Make the queued state visible before foreground-service startup. No paid
            // provider request has begun yet, so a foreground-start failure remains safe.
            context.emitProgress(listOf(snapshot().toStatusPart()))
            val ownerJob = currentCoroutineContext()[Job]
            chatImageTaskController.begin(
                    task = ChatImageGenerationTaskRecord(
                        taskId = durableTaskId,
                        conversationId = context.contextId.orEmpty(),
                        toolCallId = context.toolCallId.ifBlank { requestId },
                        requestId = requestId,
                        attempt = attempt,
                        modelId = model.id.toString(),
                        modelName = model.displayName,
                        providerId = providerId,
                        size = size,
                        referenceImageCount = referencePaths.size,
                        prompt = prompt,
                        mediaOrigin = assetOrigin,
                        parentAssetId = parentAssetId,
                        referenceAssetIds = resolvedReferences.mapNotNull(
                            ResolvedImageGenerationReference::assetId,
                        ),
                        referenceSourcePaths = resolvedReferences.mapNotNull(
                            ResolvedImageGenerationReference::managedSourcePath,
                        ),
                        requestedImageCount = count,
                        reservedOutputAssetIds = reservedAssetIds,
                        startedAtEpochMillis = startedAt,
                    ),
                    cancelExecution = {
                        ownerJob?.cancel(CancellationException("Image generation cancelled from notification"))
                    },
            )

            val semaphore = Semaphore(imageGenerationParallelism(slots.size))
            val taskExecutor = ImageGenerationTaskExecutor(gateway)
            coroutineScope {
                // Carry immutable projections rather than wake-up signals. A Unit signal followed
                // by a later snapshot can collapse two near-simultaneous completions into one UI
                // update, which makes independently completed images appear as an all-at-once batch.
                val updates = Channel<ImageGenerationProgressProjection>(Channel.UNLIMITED)
                val jobs = slots.indices.map { index ->
                    async {
                        try {
                            semaphore.withPermit {
                                val opened = imageGenerationLedgerCoordinator.openSlot(plans[index])
                                if (opened is ImageGenerationSlotOpenResult.AlreadySucceeded) {
                                    val committed = checkNotNull(
                                        findCommittedGeneratedImage(applicationContext, plans[index].assetId),
                                    ) {
                                        "Succeeded image slot ${plans[index].requestId.value} has no durable file"
                                    }
                                    check(committed.sha256 == opened.output.contentDigest) {
                                        "Succeeded image slot file digest no longer matches RequestLedger"
                                    }
                                    val finishedAt = System.currentTimeMillis()
                                    val part = UIMessagePart.Image(
                                        url = committed.file.toUri().toString(),
                                        assetId = committed.assetId,
                                    )
                                    stateMutex.withLock {
                                        slotImages[index] = part
                                        committedAssetIds[index] = committed.assetId
                                        slots[index] = slots[index].copy(
                                            status = ChatImageSlotStatus.SUCCEEDED,
                                            imageUrl = part.url,
                                            error = null,
                                            failureKind = null,
                                            finishedAtEpochMillis = finishedAt,
                                        )
                                        updates.trySend(projectionLocked())
                                    }
                                    return@withPermit
                                }
                                opened as ImageGenerationSlotOpenResult.Dispatch
                                val ledgerSession = opened.session
                                val slotStartedAt = System.currentTimeMillis()
                                stateMutex.withLock {
                                    slots[index] = slots[index].copy(
                                        status = ChatImageSlotStatus.RUNNING,
                                        startedAtEpochMillis = slotStartedAt,
                                    )
                                    updates.trySend(projectionLocked())
                                }
                                taskExecutor.execute(
                                    execution = ImageGenerationExecution(
                                        requestId = slots[index].requestId,
                                        attempt = slots[index].attempt,
                                        ledgerSession = ledgerSession,
                                        request = ImageGenerationRequest(
                                            requestId = slots[index].requestId,
                                            prompt = prompt,
                                            modelId = model.id.toString(),
                                            modelName = model.displayName,
                                            providerId = providerId,
                                            credentialEvidence = credentialEvidence,
                                            transportConfigurationDigest = transportConfigurationDigest,
                                            size = size,
                                            numberOfImages = 1,
                                            referenceImages = referencePaths,
                                        ),
                                    ),
                                ) { event ->
                                    when (event) {
                                        is ImageGenerationExecutionEvent.Running -> Unit
                                        is ImageGenerationExecutionEvent.Preview -> Unit
                                        is ImageGenerationExecutionEvent.FinalImage -> {
                                            // Once the paid bytes arrive, finish the local commit even
                                            // if the user cancels at the same instant. A committed file
                                            // is authoritative and must never be downgraded to failure.
                                            withContext(NonCancellable) {
                                                val image = saveToolImage(
                                                    filesManager = filesManager,
                                                    mediaAssetRepository = mediaAssetRepository,
                                                    item = event.item,
                                                    index = index,
                                                    registration = GeneratedMediaAssetRegistration(
                                                        assetId = reservedAssetIds[index],
                                                        origin = assetOrigin,
                                                        modelId = model.id.toString(),
                                                        modelDisplayName = model.displayName,
                                                        providerId = providerId,
                                                        prompt = prompt,
                                                        createdAt = startedAt,
                                                        conversationId = context.contextId,
                                                        toolCallId = context.toolCallId
                                                            .takeIf(String::isNotBlank)
                                                            ?: requestId,
                                                        parentAssetId = parentAssetId,
                                                        referenceInputs = mediaReferenceInputs,
                                                    ),
                                                    onFileCommitted = ledgerSession::markDurableFileReceived,
                                                )
                                                ledgerSession.commitDurableOutput(
                                                    DurableImageSlotOutput(
                                                        contentDigest = image.sha256,
                                                        assetId = reservedAssetIds[index],
                                                        sourceId = requestId,
                                                        relativePath = image.relativePath,
                                                        mimeType = image.mimeType,
                                                        byteSize = image.byteSize,
                                                    ),
                                                )
                                                val finishedAt = System.currentTimeMillis()
                                                stateMutex.withLock {
                                                    slotImages[index] = image.part
                                                    committedAssetIds[index] = image.part.assetId
                                                    slots[index] = slots[index].copy(
                                                        status = ChatImageSlotStatus.SUCCEEDED,
                                                        imageUrl = image.part.url,
                                                        error = null,
                                                        failureKind = null,
                                                        finishedAtEpochMillis = finishedAt,
                                                    )
                                                    updates.trySend(projectionLocked())
                                                }
                                            }
                                        }
                                        is ImageGenerationExecutionEvent.Succeeded -> Unit
                                        is ImageGenerationExecutionEvent.Failed -> {
                                            // A local DB commit can fail after the validated file rename. The
                                            // file remains authoritative for UI and startup repair; never replace
                                            // it with a paid-request failure or invite a retry.
                                            val committed = findCommittedGeneratedImage(
                                                applicationContext,
                                                plans[index].assetId,
                                            )
                                            val inspected = runCatching {
                                                imageGenerationLedgerCoordinator.inspectSlot(plans[index])
                                            }.getOrNull()
                                            stateMutex.withLock {
                                                if (slots[index].status != ChatImageSlotStatus.SUCCEEDED) {
                                                    if (committed != null && inspected?.boundary in setOf(
                                                            BillableBoundary.RESPONSE_STARTED,
                                                            BillableBoundary.RESULT_RECEIVED,
                                                            BillableBoundary.RESULT_COMMITTED,
                                                        )
                                                    ) {
                                                        val part = UIMessagePart.Image(
                                                            committed.file.toUri().toString(),
                                                            assetId = committed.assetId,
                                                        )
                                                        slotImages[index] = part
                                                        committedAssetIds[index] = committed.assetId
                                                        slots[index] = slots[index].copy(
                                                            status = ChatImageSlotStatus.SUCCEEDED,
                                                            imageUrl = part.url,
                                                            error = null,
                                                            failureKind = null,
                                                            finishedAtEpochMillis = System.currentTimeMillis(),
                                                        )
                                                        logSafeFailure(
                                                            tag = TAG,
                                                            domain = "image_generation",
                                                            operation = "repair_slot_ledger",
                                                            warning = true,
                                                            requestId = plans[index].requestId.value,
                                                        )
                                                    } else {
                                                        slots[index] = slots[index].copy(
                                                            status = event.ledgerState.toImageSlotStatus(
                                                                fallback = inspected.toImageSlotStatus(
                                                                    ChatImageSlotStatus.FAILED,
                                                                ),
                                                            ),
                                                            error = event.failure.message.take(160),
                                                            failureKind = event.failure.kind,
                                                            finishedAtEpochMillis = System.currentTimeMillis(),
                                                        )
                                                    }
                                                }
                                                updates.trySend(projectionLocked())
                                            }
                                        }
                                        is ImageGenerationExecutionEvent.Cancelled -> {
                                            stateMutex.withLock {
                                                if (slots[index].status != ChatImageSlotStatus.SUCCEEDED) {
                                                    slots[index] = slots[index].copy(
                                                        status = event.ledgerState.toImageSlotStatus(
                                                            fallback = ChatImageSlotStatus.CANCELLED,
                                                        ),
                                                        finishedAtEpochMillis = System.currentTimeMillis(),
                                                    )
                                                }
                                                updates.trySend(projectionLocked())
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            val ledgerState = withContext(NonCancellable) {
                                runCatching {
                                    imageGenerationLedgerCoordinator.cancelBeforeDispatch(plans[index])
                                }.getOrNull()
                            }
                            val inspected = withContext(NonCancellable) {
                                runCatching {
                                    imageGenerationLedgerCoordinator.inspectSlot(plans[index])
                                }.getOrNull()
                            }
                            val finishedAt = System.currentTimeMillis()
                            stateMutex.withLock {
                                if (slots[index].status != ChatImageSlotStatus.SUCCEEDED) {
                                    slots[index] = slots[index].copy(
                                        status = ledgerState.toImageSlotStatus(
                                            inspected.toImageSlotStatus(ChatImageSlotStatus.CANCELLED),
                                        ),
                                        finishedAtEpochMillis = finishedAt,
                                    )
                                }
                                updates.trySend(projectionLocked())
                            }
                            throw cancelled
                        } catch (error: Exception) {
                            val finishedAt = System.currentTimeMillis()
                            stateMutex.withLock {
                                if (slots[index].status != ChatImageSlotStatus.SUCCEEDED) {
                                    slots[index] = slots[index].copy(
                                        status = ChatImageSlotStatus.FAILED,
                                        error = error.javaClass.simpleName.ifBlank { "UnknownError" },
                                        failureKind = ImageGenerationFailureKind.UNKNOWN,
                                        finishedAtEpochMillis = finishedAt,
                                    )
                                }
                                updates.trySend(projectionLocked())
                            }
                        }
                    }
                }
                launch {
                    jobs.awaitAll()
                    updates.close()
                }
                // Flow emissions must remain on the owning coroutine. Workers only signal that
                // their slot changed; this parent serializes snapshots for progressive rendering.
                for (projection in updates) {
                    val progressState = projection.state
                    runCatching {
                        chatImageTaskController.updateProgress(
                            taskId = durableTaskId,
                            completedImageCount = progressState.succeededCount,
                            failedImageCount = progressState.failedCount,
                            outputAssetIds = projection.outputAssetIds,
                            slotStatuses = progressState.slots.map(ChatImageGenerationSlot::status),
                        )
                    }.onFailure { error ->
                        logSafeError(
                            tag = TAG,
                            domain = "image_generation",
                            operation = "persist_task_progress",
                            error = error,
                            requestId = durableTaskId,
                        )
                    }
                    try {
                        context.emitProgress(
                            buildList {
                                add(progressState.toStatusPart())
                                projection.images.forEach(::add)
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logSafeError(
                            tag = TAG,
                            domain = "image_generation",
                            operation = "emit_progressive_result",
                            error = error,
                            requestId = requestId,
                        )
                    }
                }
            }

            val completedAt = System.currentTimeMillis()
            val finalState = snapshot(finishedAt = completedAt)
            settleDurableTask(finalState, "Image generation did not return an image")
            return buildList {
                add(finalState.toStatusPart())
                slotImages.filterNotNull().forEach(::add)
            }
        } catch (cancelled: CancellationException) {
            settleRemainingSlots(ChatImageSlotStatus.CANCELLED)
            settleDurableTask(
                snapshot(finishedAt = System.currentTimeMillis()),
                "Image generation was cancelled",
            )
            throw cancelled
        } catch (error: Throwable) {
            settleRemainingSlots(ChatImageSlotStatus.FAILED)
            settleDurableTask(
                snapshot(finishedAt = System.currentTimeMillis()),
                "Image generation failed (${error.javaClass.simpleName.ifBlank { "UnknownError" }})",
            )
            throw error
        }
}

private data class ReferenceImage(
    val id: String,
    val url: String,
    val role: MessageRole,
    val assetId: String? = null,
)

internal data class ResolvedImageGenerationReference(
    val localPath: String,
    val assetId: String? = null,
    val managedSourcePath: String? = null,
)

internal fun buildMediaReferenceInputs(
    references: List<ResolvedImageGenerationReference>,
): List<MediaAssetReferenceInput> = buildList {
    val seenIdentities = mutableSetOf<String>()
    references.forEach { reference ->
        val assetId = reference.assetId?.takeIf(String::isNotBlank)
        val sourcePath = reference.managedSourcePath?.takeIf(String::isNotBlank)
        if (assetId == null && sourcePath == null) return@forEach
        val identity = assetId?.let { "asset:$it" } ?: "path:$sourcePath"
        if (!seenIdentities.add(identity)) return@forEach
        add(MediaAssetReferenceInput(assetId = assetId, sourcePath = sourcePath))
    }
}

private fun buildReferenceImageCatalog(messages: List<UIMessage>): List<ReferenceImage> = buildList {
    messages.forEachIndexed { messageIndex, message ->
        message.parts.forEachIndexed { partIndex, part ->
            when (part) {
                is UIMessagePart.Image -> add(
                    ReferenceImage(
                        id = "msg-$messageIndex-img-$partIndex",
                        url = part.url,
                        role = message.role,
                        assetId = part.assetId,
                    ),
                )
                is UIMessagePart.Tool -> (part.output + part.progress)
                    .filterIsInstance<UIMessagePart.Image>()
                    .forEachIndexed { imageIndex, image ->
                        add(
                            ReferenceImage(
                                id = "msg-$messageIndex-tool-$partIndex-img-$imageIndex",
                                url = image.url,
                                role = message.role,
                                assetId = image.assetId,
                            ),
                        )
                    }
                else -> Unit
            }
        }
    }
}

private fun latestUserReferenceImages(messages: List<UIMessage>): List<ReferenceImage> {
    val messageIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (messageIndex < 0) return emptyList()
    return messages[messageIndex].parts.mapIndexedNotNull { partIndex, part ->
        (part as? UIMessagePart.Image)?.let {
            ReferenceImage(
                id = "msg-$messageIndex-img-$partIndex",
                url = it.url,
                role = MessageRole.USER,
                assetId = it.assetId,
            )
        }
    }
}

private fun String.toLocalImagePath(): String? = runCatching {
    when {
        startsWith("file:") -> toUri().path
        startsWith("/") -> this
        else -> null
    }?.takeIf { File(it).isFile }
}.getOrNull()

private data class CommittedToolImage(
    val part: UIMessagePart.Image,
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
)

private suspend fun saveToolImage(
    filesManager: FilesManager,
    mediaAssetRepository: MediaAssetRepository,
    item: ImageGenerationItem,
    index: Int,
    registration: GeneratedMediaAssetRegistration,
    onFileCommitted: suspend (String) -> Unit,
): CommittedToolImage {
    val payload = try {
        decodeValidatedImage(item).also { validated ->
            require(isAndroidDecodableImage(validated.bytes)) {
                "Generated image cannot be decoded by Android"
            }
        }
    } catch (error: Throwable) {
        throw ImageGenerationException(
            kind = ImageGenerationFailureKind.RESPONSE_PARSE,
            message = "The generated image payload is invalid",
            cause = error,
        )
    }
    val normalizedMime = payload.mimeType
    val extension = when (normalizedMime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }
    val file = try {
        filesManager.commitManagedBytesWithIdentity(
            // Final generated images are conversation assets. TOOL_OUTPUTS is an
            // intentionally ephemeral workspace and is deleted on app startup.
            folder = FileFolders.CHAT_GENERATED_IMAGES,
            bytes = payload.bytes,
            assetId = registration.assetId,
            mimeType = normalizedMime,
        )
    } catch (error: Throwable) {
        throw ImageGenerationException(
            kind = ImageGenerationFailureKind.IMAGE_WRITE,
            message = "The generated image could not be saved",
            cause = error,
        )
    }
    val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(payload.bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    // The rename/fsync completed. Persist this checkpoint before any repairable metadata work.
    onFileCommitted(sha256)
    val relativePath = checkNotNull(filesManager.toManagedRelativePath(file)) {
        "Committed generated image escaped managed storage"
    }
    val managed = try {
        withContext(NonCancellable) {
            filesManager.registerExistingManagedFile(
                folder = FileFolders.CHAT_GENERATED_IMAGES,
                file = file,
                displayName = "generated_image_${index + 1}.$extension",
                mimeType = normalizedMime,
                createdAt = registration.createdAt,
            )
        }
    } catch (error: Exception) {
        logSafeError(
            tag = TAG,
            domain = "image_generation",
            operation = "register_managed_file",
            error = error,
            requestId = registration.assetId,
        )
        null
    }
    val assetId = if (managed == null) {
        registration.assetId
    } else {
        registerCommittedImageOrDefer(
            reservedAssetId = registration.assetId,
            register = {
                mediaAssetRepository.registerGeneratedAsset(
                    managedFile = managed,
                    file = file,
                    registration = registration,
                ).assetId
            },
            onDeferred = { error ->
            logSafeError(
                tag = TAG,
                domain = "image_generation",
                operation = "register_media_asset",
                error = error,
                requestId = registration.assetId,
            )
            },
        )
    }
    return CommittedToolImage(
        part = UIMessagePart.Image(
            url = file.toUri().toString(),
            assetId = assetId,
        ),
        relativePath = relativePath,
        sha256 = sha256,
        mimeType = normalizedMime,
        byteSize = payload.bytes.size.toLong(),
    )
}

/**
 * The paid byte commit is the user-visible success boundary. MediaAsset metadata is
 * repairable from the reserved UUID file, so an indexing failure must not discard the
 * image or invite a second paid request.
 */
internal suspend fun registerCommittedImageOrDefer(
    reservedAssetId: String,
    register: suspend () -> String,
    onDeferred: (Exception) -> Unit,
): String = try {
    register()
} catch (error: Exception) {
    onDeferred(error)
    reservedAssetId
}

private fun RequestState?.toImageSlotStatus(fallback: ChatImageSlotStatus): ChatImageSlotStatus = when (this) {
    RequestState.SUCCEEDED -> ChatImageSlotStatus.SUCCEEDED
    RequestState.CANCELLED -> ChatImageSlotStatus.CANCELLED
    RequestState.INTERRUPTED -> ChatImageSlotStatus.INTERRUPTED
    RequestState.UNKNOWN_OUTCOME -> ChatImageSlotStatus.UNKNOWN_OUTCOME
    RequestState.FAILED -> ChatImageSlotStatus.FAILED
    else -> fallback
}

private fun ImageGenerationSlotLedgerStatus?.toImageSlotStatus(
    fallback: ChatImageSlotStatus,
): ChatImageSlotStatus = when {
    this == null -> fallback
    state.isTerminal -> state.toImageSlotStatus(fallback)
    boundary == BillableBoundary.UNKNOWN || boundary == BillableBoundary.SENT ->
        ChatImageSlotStatus.UNKNOWN_OUTCOME
    boundary == BillableBoundary.RESPONSE_STARTED -> ChatImageSlotStatus.INTERRUPTED
    else -> fallback
}

private fun ChatImageSlotStatus.isTerminalStatus(): Boolean = this in setOf(
    ChatImageSlotStatus.SUCCEEDED,
    ChatImageSlotStatus.FAILED,
    ChatImageSlotStatus.CANCELLED,
    ChatImageSlotStatus.INTERRUPTED,
    ChatImageSlotStatus.UNKNOWN_OUTCOME,
)

private fun ChatImageGenerationState.aggregateTaskPhase(): ChatImageGenerationTaskPhase = when {
    slots.any { it.status == ChatImageSlotStatus.UNKNOWN_OUTCOME } ->
        ChatImageGenerationTaskPhase.UNKNOWN_OUTCOME
    slots.any { it.status == ChatImageSlotStatus.INTERRUPTED } ->
        ChatImageGenerationTaskPhase.INTERRUPTED
    slots.any { it.status == ChatImageSlotStatus.SUCCEEDED } ->
        ChatImageGenerationTaskPhase.COMPLETED
    slots.all { it.status == ChatImageSlotStatus.CANCELLED } ->
        ChatImageGenerationTaskPhase.CANCELLED
    else -> ChatImageGenerationTaskPhase.FAILED
}

private fun ChatImageGenerationTaskPhase.defaultFailureKind(): ImageGenerationFailureKind? = when (this) {
    ChatImageGenerationTaskPhase.CANCELLED -> ImageGenerationFailureKind.USER_CANCELLED
    ChatImageGenerationTaskPhase.INTERRUPTED -> ImageGenerationFailureKind.PROCESS_INTERRUPTED
    ChatImageGenerationTaskPhase.UNKNOWN_OUTCOME -> ImageGenerationFailureKind.UNKNOWN
    ChatImageGenerationTaskPhase.FAILED -> ImageGenerationFailureKind.UNKNOWN
    else -> null
}

private fun ProviderSetting.providerKind(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

private fun sha256File(path: String): String {
    val file = File(path)
    require(file.isFile) { "Reference image is no longer available" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

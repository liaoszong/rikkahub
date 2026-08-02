package me.rerere.rikkahub.data.ai.tools.local

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskExecutor
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.toStatusPart
import me.rerere.rikkahub.data.repository.GeneratedMediaAssetRegistration
import me.rerere.rikkahub.data.repository.MediaAssetIds
import me.rerere.rikkahub.data.repository.MediaAssetRepository
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun buildImageGenerationTool(
    settingsStore: SettingsStore,
    gateway: ImageGenerationGateway,
    filesManager: FilesManager,
    chatImageTaskController: ChatImageGenerationTaskController,
    mediaAssetRepository: MediaAssetRepository,
): Tool = Tool(
    name = "generate_image",
    description = """
        Generate an image in the current conversation with the image model selected in the app.
        Call this only when the user explicitly asks to draw, create, or generate an image.
        Do not call it merely to analyze or discuss an existing image.
        Write a complete standalone visual prompt; the returned image is shown directly to the user.
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
                    put("description", "A complete, detailed prompt for the image model")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("description", "Image size such as auto, 1024x1024, 1536x1024, or 1024x1536")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of image variants to generate, from 1 to 8")
                    put("minimum", 1)
                    put("maximum", 8)
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
        executeImageGeneration(
            input = input,
            context = null,
            settingsStore = settingsStore,
            gateway = gateway,
            filesManager = filesManager,
            chatImageTaskController = chatImageTaskController,
            mediaAssetRepository = mediaAssetRepository,
        )
    },
    executeWithContext = { input, context ->
        executeImageGeneration(
            input = input,
            context = context,
            settingsStore = settingsStore,
            gateway = gateway,
            filesManager = filesManager,
            chatImageTaskController = chatImageTaskController,
            mediaAssetRepository = mediaAssetRepository,
        )
    },
)

private suspend fun executeImageGeneration(
    input: kotlinx.serialization.json.JsonElement,
    context: ToolExecutionContext?,
    settingsStore: SettingsStore,
    gateway: ImageGenerationGateway,
    filesManager: FilesManager,
    chatImageTaskController: ChatImageGenerationTaskController,
    mediaAssetRepository: MediaAssetRepository,
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
        val count = arguments["count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 8) ?: 1
        val settings = settingsStore.settingsFlow.value
        val model = settings.resolveImageGenerationModel()
            ?: error("No image generation model is selected in the app settings")

        val catalog = buildReferenceImageCatalog(context?.messages.orEmpty())
        val requestedReferenceIds = arguments["reference_image_ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val selectedReferences = if (requestedReferenceIds.isNotEmpty()) {
            val byId = catalog.associateBy(ReferenceImage::id)
            requestedReferenceIds.mapNotNull(byId::get)
        } else {
            latestUserReferenceImages(context?.messages.orEmpty())
        }
        val referencePaths = selectedReferences.mapNotNull { it.url.toLocalImagePath() }
        if (requestedReferenceIds.isNotEmpty() && referencePaths.isEmpty()) {
            error("The selected reference images are no longer available")
        }

        val startedAt = System.currentTimeMillis()
        val requestId = context?.executionRequestId?.takeIf(String::isNotBlank)
            ?: context?.toolCallId?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        val attempt = 1
        val providerId = model.findProvider(settings.providers)?.id?.toString()
        val reservedAssetIds = List(count) { index ->
            MediaAssetIds.forChatToolOutput(requestId, index)
        }
        val referencedAssetId = selectedReferences.singleOrNull()?.assetId
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
        val slots = MutableList(count) { index ->
            ChatImageGenerationSlot(
                index = index,
                status = ChatImageSlotStatus.QUEUED,
                requestId = "$requestId:$index",
                attempt = attempt,
            )
        }
        val slotImages = MutableList<UIMessagePart.Image?>(count) { null }
        val committedAssetIds = MutableList<String?>(count) { null }
        val stateMutex = Mutex()
        val durableTaskId = context?.contextId
            ?.takeIf(String::isNotBlank)
            ?.let { requestId }

        suspend fun snapshot(finishedAt: Long? = null): ChatImageGenerationState = stateMutex.withLock {
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
        }

        try {
            if (durableTaskId != null) {
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
                        prompt = prompt,
                        mediaOrigin = assetOrigin,
                        parentAssetId = parentAssetId,
                        requestedImageCount = count,
                        reservedOutputAssetIds = reservedAssetIds,
                        startedAtEpochMillis = startedAt,
                    ),
                    cancelExecution = {
                        ownerJob?.cancel(CancellationException("Image generation cancelled from notification"))
                    },
                )
            }

            context?.emitProgress(listOf(snapshot().toStatusPart()))

            val semaphore = Semaphore(2)
            val taskExecutor = ImageGenerationTaskExecutor(gateway)
            coroutineScope {
                val updates = Channel<Unit>(Channel.UNLIMITED)
                val jobs = slots.indices.map { index ->
                    async {
                        semaphore.withPermit {
                            val slotStartedAt = System.currentTimeMillis()
                            stateMutex.withLock {
                                slots[index] = slots[index].copy(
                                    status = ChatImageSlotStatus.RUNNING,
                                    startedAtEpochMillis = slotStartedAt,
                                )
                            }
                            updates.trySend(Unit)

                            try {
                                taskExecutor.execute(
                                    execution = ImageGenerationExecution(
                                        requestId = slots[index].requestId,
                                        attempt = slots[index].attempt,
                                        request = ImageGenerationRequest(
                                            prompt = prompt,
                                            modelId = model.id.toString(),
                                            modelName = model.displayName,
                                            providerId = providerId,
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
                                            val image = saveToolImage(
                                                filesManager = filesManager,
                                                mediaAssetRepository = mediaAssetRepository,
                                                base64Data = event.item.data,
                                                mimeType = event.item.mimeType,
                                                index = index,
                                                registration = GeneratedMediaAssetRegistration(
                                                    assetId = reservedAssetIds[index],
                                                    origin = assetOrigin,
                                                    modelId = model.id.toString(),
                                                    modelDisplayName = model.displayName,
                                                    providerId = providerId,
                                                    prompt = prompt,
                                                    createdAt = startedAt,
                                                    conversationId = context?.contextId,
                                                    toolCallId = context?.toolCallId
                                                        ?.takeIf(String::isNotBlank)
                                                        ?: requestId,
                                                    parentAssetId = parentAssetId,
                                                ),
                                            )
                                            val finishedAt = System.currentTimeMillis()
                                            stateMutex.withLock {
                                                slotImages[index] = image
                                                committedAssetIds[index] = image.assetId
                                                slots[index] = slots[index].copy(
                                                    status = ChatImageSlotStatus.SUCCEEDED,
                                                    imageUrl = image.url,
                                                    finishedAtEpochMillis = finishedAt,
                                                )
                                            }
                                        }
                                        is ImageGenerationExecutionEvent.Succeeded -> Unit
                                        is ImageGenerationExecutionEvent.Failed -> stateMutex.withLock {
                                            slots[index] = slots[index].copy(
                                                status = ChatImageSlotStatus.FAILED,
                                                error = event.failure.message.take(160),
                                                failureKind = event.failure.kind,
                                                finishedAtEpochMillis = System.currentTimeMillis(),
                                            )
                                        }
                                        is ImageGenerationExecutionEvent.Cancelled -> stateMutex.withLock {
                                            slots[index] = slots[index].copy(
                                                status = ChatImageSlotStatus.CANCELLED,
                                                finishedAtEpochMillis = System.currentTimeMillis(),
                                            )
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                val finishedAt = System.currentTimeMillis()
                                stateMutex.withLock {
                                    slots[index] = slots[index].copy(
                                        status = ChatImageSlotStatus.CANCELLED,
                                        finishedAtEpochMillis = finishedAt,
                                    )
                                }
                                throw cancelled
                            } catch (error: Throwable) {
                                val finishedAt = System.currentTimeMillis()
                                stateMutex.withLock {
                                    slots[index] = slots[index].copy(
                                        status = ChatImageSlotStatus.FAILED,
                                        error = error.message?.take(160) ?: error.javaClass.simpleName,
                                        failureKind = ImageGenerationFailureKind.UNKNOWN,
                                        finishedAtEpochMillis = finishedAt,
                                    )
                                }
                            } finally {
                                updates.trySend(Unit)
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
                for (ignored in updates) {
                    val progressState = snapshot()
                    durableTaskId?.let { taskId ->
                        chatImageTaskController.updateProgress(
                            taskId = taskId,
                            completedImageCount = progressState.succeededCount,
                            failedImageCount = progressState.failedCount,
                            outputAssetIds = stateMutex.withLock { committedAssetIds.filterNotNull() },
                        )
                    }
                    context?.emitProgress(
                        buildList {
                            add(progressState.toStatusPart())
                            stateMutex.withLock { slotImages.filterNotNull().forEach(::add) }
                        },
                    )
                }
            }

            val completedAt = System.currentTimeMillis()
            val finalState = snapshot(finishedAt = completedAt)
            durableTaskId?.let { taskId ->
                when {
                    finalState.succeededCount > 0 -> chatImageTaskController.complete(taskId)
                    finalState.slots.all { it.status == ChatImageSlotStatus.CANCELLED } -> {
                        chatImageTaskController.cancelled(taskId)
                    }
                    else -> {
                        val failure = finalState.slots.firstOrNull { it.failureKind != null }
                        chatImageTaskController.fail(
                            taskId = taskId,
                            errorKind = failure?.failureKind ?: ImageGenerationFailureKind.UNKNOWN,
                            errorMessage = failure?.error ?: "Image generation did not return an image",
                        )
                    }
                }
            }
            return buildList {
                add(finalState.toStatusPart())
                slotImages.filterNotNull().forEach(::add)
            }
        } catch (cancelled: CancellationException) {
            durableTaskId?.let(chatImageTaskController::cancelled)
            throw cancelled
        } catch (error: Throwable) {
            durableTaskId?.let { taskId ->
                chatImageTaskController.fail(
                    taskId = taskId,
                    errorKind = (error as? ImageGenerationException)?.kind ?: ImageGenerationFailureKind.UNKNOWN,
                    errorMessage = error.message ?: "Image generation failed",
                )
            }
            throw error
        }
}

private data class ReferenceImage(
    val id: String,
    val url: String,
    val role: MessageRole,
    val assetId: String? = null,
)

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

@OptIn(ExperimentalEncodingApi::class)
private suspend fun saveToolImage(
    filesManager: FilesManager,
    mediaAssetRepository: MediaAssetRepository,
    base64Data: String,
    mimeType: String,
    index: Int,
    registration: GeneratedMediaAssetRegistration,
): UIMessagePart.Image {
    val normalizedMime = mimeType.ifBlank { "image/png" }
    val extension = when (normalizedMime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }
    val rawData = base64Data.substringAfter("base64,", base64Data)
    val managed = try {
        filesManager.saveManagedFromBytesWithIdentity(
            // Final generated images are conversation assets. TOOL_OUTPUTS is an
            // intentionally ephemeral workspace and is deleted on app startup.
            folder = FileFolders.CHAT_GENERATED_IMAGES,
            bytes = Base64.decode(rawData),
            assetId = registration.assetId,
            displayName = "generated_image_${index + 1}.$extension",
            mimeType = normalizedMime,
            createdAt = registration.createdAt,
        )
    } catch (error: Throwable) {
        throw ImageGenerationException(
            kind = ImageGenerationFailureKind.IMAGE_WRITE,
            message = "The generated image could not be saved",
            cause = error,
        )
    }
    val file = filesManager.getFile(managed)
    val asset = try {
        mediaAssetRepository.registerGeneratedAsset(
            managedFile = managed,
            file = file,
            registration = registration,
        )
    } catch (error: Throwable) {
        throw ImageGenerationException(
            kind = ImageGenerationFailureKind.DATABASE_WRITE,
            message = "The generated image was saved but could not be registered in the image library",
            cause = error,
        )
    }
    return UIMessagePart.Image(
        url = file.toUri().toString(),
        assetId = asset.assetId,
    )
}

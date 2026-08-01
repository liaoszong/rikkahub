package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.UUID

interface ImageGenerationForegroundController {
    fun start(taskId: String)
}

class ImageGenerationTaskManager(
    private val scope: CoroutineScope,
    private val gateway: ImageGenerationGateway,
    private val resultStore: ImageGenerationResultStore,
    private val taskStore: ImageGenerationTaskStore,
    private val foregroundController: ImageGenerationForegroundController,
    private val executionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val initialTask = restoreInitialTask()
    private val _task = MutableStateFlow(initialTask)
    val task: StateFlow<ImageGenerationTask> = _task.asStateFlow()

    private var activeJob: Job? = null
    @Volatile
    private var userCancellationRequested = false

    @Synchronized
    fun start(request: ImageGenerationRequest): ImageGenerationStartResult {
        if (_task.value.isActive || activeJob?.isActive == true) {
            return ImageGenerationStartResult.ALREADY_RUNNING
        }

        val newTask = ImageGenerationTask(
            taskId = idGenerator(),
            prompt = request.prompt,
            modelId = request.modelId,
            modelName = request.modelName,
            size = request.size,
            numberOfImages = request.numberOfImages,
            startedAt = clock(),
            phase = ImageGenerationPhase.RUNNING,
        )
        userCancellationRequested = false
        try {
            updateTask(newTask)
        } catch (error: Exception) {
            _task.value = newTask.copy(
                phase = ImageGenerationPhase.FAILED,
                finishedAt = clock(),
                errorKind = ImageGenerationFailureKind.CONFIGURATION,
                errorMessage = "Image generation was not started because its state could not be saved",
            )
            return ImageGenerationStartResult.STATE_PERSISTENCE_UNAVAILABLE
        }
        try {
            foregroundController.start(newTask.taskId)
        } catch (error: Exception) {
            updateTaskBestEffort(
                newTask.copy(
                    phase = ImageGenerationPhase.FAILED,
                    finishedAt = clock(),
                    errorKind = ImageGenerationFailureKind.CONFIGURATION,
                    errorMessage = "Unable to start the required background notification service",
                ),
            )
            return ImageGenerationStartResult.FOREGROUND_SERVICE_UNAVAILABLE
        }
        activeJob = scope.launch(executionDispatcher) {
            runTask(newTask, request)
        }
        return ImageGenerationStartResult.STARTED
    }

    @Synchronized
    fun cancel() {
        if (!_task.value.isActive) return
        userCancellationRequested = true
        activeJob?.cancel(CancellationException("Image generation cancelled by the user"))
    }

    @Synchronized
    fun clearCompletedTask(): Boolean {
        if (_task.value.isActive) return false
        _task.value.images.filter { it.isPreview }.forEach(resultStore::deletePreview)
        return clearTask()
    }

    fun dismissError(taskId: String) {
        val current = _task.value
        if (current.taskId == taskId && current.errorMessage != null) {
            updateTaskBestEffort(current.copy(errorMessage = null))
        }
    }

    private suspend fun runTask(
        startingTask: ImageGenerationTask,
        request: ImageGenerationRequest,
    ) {
        val previews = mutableMapOf<Int, GeneratedImage>()
        val finalImages = mutableListOf<GeneratedImage>()
        var finalIndex = 0
        try {
            gateway.generate(request).collect { item ->
                if (item.partial) {
                    val previewIndex = item.partialImageIndex ?: finalIndex
                    previews.remove(previewIndex)?.let(resultStore::deletePreview)
                    previews[previewIndex] = resultStore.savePreview(
                        task = startingTask,
                        item = item,
                        index = previewIndex,
                    )
                    updateTask(
                        startingTask.copy(
                            phase = ImageGenerationPhase.PREVIEW_AVAILABLE,
                            images = finalImages + previews.toSortedMap().values,
                        ),
                    )
                } else {
                    previews.remove(finalIndex)?.let(resultStore::deletePreview)
                    val image = resultStore.saveFinal(
                        task = startingTask,
                        item = item,
                        index = finalIndex,
                        sourcePaths = request.referenceImages,
                    )
                    finalImages += image
                    finalIndex++
                    updateTask(
                        startingTask.copy(
                            phase = if (previews.isEmpty()) {
                                ImageGenerationPhase.RUNNING
                            } else {
                                ImageGenerationPhase.PREVIEW_AVAILABLE
                            },
                            images = finalImages + previews.toSortedMap().values,
                        ),
                    )
                }
            }
            if (finalImages.isEmpty()) {
                throw ImageGenerationException(
                    ImageGenerationFailureKind.RESPONSE_PARSE,
                    "The provider returned no generated images",
                )
            }
            updateTask(
                startingTask.copy(
                    phase = ImageGenerationPhase.COMPLETED,
                    finishedAt = clock(),
                    images = finalImages.toList(),
                ),
            )
        } catch (error: CancellationException) {
            previews.values.forEach(resultStore::deletePreview)
            val current = _task.value
            updateTaskBestEffort(
                current.copy(
                    phase = if (userCancellationRequested) {
                        ImageGenerationPhase.CANCELLED
                    } else {
                        ImageGenerationPhase.INTERRUPTED
                    },
                    finishedAt = clock(),
                    errorKind = if (userCancellationRequested) {
                        ImageGenerationFailureKind.USER_CANCELLED
                    } else {
                        ImageGenerationFailureKind.PROCESS_INTERRUPTED
                    },
                    errorMessage = if (userCancellationRequested) {
                        "Image generation was cancelled"
                    } else {
                        "Image generation was interrupted and was not retried"
                    },
                    images = finalImages.toList(),
                ),
            )
        } catch (error: Throwable) {
            previews.values.forEach(resultStore::deletePreview)
            val (kind, message) = classifyFailure(error)
            val preservedImages = if (error is ImageGenerationException && error.recoveredImage != null) {
                finalImages + error.recoveredImage
            } else {
                finalImages.toList()
            }
            updateTaskBestEffort(
                _task.value.copy(
                    phase = ImageGenerationPhase.FAILED,
                    finishedAt = clock(),
                    errorKind = kind,
                    errorMessage = message,
                    images = preservedImages,
                ),
            )
        } finally {
            synchronized(this) {
                activeJob = null
                userCancellationRequested = false
            }
        }
    }

    private fun classifyFailure(error: Throwable): Pair<ImageGenerationFailureKind, String> {
        if (error is ImageGenerationException) {
            return error.kind to (error.message ?: "Image generation failed")
        }
        return when (error) {
            is IOException -> ImageGenerationFailureKind.NETWORK to
                "The network connection was interrupted: ${error.message ?: "unknown I/O error"}"
            is SerializationException -> ImageGenerationFailureKind.RESPONSE_PARSE to
                "The image response could not be parsed"
            is IllegalStateException -> ImageGenerationFailureKind.SERVER to
                (error.message ?: "The image provider returned an error")
            else -> ImageGenerationFailureKind.UNKNOWN to
                (error.message ?: "Image generation failed")
        }
    }

    private fun restoreInitialTask(): ImageGenerationTask {
        val restored = taskStore.load() ?: return ImageGenerationTask.Idle
        if (!restored.isActive) return restored

        restored.images.filter { it.isPreview }.forEach(resultStore::deletePreview)
        return restored.copy(
            phase = ImageGenerationPhase.INTERRUPTED,
            finishedAt = clock(),
            errorKind = ImageGenerationFailureKind.PROCESS_INTERRUPTED,
            errorMessage = "The app process stopped before the response was saved. The request was not retried.",
            images = restored.images.filterNot { it.isPreview },
        ).also { interrupted ->
            runCatching { taskStore.save(interrupted) }
        }
    }

    private fun updateTask(task: ImageGenerationTask) {
        taskStore.save(task)
        _task.value = task
    }

    private fun updateTaskBestEffort(task: ImageGenerationTask) {
        runCatching { taskStore.save(task) }
        _task.value = task
    }

    private fun clearTask(): Boolean {
        if (runCatching { taskStore.clear() }.isFailure) return false
        _task.value = ImageGenerationTask.Idle
        return true
    }
}

package me.rerere.rikkahub.ui.pages.imggen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ImageGenSize
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.imggen.GeneratedImage
import me.rerere.rikkahub.data.imggen.ImageGenerationRequest
import me.rerere.rikkahub.data.imggen.ImageGenerationStartResult
import me.rerere.rikkahub.data.imggen.ImageGenerationTask
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val fullPath = File(filesManager.getImagesDir(), path.removePrefix("images/")).absolutePath
    return GeneratedImage(
        id = id,
        prompt = prompt,
        filePath = fullPath,
        timestamp = createAt,
        model = modelId,
    )
}

class ImgGenVM(
    val settingsStore: SettingsStore,
    private val taskManager: ImageGenerationTaskManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    private val restoredTask = taskManager.task.value
    private val _prompt = MutableStateFlow(restoredTask.prompt)
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(restoredTask.numberOfImages.takeIf { it > 0 } ?: 1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(restoredTask.size.ifBlank { ImageGenSize.AUTO.value })
    val size: StateFlow<String> = _size

    val task: StateFlow<ImageGenerationTask> = taskManager.task
    val isGenerating: StateFlow<Boolean> = task
        .map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.Eagerly, task.value.isActive)

    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = task
        .map { it.images }
        .stateIn(viewModelScope, SharingStarted.Eagerly, task.value.images)

    private val _localError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = combine(task, _localError) { currentTask, localError ->
        localError ?: currentTask.errorMessage
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        _localError.value ?: task.value.errorMessage,
    )

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() },
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData -> pagingData.map { it.toGeneratedImage(filesManager) } }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    fun addReferenceImages(paths: List<String>) {
        if (task.value.isActive) return
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        if (task.value.isActive) return
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        if (task.value.isActive) return
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _localError.value = null
        taskManager.dismissError(task.value.taskId)
    }

    fun startNewSession() {
        if (task.value.isActive) {
            _localError.value = "An image generation task is still running. It was not cancelled."
            return
        }
        if (!taskManager.clearCompletedTask()) {
            _localError.value = "The previous image generation state could not be cleared"
            return
        }
        clearReferenceImages()
        _prompt.value = ""
        _numberOfImages.value = 1
        _size.value = ImageGenSize.AUTO.value
        _localError.value = null
    }

    fun generateImage() {
        submit(referenceImages = emptyList())
    }

    fun editImage() {
        if (_referenceImages.value.isEmpty()) return
        submit(referenceImages = _referenceImages.value)
    }

    fun cancelGeneration() {
        taskManager.cancel()
    }

    fun deleteImage(image: GeneratedImage) {
        if (image.isPreview) return
        viewModelScope.launch {
            try {
                genMediaRepository.deleteMedia(image.id)
                File(image.filePath).takeIf(File::exists)?.delete()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to delete image", error)
                _localError.value = "Failed to delete image"
            }
        }
    }

    private fun submit(referenceImages: List<String>) {
        val requestPrompt = _prompt.value
        if (requestPrompt.isBlank()) return

        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.imageGenerationModelId)
        if (model == null) {
            _localError.value = "No image generation model is selected"
            return
        }
        val result = taskManager.start(
            ImageGenerationRequest(
                prompt = requestPrompt,
                modelId = model.id.toString(),
                modelName = model.displayName,
                size = _size.value,
                numberOfImages = _numberOfImages.value,
                referenceImages = referenceImages,
            ),
        )
        _localError.value = when (result) {
            ImageGenerationStartResult.STARTED -> null
            ImageGenerationStartResult.ALREADY_RUNNING ->
                "An image generation task is already running"
            ImageGenerationStartResult.FOREGROUND_SERVICE_UNAVAILABLE ->
                "Unable to start image generation in the background"
            ImageGenerationStartResult.STATE_PERSISTENCE_UNAVAILABLE ->
                "Image generation was not started because its recovery state could not be saved"
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                File(path).takeIf(File::exists)?.delete()
            }
        }
    }

    private companion object {
        const val TAG = "ImgGenVM"
        const val MAX_REFERENCE_IMAGES = 16
    }
}

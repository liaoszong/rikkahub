package me.rerere.rikkahub.ui.pages.imggen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.logSafeError
import java.io.File

data class ImageLibraryItem(
    val assetId: String,
    val legacyId: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String,
    val modelId: String,
    val modelDisplayName: String,
    val providerId: String?,
    val mimeType: String,
    val origin: String,
    val conversationId: String?,
    val messageNodeId: String?,
    val toolCallId: String?,
    val parentAssetId: String?,
    val displayName: String,
    val mediaKind: String,
    val sizeBytes: Long,
    val storageState: String,
    val fileExists: Boolean,
)

internal fun MediaAssetEntity.toImageLibraryItem(resolvePath: (String) -> File): ImageLibraryItem {
    val resolvedFile = resolvePath(path)
    return ImageLibraryItem(
        assetId = assetId,
        legacyId = id,
        prompt = prompt,
        filePath = resolvedFile.absolutePath,
        timestamp = createAt,
        model = modelDisplayName ?: modelId,
        modelId = modelId,
        modelDisplayName = modelDisplayName ?: modelId,
        providerId = providerId,
        mimeType = mimeType,
        origin = origin,
        conversationId = conversationId,
        messageNodeId = messageNodeId,
        toolCallId = toolCallId,
        parentAssetId = parentAssetId,
        displayName = displayName.ifBlank { resolvedFile.name },
        mediaKind = mediaKind,
        sizeBytes = sizeBytes,
        storageState = storageState,
        fileExists = resolvedFile.isFile,
    )
}

class ImgGenVM(
    private val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    private val _localError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _localError.asStateFlow()

    private val imagesPager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getLibraryImages() },
    )
    val generatedImages: Flow<PagingData<ImageLibraryItem>> = imagesPager.flow
        .map { pagingData ->
            pagingData.map { asset ->
                asset.toImageLibraryItem(filesManager::resolveManagedFile)
            }
        }
        .cachedIn(viewModelScope)

    private val attachmentsPager = Pager(
        config = PagingConfig(pageSize = 30, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getLibraryAttachments() },
    )
    val attachments: Flow<PagingData<ImageLibraryItem>> = attachmentsPager.flow
        .map { pagingData ->
            pagingData.map { asset -> asset.toImageLibraryItem(filesManager::resolveManagedFile) }
        }
        .cachedIn(viewModelScope)

    fun clearError() {
        _localError.value = null
    }

    fun hideImage(image: ImageLibraryItem) {
        viewModelScope.launch {
            try {
                if (!genMediaRepository.hideAsset(image.assetId)) {
                    _localError.value = "Image is no longer available in the library"
                }
            } catch (error: Exception) {
                logSafeError(TAG, "image_library", "hide_asset", error, requestId = image.assetId)
                _localError.value = "Failed to remove image from library"
            }
        }
    }

    private companion object {
        const val TAG = "ImgGenVM"
    }

    fun hideAsset(asset: ImageLibraryItem) = hideImage(asset)
}

typealias ImageLibraryVM = ImgGenVM

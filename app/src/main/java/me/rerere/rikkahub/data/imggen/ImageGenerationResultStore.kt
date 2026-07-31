package me.rerere.rikkahub.data.imggen

import android.content.Context
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File

interface ImageGenerationResultStore {
    suspend fun savePreview(
        task: ImageGenerationTask,
        item: ImageGenerationItem,
        index: Int,
    ): GeneratedImage

    suspend fun saveFinal(
        task: ImageGenerationTask,
        item: ImageGenerationItem,
        index: Int,
        sourcePaths: List<String>,
    ): GeneratedImage

    fun deletePreview(image: GeneratedImage)
}

class LocalImageGenerationResultStore(
    private val context: Context,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
) : ImageGenerationResultStore {
    override suspend fun savePreview(
        task: ImageGenerationTask,
        item: ImageGenerationItem,
        index: Int,
    ): GeneratedImage {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(context.appTempFolder, "imggen_${task.taskId}_$index.png")
        val createdFile = try {
            filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
        } catch (error: Exception) {
            throw ImageGenerationException(
                ImageGenerationFailureKind.IMAGE_WRITE,
                "Failed to save the image preview",
                error,
            )
        }
        return GeneratedImage(
            id = 0,
            prompt = task.prompt,
            filePath = createdFile.absolutePath,
            timestamp = timestamp,
            model = task.modelName,
            isPreview = true,
        )
    }

    override suspend fun saveFinal(
        task: ImageGenerationTask,
        item: ImageGenerationItem,
        index: Int,
        sourcePaths: List<String>,
    ): GeneratedImage {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(filesManager.getImagesDir(), "${task.taskId}_$index.png")
        val createdFile = try {
            filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
        } catch (error: Exception) {
            throw ImageGenerationException(
                ImageGenerationFailureKind.IMAGE_WRITE,
                "Failed to save the generated image",
                error,
            )
        }

        val entity = GenMediaEntity(
            path = "images/${createdFile.name}",
            modelId = task.modelName,
            prompt = task.prompt,
            createAt = timestamp,
            type = if (sourcePaths.isEmpty()) {
                GenMediaEntity.TYPE_IMAGE_GENERATION
            } else {
                GenMediaEntity.TYPE_IMAGE_EDIT
            },
            sourcePaths = sourcePaths.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
        val id = try {
            genMediaRepository.insertMedia(entity).toInt()
        } catch (error: Exception) {
            val recoveredImage = GeneratedImage(
                id = 0,
                prompt = task.prompt,
                filePath = createdFile.absolutePath,
                timestamp = timestamp,
                model = task.modelName,
            )
            throw ImageGenerationException(
                ImageGenerationFailureKind.DATABASE_WRITE,
                "The image was saved, but could not be added to the gallery",
                error,
                recoveredImage,
            )
        }
        return GeneratedImage(
            id = id,
            prompt = task.prompt,
            filePath = createdFile.absolutePath,
            timestamp = timestamp,
            model = task.modelName,
        )
    }

    override fun deletePreview(image: GeneratedImage) {
        if (image.isPreview) {
            File(image.filePath).delete()
        }
    }
}

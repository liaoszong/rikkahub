package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String,
    val isPreview: Boolean = false,
)

@Serializable
enum class ImageGenerationPhase {
    IDLE,
    RUNNING,
    PREVIEW_AVAILABLE,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

@Serializable
enum class ImageGenerationFailureKind {
    NETWORK,
    USER_CANCELLED,
    PROCESS_INTERRUPTED,
    SERVER,
    RESPONSE_PARSE,
    IMAGE_WRITE,
    DATABASE_WRITE,
    CONFIGURATION,
    UNKNOWN,
}

@Serializable
data class ImageGenerationTask(
    val taskId: String,
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val size: String,
    val numberOfImages: Int,
    val startedAt: Long,
    val phase: ImageGenerationPhase,
    val errorKind: ImageGenerationFailureKind? = null,
    val errorMessage: String? = null,
    val images: List<GeneratedImage> = emptyList(),
) {
    val isActive: Boolean
        get() = phase == ImageGenerationPhase.RUNNING ||
            phase == ImageGenerationPhase.PREVIEW_AVAILABLE

    companion object {
        val Idle = ImageGenerationTask(
            taskId = "",
            prompt = "",
            modelId = "",
            modelName = "",
            size = "",
            numberOfImages = 0,
            startedAt = 0,
            phase = ImageGenerationPhase.IDLE,
        )
    }
}

data class ImageGenerationRequest(
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val size: String,
    val numberOfImages: Int,
    val referenceImages: List<String> = emptyList(),
)

enum class ImageGenerationStartResult {
    STARTED,
    ALREADY_RUNNING,
    FOREGROUND_SERVICE_UNAVAILABLE,
    STATE_PERSISTENCE_UNAVAILABLE,
}

class ImageGenerationException(
    val kind: ImageGenerationFailureKind,
    message: String,
    cause: Throwable? = null,
    val recoveredImage: GeneratedImage? = null,
) : Exception(message, cause)

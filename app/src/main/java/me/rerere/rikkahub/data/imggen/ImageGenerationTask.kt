package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    /** Legacy display field retained so persisted task JSON from older builds still decodes. */
    val model: String,
    /** Stable configured model identity; old records fall back to the legacy [model] value. */
    val modelId: String = model,
    /** User-facing model label; old records fall back to the legacy [model] value. */
    val modelDisplayName: String = model,
    /** Stable configured provider identity when available; absent in legacy records. */
    val providerId: String? = null,
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
    val providerId: String? = null,
    val size: String,
    val numberOfImages: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val phase: ImageGenerationPhase,
    /** Stable identity for the paid provider request represented by this task. */
    val requestId: String = taskId,
    /** A retry must create a new attempt instead of silently replaying attempt 1. */
    val attempt: Int = 1,
    val errorKind: ImageGenerationFailureKind? = null,
    val errorMessage: String? = null,
    val images: List<GeneratedImage> = emptyList(),
) {
    val isActive: Boolean
        get() = phase == ImageGenerationPhase.RUNNING ||
            phase == ImageGenerationPhase.PREVIEW_AVAILABLE

    val durationMillis: Long?
        get() = finishedAt?.let { (it - startedAt).coerceAtLeast(0) }

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
    val providerId: String? = null,
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

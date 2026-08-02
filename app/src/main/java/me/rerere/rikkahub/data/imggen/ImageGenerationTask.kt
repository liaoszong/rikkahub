package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable

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

data class ImageGenerationRequest(
    val requestId: String = "",
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val providerId: String? = null,
    val size: String,
    val numberOfImages: Int,
    val referenceImages: List<String> = emptyList(),
)

class ImageGenerationException(
    val kind: ImageGenerationFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

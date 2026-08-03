package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderDispatchObserver

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
    /** Stable identity for the concrete paid attempt, not the long-lived logical request. */
    val providerRequestId: String? = null,
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val providerId: String? = null,
    val size: String,
    val numberOfImages: Int,
    val referenceImages: List<String> = emptyList(),
    /** Runtime-only callback fired at the provider transport handoff boundary. */
    val dispatchObserver: ProviderDispatchObserver = ProviderDispatchObserver.NONE,
)

class ImageGenerationException(
    val kind: ImageGenerationFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

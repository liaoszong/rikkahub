package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderDispatchObserver

enum class ImageGenerationCredentialTarget {
    PROVIDER_API_KEY,
    GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY,
    MODEL_CUSTOM_HEADER,
}

/**
 * Privacy-minimal runtime proof describing which Vault entry must authenticate this paid attempt.
 * It contains a stable reference and slot coordinates, never the credential value.
 */
data class ImageGenerationCredentialEvidence(
    val reference: String,
    val namespace: String,
    val ownerStableId: String,
    val fieldSlot: String,
    val kind: String,
    val target: ImageGenerationCredentialTarget,
    val customHeaderId: String? = null,
) {
    init {
        require(reference.isNotBlank())
        require(namespace.isNotBlank())
        require(ownerStableId.isNotBlank())
        require(fieldSlot.isNotBlank())
        require(kind.isNotBlank())
        require(target != ImageGenerationCredentialTarget.MODEL_CUSTOM_HEADER || !customHeaderId.isNullOrBlank())
    }
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

data class ImageGenerationRequest(
    val requestId: String = "",
    /** Stable identity for the concrete paid attempt, not the long-lived logical request. */
    val providerRequestId: String? = null,
    val prompt: String,
    val modelId: String,
    val modelName: String,
    val providerId: String? = null,
    /** Exact Credential Vault proof frozen into the child RequestLedger row. */
    val credentialEvidence: ImageGenerationCredentialEvidence? = null,
    /** Digest of the effective provider/model transport after applying [credentialEvidence]. */
    val transportConfigurationDigest: String? = null,
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

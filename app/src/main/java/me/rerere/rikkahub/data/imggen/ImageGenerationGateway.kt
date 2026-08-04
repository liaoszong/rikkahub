package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.credential.CredentialSettingsAddress
import me.rerere.rikkahub.data.credential.CredentialSettingsResolveResult
import me.rerere.rikkahub.data.credential.CredentialVaultProjectionStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

interface ImageGenerationGateway {
    suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem>
}

internal class ProviderImageGenerationGateway(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val credentialStore: CredentialVaultProjectionStore,
) : ImageGenerationGateway {
    override suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem> {
        settingsStore.awaitCredentialReady()
        val settings = settingsStore.settingsFlow.value
        val executionSettings = resolveImageGenerationExecutionSettings(
            settings = settings,
            request = request,
            resolveSecret = ::resolveFrozenCredential,
        )
        val model = executionSettings.model
        val providerSetting = executionSettings.provider
        val providerClient = providerManager.getProviderByType(providerSetting)

        return if (request.referenceImages.isEmpty()) {
            providerClient.generateImage(
                providerSetting = providerSetting,
                params = ImageGenerationParams(
                    model = model,
                    prompt = request.prompt,
                    numOfImages = request.numberOfImages,
                    size = request.size,
                    requestId = request.providerRequestId?.takeIf(String::isNotBlank)
                        ?: request.requestId.takeIf(String::isNotBlank),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    dispatchObserver = request.dispatchObserver,
                ),
            )
        } else {
            providerClient.editImage(
                providerSetting = providerSetting,
                params = ImageEditParams(
                    model = model,
                    prompt = request.prompt,
                    images = request.referenceImages,
                    numOfImages = request.numberOfImages,
                    size = request.size,
                    requestId = request.providerRequestId?.takeIf(String::isNotBlank)
                        ?: request.requestId.takeIf(String::isNotBlank),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    dispatchObserver = request.dispatchObserver,
                ),
            )
        }
    }

    private fun resolveFrozenCredential(evidence: ImageGenerationCredentialEvidence): String {
        val proof = credentialStore.inspectBinding(evidence.reference)
            ?: throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The credential frozen by RequestLedger is no longer available",
            )
        if (proof.slotId != evidence.slotId()) {
            throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The frozen image credential belongs to a different Vault slot",
            )
        }
        val address = CredentialSettingsAddress(
            namespace = evidence.namespace,
            ownerStableId = evidence.ownerStableId,
            fieldSlot = evidence.fieldSlot,
            kind = evidence.kind,
            audience = proof.audience,
        )
        return when (val resolved = credentialStore.resolve(evidence.reference, address)) {
            is CredentialSettingsResolveResult.Found -> resolved.secret.jsonPrimitive.contentOrNull
                ?: throw ImageGenerationException(
                    ImageGenerationFailureKind.CONFIGURATION,
                    "The frozen image credential is not a string",
                )
            CredentialSettingsResolveResult.Missing -> throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The credential frozen by RequestLedger is missing",
            )
            is CredentialSettingsResolveResult.Locked -> throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The credential frozen by RequestLedger is locked",
            )
            is CredentialSettingsResolveResult.Corrupt -> throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The credential frozen by RequestLedger is corrupt",
            )
        }
    }
}

internal data class ImageGenerationExecutionSettings(
    val model: me.rerere.ai.provider.Model,
    val provider: me.rerere.ai.provider.ProviderSetting,
)

/**
 * Reconstructs the paid transport from the frozen Vault proof, then verifies it against the digest
 * captured by RequestLedger. This runs before a provider adapter is allowed to create network I/O.
 */
internal suspend fun resolveImageGenerationExecutionSettings(
    settings: Settings,
    request: ImageGenerationRequest,
    resolveSecret: suspend (ImageGenerationCredentialEvidence) -> String,
): ImageGenerationExecutionSettings {
    val modelId = runCatching { Uuid.parse(request.modelId) }.getOrElse {
        throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "The selected image model is invalid",
            it,
        )
    }
    val model = settings.findModelById(modelId)
        ?: throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "No image generation model is selected",
        )
    val provider = model.findProvider(settings.providers)
        ?: throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "The selected image provider was not found",
        )
    if (request.providerId != null && request.providerId != provider.id.toString()) {
        throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "The selected image provider no longer matches RequestLedger",
        )
    }
    val evidence = request.credentialEvidence
    val secret = evidence?.let { resolveSecret(it) }
    val (boundProvider, boundModel) = try {
        bindImageGenerationCredential(provider, model, evidence, secret)
    } catch (failure: Throwable) {
        throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "The frozen image credential cannot be applied",
            failure,
        )
    }
    val expectedDigest = request.transportConfigurationDigest
        ?: throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "Image request is missing its frozen transport digest",
        )
    if (imageTransportConfigurationDigest(boundModel, boundProvider) != expectedDigest) {
        throw ImageGenerationException(
            ImageGenerationFailureKind.CONFIGURATION,
            "Image provider configuration changed after RequestLedger admission",
        )
    }
    return ImageGenerationExecutionSettings(boundModel, boundProvider)
}

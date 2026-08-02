package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

interface ImageGenerationGateway {
    suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem>
}

class ProviderImageGenerationGateway(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : ImageGenerationGateway {
    override suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem> {
        val settings = settingsStore.settingsFlow.value
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
        val providerSetting = settings.providers.find { it.id == provider.id }
            ?: throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "The selected image provider is not configured",
            )
        val providerClient = providerManager.getProviderByType(provider)

        return if (request.referenceImages.isEmpty()) {
            providerClient.generateImage(
                providerSetting = providerSetting,
                params = ImageGenerationParams(
                    model = model,
                    prompt = request.prompt,
                    numOfImages = request.numberOfImages,
                    size = request.size,
                    requestId = request.requestId.takeIf(String::isNotBlank),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
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
                    requestId = request.requestId.takeIf(String::isNotBlank),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
    }
}

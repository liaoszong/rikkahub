package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

/**
 * Provider-neutral handoff hook invoked after a request has been fully built and immediately
 * before it is submitted to the HTTP transport. Durable request ledgers use this boundary to
 * conservatively record a potentially billable dispatch before network ownership changes.
 */
fun interface ProviderDispatchObserver {
    suspend fun onDispatch()

    companion object {
        val NONE = ProviderDispatchObserver { }
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    @Transient
    val dispatchObserver: ProviderDispatchObserver = ProviderDispatchObserver.NONE,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    /** Stable identity for one paid provider attempt. Compatible gateways may use it for deduplication. */
    val requestId: String? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    @Transient
    val dispatchObserver: ProviderDispatchObserver = ProviderDispatchObserver.NONE,
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    /** Stable identity for one paid provider attempt. Compatible gateways may use it for deduplication. */
    val requestId: String? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    @Transient
    val dispatchObserver: ProviderDispatchObserver = ProviderDispatchObserver.NONE,
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String,
    /** Stable credential slot identity; legacy values are persisted on the first Vault migration. */
    val id: Uuid = Uuid.random(),
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement,
    /** Stable credential slot identity; legacy values are persisted on the first Vault migration. */
    val id: Uuid = Uuid.random(),
)

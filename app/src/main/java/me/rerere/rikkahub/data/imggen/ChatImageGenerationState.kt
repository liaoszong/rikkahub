package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart

const val CHAT_IMAGE_GENERATION_PAYLOAD_TYPE = "image_generation_group"

@Serializable
enum class ChatImageSlotStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class ChatImageGenerationSlot(
    val index: Int,
    val status: ChatImageSlotStatus,
    val imageUrl: String? = null,
    val error: String? = null,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val requestId: String = "",
    val attempt: Int = 1,
    val failureKind: ImageGenerationFailureKind? = null,
)

@Serializable
data class ChatImageGenerationState(
    val type: String = CHAT_IMAGE_GENERATION_PAYLOAD_TYPE,
    val version: Int = 1,
    val requestId: String = "",
    val attempt: Int = 1,
    val prompt: String,
    val model: String,
    val size: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val referenceImageCount: Int = 0,
    val slots: List<ChatImageGenerationSlot>,
) {
    val isTerminal: Boolean
        get() = slots.isNotEmpty() && slots.all {
            it.status == ChatImageSlotStatus.SUCCEEDED ||
                it.status == ChatImageSlotStatus.FAILED ||
                it.status == ChatImageSlotStatus.CANCELLED
        }

    val succeededCount: Int get() = slots.count { it.status == ChatImageSlotStatus.SUCCEEDED }
    val failedCount: Int get() = slots.count { it.status == ChatImageSlotStatus.FAILED }
}

private val chatImageGenerationJson = Json { ignoreUnknownKeys = true }

fun ChatImageGenerationState.toStatusPart(): UIMessagePart.Text =
    UIMessagePart.Text(chatImageGenerationJson.encodeToString(this))

fun UIMessagePart.Text.parseChatImageGenerationState(): ChatImageGenerationState? =
    runCatching {
        chatImageGenerationJson.decodeFromString<ChatImageGenerationState>(text)
    }.getOrNull()?.takeIf { it.type == CHAT_IMAGE_GENERATION_PAYLOAD_TYPE }

fun List<UIMessagePart>.findChatImageGenerationState(): ChatImageGenerationState? =
    filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull(UIMessagePart.Text::parseChatImageGenerationState)

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

/**
 * Older chat-image tool outputs persisted the final [UIMessagePart.Image] values but
 * did not include the v1 status JSON. Treat durable image output as stronger evidence
 * than an absent/stale slot status so upgrades never replace real images with spinners.
 */
fun ChatImageGenerationState?.withFallbackImages(
    toolCallId: String,
    images: List<UIMessagePart.Image>,
): ChatImageGenerationState? {
    val distinctImages = images.distinctBy { image ->
        image.assetId?.takeIf(String::isNotBlank) ?: image.url
    }
    if (distinctImages.isEmpty()) return this

    if (this == null) {
        return ChatImageGenerationState(
            requestId = toolCallId,
            prompt = "",
            model = "",
            size = "auto",
            startedAtEpochMillis = 0L,
            finishedAtEpochMillis = 0L,
            slots = distinctImages.mapIndexed { index, image ->
                ChatImageGenerationSlot(
                    index = index,
                    status = ChatImageSlotStatus.SUCCEEDED,
                    imageUrl = image.url,
                    requestId = "$toolCallId:$index",
                    startedAtEpochMillis = 0L,
                    finishedAtEpochMillis = 0L,
                )
            },
        )
    }

    val recoveredSlotCount = maxOf(slots.size, distinctImages.size)
    val recoveredSlots = List(recoveredSlotCount) { index ->
        val slot = slots.getOrNull(index)
        val image = distinctImages.getOrNull(index)
        when {
            image != null -> (slot ?: ChatImageGenerationSlot(
                index = index,
                status = ChatImageSlotStatus.SUCCEEDED,
                requestId = "$toolCallId:$index",
            )).copy(
                status = ChatImageSlotStatus.SUCCEEDED,
                imageUrl = slot?.imageUrl?.takeIf(String::isNotBlank) ?: image.url,
                error = null,
                failureKind = null,
                finishedAtEpochMillis = slot?.finishedAtEpochMillis ?: finishedAtEpochMillis,
            )
            slot != null -> slot
            else -> error("Unreachable image-generation slot")
        }
    }
    return copy(
        finishedAtEpochMillis = finishedAtEpochMillis
            ?: startedAtEpochMillis.takeIf { recoveredSlots.all { slot -> slot.isTerminal } },
        slots = recoveredSlots,
    )
}

private val ChatImageGenerationSlot.isTerminal: Boolean
    get() = status == ChatImageSlotStatus.SUCCEEDED ||
        status == ChatImageSlotStatus.FAILED ||
        status == ChatImageSlotStatus.CANCELLED

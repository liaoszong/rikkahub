package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.findChatImageGenerationState

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ImageBlock(val images: List<UIMessagePart.Image>, val firstIndex: Int) : MessagePartBlock
    data class ImageGenerationBlock(
        val tool: UIMessagePart.Tool,
        val state: ChatImageGenerationState?,
        val fallbackImages: List<UIMessagePart.Image>,
        val index: Int,
    ) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(
    groupImages: Boolean = false,
    groupSingleImages: Boolean = true,
): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    var currentImages = mutableListOf<UIMessagePart.Image>()
    var firstImageIndex = -1

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    fun flushImages() {
        if (currentImages.isNotEmpty()) {
            if (currentImages.size == 1 && !groupSingleImages) {
                result.add(MessagePartBlock.ContentBlock(currentImages.single(), firstImageIndex))
            } else {
                result.add(MessagePartBlock.ImageBlock(currentImages.toList(), firstImageIndex))
            }
            currentImages = mutableListOf()
            firstImageIndex = -1
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                flushImages()
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                flushImages()
                if (part.toolName == "generate_image") {
                    flushThinkingSteps()
                    result.add(
                        MessagePartBlock.ImageGenerationBlock(
                            tool = part,
                            state = (part.output + part.progress).findChatImageGenerationState(),
                            fallbackImages = (part.output + part.progress)
                                .filterIsInstance<UIMessagePart.Image>()
                                .distinctBy { image ->
                                    image.assetId?.takeIf(String::isNotBlank) ?: image.url
                                },
                            index = index,
                        )
                    )
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.Image -> {
                flushThinkingSteps()
                if (groupImages) {
                    if (firstImageIndex < 0) firstImageIndex = index
                    currentImages.add(part)
                } else {
                    flushImages()
                    result.add(MessagePartBlock.ContentBlock(part, index))
                }
            }

            else -> {
                flushThinkingSteps()
                flushImages()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    flushImages()
    return result
}

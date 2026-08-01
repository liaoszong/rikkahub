package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import me.rerere.ai.ui.ImageGenerationItem
import java.io.IOException

data class ImageGenerationExecution(
    val requestId: String,
    val attempt: Int,
    val request: ImageGenerationRequest,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(attempt > 0) { "attempt must be positive" }
    }
}

sealed interface ImageGenerationExecutionEvent {
    val requestId: String
    val attempt: Int

    data class Running(
        override val requestId: String,
        override val attempt: Int,
    ) : ImageGenerationExecutionEvent

    data class Preview(
        override val requestId: String,
        override val attempt: Int,
        val item: ImageGenerationItem,
        val index: Int,
    ) : ImageGenerationExecutionEvent

    data class FinalImage(
        override val requestId: String,
        override val attempt: Int,
        val item: ImageGenerationItem,
        val index: Int,
    ) : ImageGenerationExecutionEvent

    data class Succeeded(
        override val requestId: String,
        override val attempt: Int,
        val imageCount: Int,
    ) : ImageGenerationExecutionEvent

    data class Failed(
        override val requestId: String,
        override val attempt: Int,
        val failure: ImageGenerationExecutionFailure,
    ) : ImageGenerationExecutionEvent

    data class Cancelled(
        override val requestId: String,
        override val attempt: Int,
    ) : ImageGenerationExecutionEvent
}

data class ImageGenerationExecutionFailure(
    val kind: ImageGenerationFailureKind,
    val message: String,
    val recoveredImage: GeneratedImage? = null,
)

sealed interface ImageGenerationExecutionResult {
    data class Success(val imageCount: Int) : ImageGenerationExecutionResult
    data class Failure(val failure: ImageGenerationExecutionFailure) : ImageGenerationExecutionResult
}

/**
 * Executes exactly one provider request attempt.
 *
 * Presentation owners decide how events are persisted and rendered. The executor owns the shared
 * provider, request identity, terminal-state, failure-classification and cancellation contract.
 */
class ImageGenerationTaskExecutor(
    private val gateway: ImageGenerationGateway,
) {
    suspend fun execute(
        execution: ImageGenerationExecution,
        onEvent: suspend (ImageGenerationExecutionEvent) -> Unit,
    ): ImageGenerationExecutionResult {
        val requestId = execution.requestId
        val attempt = execution.attempt
        var finalImageCount = 0
        var nextPreviewIndex = 0
        onEvent(ImageGenerationExecutionEvent.Running(requestId, attempt))
        try {
            gateway.generate(execution.request).collect { item ->
                if (item.partial) {
                    val index = item.partialImageIndex ?: nextPreviewIndex
                    nextPreviewIndex = maxOf(nextPreviewIndex, index)
                    onEvent(ImageGenerationExecutionEvent.Preview(requestId, attempt, item, index))
                } else {
                    val index = finalImageCount++
                    nextPreviewIndex = finalImageCount
                    onEvent(ImageGenerationExecutionEvent.FinalImage(requestId, attempt, item, index))
                }
            }
            if (finalImageCount == 0) {
                throw ImageGenerationException(
                    ImageGenerationFailureKind.RESPONSE_PARSE,
                    "The provider returned no generated images",
                )
            }
            onEvent(ImageGenerationExecutionEvent.Succeeded(requestId, attempt, finalImageCount))
            return ImageGenerationExecutionResult.Success(finalImageCount)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                onEvent(ImageGenerationExecutionEvent.Cancelled(requestId, attempt))
            }
            throw cancelled
        } catch (error: Throwable) {
            val failure = classifyFailure(error)
            onEvent(ImageGenerationExecutionEvent.Failed(requestId, attempt, failure))
            return ImageGenerationExecutionResult.Failure(failure)
        }
    }

    private fun classifyFailure(error: Throwable): ImageGenerationExecutionFailure {
        if (error is ImageGenerationException) {
            return ImageGenerationExecutionFailure(
                kind = error.kind,
                message = error.message ?: "Image generation failed",
                recoveredImage = error.recoveredImage,
            )
        }
        val (kind, message) = when (error) {
            is IOException -> ImageGenerationFailureKind.NETWORK to
                "The network connection was interrupted: ${error.message ?: "unknown I/O error"}"
            is SerializationException -> ImageGenerationFailureKind.RESPONSE_PARSE to
                "The image response could not be parsed"
            is IllegalStateException -> ImageGenerationFailureKind.SERVER to
                (error.message ?: "The image provider returned an error")
            else -> ImageGenerationFailureKind.UNKNOWN to
                (error.message ?: "Image generation failed")
        }
        return ImageGenerationExecutionFailure(kind, message)
    }
}

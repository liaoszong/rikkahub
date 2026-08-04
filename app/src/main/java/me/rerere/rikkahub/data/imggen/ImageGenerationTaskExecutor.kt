package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.pale.request.RequestState
import me.rerere.rikkahub.fork.pale.request.ImageGenerationLedgerSession
import java.io.IOException

data class ImageGenerationExecution(
    val requestId: String,
    val attempt: Int,
    val request: ImageGenerationRequest,
    val ledgerSession: ImageGenerationLedgerSession? = null,
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
        val ledgerState: RequestState? = null,
    ) : ImageGenerationExecutionEvent

    data class Cancelled(
        override val requestId: String,
        override val attempt: Int,
        val ledgerState: RequestState? = null,
    ) : ImageGenerationExecutionEvent
}

data class ImageGenerationExecutionFailure(
    val kind: ImageGenerationFailureKind,
    val message: String,
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
        val ledgerSession = execution.ledgerSession
        onEvent(ImageGenerationExecutionEvent.Running(requestId, attempt))
        try {
            if (
                ledgerSession != null &&
                execution.request.credentialEvidence?.reference != ledgerSession.credentialRefId
            ) {
                throw ImageGenerationException(
                    ImageGenerationFailureKind.CONFIGURATION,
                    "Image credential evidence does not match RequestLedger",
                )
            }
            ledgerSession?.prepareDispatch()
            val providerRequest = execution.request.copy(
                providerRequestId = ledgerSession?.providerRequestId
                    ?: execution.request.providerRequestId,
                dispatchObserver = ledgerSession?.dispatchObserver
                    ?: execution.request.dispatchObserver,
            )
            val collectProvider: suspend () -> Unit = {
                // One ledger slot owns exactly one final image. Stop after the first final item so
                // a non-conforming provider cannot overwrite the reserved MediaAsset identity.
                gateway.generate(providerRequest)
                    .transformWhile { item ->
                        emit(item)
                        item.partial
                    }
                    .collect { item ->
                        ledgerSession?.markResponseStarted()
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
            }
            if (ledgerSession != null) {
                ledgerSession.withLeaseHeartbeat { collectProvider() }
            } else {
                collectProvider()
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
            val ledgerState = withContext(NonCancellable) {
                ledgerSession?.terminalState()?.takeIf { it == RequestState.SUCCEEDED }
                    ?: if (finalImageCount > 0) {
                        // A final provider item may already have been atomically committed by the
                        // event sink. Cancellation during local metadata work is repairable and
                        // must not turn that exact file into an irreversible INTERRUPTED result.
                        ledgerSession?.releaseForLocalRepair(cancelled)
                        ledgerSession?.terminalState()
                    } else {
                        runCatching { ledgerSession?.finishCancellation() }.getOrNull()
                    }
            }
            if (ledgerState != RequestState.SUCCEEDED) {
                withContext(NonCancellable) {
                    onEvent(ImageGenerationExecutionEvent.Cancelled(requestId, attempt, ledgerState))
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            val alreadySucceeded = ledgerSession?.terminalState() == RequestState.SUCCEEDED
            if (alreadySucceeded) {
                onEvent(ImageGenerationExecutionEvent.Succeeded(requestId, attempt, finalImageCount.coerceAtLeast(1)))
                return ImageGenerationExecutionResult.Success(finalImageCount.coerceAtLeast(1))
            }
            val ledgerState = withContext(NonCancellable) {
                if (finalImageCount > 0) {
                    // The provider has returned its final paid payload. From here, failures belong
                    // to the local file/Room commit chain; preserve RESPONSE_STARTED or
                    // RESULT_RECEIVED so exact-file startup reconciliation can finish it.
                    ledgerSession?.releaseForLocalRepair(error)
                    ledgerSession?.terminalState()
                } else {
                    runCatching {
                        ledgerSession?.finishFailure(responseProvedFailure = error.provesProviderFailure())
                    }.getOrElse { ledgerFailure ->
                        ledgerSession?.releaseForLocalRepair(error)
                        error.addSuppressed(ledgerFailure)
                        null
                    }
                }
            }
            val failure = classifyFailure(error)
            onEvent(ImageGenerationExecutionEvent.Failed(requestId, attempt, failure, ledgerState))
            return ImageGenerationExecutionResult.Failure(failure)
        }
    }

    private fun classifyFailure(error: Throwable): ImageGenerationExecutionFailure {
        if (error is ImageGenerationException) {
            return ImageGenerationExecutionFailure(
                kind = error.kind,
                message = error.message ?: "Image generation failed",
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

    private fun Throwable.provesProviderFailure(): Boolean =
        this is ImageGenerationException && kind in setOf(
            ImageGenerationFailureKind.SERVER,
            ImageGenerationFailureKind.RESPONSE_PARSE,
        )
}

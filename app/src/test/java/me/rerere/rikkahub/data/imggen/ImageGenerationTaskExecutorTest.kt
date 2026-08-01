package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ImageGenerationItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageGenerationTaskExecutorTest {
    @Test
    fun `events preserve immutable request identity and attempt`() = runBlocking {
        val events = mutableListOf<ImageGenerationExecutionEvent>()
        val gateway = FakeGateway {
            emit(ImageGenerationItem(data = "preview", mimeType = "image/png", partial = true))
            emit(ImageGenerationItem(data = "final", mimeType = "image/png"))
        }

        val result = ImageGenerationTaskExecutor(gateway).execute(execution()) { events += it }

        assertEquals(ImageGenerationExecutionResult.Success(1), result)
        assertEquals(1, gateway.calls)
        assertTrue(events.first() is ImageGenerationExecutionEvent.Running)
        assertTrue(events.last() is ImageGenerationExecutionEvent.Succeeded)
        assertTrue(events.all { it.requestId == "request-42" && it.attempt == 3 })
    }

    @Test
    fun `provider failure becomes a terminal typed failure`() = runBlocking {
        val events = mutableListOf<ImageGenerationExecutionEvent>()
        val gateway = FakeGateway { throw IOException("offline") }

        val result = ImageGenerationTaskExecutor(gateway).execute(execution()) { events += it }

        val failure = (result as ImageGenerationExecutionResult.Failure).failure
        assertEquals(ImageGenerationFailureKind.NETWORK, failure.kind)
        assertTrue(events.last() is ImageGenerationExecutionEvent.Failed)
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `empty provider response is a parse failure`() = runBlocking {
        val result = ImageGenerationTaskExecutor(FakeGateway { }).execute(execution()) { }

        assertEquals(
            ImageGenerationFailureKind.RESPONSE_PARSE,
            (result as ImageGenerationExecutionResult.Failure).failure.kind,
        )
    }

    @Test
    fun `cancellation emits cancelled and never creates another attempt`() = runBlocking {
        val events = mutableListOf<ImageGenerationExecutionEvent>()
        val gateway = FakeGateway { awaitCancellation() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ImageGenerationTaskExecutor(gateway).execute(execution()) { events += it }
            } catch (_: CancellationException) {
                // Expected: cancellation remains structured and is not converted to failure.
            }
        }

        job.cancel()
        job.join()

        assertEquals(1, gateway.calls)
        assertTrue(events.last() is ImageGenerationExecutionEvent.Cancelled)
        assertTrue(events.all { it.requestId == "request-42" && it.attempt == 3 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank request id is rejected before provider execution`() {
        ImageGenerationExecution(requestId = "", attempt = 1, request = request())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive attempt is rejected before provider execution`() {
        ImageGenerationExecution(requestId = "request", attempt = 0, request = request())
    }

    private class FakeGateway(
        private val response: suspend kotlinx.coroutines.flow.FlowCollector<ImageGenerationItem>.() -> Unit,
    ) : ImageGenerationGateway {
        var calls = 0
            private set

        override suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem> = flow {
            calls++
            response()
        }
    }

    private companion object {
        fun execution() = ImageGenerationExecution(
            requestId = "request-42",
            attempt = 3,
            request = request(),
        )

        fun request() = ImageGenerationRequest(
            prompt = "prompt",
            modelId = "model-id",
            modelName = "model",
            size = "1024x1024",
            numberOfImages = 1,
        )
    }
}

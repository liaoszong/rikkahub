package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.ImageGenerationItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageGenerationTaskManagerTest {
    @Test
    fun `page observer destruction does not cancel application task`() = runBlocking {
        val fixture = Fixture()
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        fixture.manager.start(request())
        pageScope.cancel()
        fixture.gateway.complete()

        val completed = fixture.awaitPhase(ImageGenerationPhase.COMPLETED)
        assertEquals(1, completed.images.size)
        assertEquals(1, fixture.gateway.calls)
    }

    @Test
    fun `recreated page observes the same running task`() = runBlocking {
        val fixture = Fixture()
        fixture.manager.start(request())

        val firstObserver = fixture.manager.task.value
        val recreatedObserver = fixture.manager.task.first { it.isActive }

        assertSame(firstObserver, recreatedObserver)
        assertEquals(firstObserver.taskId, recreatedObserver.taskId)
        assertEquals(ImageGenerationPhase.RUNNING, recreatedObserver.phase)
    }

    @Test
    fun `delayed provider result is saved and exposed to gallery`() = runBlocking {
        val fixture = Fixture()
        fixture.manager.start(request())

        fixture.gateway.complete()
        fixture.awaitPhase(ImageGenerationPhase.COMPLETED)

        assertEquals(1, fixture.resultStore.gallery.size)
        assertEquals("/images/1.png", fixture.resultStore.gallery.single().filePath)
    }

    @Test
    fun `explicit cancellation ends task without retry`() = runBlocking {
        val fixture = Fixture()
        fixture.manager.start(request())

        fixture.manager.cancel()

        val cancelled = fixture.awaitPhase(ImageGenerationPhase.CANCELLED)
        assertEquals(ImageGenerationFailureKind.USER_CANCELLED, cancelled.errorKind)
        assertEquals(1, fixture.gateway.calls)
        assertFalse(cancelled.isActive)
    }

    @Test
    fun `navigation away has no cancellation side effect`() = runBlocking {
        val fixture = Fixture()
        fixture.manager.start(request())
        val taskIdBeforeNavigation = fixture.manager.task.value.taskId

        fixture.gateway.complete()

        val completed = fixture.awaitPhase(ImageGenerationPhase.COMPLETED)
        assertEquals(taskIdBeforeNavigation, completed.taskId)
    }

    @Test
    fun `failure always clears active generation state`() = runBlocking {
        val fixture = Fixture(gateway = DelayedGateway(failure = IOException("offline")))
        fixture.manager.start(request())

        fixture.gateway.complete()

        val failed = fixture.awaitPhase(ImageGenerationPhase.FAILED)
        assertFalse(failed.isActive)
        assertEquals(ImageGenerationFailureKind.NETWORK, failed.errorKind)
    }

    @Test
    fun `page recreation cannot submit the request twice`() {
        val fixture = Fixture()

        assertEquals(ImageGenerationStartResult.STARTED, fixture.manager.start(request()))
        assertEquals(ImageGenerationStartResult.ALREADY_RUNNING, fixture.manager.start(request()))
        assertEquals(1, fixture.gateway.calls)
    }

    @Test
    fun `restored unfinished task becomes interrupted without provider call`() {
        val persisted = runningTask()
        val gateway = DelayedGateway()
        val fixture = Fixture(
            gateway = gateway,
            taskStore = InMemoryTaskStore(persisted),
        )

        assertEquals(ImageGenerationPhase.INTERRUPTED, fixture.manager.task.value.phase)
        assertEquals(ImageGenerationFailureKind.PROCESS_INTERRUPTED, fixture.manager.task.value.errorKind)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `rapid repeated starts create only one task`() {
        val fixture = Fixture()

        repeat(10) {
            fixture.manager.start(request())
        }

        assertEquals(1, fixture.gateway.calls)
        assertEquals(1, fixture.foregroundController.starts)
    }

    @Test
    fun `completed image remains readable from gallery data source`() = runBlocking {
        val fixture = Fixture()
        fixture.manager.start(request())
        fixture.gateway.complete()
        fixture.awaitPhase(ImageGenerationPhase.COMPLETED)

        val galleryReload = fixture.resultStore.gallery.toList()
        assertEquals("prompt", galleryReload.single().prompt)
        assertFalse(galleryReload.single().isPreview)
    }

    private class Fixture(
        val gateway: DelayedGateway = DelayedGateway(),
        val taskStore: InMemoryTaskStore = InMemoryTaskStore(),
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resultStore = FakeResultStore()
        val foregroundController = FakeForegroundController()
        val manager = ImageGenerationTaskManager(
            scope = scope,
            gateway = gateway,
            resultStore = resultStore,
            taskStore = taskStore,
            foregroundController = foregroundController,
            executionDispatcher = Dispatchers.Unconfined,
            clock = { 100L },
            idGenerator = { "task-1" },
        )

        suspend fun awaitPhase(phase: ImageGenerationPhase): ImageGenerationTask =
            withTimeout(1_000) {
                manager.task.first { it.phase == phase }
            }
    }

    private class DelayedGateway(
        private val failure: Throwable? = null,
    ) : ImageGenerationGateway {
        private val gate = CompletableDeferred<Unit>()
        var calls = 0
            private set

        override suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationItem> = flow {
            calls++
            gate.await()
            failure?.let { throw it }
            emit(ImageGenerationItem(data = "image-data", mimeType = "image/png"))
        }

        fun complete() {
            gate.complete(Unit)
        }
    }

    private class FakeResultStore : ImageGenerationResultStore {
        val gallery = mutableListOf<GeneratedImage>()

        override suspend fun savePreview(
            task: ImageGenerationTask,
            item: ImageGenerationItem,
            index: Int,
        ) = GeneratedImage(
            id = 0,
            prompt = task.prompt,
            filePath = "/preview/$index.png",
            timestamp = 101,
            model = task.modelName,
            isPreview = true,
        )

        override suspend fun saveFinal(
            task: ImageGenerationTask,
            item: ImageGenerationItem,
            index: Int,
            sourcePaths: List<String>,
        ) = GeneratedImage(
            id = gallery.size + 1,
            prompt = task.prompt,
            filePath = "/images/${gallery.size + 1}.png",
            timestamp = 102,
            model = task.modelName,
        ).also(gallery::add)

        override fun deletePreview(image: GeneratedImage) = Unit
    }

    private class InMemoryTaskStore(
        initial: ImageGenerationTask? = null,
    ) : ImageGenerationTaskStore {
        private var task = initial

        override fun load(): ImageGenerationTask? = task

        override fun save(task: ImageGenerationTask) {
            this.task = task
        }

        override fun clear() {
            task = null
        }
    }

    private class FakeForegroundController : ImageGenerationForegroundController {
        var starts = 0
            private set

        override fun start(taskId: String) {
            starts++
        }
    }

    private companion object {
        fun request() = ImageGenerationRequest(
            prompt = "prompt",
            modelId = "model-id",
            modelName = "model",
            size = "1024x1024",
            numberOfImages = 1,
        )

        fun runningTask() = ImageGenerationTask(
            taskId = "restored",
            prompt = "prompt",
            modelId = "model-id",
            modelName = "model",
            size = "1024x1024",
            numberOfImages = 1,
            startedAt = 1,
            phase = ImageGenerationPhase.RUNNING,
        )
    }
}

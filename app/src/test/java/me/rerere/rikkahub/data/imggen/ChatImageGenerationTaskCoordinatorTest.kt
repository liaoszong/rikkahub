package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageGenerationTaskCoordinatorTest {
    @Test
    fun `queued state is durable before foreground readiness and provider ownership`() = runBlocking {
        val store = InMemoryStore()
        val foreground = FakeForegroundController(readyImmediately = false)
        val coordinator = coordinator(store, foreground)

        val begin = launch { coordinator.begin(record(), cancelExecution = {}) }
        foreground.started.await()

        assertEquals(ChatImageGenerationTaskPhase.QUEUED, store.last.single().phase)
        assertEquals(ChatImageGenerationTaskPhase.QUEUED, coordinator.tasks.value.getValue(TASK_ID).phase)

        foreground.markReady()
        begin.join()

        assertEquals(ChatImageGenerationTaskPhase.RUNNING, store.last.single().phase)
        assertEquals(ChatImageGenerationTaskPhase.RUNNING, coordinator.tasks.value.getValue(TASK_ID).phase)
    }

    @Test
    fun `foreground readiness failure becomes terminal before provider can start`() = runBlocking {
        val store = InMemoryStore()
        val foreground = FakeForegroundController(readyImmediately = false)
        val coordinator = coordinator(store, foreground)

        val begin = launch {
            runCatching { coordinator.begin(record(), cancelExecution = {}) }
        }
        foreground.started.await()
        foreground.fail(IllegalStateException("notifications unavailable"))
        begin.join()

        val failed = coordinator.tasks.value.getValue(TASK_ID)
        assertEquals(ChatImageGenerationTaskPhase.FAILED, failed.phase)
        assertEquals(ImageGenerationFailureKind.CONFIGURATION, failed.errorKind)
        assertFalse(failed.isActive)
    }

    @Test
    fun `restored active task waits for ledger recovery and never restarts provider`() {
        val store = InMemoryStore(listOf(record().copy(phase = ChatImageGenerationTaskPhase.RUNNING)))
        val foreground = FakeForegroundController()

        val coordinator = coordinator(store, foreground)

        val restored = coordinator.tasks.value.getValue(TASK_ID)
        assertEquals(ChatImageGenerationTaskPhase.RECOVERING, restored.phase)
        assertEquals(ImageGenerationFailureKind.PROCESS_INTERRUPTED, restored.errorKind)
        assertEquals(0, foreground.starts)
        assertTrue(restored.isActive)
    }

    @Test
    fun `same paid request attempt cannot be replayed`() = runBlocking {
        val foreground = FakeForegroundController()
        val coordinator = coordinator(InMemoryStore(), foreground)
        coordinator.begin(record(), cancelExecution = {})

        val duplicate = runCatching {
            coordinator.begin(record(taskId = "different-task"), cancelExecution = {})
        }.exceptionOrNull()

        assertTrue(duplicate is ImageGenerationException)
        assertEquals(1, foreground.starts)
        assertEquals(1, coordinator.tasks.value.size)
    }

    @Test
    fun `notification cancellation is a signal until slot ledgers settle`() = runBlocking {
        val coordinator = coordinator(InMemoryStore(), FakeForegroundController())
        var cancellationCalls = 0
        coordinator.begin(record(), cancelExecution = { cancellationCalls++ })

        assertTrue(coordinator.cancel(TASK_ID))

        assertEquals(1, cancellationCalls)
        val signalled = coordinator.tasks.value.getValue(TASK_ID)
        assertEquals(ChatImageGenerationTaskPhase.RUNNING, signalled.phase)
        assertEquals(2_000L, signalled.cancellationRequestedAtEpochMillis)
        assertFalse(coordinator.cancel(TASK_ID))

        coordinator.applyRecoveredState(
            taskId = TASK_ID,
            phase = ChatImageGenerationTaskPhase.CANCELLED,
            completedImageCount = 0,
            failedImageCount = 0,
            outputAssetIds = emptyList(),
            slotStatuses = List(3) { ChatImageSlotStatus.CANCELLED },
        )
        assertEquals(ChatImageGenerationTaskPhase.CANCELLED, coordinator.tasks.value.getValue(TASK_ID).phase)
    }

    @Test
    fun `progress and terminal result survive coordinator observers`() = runBlocking {
        val store = InMemoryStore()
        val coordinator = coordinator(store, FakeForegroundController())
        coordinator.begin(record(), cancelExecution = {})

        coordinator.updateProgress(
            taskId = TASK_ID,
            completedImageCount = 2,
            failedImageCount = 1,
            outputAssetIds = listOf("asset-1", "asset-2"),
            slotStatuses = listOf(
                ChatImageSlotStatus.SUCCEEDED,
                ChatImageSlotStatus.SUCCEEDED,
                ChatImageSlotStatus.FAILED,
            ),
        )
        coordinator.complete(TASK_ID)

        val completed = store.last.single()
        assertEquals(ChatImageGenerationTaskPhase.COMPLETED, completed.phase)
        assertEquals(2, completed.completedImageCount)
        assertEquals(1, completed.failedImageCount)
        assertEquals(listOf("asset-1", "asset-2"), completed.outputAssetIds)
        assertEquals(ChatImageSlotStatus.FAILED, completed.slotStatuses.last())
    }

    @Test
    fun `service interruption only signals tasks owned by that service instance`() = runBlocking {
        val coordinator = coordinator(InMemoryStore(), FakeForegroundController())
        coordinator.begin(record(), cancelExecution = {})
        coordinator.begin(
            record(taskId = "tool-call-2").copy(requestId = "request-2"),
            cancelExecution = {},
        )

        coordinator.interruptActive(
            taskIds = setOf(TASK_ID),
            reason = "service instance stopped",
        )

        assertEquals(
            ChatImageGenerationTaskPhase.RUNNING,
            coordinator.tasks.value.getValue(TASK_ID).phase,
        )
        assertEquals(
            ImageGenerationFailureKind.PROCESS_INTERRUPTED,
            coordinator.tasks.value.getValue(TASK_ID).errorKind,
        )
        assertEquals(2_000L, coordinator.tasks.value.getValue(TASK_ID).cancellationRequestedAtEpochMillis)
        assertEquals(
            ChatImageGenerationTaskPhase.RUNNING,
            coordinator.tasks.value.getValue("tool-call-2").phase,
        )
    }

    @Test
    fun `durable task retains full media registration for file only recovery`() {
        val registration = listOf(
            record().copy(
                modelId = "model-id",
                providerId = "provider-id",
                prompt = "draw a harbor",
                mediaOrigin = "ai_edited",
                parentAssetId = "parent-asset",
                referenceAssetIds = listOf("reference-asset"),
                referenceSourcePaths = listOf("uploads/reference.png"),
            ),
        ).toPendingMediaRegistrations().getValue("asset-1")

        assertEquals("asset-1", registration.assetId)
        assertEquals("ai_edited", registration.origin)
        assertEquals("model-id", registration.modelId)
        assertEquals("image-model", registration.modelDisplayName)
        assertEquals("provider-id", registration.providerId)
        assertEquals("draw a harbor", registration.prompt)
        assertEquals("conversation-1", registration.conversationId)
        assertEquals(TASK_ID, registration.toolCallId)
        assertEquals("parent-asset", registration.parentAssetId)
        assertEquals(
            listOf("reference-asset", null),
            registration.referenceInputs.map { it.assetId },
        )
        assertEquals(
            listOf(null, "uploads/reference.png"),
            registration.referenceInputs.map { it.sourcePath },
        )
    }

    private fun coordinator(
        store: InMemoryStore,
        foreground: FakeForegroundController,
    ) = ChatImageGenerationTaskCoordinator(
        store = store,
        foregroundController = foreground,
        clock = { 2_000L },
    )

    private fun record(taskId: String = TASK_ID) = ChatImageGenerationTaskRecord(
        taskId = taskId,
        conversationId = "conversation-1",
        toolCallId = TASK_ID,
        requestId = REQUEST_ID,
        attempt = 1,
        modelName = "image-model",
        requestedImageCount = 3,
        reservedOutputAssetIds = listOf("asset-1", "asset-2", "asset-3"),
        startedAtEpochMillis = 1_000L,
    )

    private class InMemoryStore(initial: List<ChatImageGenerationTaskRecord> = emptyList()) :
        ChatImageGenerationTaskStore {
        private var stored = initial
        val saves = mutableListOf<List<ChatImageGenerationTaskRecord>>()
        val last: List<ChatImageGenerationTaskRecord>
            get() = stored

        override fun load(): List<ChatImageGenerationTaskRecord> = stored

        override fun save(tasks: List<ChatImageGenerationTaskRecord>) {
            stored = tasks
            saves += tasks
        }
    }

    private class FakeForegroundController(readyImmediately: Boolean = true) :
        ChatImageGenerationForegroundController {
        val started = CompletableDeferred<Unit>()
        private val readiness = CompletableDeferred<Unit>()
        private var readinessFailure: Throwable? = null
        var starts = 0
            private set

        init {
            if (readyImmediately) readiness.complete(Unit)
        }

        override fun start(taskId: String) {
            starts++
            started.complete(Unit)
        }

        override suspend fun awaitReady(taskId: String) {
            readiness.await()
            readinessFailure?.let { throw it }
        }

        fun markReady() {
            readiness.complete(Unit)
        }

        fun fail(error: Throwable) {
            readinessFailure = error
            readiness.complete(Unit)
        }
    }

    private companion object {
        const val TASK_ID = "tool-call-1"
        const val REQUEST_ID = "request-1"
    }
}

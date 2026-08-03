package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.RequestState
import me.rerere.pale.request.ToolPermissionDecision
import me.rerere.pale.request.ToolPermissionScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.imggen.ChatImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskCoordinator
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskPhase
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskStore
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.findCommittedGeneratedImage
import me.rerere.rikkahub.data.imggen.toStatusPart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageTaskRecoveryCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: RequestLedgerRepository
    private val createdFiles = mutableListOf<File>()
    private var auditSequence = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RequestLedgerRepository(
            database = database,
            requestAuditId = { "task-recovery-request-${++auditSequence}" },
            toolAuditId = { "task-recovery-tool-${++auditSequence}" },
        )
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
        database.close()
    }

    @Test
    fun successfulChildRebuildsConversationToolResultWithoutProviderReplay() = runTest {
        val parent = createParent()
        val assetId = UUID.randomUUID().toString()
        val taskController = taskController(task(parent, listOf(assetId)))
        val writer = CapturingWriter()
        val plan = ledger().prepareSlots(descriptor(parent, listOf(assetId))).single()
        commitSuccess(plan, parent.value)

        val report = recovery(taskController, writer).reconcilePending()

        assertEquals(1, report.projected)
        assertEquals(1, report.conversationResultsRepaired)
        assertEquals(0, report.pending)
        assertEquals(ChatImageSlotStatus.SUCCEEDED, writer.state!!.slots.single().status)
        assertEquals(assetId, writer.images.single().assetId)
        assertEquals(
            ChatImageGenerationTaskPhase.COMPLETED,
            taskController.tasks.value.getValue(parent.value).phase,
        )
    }

    @Test
    fun partialSuccessAndUnknownOutcomePreserveImageAndDoNotReplayUnknownSlot() = runTest {
        val parent = createParent()
        val assetIds = List(2) { UUID.randomUUID().toString() }
        val taskController = taskController(task(parent, assetIds))
        val writer = CapturingWriter()
        val plans = ledger().prepareSlots(descriptor(parent, assetIds))
        commitSuccess(plans[0], parent.value)
        val unknown = ledger().openSlot(plans[1]) as ImageGenerationSlotOpenResult.Dispatch
        unknown.session.prepareDispatch()
        unknown.session.dispatchObserver.onDispatch()
        assertEquals(RequestState.UNKNOWN_OUTCOME, unknown.session.finishFailure())

        val report = recovery(taskController, writer).reconcilePending()

        assertEquals(1, report.projected)
        assertEquals(1, writer.images.size)
        assertEquals(
            listOf(ChatImageSlotStatus.SUCCEEDED, ChatImageSlotStatus.UNKNOWN_OUTCOME),
            writer.state!!.slots.map { it.status },
        )
        assertEquals(
            ChatImageGenerationTaskPhase.UNKNOWN_OUTCOME,
            taskController.tasks.value.getValue(parent.value).phase,
        )
    }

    @Test
    fun conversationBarrierFailureLeavesTaskRecoverableForNextPass() = runTest {
        val parent = createParent()
        val assetId = UUID.randomUUID().toString()
        val taskController = taskController(task(parent, listOf(assetId)))
        val writer = CapturingWriter(writeResult = false)
        val plan = ledger().prepareSlots(descriptor(parent, listOf(assetId))).single()
        commitSuccess(plan, parent.value)

        val report = recovery(taskController, writer).reconcilePending()

        assertEquals(0, report.projected)
        assertEquals(1, report.pending)
        assertEquals(
            ChatImageGenerationTaskPhase.RECOVERING,
            taskController.tasks.value.getValue(parent.value).phase,
        )
    }

    @Test
    fun conversationDescriptorRecoversWhenSharedPreferencesTaskWasLost() = runTest {
        val parent = createParent()
        val assetId = UUID.randomUUID().toString()
        val restoredTask = task(parent, listOf(assetId))
        val taskController = taskController(emptyList())
        val writer = CapturingWriter()
        database.requestLedgerDao().insertInvocation(
            ToolInvocationEntity(
                invocationId = UUID.randomUUID().toString(),
                requestId = parent.value,
                providerToolCallId = restoredTask.toolCallId,
                toolName = "generate_image",
                principalKind = "local",
                principalId = "generate_image",
                action = "execute",
                schemaDigest = "schema",
                inputDigest = "input",
                sideEffectClass = "irreversible",
                approvalState = "not_required",
                executionState = "running",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        val plan = ledger().prepareSlots(descriptor(parent, listOf(assetId))).single()
        commitSuccess(plan, parent.value)
        val coordinator = ImageTaskRecoveryCoordinator(
            context = context,
            requestRepository = repository,
            taskController = taskController,
            toolResultWriter = writer,
            taskSource = DurableImageTaskSource { _, _, _ -> restoredTask },
        )

        val report = coordinator.reconcilePending()

        assertEquals(1, report.projected)
        assertEquals(ChatImageGenerationTaskPhase.COMPLETED, taskController.tasks.value[parent.value]?.phase)
        assertEquals(assetId, writer.images.single().assetId)
    }

    @Test
    fun productionDescriptorFallbackDoesNotRequireAnEmittedProgressState() = runTest {
        val parentId = createParent()
        val parent = repository.getRequest(parentId)!!
        val invocation = ToolInvocationEntity(
            invocationId = UUID.randomUUID().toString(),
            requestId = parentId.value,
            providerToolCallId = "tool-${parentId.value}",
            toolName = "generate_image",
            principalKind = "local",
            principalId = "generate_image",
            action = "execute",
            schemaDigest = "schema",
            inputDigest = "input",
            sideEffectClass = "irreversible",
            approvalState = "not_required",
            executionState = "running",
            createdAt = 1_000L,
            startedAt = 1_100L,
            updatedAt = 1_100L,
        )
        val assetIds = List(2) { UUID.randomUUID().toString() }
        ledger().prepareSlots(
            descriptor(parentId, assetIds).copy(
                size = "1536x1024",
                referenceImageDigests = listOf("a".repeat(64)),
                referenceAssetIds = listOf("reference-asset"),
                referenceSourcePaths = listOf("uploads/reference.png"),
                parentAssetId = "reference-asset",
            ),
        )
        val children = repository.getImageRequestsByParent(parentId)
        val tool = UIMessagePart.Tool(
            toolCallId = invocation.providerToolCallId,
            toolName = "generate_image",
            input = """{"prompt":"recover without progress","size":"1536x1024","count":2}""",
            executionState = ToolExecutionState.RUNNING,
            requestId = parentId.value,
        )

        val recovered = reconstructImageTask(
            parent = parent,
            invocation = invocation,
            children = children,
            tool = tool,
            conversationId = Uuid.parse(parent.conversationId!!),
            descriptor = decodeImageTaskDescriptor(repository.getImageTaskDescriptor(parentId)!!)!!,
        )!!

        assertEquals("recover without progress", recovered.prompt)
        assertEquals("1536x1024", recovered.size)
        assertEquals(2, recovered.requestedImageCount)
        assertEquals(assetIds, recovered.reservedOutputAssetIds)
        assertEquals("model-1", recovered.modelId)
        assertEquals(1_100L, recovered.startedAtEpochMillis)
        assertEquals("reference-asset", recovered.parentAssetId)
        assertEquals(listOf("reference-asset"), recovered.referenceAssetIds)
        assertEquals(listOf("uploads/reference.png"), recovered.referenceSourcePaths)
    }

    @Test
    fun cancelledParentKeepsInterruptedConversationExecutionSemantics() {
        assertEquals(
            ToolExecutionState.INTERRUPTED,
            RequestState.CANCELLED.toRecoveredToolExecutionState(),
        )
        assertEquals(
            ToolExecutionState.SUCCEEDED,
            RequestState.RUNNING.toRecoveredToolExecutionState(),
        )
    }

    @Test
    fun truncatedImageWithReadableHeaderIsRejectedAsRecoveryEvidence() {
        val assetId = UUID.randomUUID().toString()
        val bytes = Base64.decode(ONE_PIXEL_PNG, Base64.DEFAULT)
        val folder = File(context.filesDir, FileFolders.CHAT_GENERATED_IMAGES).apply { mkdirs() }
        val file = File(folder, "$assetId.png")
        file.writeBytes(bytes.copyOf(bytes.size - 12))
        createdFiles += file

        assertNull(findCommittedGeneratedImage(context, assetId))
    }

    @Test
    fun terminalCancelledParentIsDiscoveredWhenTaskCacheWasLost() = runTest {
        val parent = createParent()
        val assetId = UUID.randomUUID().toString()
        val restoredTask = task(parent, listOf(assetId))
        database.requestLedgerDao().insertInvocation(
            ToolInvocationEntity(
                invocationId = UUID.randomUUID().toString(),
                requestId = parent.value,
                providerToolCallId = restoredTask.toolCallId,
                toolName = "generate_image",
                principalKind = "local",
                principalId = "generate_image",
                action = "execute",
                schemaDigest = "schema",
                inputDigest = "input",
                sideEffectClass = "irreversible",
                approvalState = "not_required",
                executionState = "cancelled",
                createdAt = 1_000L,
                finishedAt = 1_100L,
                updatedAt = 1_100L,
            ),
        )
        val child = ledger().prepareSlots(descriptor(parent, listOf(assetId))).single()
        assertEquals(RequestState.CANCELLED, ledger().cancelBeforeDispatch(child))
        RequestDispatchSession.open(
            repository = repository,
            request = parentSpec(parent),
            owner = "cancelled-parent-test",
            leaseDurationMillis = 10_000L,
            attemptId = RequestAttemptId(UUID.randomUUID().toString()),
            idempotencyKey = "cancelled-parent",
            requestFingerprint = "parent-input",
        ).cancel()
        val writer = CapturingWriter()
        val coordinator = ImageTaskRecoveryCoordinator(
            context = context,
            requestRepository = repository,
            taskController = taskController(emptyList()),
            toolResultWriter = writer,
            taskSource = DurableImageTaskSource { terminalParent, _, _ ->
                assertEquals("cancelled", terminalParent.requestState)
                restoredTask
            },
        )

        val report = coordinator.reconcilePending()

        assertEquals(1, report.projected)
        assertEquals(RequestState.CANCELLED, writer.parentState)
        assertEquals(ChatImageSlotStatus.CANCELLED, writer.state!!.slots.single().status)
    }

    @Test
    fun terminalParentWithoutConversationProjectionDoesNotKeepStartupPending() = runTest {
        val parent = createParent()
        val assetId = UUID.randomUUID().toString()
        val restoredTask = task(parent, listOf(assetId))
        database.requestLedgerDao().insertInvocation(
            ToolInvocationEntity(
                invocationId = UUID.randomUUID().toString(),
                requestId = parent.value,
                providerToolCallId = restoredTask.toolCallId,
                toolName = "generate_image",
                principalKind = "local",
                principalId = "generate_image",
                action = "execute",
                schemaDigest = "schema",
                inputDigest = "input",
                sideEffectClass = "irreversible",
                approvalState = "not_required",
                executionState = "cancelled",
                createdAt = 1_000L,
                finishedAt = 1_100L,
                updatedAt = 1_100L,
            ),
        )
        val child = ledger().prepareSlots(descriptor(parent, listOf(assetId))).single()
        ledger().cancelBeforeDispatch(child)
        RequestDispatchSession.open(
            repository = repository,
            request = parentSpec(parent),
            owner = "deleted-conversation-test",
            leaseDurationMillis = 10_000L,
            attemptId = RequestAttemptId(UUID.randomUUID().toString()),
            idempotencyKey = "deleted-conversation",
            requestFingerprint = "parent-input",
        ).cancel()
        val coordinator = ImageTaskRecoveryCoordinator(
            context = context,
            requestRepository = repository,
            taskController = taskController(emptyList()),
            toolResultWriter = CapturingWriter(),
            taskSource = DurableImageTaskSource { _, _, _ -> null },
        )

        val report = coordinator.reconcilePending()

        assertEquals(0, report.pending)
        assertEquals(0, report.projected)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun runningParentWithDeletedConversationConvergesAfterChildrenBecomeTerminal() = runTest {
        val parentId = createParent()
        val parentSession = RequestDispatchSession.open(
            repository = repository,
            request = parentSpec(parentId),
            owner = "deleted-running-owner",
            leaseDurationMillis = 10_000L,
            attemptId = RequestAttemptId(UUID.randomUUID().toString()),
            idempotencyKey = "deleted-running",
            requestFingerprint = "parent-input",
        )
        parentSession.markLocalExecutionStarted()
        val invocation = createPolicyBackedInvocation(
            parentId = parentId,
            parentSession = parentSession,
            executionState = "running",
        )
        val child = ledger().prepareSlots(
            descriptor(parentId, listOf(UUID.randomUUID().toString())),
        ).single()
        ledger().cancelBeforeDispatch(child)
        parentSession.releaseLease()

        val settled = repository.settleOrphanedImageParent(
            parent = repository.getRequest(parentId)!!,
            invocation = invocation,
            children = repository.getImageRequestsByParent(parentId),
        )

        assertEquals(RequestState.CANCELLED, settled)
        assertEquals("cancelled", repository.getRequest(parentId)!!.requestState)
        assertEquals("cancelled", repository.getInvocation(
            me.rerere.pale.id.ToolInvocationId(invocation.invocationId),
        )!!.executionState)
    }

    @Test
    fun committingParentWithDeletedConversationConvergesToFailure() = runTest {
        val parentId = createParent()
        val parentSession = openRunningParent(parentId, "deleted-committing")
        val invocation = createPolicyBackedInvocation(parentId, parentSession, "committing")
        val child = ledger().prepareSlots(
            descriptor(parentId, listOf(UUID.randomUUID().toString())),
        ).single()
        ledger().cancelBeforeDispatch(child)
        parentSession.releaseLease()

        val settled = repository.settleOrphanedImageParent(
            parent = repository.getRequest(parentId)!!,
            invocation = invocation,
            children = repository.getImageRequestsByParent(parentId),
        )

        assertEquals(RequestState.FAILED, settled)
        assertEquals("failed", repository.getInvocation(
            me.rerere.pale.id.ToolInvocationId(invocation.invocationId),
        )!!.executionState)
    }

    @Test
    fun resultReceivedParentWithDeletedConversationPreservesBoundaryAndFails() = runTest {
        val parentId = createParent()
        val parentSession = openRunningParent(parentId, "deleted-result-received")
        parentSession.markResultReceived("parent-result-digest")
        val invocation = createPolicyBackedInvocation(parentId, parentSession, "committing")
        val child = ledger().prepareSlots(
            descriptor(parentId, listOf(UUID.randomUUID().toString())),
        ).single()
        ledger().cancelBeforeDispatch(child)
        parentSession.releaseLease()

        val settled = repository.settleOrphanedImageParent(
            parent = repository.getRequest(parentId)!!,
            invocation = invocation,
            children = repository.getImageRequestsByParent(parentId),
        )

        assertEquals(RequestState.FAILED, settled)
        val attempt = repository.getAttempt(parentSession.attemptId)!!
        assertEquals("failed", attempt.attemptState)
        assertEquals("result_received", attempt.billableBoundary)
        assertEquals("failed", repository.getInvocation(
            me.rerere.pale.id.ToolInvocationId(invocation.invocationId),
        )!!.executionState)
    }

    @Test
    fun staleTerminalConversationProjectionCannotHideRecoveredSuccessfulChild() = runTest {
        val parentId = createParent()
        val assetId = UUID.randomUUID().toString()
        val plan = ledger().prepareSlots(descriptor(parentId, listOf(assetId))).single()
        commitSuccess(plan, parentId.value)
        val parent = repository.getRequest(parentId)!!
        val child = repository.getImageRequestsByParent(parentId).single()
        val committed = findCommittedGeneratedImage(context, assetId)!!
        val interruptedState = ChatImageGenerationState(
            requestId = parentId.value,
            prompt = "draw a durable image",
            model = "Image Model",
            size = "1024x1024",
            startedAtEpochMillis = 1_000L,
            finishedAtEpochMillis = 2_000L,
            slots = listOf(
                me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot(
                    index = 0,
                    status = ChatImageSlotStatus.INTERRUPTED,
                    requestId = child.requestId,
                ),
            ),
        )
        val staleTool = UIMessagePart.Tool(
            toolCallId = "tool-${parentId.value}",
            toolName = "generate_image",
            input = """{"prompt":"draw a durable image"}""",
            output = listOf(interruptedState.toStatusPart()),
            executionState = ToolExecutionState.INTERRUPTED,
            requestId = parentId.value,
        )

        assertTrue(
            !hasExactImageLedgerProjection(
                context = context,
                repository = repository,
                parent = parent,
                children = listOf(child),
                tool = staleTool,
                state = interruptedState,
            ),
        )

        val succeededState = interruptedState.copy(
            slots = listOf(
                interruptedState.slots.single().copy(
                    status = ChatImageSlotStatus.SUCCEEDED,
                    imageUrl = committed.file.toUri().toString(),
                ),
            ),
        )
        val exactTool = staleTool.copy(
            output = listOf(
                succeededState.toStatusPart(),
                UIMessagePart.Image(committed.file.toUri().toString(), assetId = assetId),
            ),
            executionState = ToolExecutionState.SUCCEEDED,
        )
        assertTrue(
            hasExactImageLedgerProjection(
                context = context,
                repository = repository,
                parent = parent,
                children = listOf(child),
                tool = exactTool,
                state = succeededState,
            ),
        )
        val wrongUrl = "file:///stale/generated-image.png"
        val wrongState = succeededState.copy(
            slots = listOf(succeededState.slots.single().copy(imageUrl = wrongUrl)),
        )
        val selfConsistentButWrongTool = exactTool.copy(
            output = listOf(
                wrongState.toStatusPart(),
                UIMessagePart.Image(wrongUrl, assetId = assetId),
            ),
        )
        assertTrue(
            !hasExactImageLedgerProjection(
                context = context,
                repository = repository,
                parent = parent,
                children = listOf(child),
                tool = selfConsistentButWrongTool,
                state = wrongState,
            ),
        )
    }

    @Test
    fun terminalFailedSlotCannotRetainAnImagePart() = runTest {
        val parentId = createParent()
        val assetId = UUID.randomUUID().toString()
        val plan = ledger().prepareSlots(descriptor(parentId, listOf(assetId))).single()
        ledger().cancelBeforeDispatch(plan)
        val parent = repository.getRequest(parentId)!!
        val child = repository.getImageRequestsByParent(parentId).single()
        val state = ChatImageGenerationState(
            requestId = parentId.value,
            prompt = "draw",
            model = "Image Model",
            size = "1024x1024",
            startedAtEpochMillis = 1_000L,
            finishedAtEpochMillis = 2_000L,
            slots = listOf(
                me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot(
                    index = 0,
                    status = ChatImageSlotStatus.CANCELLED,
                    imageUrl = "file:///stale.png",
                    requestId = child.requestId,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "tool-${parentId.value}",
            toolName = "generate_image",
            input = "{}",
            output = listOf(state.toStatusPart(), UIMessagePart.Image("file:///stale.png", assetId = assetId)),
            executionState = ToolExecutionState.INTERRUPTED,
            requestId = parentId.value,
        )

        assertTrue(
            !hasExactImageLedgerProjection(
                context = context,
                repository = repository,
                parent = parent,
                children = listOf(child),
                tool = tool,
                state = state,
            ),
        )
    }

    private fun recovery(
        taskController: ChatImageGenerationTaskCoordinator,
        writer: CapturingWriter,
    ) = ImageTaskRecoveryCoordinator(
        context = context,
        requestRepository = repository,
        taskController = taskController,
        toolResultWriter = writer,
        taskSource = DurableImageTaskSource { _, _, _ -> null },
    )

    private fun ledger() = ImageGenerationLedgerCoordinator(
        repository = repository,
        leaseDurationMillis = 10_000L,
        processOwnerId = "task-recovery",
    )

    private suspend fun commitSuccess(plan: ImageGenerationSlotPlan, taskId: String) {
        val bytes = Base64.decode(ONE_PIXEL_PNG, Base64.DEFAULT)
        val folder = File(context.filesDir, FileFolders.CHAT_GENERATED_IMAGES).apply { mkdirs() }
        val file = File(folder, "${plan.assetId}.png")
        file.writeBytes(bytes)
        createdFiles += file
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val session = (ledger().openSlot(plan) as ImageGenerationSlotOpenResult.Dispatch).session
        session.prepareDispatch()
        session.dispatchObserver.onDispatch()
        session.markResponseStarted()
        session.markDurableFileReceived(digest)
        session.commitDurableOutput(
            DurableImageSlotOutput(
                contentDigest = digest,
                assetId = plan.assetId,
                sourceId = taskId,
                relativePath = "${FileFolders.CHAT_GENERATED_IMAGES}/${file.name}",
                mimeType = "image/png",
                byteSize = bytes.size.toLong(),
            ),
        )
    }

    private suspend fun createParent(): RequestId {
        val requestId = RequestId.random()
        repository.createRequest(parentSpec(requestId))
        return requestId
    }

    private suspend fun openRunningParent(parentId: RequestId, identity: String): RequestDispatchSession =
        RequestDispatchSession.open(
            repository = repository,
            request = parentSpec(parentId),
            owner = "$identity-owner",
            leaseDurationMillis = 10_000L,
            attemptId = RequestAttemptId(UUID.randomUUID().toString()),
            idempotencyKey = identity,
            requestFingerprint = "parent-input",
        ).also { it.markLocalExecutionStarted() }

    private suspend fun createPolicyBackedInvocation(
        parentId: RequestId,
        parentSession: RequestDispatchSession,
        executionState: String,
    ): ToolInvocationEntity {
        val invocationId = UUID.randomUUID().toString()
        val permissionId = me.rerere.pale.id.ToolPermissionId(UUID.randomUUID().toString())
        repository.createPermission(
            NewToolPermissionSpec(
                permissionId = permissionId,
                permissionKey = "task-recovery:$invocationId",
                principalKind = "local",
                principalId = "generate_image",
                toolName = "generate_image",
                action = "execute",
                schemaDigest = "schema",
                decision = ToolPermissionDecision.ALLOW,
                scope = ToolPermissionScope.ONCE,
                scopeId = invocationId,
                constraintsJson = "{}",
                capabilitySnapshotJson = "{}",
                policyVersion = 1,
                sourceRequestId = parentId,
                actor = AuditActor.system("task-recovery-test"),
            ),
        )
        return ToolInvocationEntity(
            invocationId = invocationId,
            requestId = parentId.value,
            attemptId = parentSession.attemptId.value,
            providerToolCallId = "tool-${parentId.value}",
            toolName = "generate_image",
            principalKind = "local",
            principalId = "generate_image",
            action = "execute",
            schemaDigest = "schema",
            inputDigest = "input",
            sideEffectClass = "irreversible",
            approvalState = "not_required",
            executionState = executionState,
            permissionId = permissionId.value,
            createdAt = 1_000L,
            startedAt = 1_000L,
            updatedAt = 1_000L,
        ).also { database.requestLedgerDao().insertInvocation(it) }
    }

    private fun parentSpec(requestId: RequestId) = NewRequestSpec(
        requestId = requestId,
        intentKey = "task-recovery-parent:${requestId.value}",
        kind = RequestKind.TOOL_CALL,
        inputDigest = "parent-input",
        capabilitySnapshotJson = "{}",
        resolverVersion = 1,
        actor = AuditActor.system("task-recovery-test"),
        conversationId = UUID.nameUUIDFromBytes("conversation:${requestId.value}".encodeToByteArray()).toString(),
        messageId = UUID.nameUUIDFromBytes("message:${requestId.value}".encodeToByteArray()).toString(),
    )

    private fun descriptor(parent: RequestId, assetIds: List<String>) = ImageGenerationRequestDescriptor(
        parentRequestId = parent,
        taskId = parent.value,
        toolCallId = "tool-${parent.value}",
        prompt = "draw a durable image",
        modelId = "model-1",
        modelName = "Image Model",
        providerId = "provider-1",
        providerKind = "openai",
        size = "1024x1024",
        referenceImageDigests = emptyList(),
        capabilitySnapshotJson = "{\"output\":\"image\"}",
        transportConfigurationDigest = "a".repeat(64),
        requestedImageCount = assetIds.size,
        reservedOutputAssetIds = assetIds,
    )

    private fun task(parent: RequestId, assetIds: List<String>) = ChatImageGenerationTaskRecord(
        taskId = parent.value,
        conversationId = UUID.randomUUID().toString(),
        toolCallId = "tool-${parent.value}",
        requestId = parent.value,
        attempt = 1,
        modelName = "Image Model",
        prompt = "draw a durable image",
        size = "1024x1024",
        requestedImageCount = assetIds.size,
        reservedOutputAssetIds = assetIds,
        startedAtEpochMillis = 1_000L,
        phase = ChatImageGenerationTaskPhase.RUNNING,
    )

    private fun taskController(task: ChatImageGenerationTaskRecord) = taskController(listOf(task))

    private fun taskController(tasks: List<ChatImageGenerationTaskRecord>) = ChatImageGenerationTaskCoordinator(
        store = InMemoryStore(tasks),
        foregroundController = object : ChatImageGenerationForegroundController {
            override fun start(taskId: String) = error("Recovery must not start foreground work")
            override suspend fun awaitReady(taskId: String) = error("Recovery must not await runtime")
        },
        clock = { 2_000L },
    )

    private class InMemoryStore(initial: List<ChatImageGenerationTaskRecord>) : ChatImageGenerationTaskStore {
        private var tasks = initial
        override fun load() = tasks
        override fun save(tasks: List<ChatImageGenerationTaskRecord>) {
            this.tasks = tasks
        }
    }

    private class CapturingWriter(private val writeResult: Boolean = true) : DurableImageToolResultWriter {
        var state: ChatImageGenerationState? = null
        var images: List<UIMessagePart.Image> = emptyList()
        var parentState: RequestState? = null
        override suspend fun write(
            task: ChatImageGenerationTaskRecord,
            state: ChatImageGenerationState,
            images: List<UIMessagePart.Image>,
            parentState: RequestState?,
        ): Boolean {
            this.state = state
            this.images = images
            this.parentState = parentState
            return writeResult
        }
    }

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}

package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.pale.id.RequestId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.RequestState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.imggen.ImageGenerationExecution
import me.rerere.rikkahub.data.imggen.ImageGenerationExecutionEvent
import me.rerere.rikkahub.data.imggen.ImageGenerationExecutionResult
import me.rerere.rikkahub.data.imggen.ImageGenerationGateway
import me.rerere.rikkahub.data.imggen.ImageGenerationRequest
import me.rerere.rikkahub.data.imggen.ImageGenerationCredentialEvidence
import me.rerere.rikkahub.data.imggen.ImageGenerationCredentialTarget
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskExecutor

@RunWith(AndroidJUnit4::class)
class ImageGenerationLedgerCoordinatorTest {
    private val finalDigest = "a".repeat(64)
    private val repairDigest = "b".repeat(64)
    private val startedFileDigest = "c".repeat(64)
    private val missingDigest = "d".repeat(64)
    private lateinit var database: AppDatabase
    private lateinit var repository: RequestLedgerRepository
    private lateinit var coordinator: ImageGenerationLedgerCoordinator
    private var now = 1_000_000L
    private var auditSequence = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RequestLedgerRepository(
            database = database,
            nowMillis = { now },
            requestAuditId = { "image-request-audit-${++auditSequence}" },
            toolAuditId = { "image-tool-audit-unused-${++auditSequence}" },
        )
        coordinator = ImageGenerationLedgerCoordinator(
            repository = repository,
            leaseDurationMillis = 1_000L,
            processOwnerId = "image-test",
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun prepareReservesOneStableLeaseFreeRequestPerPaidSlot() = runTest {
        val parent = createParentRequest()
        val descriptor = descriptor(parent, taskId = "task-a", count = 2)

        val first = coordinator.prepareSlots(descriptor)
        val second = coordinator.prepareSlots(descriptor)

        assertEquals(first.map { it.requestId }, second.map { it.requestId })
        assertEquals(first.map { it.attemptId }, second.map { it.attemptId })
        assertEquals(first.map { it.outputId }, second.map { it.outputId })
        assertNotEquals(first[0].requestId, first[1].requestId)
        first.forEachIndexed { ordinal, plan ->
            assertTrue(plan.inputDigest.matches(Regex("[0-9a-f]{64}")))
            val request = repository.getRequest(plan.requestId)!!
            assertEquals("created", request.requestState)
            assertEquals("not_sent", request.billableBoundary)
            assertEquals(parent.value, request.parentRequestId)
            assertEquals("conversation-1", request.conversationId)
            assertEquals("assistant-1", request.assistantId)
            assertEquals("message-1", request.messageId)
            assertEquals("asset-$ordinal", request.partId)
            assertNull(request.leaseOwner)
            assertNull(request.activeAttemptId)
        }
    }

    @Test
    fun reservationPersistsPrivacyMinimalLineageDescriptorBeforeDispatch() = runTest {
        val parent = createParentRequest()
        val request = descriptor(parent, taskId = "task-lineage", count = 1).copy(
            referenceAssetIds = listOf("reference-asset"),
            referenceSourcePaths = listOf("uploads/reference.png"),
            parentAssetId = "reference-asset",
        )

        coordinator.prepareSlots(request)

        val payload = repository.getImageTaskDescriptor(parent)!!
        assertEquals("task-lineage", payload.getValue("task_id").jsonPrimitive.content)
        assertEquals("reference-asset", payload.getValue("parent_asset_id").jsonPrimitive.content)
        assertEquals(
            "reference-asset",
            payload.getValue("reference_asset_ids").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            "uploads/reference.png",
            payload.getValue("reference_source_paths").jsonArray.single().jsonPrimitive.content,
        )
        assertFalse(payload.containsKey("prompt"))
    }

    @Test
    fun durableFileAndAssetCommitAreRequiredBeforeSuccess() = runTest {
        val plan = coordinator.prepareSlots(descriptor(createParentRequest(), "task-commit", 1)).single()
        val session = coordinator.openSlot(plan).requireDispatch()

        session.dispatchObserver.onDispatch()
        session.markResponseStarted()
        session.markDurableFileReceived(finalDigest)

        val committing = repository.getRequest(plan.requestId)!!
        assertEquals("committing", committing.requestState)
        assertEquals("result_received", committing.billableBoundary)
        assertTrue(repository.getOutputs(plan.requestId).isEmpty())

        session.commitDurableOutput(
            DurableImageSlotOutput(
                contentDigest = finalDigest,
                assetId = plan.assetId,
                sourceId = "task-commit",
                relativePath = "chat_generated_images/final.png",
                mimeType = "image/png",
                byteSize = 128L,
            ),
        )

        assertEquals("succeeded", repository.getRequest(plan.requestId)!!.requestState)
        val output = repository.getOutputs(plan.requestId).single()
        assertEquals(plan.outputId.value, output.outputId)
        assertEquals(plan.assetId, output.assetId)
        assertEquals(finalDigest, output.contentDigest)
    }

    @Test
    fun cancellationReturnsTheLedgerTruthAcrossThePaidBoundary() = runTest {
        val before = coordinator.prepareSlots(descriptor(createParentRequest(), "task-before", 1)).single()
        val beforeSession = coordinator.openSlot(before).requireDispatch()
        assertEquals(RequestState.CANCELLED, beforeSession.finishCancellation())

        val after = coordinator.prepareSlots(descriptor(createParentRequest(), "task-after", 1)).single()
        val afterSession = coordinator.openSlot(after).requireDispatch()
        afterSession.dispatchObserver.onDispatch()
        assertEquals(RequestState.UNKNOWN_OUTCOME, afterSession.finishCancellation())
    }

    @Test
    fun crossedBoundaryCanNeverBeOpenedForAutomaticRedispatch() = runTest {
        val plan = coordinator.prepareSlots(descriptor(createParentRequest(), "task-sent", 1)).single()
        val session = coordinator.openSlot(plan).requireDispatch()
        session.dispatchObserver.onDispatch()

        val failure = runCatching { coordinator.openSlot(plan) }.exceptionOrNull()

        assertTrue(failure is ImageGenerationSlotBlocked || failure is RequestLedgerLeaseConflict)
        assertEquals(1, database.requestLedgerDao().getAttempts(plan.requestId.value).size)
    }

    @Test
    fun coldStartClassifiesSlotsWithoutCallingProviderAndRepairsExactFile() = runTest {
        val notSent = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-not-sent", 1),
        ).single()
        val sent = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-sent-recovery", 1),
        ).single()
        coordinator.openSlot(sent).requireDispatch().dispatchObserver.onDispatch()
        val partial = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-partial", 1),
        ).single()
        coordinator.openSlot(partial).requireDispatch().run {
            dispatchObserver.onDispatch()
            markResponseStarted()
        }
        val startedWithFile = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-started-file", 1),
        ).single()
        coordinator.openSlot(startedWithFile).requireDispatch().run {
            dispatchObserver.onDispatch()
            markResponseStarted()
        }
        val committing = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-repair", 1),
        ).single()
        coordinator.openSlot(committing).requireDispatch().run {
            dispatchObserver.onDispatch()
            markResponseStarted()
            markDurableFileReceived(repairDigest)
        }
        now += 2_000L
        var resolverCalls = 0
        val reconciler = ImageRequestReconciler(
            repository = repository,
            durableOutputResolver = DurableImageSlotResolver { candidate ->
                resolverCalls++
                when (candidate.requestId) {
                    startedWithFile.requestId -> {
                        assertNull(candidate.checkpointDigest)
                        DurableImageSlotOutput(
                            contentDigest = startedFileDigest,
                            assetId = startedWithFile.assetId,
                            sourceId = "task-started-file",
                            relativePath = "chat_generated_images/started.png",
                            mimeType = "image/png",
                            byteSize = 192L,
                        )
                    }

                    committing.requestId -> {
                        assertEquals(repairDigest, candidate.checkpointDigest)
                        DurableImageSlotOutput(
                            contentDigest = repairDigest,
                            assetId = committing.assetId,
                            sourceId = "task-repair",
                            relativePath = "chat_generated_images/repaired.png",
                            mimeType = "image/png",
                            byteSize = 256L,
                        )
                    }

                    else -> null
                }
            },
            nowMillis = { now },
            ownerId = "recovery-test",
        )

        val report = reconciler.reconcilePending()

        assertEquals(5, report.inspected)
        assertEquals(1, report.cancelled)
        assertEquals(1, report.unknown)
        assertEquals(1, report.interrupted)
        assertEquals(2, report.committed)
        assertEquals(0, report.failed)
        assertTrue(report.failures.isEmpty())
        assertEquals(3, resolverCalls)
        assertEquals("cancelled", repository.getRequest(notSent.requestId)!!.requestState)
        assertEquals("unknown_outcome", repository.getRequest(sent.requestId)!!.requestState)
        assertEquals("interrupted", repository.getRequest(partial.requestId)!!.requestState)
        assertEquals("succeeded", repository.getRequest(startedWithFile.requestId)!!.requestState)
        assertEquals("succeeded", repository.getRequest(committing.requestId)!!.requestState)
        assertEquals(1, database.requestLedgerDao().getAttempts(notSent.requestId.value).size)
        assertEquals(1, database.requestLedgerDao().getAttempts(sent.requestId.value).size)
    }

    @Test
    fun committingSlotWithoutExactDurableFileFailsClosed() = runTest {
        val plan = coordinator.prepareSlots(descriptor(createParentRequest(), "task-missing", 1)).single()
        coordinator.openSlot(plan).requireDispatch().run {
            dispatchObserver.onDispatch()
            markResponseStarted()
            markDurableFileReceived(missingDigest)
        }
        now += 2_000L

        val report = ImageRequestReconciler(
            repository = repository,
            nowMillis = { now },
            ownerId = "missing-test",
        ).reconcilePending()

        assertEquals(1, report.inspected)
        assertEquals(1, report.failed)
        assertEquals("failed", repository.getRequest(plan.requestId)!!.requestState)
        assertTrue(repository.getOutputs(plan.requestId).isEmpty())
    }

    @Test
    fun executingImageRequestWithoutAttemptFailsClosedWithoutCreatingOne() = runTest {
        val requestId = "44444444-4444-4444-4444-444444444401"
        database.requestLedgerDao().insertRequest(
            RequestLedgerEntity(
                requestId = requestId,
                intentKey = "image-slot:v1:parent:missing-attempt:0",
                requestKind = "image_generation",
                conversationId = "conversation-1",
                messageId = "message-1",
                partId = "asset-missing-attempt",
                inputDigest = "missing-attempt-input",
                capabilitySnapshotJson = "{}",
                resolverVersion = 1,
                approvalState = "not_required",
                requestState = "dispatching",
                billableBoundary = "not_sent",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val report = ImageRequestReconciler(
            repository = repository,
            nowMillis = { now },
            ownerId = "missing-attempt-test",
        ).reconcilePending()

        assertEquals(1, report.inspected)
        assertEquals(1, report.failed)
        assertTrue(report.failures.isEmpty())
        assertEquals("failed", repository.getRequest(RequestId(requestId))!!.requestState)
        assertTrue(database.requestLedgerDao().getAttempts(requestId).isEmpty())
    }

    @Test
    fun corruptCreatedImageRequestWithSentBoundaryFailsClosedWithoutCreatingAttempt() = runTest {
        val requestId = "44444444-4444-4444-4444-444444444402"
        database.requestLedgerDao().insertRequest(
            RequestLedgerEntity(
                requestId = requestId,
                intentKey = "image-slot:v1:parent:created-sent-without-attempt:0",
                requestKind = "image_generation",
                conversationId = "conversation-1",
                messageId = "message-1",
                partId = "asset-created-sent-without-attempt",
                inputDigest = "created-sent-without-attempt-input",
                capabilitySnapshotJson = "{}",
                resolverVersion = 1,
                approvalState = "not_required",
                requestState = "created",
                billableBoundary = "sent",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val report = ImageRequestReconciler(
            repository = repository,
            nowMillis = { now },
            ownerId = "created-sent-without-attempt-test",
        ).reconcilePending()

        assertEquals(1, report.inspected)
        assertEquals(1, report.failed)
        assertTrue(report.failures.isEmpty())
        val settled = repository.getRequest(RequestId(requestId))!!
        assertEquals("failed", settled.requestState)
        assertEquals("sent", settled.billableBoundary)
        assertTrue(database.requestLedgerDao().getAttempts(requestId).isEmpty())
    }

    @Test
    fun existingOutputRowCannotHideADeletedOrCorruptFile() = runTest {
        val plan = coordinator.prepareSlots(descriptor(createParentRequest(), "task-output-only", 1)).single()
        coordinator.openSlot(plan).requireDispatch().run {
            dispatchObserver.onDispatch()
            markResponseStarted()
            markDurableFileReceived(missingDigest)
        }
        database.requestLedgerDao().insertOutput(
            RequestOutputEntity(
                outputId = plan.outputId.value,
                requestId = plan.requestId.value,
                attemptId = plan.attemptId.value,
                outputKind = "image_generation_slot",
                ordinal = 0,
                conversationId = "conversation-1",
                messageId = "message-1",
                partId = plan.assetId,
                assetId = plan.assetId,
                sourceId = "task-output-only",
                contentDigest = missingDigest,
                committedAt = now,
            ),
        )
        now += 2_000L

        val report = ImageRequestReconciler(
            repository = repository,
            nowMillis = { now },
            ownerId = "output-only-test",
        ).reconcilePending()

        assertEquals(1, report.failed)
        assertEquals("failed", repository.getRequest(plan.requestId)!!.requestState)
    }

    @Test
    fun finalProviderItemFollowedByLocalCommitFailureRemainsRecoverable() = runTest {
        val plan = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-local-commit-failure", 1),
        ).single()
        val session = coordinator.openSlot(plan).requireDispatch()
        val executor = ImageGenerationTaskExecutor(
            gateway = object : ImageGenerationGateway {
                override suspend fun generate(request: ImageGenerationRequest) = flow {
                    request.dispatchObserver.onDispatch()
                    emit(ImageGenerationItem(data = "unused", mimeType = "image/png"))
                }
            },
        )

        val result = executor.execute(
            execution = ImageGenerationExecution(
                requestId = plan.requestId.value,
                attempt = 1,
                request = ImageGenerationRequest(
                    prompt = "draw",
                    modelId = "image-model",
                    modelName = "Image Model",
                    size = "1024x1024",
                    numberOfImages = 1,
                ),
                ledgerSession = session,
            ),
            onEvent = { event ->
                if (event is ImageGenerationExecutionEvent.FinalImage) {
                    error("simulated local metadata failure after final provider item")
                }
            },
        )

        assertTrue(result is ImageGenerationExecutionResult.Failure)
        val durable = repository.getRequest(plan.requestId)!!
        assertTrue(!RequestState.valueOf(durable.requestState.uppercase()).isTerminal)
        assertEquals(BillableBoundary.RESPONSE_STARTED.name.lowercase(), durable.billableBoundary)
        assertTrue(repository.getOutputs(plan.requestId).isEmpty())
    }

    @Test
    fun imageChildDoesNotInheritUnrelatedParentCredential() = runTest {
        val plan = coordinator.prepareSlots(
            descriptor(createParentRequest(), "task-no-image-auth", 1),
        ).single()

        assertEquals(null, repository.getRequest(plan.requestId)!!.credentialRefId)
    }

    @Test
    fun executorRejectsCredentialDifferentFromLedgerBeforeNetworkDispatch() = runTest {
        val parent = createParentRequest()
        val plan = coordinator.prepareSlots(
            descriptor(parent, "task-credential-mismatch", 1, credentialRefId = "vault-ref-A"),
        ).single()
        val session = coordinator.openSlot(plan).requireDispatch()
        var gatewayCalls = 0
        val executor = ImageGenerationTaskExecutor(
            gateway = object : ImageGenerationGateway {
                override suspend fun generate(request: ImageGenerationRequest) = flow {
                    gatewayCalls++
                    emit(ImageGenerationItem(data = "unused", mimeType = "image/png"))
                }
            },
        )

        val result = executor.execute(
            execution = ImageGenerationExecution(
                requestId = plan.requestId.value,
                attempt = 1,
                request = ImageGenerationRequest(
                    prompt = "draw",
                    modelId = "image-model",
                    modelName = "Image Model",
                    credentialEvidence = ImageGenerationCredentialEvidence(
                        reference = "vault-ref-B",
                        namespace = "settings.providers",
                        ownerStableId = "openai:provider-1",
                        fieldSlot = "apikey",
                        kind = "secret",
                        target = ImageGenerationCredentialTarget.PROVIDER_API_KEY,
                    ),
                    transportConfigurationDigest = "a".repeat(64),
                    size = "1024x1024",
                    numberOfImages = 1,
                ),
                ledgerSession = session,
            ),
            onEvent = {},
        )

        assertTrue(result is ImageGenerationExecutionResult.Failure)
        assertEquals(0, gatewayCalls)
        assertEquals(
            BillableBoundary.NOT_SENT.name.lowercase(),
            repository.getRequest(plan.requestId)!!.billableBoundary,
        )
    }

    @Test
    fun coldRecoveryKeepsOriginalCredentialEvidenceAndRejectsCurrentReplacement() = runTest {
        val parent = createParentRequest()
        val frozen = descriptor(
            parentRequestId = parent,
            taskId = "task-cold-credential-recovery",
            count = 1,
            credentialRefId = "vault-ref-A",
        )
        val originalPlan = coordinator.prepareSlots(frozen).single()

        val recoveredCoordinator = ImageGenerationLedgerCoordinator(
            repository = repository,
            leaseDurationMillis = 1_000L,
            processOwnerId = "image-recovery-test",
        )
        assertSuspendFails<RequestLedgerIdentityConflict> {
            recoveredCoordinator.prepareSlots(
                frozen.copy(
                    credentialRefId = "vault-ref-B",
                    transportConfigurationDigest = "b".repeat(64),
                ),
            )
        }

        val recoveredPlan = recoveredCoordinator.prepareSlots(frozen).single()
        val recoveredSession = recoveredCoordinator.openSlot(recoveredPlan).requireDispatch()
        assertEquals(originalPlan.requestId, recoveredPlan.requestId)
        assertEquals("vault-ref-A", recoveredSession.credentialRefId)
        assertEquals("vault-ref-A", repository.getRequest(recoveredPlan.requestId)!!.credentialRefId)
    }

    private suspend fun createParentRequest(): RequestId {
        val requestId = RequestId.random()
        repository.createRequest(
            NewRequestSpec(
                requestId = requestId,
                intentKey = "parent:${requestId.value}",
                kind = RequestKind.TOOL_CALL,
                inputDigest = "parent-input-${requestId.value}",
                capabilitySnapshotJson = "{}",
                resolverVersion = 1,
                actor = AuditActor.system("test"),
                conversationId = "conversation-1",
                assistantId = "assistant-1",
                messageId = "message-1",
                workspaceId = "workspace-1",
                credentialRefId = "credential-1",
            ),
        )
        return requestId
    }

    private fun descriptor(
        parentRequestId: RequestId,
        taskId: String,
        count: Int,
        credentialRefId: String? = null,
    ) = ImageGenerationRequestDescriptor(
        parentRequestId = parentRequestId,
        taskId = taskId,
        toolCallId = "tool-call-$taskId",
        prompt = "draw a durable cat",
        modelId = "image-model",
        modelName = "Image Model",
        providerId = "provider-1",
        providerKind = "openai",
        size = "1024x1024",
        referenceImageDigests = listOf("reference-sha256"),
        capabilitySnapshotJson = "{\"image\":true}",
        transportConfigurationDigest = "a".repeat(64),
        credentialRefId = credentialRefId,
        requestedImageCount = count,
        reservedOutputAssetIds = List(count) { "asset-$it" },
    )

    private fun ImageGenerationSlotOpenResult.requireDispatch() =
        (this as ImageGenerationSlotOpenResult.Dispatch).session

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.name}, got ${failure::class.java.name}", failure)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}

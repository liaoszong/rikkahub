package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.ToolInvocationId
import me.rerere.pale.id.ToolPermissionId
import me.rerere.pale.request.RequestKind
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ToolExecutionLedgerCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RequestLedgerRepository
    private lateinit var coordinator: ToolExecutionLedgerCoordinator
    private var requestAudit = 0
    private var toolAudit = 0
    private var persisted = 0
    private var durableMessage: UIMessage? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RequestLedgerRepository(
            database = database,
            requestAuditId = { "tool-request-audit-${++requestAudit}" },
            toolAuditId = { "tool-audit-${++toolAudit}" },
        )
        coordinator = ToolExecutionLedgerCoordinator(
            repository = repository,
            json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
            processOwnerId = "test-process",
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun pendingToolFreezesOneChildAttemptAndApprovalEvidence() = runTest {
        createParent()
        val tool = tool(ToolApprovalState.Pending)

        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition(needsApproval = true))
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition(needsApproval = true))

        val request = repository.getRequest(RequestId(TOOL_REQUEST_ID))!!
        val attempts = database.requestLedgerDao().getAttempts(TOOL_REQUEST_ID)
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        val permission = database.requestLedgerDao().getPermissionById(invocation.permissionId!!)!!
        assertEquals(PARENT_REQUEST_ID.value, request.parentRequestId)
        assertEquals("tool_call", request.requestKind)
        assertEquals("queued", request.requestState)
        assertEquals("not_sent", request.billableBoundary)
        assertNull(request.leaseOwner)
        assertEquals(1, attempts.size)
        assertEquals("waiting_approval", invocation.executionState)
        assertEquals("pending", invocation.approvalState)
        assertEquals("ask", permission.decision)
    }

    @Test
    fun approvedToolCommitsResultBeforeRequestSuccess() = runTest {
        createParent()
        val definition = definition(needsApproval = true)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool(ToolApprovalState.Pending), definition)
        val approved = tool(ToolApprovalState.Approved)
        val session = coordinator.openExecution(ledgerContext(), approved, definition)

        session.startExternal()
        val result = approved.copy(
            output = listOf(UIMessagePart.Text("ok")),
            executionState = ToolExecutionState.SUCCEEDED,
        )
        session.commitDurableResult(result) { persisted++ }

        val request = repository.getRequest(RequestId(TOOL_REQUEST_ID))!!
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        assertEquals("succeeded", request.requestState)
        assertEquals("result_committed", request.billableBoundary)
        assertEquals("succeeded", invocation.executionState)
        assertEquals("approved", invocation.approvalState)
        assertTrue(!invocation.resultDigest.isNullOrBlank())
        assertEquals(1, database.requestLedgerDao().getOutputs(TOOL_REQUEST_ID).size)
        assertEquals(1, persisted)
    }

    @Test
    fun deniedToolProducesDurableLocalOutcomeWithoutSentBoundary() = runTest {
        createParent()
        val definition = definition(needsApproval = true)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool(ToolApprovalState.Pending), definition)
        val denied = tool(ToolApprovalState.Denied("no"))
        val session = coordinator.openExecution(ledgerContext(), denied, definition)

        session.startLocal()
        val runningAttempt = database.requestLedgerDao().getAttempts(TOOL_REQUEST_ID).single()
        assertEquals("not_sent", runningAttempt.billableBoundary)
        assertNull(runningAttempt.sentAt)
        session.commitDurableResult(
            denied.copy(
                output = listOf(UIMessagePart.Text("denied")),
                executionState = ToolExecutionState.FAILED,
            ),
        ) { persisted++ }

        val request = repository.getRequest(RequestId(TOOL_REQUEST_ID))!!
        val attempt = database.requestLedgerDao().getAttempts(TOOL_REQUEST_ID).single()
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        assertEquals("succeeded", request.requestState)
        assertEquals("result_committed", request.billableBoundary)
        assertEquals("result_committed", attempt.billableBoundary)
        assertEquals("failed", invocation.executionState)
        assertEquals("denied", invocation.approvalState)
    }

    @Test
    fun answeredToolIsAuthorizedAsLocalResult() = runTest {
        createParent()
        val definition = definition(needsApproval = true, name = "ask_user")
        coordinator.prepare(
            PARENT_REQUEST_ID,
            ledgerContext(),
            tool(ToolApprovalState.Pending, name = "ask_user"),
            definition,
        )
        val answered = tool(ToolApprovalState.Answered("blue"), name = "ask_user")
        val session = coordinator.openExecution(ledgerContext(), answered, definition)

        session.startLocal()
        session.commitDurableResult(
            answered.copy(
                output = listOf(UIMessagePart.Text("blue")),
                executionState = ToolExecutionState.SUCCEEDED,
            ),
        ) { persisted++ }

        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        assertEquals("answered", invocation.approvalState)
        assertEquals("succeeded", invocation.executionState)
        assertEquals("succeeded", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
    }

    @Test
    fun answeredApprovalCannotBypassOrdinaryToolExecution() = runTest {
        createParent()
        val definition = definition(needsApproval = true)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool(ToolApprovalState.Pending), definition)

        val failure = runCatching {
            coordinator.openExecution(
                ledgerContext(),
                tool(ToolApprovalState.Answered("forged")),
                definition,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        assertEquals("waiting_approval", invocation.executionState)
        assertEquals("ask", database.requestLedgerDao().getPermissionById(invocation.permissionId!!)!!.decision)
    }

    @Test
    fun cancellationAfterToolDispatchIsUnknownAndNeverAutoRetried() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)

        session.startExternal()
        session.finishCancellation(externalBoundaryCrossed = true)

        val request = repository.getRequest(RequestId(TOOL_REQUEST_ID))!!
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        assertEquals("unknown_outcome", request.requestState)
        assertEquals("unknown", request.billableBoundary)
        assertEquals("unknown_outcome", invocation.executionState)
        assertEquals(1, database.requestLedgerDao().getAttempts(TOOL_REQUEST_ID).size)
    }

    @Test
    fun longRunningToolRenewsLeaseUntilDurableCommit() = runTest {
        createParent()
        val shortLeaseCoordinator = ToolExecutionLedgerCoordinator(
            repository = repository,
            json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
            leaseDurationMillis = 2_000L,
            processOwnerId = "short-lease-process",
        )
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        shortLeaseCoordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = shortLeaseCoordinator.openExecution(ledgerContext(), tool, definition)
        session.startExternal()

        session.withLeaseHeartbeat {
            withContext(Dispatchers.Default) { delay(2_500L) }
        }
        session.commitDurableResult(
            tool.copy(
                output = listOf(UIMessagePart.Text("late")),
                executionState = ToolExecutionState.SUCCEEDED,
            ),
        ) { persisted++ }

        assertEquals("succeeded", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertTrue(
            database.requestLedgerDao().getRequestAudit(TOOL_REQUEST_ID)
                .any { it.eventKind == "lease_renewed" },
        )
    }

    @Test
    fun sameStableRequestRejectsChangedToolInput() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool(ToolApprovalState.Auto), definition)

        val failure = runCatching {
            coordinator.prepare(
                PARENT_REQUEST_ID,
                ledgerContext(),
                tool(ToolApprovalState.Auto).copy(input = "{\"changed\":true}"),
                definition,
            )
        }.exceptionOrNull()

        assertTrue(failure is RequestLedgerIdentityConflict)
        assertEquals(1, database.requestLedgerDao().getAttempts(TOOL_REQUEST_ID).size)
    }

    @Test
    fun startupCommitsPersistedToolResultWithoutExecutingAgain() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)
        session.startExternal()
        val result = tool.copy(
            output = listOf(UIMessagePart.Text("durable")),
            executionState = ToolExecutionState.SUCCEEDED,
        )
        durableMessage = assistantMessage(result)
        session.releaseForLocalRepair(IllegalStateException("process stopped after conversation commit"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.committed)
        assertEquals("succeeded", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertEquals(
            "succeeded",
            database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single().executionState,
        )
        assertEquals(1, database.requestLedgerDao().getOutputs(TOOL_REQUEST_ID).size)
    }

    @Test
    fun startupMarksSentToolWithoutDurableResultUnknown() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)
        session.startExternal()
        session.releaseForLocalRepair(IllegalStateException("process stopped during tool"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.unknown)
        assertEquals("unknown_outcome", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertEquals(
            "unknown_outcome",
            database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single().executionState,
        )
    }

    @Test
    fun startupPreservesTerminalUnknownInvocationWithoutDurableResult() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)
        session.startExternal()
        session.releaseForLocalRepair(IllegalStateException("process stopped before request terminal state"))
        advanceInvocationForCrash(me.rerere.pale.request.ToolExecutionState.UNKNOWN_OUTCOME)

        val report = reconciler().reconcilePending()

        assertEquals(1, report.unknown)
        assertEquals("unknown_outcome", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertEquals("unknown", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.billableBoundary)
        assertEquals(
            "unknown_outcome",
            database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single().executionState,
        )
    }

    @Test
    fun startupPreservesTerminalCancelledInvocationWithoutDurableResult() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)
        session.startLocal()
        session.releaseForLocalRepair(IllegalStateException("process stopped before local cancellation commit"))
        advanceInvocationForCrash(me.rerere.pale.request.ToolExecutionState.CANCELLED)

        val report = reconciler().reconcilePending()

        assertEquals(1, report.cancelled)
        assertEquals("cancelled", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertEquals("not_sent", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.billableBoundary)
        assertEquals(
            "cancelled",
            database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single().executionState,
        )
    }

    @Test
    fun startupRejectsDurableResultThatChangedAfterInvocationSuccess() = runTest {
        createParent()
        val definition = definition(needsApproval = false)
        val tool = tool(ToolApprovalState.Auto)
        coordinator.prepare(PARENT_REQUEST_ID, ledgerContext(), tool, definition)
        val session = coordinator.openExecution(ledgerContext(), tool, definition)
        session.startExternal()
        session.releaseForLocalRepair(IllegalStateException("process stopped before attempt checkpoint"))
        advanceInvocationForCrash(
            nextState = me.rerere.pale.request.ToolExecutionState.COMMITTING,
        )
        advanceInvocationForCrash(
            nextState = me.rerere.pale.request.ToolExecutionState.SUCCEEDED,
            resultDigest = "digest-before-tamper",
        )
        durableMessage = assistantMessage(
            tool.copy(
                output = listOf(UIMessagePart.Text("tampered")),
                executionState = ToolExecutionState.SUCCEEDED,
            ),
        )

        val report = reconciler().reconcilePending()

        assertEquals(0, report.committed)
        assertEquals(1, report.failures.size)
        assertEquals("running", repository.getRequest(RequestId(TOOL_REQUEST_ID))!!.requestState)
        assertEquals(0, database.requestLedgerDao().getOutputs(TOOL_REQUEST_ID).size)
    }

    private suspend fun advanceInvocationForCrash(
        nextState: me.rerere.pale.request.ToolExecutionState,
        resultDigest: String? = null,
    ) {
        val requestId = RequestId(TOOL_REQUEST_ID)
        val invocation = database.requestLedgerDao().getInvocations(TOOL_REQUEST_ID).single()
        val lease = repository.claimRequest(requestId, "test-crash-window", 30_000L)
        try {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = lease,
                    invocationId = ToolInvocationId(invocation.invocationId),
                    nextApprovalState = me.rerere.pale.request.ToolApprovalState.NOT_REQUIRED,
                    nextExecutionState = nextState,
                    permissionId = invocation.permissionId?.let(::ToolPermissionId),
                    resultDigest = resultDigest,
                    actor = AuditActor.system("test-crash-window"),
                ),
            )
        } finally {
            repository.releaseRequest(lease)
        }
    }

    private suspend fun createParent() {
        repository.createRequest(
            NewRequestSpec(
                requestId = PARENT_REQUEST_ID,
                intentKey = "parent",
                kind = RequestKind.CHAT_GENERATION,
                inputDigest = "parent-input",
                capabilitySnapshotJson = "{}",
                resolverVersion = 1,
                actor = AuditActor.system("test"),
                conversationId = CONVERSATION_ID,
                assistantId = ASSISTANT_ID,
                messageId = MESSAGE_ID,
            ),
        )
    }

    private fun ledgerContext() = ChatGenerationLedgerContext(
        conversationId = CONVERSATION_ID,
        assistantId = ASSISTANT_ID,
        responseMessageId = MESSAGE_ID,
        workspaceId = WORKSPACE_ID,
        persistCurrentConversation = { persisted++ },
        persistMessages = { persisted++ },
        loadResponseMessage = { durableMessage },
    )

    private fun reconciler() = ToolRequestReconciler(
        repository = repository,
        coordinator = coordinator,
        loadDurableMessage = { _, _ -> durableMessage },
        nowMillis = { Long.MAX_VALUE },
        ownerId = "test-reconciler",
    )

    private fun assistantMessage(tool: UIMessagePart.Tool) = UIMessage(
        id = Uuid.parse(MESSAGE_ID),
        role = MessageRole.ASSISTANT,
        parts = listOf(tool),
    )

    private fun tool(approval: ToolApprovalState, name: String = "test_tool") = UIMessagePart.Tool(
        toolCallId = "provider-call-1",
        toolName = name,
        input = "{}",
        approvalState = approval,
        requestId = TOOL_REQUEST_ID,
    )

    private fun definition(needsApproval: Boolean, name: String = "test_tool") = Tool(
        name = name,
        description = "test",
        needsApproval = { needsApproval },
        execute = { listOf(UIMessagePart.Text("ok")) },
        ledgerSideEffectClass = "unknown",
    )

    companion object {
        private val PARENT_REQUEST_ID = RequestId("11111111-1111-1111-1111-111111111111")
        private const val TOOL_REQUEST_ID = "22222222-2222-2222-2222-222222222222"
        private const val CONVERSATION_ID = "33333333-3333-3333-3333-333333333333"
        private const val ASSISTANT_ID = "44444444-4444-4444-4444-444444444444"
        private const val MESSAGE_ID = "55555555-5555-5555-5555-555555555555"
        private const val WORKSPACE_ID = "66666666-6666-6666-6666-666666666666"
    }
}

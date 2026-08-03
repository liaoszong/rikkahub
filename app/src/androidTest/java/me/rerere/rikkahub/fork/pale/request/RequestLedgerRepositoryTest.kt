package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.id.ToolInvocationId
import me.rerere.pale.id.ToolPermissionId
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestAttemptState
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.ToolApprovalState
import me.rerere.pale.request.ToolExecutionState
import me.rerere.pale.request.ToolPermissionDecision
import me.rerere.pale.request.ToolPermissionScope
import me.rerere.pale.request.ToolSideEffectClass
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestLedgerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RequestLedgerDAO
    private lateinit var repository: RequestLedgerRepository
    private var failAt: RequestLedgerCheckpoint? = null
    private var requestAuditSequence = 0
    private var toolAuditSequence = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.requestLedgerDao()
        repository = RequestLedgerRepository(
            database = database,
            nowMillis = { NOW },
            requestAuditId = { "request-audit-${++requestAuditSequence}" },
            toolAuditId = { "tool-audit-${++toolAuditSequence}" },
            faultInjector = RequestLedgerFaultInjector { point ->
                if (point == failAt) throw InjectedLedgerFailure(point)
            },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createRequestIsIdempotentAndAuditIsAtomic() = runTest {
        val spec = requestSpec("request-1", "intent-1")

        val first = repository.createRequest(spec)
        val second = repository.createRequest(spec)

        assertEquals(first, second)
        assertEquals(1, dao.getRequestAudit("request-1").size)

        failAt = RequestLedgerCheckpoint.AFTER_REQUEST_INSERT
        assertSuspendFails<InjectedLedgerFailure> {
            repository.createRequest(requestSpec("request-2", "intent-2"))
        }
        assertNull(dao.getRequest("request-2"))
    }

    @Test
    fun requestIntentRejectsDifferentFrozenExecutionSnapshot() = runTest {
        val spec = requestSpec("request-1", "intent-1")
        repository.createRequest(spec)

        assertSuspendFails<RequestLedgerIdentityConflict> {
            repository.createRequest(spec.copy(modelId = "different-model"))
        }
        assertSuspendFails<RequestLedgerIdentityConflict> {
            repository.createRequest(spec.copy(credentialRefId = "different-credential"))
        }
    }

    @Test
    fun attemptPreparationRollsBackStateAndCountAfterInjectedCrash() = runTest {
        repository.createRequest(requestSpec("request-1", "intent-1"))
        val lease = repository.claimRequest(RequestId("request-1"), "worker-1", 1_000)
        failAt = RequestLedgerCheckpoint.AFTER_ATTEMPT_INSERT

        assertSuspendFails<InjectedLedgerFailure> {
            repository.beginAttempt(beginAttempt(lease, "attempt-1", "idem-1"))
        }

        val request = dao.getRequest("request-1")!!
        assertEquals("created", request.requestState)
        assertEquals(0, request.attemptCount)
        assertNull(request.activeAttemptId)
        assertTrue(dao.getAttempts("request-1").isEmpty())
    }

    @Test
    fun requestAttemptOutputAndAuditCommitAsOneDurableLifecycle() = runTest {
        repository.createRequest(requestSpec("request-1", "intent-1"))
        val lease = repository.claimRequest(RequestId("request-1"), "worker-1", 1_000)
        repository.beginAttempt(beginAttempt(lease, "attempt-1", "idem-1"))

        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.DISPATCHING, BillableBoundary.SENT),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.RUNNING, BillableBoundary.RESPONSE_STARTED),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.COMMITTING, BillableBoundary.RESULT_RECEIVED),
        )
        repository.commitOutput(
            CommitRequestOutputCommand(
                lease = lease,
                attemptId = RequestAttemptId("attempt-1"),
                outputId = RequestOutputId("output-1"),
                outputKind = "message",
                ordinal = 0,
                contentDigest = "output-digest",
                actor = AuditActor.system("worker-1"),
                conversationId = "conversation-1",
                messageId = "message-1",
                partId = "part-1",
            ),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.SUCCEEDED, BillableBoundary.RESULT_COMMITTED),
        )

        val request = dao.getRequest("request-1")!!
        val attempt = dao.getAttempt("attempt-1")!!
        assertEquals("succeeded", request.requestState)
        assertEquals("result_committed", request.billableBoundary)
        assertNull(request.activeAttemptId)
        assertEquals("succeeded", attempt.attemptState)
        assertEquals(1, dao.getOutputs("request-1").size)
        val audit = dao.getRequestAudit("request-1")
        assertTrue(audit.size >= 10)
        assertEquals(audit.map { it.eventSeq }.sorted(), audit.map { it.eventSeq })
    }

    @Test
    fun outputAndAuditRollbackTogetherAfterInjectedCrash() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.advanceAttempt(
            advance(lease, attemptId, RequestAttemptState.COMMITTING, BillableBoundary.RESULT_RECEIVED),
        )
        val auditBefore = dao.getRequestAudit("request-1").size
        failAt = RequestLedgerCheckpoint.AFTER_OUTPUT_INSERT

        assertSuspendFails<InjectedLedgerFailure> {
            repository.commitOutput(
                CommitRequestOutputCommand(
                    lease = lease,
                    attemptId = RequestAttemptId(attemptId),
                    outputId = RequestOutputId("output-1"),
                    outputKind = "message",
                    ordinal = 0,
                    contentDigest = "output-digest",
                    actor = AuditActor.system("worker-1"),
                ),
            )
        }

        assertTrue(dao.getOutputs("request-1").isEmpty())
        assertEquals(auditBefore, dao.getRequestAudit("request-1").size)
    }

    @Test
    fun succeededRequiresCommittedBoundaryAndExplicitOutput() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.advanceAttempt(
            advance(lease, attemptId, RequestAttemptState.COMMITTING, BillableBoundary.RESULT_RECEIVED),
        )

        assertSuspendFails<IllegalStateException> {
            repository.advanceAttempt(
                advance(lease, attemptId, RequestAttemptState.SUCCEEDED, BillableBoundary.RESULT_RECEIVED),
            )
        }
        assertSuspendFails<IllegalStateException> {
            repository.advanceAttempt(
                advance(lease, attemptId, RequestAttemptState.SUCCEEDED, BillableBoundary.RESULT_COMMITTED),
            )
        }
        assertEquals("committing", dao.getAttempt(attemptId)?.attemptState)
        assertEquals(attemptId, dao.getRequest("request-1")?.activeAttemptId)
    }

    @Test
    fun outputIdempotencyRejectsDifferentDurableAnchors() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.advanceAttempt(
            advance(lease, attemptId, RequestAttemptState.COMMITTING, BillableBoundary.RESULT_RECEIVED),
        )
        val command = CommitRequestOutputCommand(
            lease = lease,
            attemptId = RequestAttemptId(attemptId),
            outputId = RequestOutputId("output-1"),
            outputKind = "message",
            ordinal = 0,
            contentDigest = "output-digest",
            actor = AuditActor.system("worker-1"),
            conversationId = "conversation-1",
            messageId = "message-1",
            partId = "part-1",
        )
        repository.commitOutput(command)

        assertSuspendFails<RequestLedgerIdentityConflict> {
            repository.commitOutput(command.copy(messageId = "different-message"))
        }
        assertEquals(1, dao.getOutputs("request-1").size)
    }

    @Test
    fun billableRetryRequiresChargeAcceptanceAndCreatesNewAttempt() = runTest {
        repository.createRequest(requestSpec("request-1", "intent-1"))
        val lease = repository.claimRequest(RequestId("request-1"), "worker-1", 1_000)
        repository.beginAttempt(beginAttempt(lease, "attempt-1", "idem-1"))
        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.DISPATCHING, BillableBoundary.SENT),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-1", RequestAttemptState.FAILED, BillableBoundary.SENT),
        )

        assertSuspendFails<RequestLedgerRetryRejected> {
            repository.beginAttempt(beginAttempt(lease, "attempt-2", "idem-2"))
        }
        val retry = repository.beginAttempt(
            beginAttempt(lease, "attempt-2", "idem-2", acceptsPossibleCharge = true),
        )

        assertEquals(2, retry.attemptOrdinal)
        assertEquals("attempt-2", dao.getRequest("request-1")?.activeAttemptId)
        assertEquals("queued", dao.getRequest("request-1")?.requestState)
        assertEquals("sent", dao.getRequest("request-1")?.billableBoundary)
    }

    @Test
    fun permissionDecisionAndAuditRollbackTogether() = runTest {
        repository.createRequest(requestSpec("request-1", "intent-1"))
        val permission = repository.createPermission(permissionSpec())
        assertEquals("ask", permission.decision)
        failAt = RequestLedgerCheckpoint.AFTER_PERMISSION_STATE_CAS

        assertSuspendFails<InjectedLedgerFailure> {
            repository.decidePermission(
                DecideToolPermissionCommand(
                    permissionId = ToolPermissionId("permission-1"),
                    decision = ToolPermissionDecision.ALLOW,
                    actor = AuditActor.user("user-1"),
                ),
            )
        }

        val rolledBack = dao.getPermissionById("permission-1")!!
        assertEquals("ask", rolledBack.decision)
        assertEquals(0L, rolledBack.stateRevision)
        assertEquals(1, dao.getToolAudit("request-1").size)
    }

    @Test
    fun revokedPermissionCannotBeResurrected() = runTest {
        repository.createRequest(requestSpec("request-1", "intent-1"))
        repository.createPermission(permissionSpec())
        repository.decidePermission(
            DecideToolPermissionCommand(
                ToolPermissionId("permission-1"),
                ToolPermissionDecision.ALLOW,
                AuditActor.user("user-1"),
            ),
        )
        repository.decidePermission(
            DecideToolPermissionCommand(
                ToolPermissionId("permission-1"),
                ToolPermissionDecision.REVOKED,
                AuditActor.user("user-1"),
            ),
        )

        assertSuspendFails<IllegalStateException> {
            repository.decidePermission(
                DecideToolPermissionCommand(
                    ToolPermissionId("permission-1"),
                    ToolPermissionDecision.ALLOW,
                    AuditActor.user("user-1"),
                ),
            )
        }
        assertEquals("revoked", dao.getPermissionById("permission-1")?.decision)
    }

    @Test
    fun invocationRejectsMissingMismatchedAndExpiredPermissionEvidence() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.createPermission(permissionSpec().copy(decision = ToolPermissionDecision.ALLOW))
        val invocation = invocationSpec(lease, attemptId)

        assertSuspendFails<RequestLedgerConflict> {
            repository.createInvocation(invocation.copy(permissionId = null))
        }
        assertSuspendFails<IllegalStateException> {
            repository.createInvocation(invocation.copy(schemaDigest = "changed-schema"))
        }

        val expiredPermission = permissionSpec().copy(
            permissionId = ToolPermissionId("permission-expired"),
            permissionKey = "permission-key-expired",
            scopeId = "invocation-expired",
            expiresAt = NOW,
            decision = ToolPermissionDecision.ALLOW,
        )
        repository.createPermission(expiredPermission)
        assertSuspendFails<IllegalStateException> {
            repository.createInvocation(
                invocation.copy(
                    invocationId = ToolInvocationId("invocation-expired"),
                    providerToolCallId = "provider-call-expired",
                    permissionId = ToolPermissionId("permission-expired"),
                ),
            )
        }
    }

    @Test
    fun revokedPermissionStillAllowsInvocationToRecordSafeTerminalState() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.createPermission(permissionSpec().copy(decision = ToolPermissionDecision.ALLOW))
        repository.createInvocation(invocationSpec(lease, attemptId))
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.APPROVED,
                nextExecutionState = ToolExecutionState.READY,
                actor = AuditActor.system("worker-1"),
            ),
        )
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.APPROVED,
                nextExecutionState = ToolExecutionState.RUNNING,
                actor = AuditActor.system("worker-1"),
            ),
        )
        repository.decidePermission(
            DecideToolPermissionCommand(
                ToolPermissionId("permission-1"),
                ToolPermissionDecision.REVOKED,
                AuditActor.user("user-1"),
            ),
        )

        val cancelled = repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.DENIED,
                nextExecutionState = ToolExecutionState.CANCELLED,
                actor = AuditActor.system("worker-1"),
                errorKind = "permission_revoked",
            ),
        )

        assertEquals("cancelled", cancelled.executionState)
        assertEquals("denied", cancelled.approvalState)
        assertEquals("permission_revoked", cancelled.errorKind)
    }

    @Test
    fun providerToolCallIdIsScopedToAttemptNotWholeRequest() = runTest {
        val (lease, firstAttemptId) = runningAttempt("request-1", "attempt-1")
        repository.createPermission(permissionSpec().copy(decision = ToolPermissionDecision.ALLOW))
        val first = repository.createInvocation(invocationSpec(lease, firstAttemptId))
        repository.advanceAttempt(
            advance(
                lease,
                firstAttemptId,
                RequestAttemptState.FAILED,
                BillableBoundary.RESPONSE_STARTED,
            ),
        )

        repository.beginAttempt(
            beginAttempt(lease, "attempt-2", "idem-2", acceptsPossibleCharge = true),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-2", RequestAttemptState.DISPATCHING, BillableBoundary.SENT),
        )
        repository.advanceAttempt(
            advance(lease, "attempt-2", RequestAttemptState.RUNNING, BillableBoundary.RESPONSE_STARTED),
        )
        repository.createPermission(
            permissionSpec().copy(
                permissionId = ToolPermissionId("permission-2"),
                permissionKey = "permission-key-2",
                scopeId = "invocation-2",
                decision = ToolPermissionDecision.ALLOW,
            ),
        )
        val second = repository.createInvocation(
            invocationSpec(lease, "attempt-2").copy(
                invocationId = ToolInvocationId("invocation-2"),
                permissionId = ToolPermissionId("permission-2"),
            ),
        )

        assertEquals("provider-call-1", first.providerToolCallId)
        assertEquals("provider-call-1", second.providerToolCallId)
        assertEquals(2, dao.getInvocations("request-1").size)
    }

    @Test
    fun toolInvocationRequiresActiveFenceAndPersistsApprovalLineage() = runTest {
        val (lease, attemptId) = runningAttempt("request-1", "attempt-1")
        repository.createPermission(permissionSpec())
        repository.createInvocation(
            NewToolInvocationSpec(
                lease = lease,
                attemptId = RequestAttemptId(attemptId),
                invocationId = ToolInvocationId("invocation-1"),
                providerToolCallId = "provider-call-1",
                toolName = "workspace.write_file",
                principalKind = "assistant",
                principalId = "assistant-1",
                action = "execute",
                schemaDigest = "schema-digest",
                inputDigest = "input-digest",
                sideEffectClass = ToolSideEffectClass.REVERSIBLE_WRITE,
                approvalState = ToolApprovalState.PENDING,
                permissionId = ToolPermissionId("permission-1"),
                actor = AuditActor.provider("provider-1"),
            ),
        )
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.PENDING,
                nextExecutionState = ToolExecutionState.WAITING_APPROVAL,
                actor = AuditActor.system("worker-1"),
            ),
        )
        repository.decidePermission(
            DecideToolPermissionCommand(
                permissionId = ToolPermissionId("permission-1"),
                decision = ToolPermissionDecision.ALLOW,
                actor = AuditActor.user("user-1"),
            ),
        )
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.APPROVED,
                nextExecutionState = ToolExecutionState.READY,
                permissionId = ToolPermissionId("permission-1"),
                actor = AuditActor.user("user-1"),
            ),
        )
        repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.APPROVED,
                nextExecutionState = ToolExecutionState.RUNNING,
                permissionId = ToolPermissionId("permission-1"),
                actor = AuditActor.system("worker-1"),
            ),
        )
        assertSuspendFails<IllegalStateException> {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = lease,
                    invocationId = ToolInvocationId("invocation-1"),
                    nextApprovalState = ToolApprovalState.APPROVED,
                    nextExecutionState = ToolExecutionState.SUCCEEDED,
                    permissionId = ToolPermissionId("permission-1"),
                    actor = AuditActor.system("worker-1"),
                ),
            )
        }
        val succeeded = repository.advanceInvocation(
            AdvanceToolInvocationCommand(
                lease = lease,
                invocationId = ToolInvocationId("invocation-1"),
                nextApprovalState = ToolApprovalState.APPROVED,
                nextExecutionState = ToolExecutionState.SUCCEEDED,
                permissionId = ToolPermissionId("permission-1"),
                resultDigest = "result-digest",
                actor = AuditActor.system("worker-1"),
            ),
        )

        assertEquals("succeeded", succeeded.executionState)
        assertEquals("permission-1", succeeded.permissionId)
        assertEquals("result-digest", succeeded.resultDigest)
        assertTrue(dao.getToolAudit("request-1").size >= 6)

        val stale = lease.copy(fencingEpoch = lease.fencingEpoch - 1)
        assertSuspendFails<RequestLedgerLeaseConflict> {
            repository.advanceInvocation(
                AdvanceToolInvocationCommand(
                    lease = stale,
                    invocationId = ToolInvocationId("invocation-1"),
                    nextApprovalState = ToolApprovalState.APPROVED,
                    nextExecutionState = ToolExecutionState.SUCCEEDED,
                    actor = AuditActor.system("stale"),
                ),
            )
        }
    }

    private suspend fun runningAttempt(requestId: String, attemptId: String): Pair<RequestLease, String> {
        repository.createRequest(requestSpec(requestId, "intent-$requestId"))
        val lease = repository.claimRequest(RequestId(requestId), "worker-1", 1_000)
        repository.beginAttempt(beginAttempt(lease, attemptId, "idem-$attemptId"))
        repository.advanceAttempt(
            advance(lease, attemptId, RequestAttemptState.DISPATCHING, BillableBoundary.SENT),
        )
        repository.advanceAttempt(
            advance(lease, attemptId, RequestAttemptState.RUNNING, BillableBoundary.RESPONSE_STARTED),
        )
        return lease to attemptId
    }

    private fun requestSpec(requestId: String, intentKey: String) = NewRequestSpec(
        requestId = RequestId(requestId),
        intentKey = intentKey,
        kind = RequestKind.CHAT_GENERATION,
        inputDigest = "input-$requestId",
        capabilitySnapshotJson = "{}",
        resolverVersion = 1,
        actor = AuditActor.user("user-1"),
        conversationId = "conversation-1",
        assistantId = "assistant-1",
        providerKind = "openai",
        providerId = "provider-1",
        modelId = "model-1",
    )

    private fun beginAttempt(
        lease: RequestLease,
        attemptId: String,
        idempotencyKey: String,
        acceptsPossibleCharge: Boolean = false,
    ) = BeginAttemptCommand(
        lease = lease,
        attemptId = RequestAttemptId(attemptId),
        idempotencyKey = idempotencyKey,
        requestFingerprint = "fingerprint-$attemptId",
        actor = AuditActor.system("worker-1"),
        acceptsPossibleCharge = acceptsPossibleCharge,
    )

    private fun advance(
        lease: RequestLease,
        attemptId: String,
        state: RequestAttemptState,
        boundary: BillableBoundary,
    ) = AdvanceAttemptCommand(
        lease = lease,
        attemptId = RequestAttemptId(attemptId),
        nextState = state,
        nextBoundary = boundary,
        actor = AuditActor.system("worker-1"),
    )

    private fun permissionSpec() = NewToolPermissionSpec(
        permissionId = ToolPermissionId("permission-1"),
        permissionKey = "permission-key-1",
        principalKind = "assistant",
        principalId = "assistant-1",
        toolName = "workspace.write_file",
        action = "execute",
        schemaDigest = "schema-digest",
        decision = ToolPermissionDecision.ASK,
        scope = ToolPermissionScope.ONCE,
        scopeId = "invocation-1",
        constraintsJson = "{}",
        capabilitySnapshotJson = "{}",
        policyVersion = 1,
        actor = AuditActor.system("policy"),
        sourceRequestId = RequestId("request-1"),
    )

    private fun invocationSpec(lease: RequestLease, attemptId: String) = NewToolInvocationSpec(
        lease = lease,
        attemptId = RequestAttemptId(attemptId),
        invocationId = ToolInvocationId("invocation-1"),
        providerToolCallId = "provider-call-1",
        toolName = "workspace.write_file",
        principalKind = "assistant",
        principalId = "assistant-1",
        action = "execute",
        schemaDigest = "schema-digest",
        inputDigest = "input-digest",
        sideEffectClass = ToolSideEffectClass.REVERSIBLE_WRITE,
        approvalState = ToolApprovalState.APPROVED,
        actor = AuditActor.provider("provider-1"),
        permissionId = ToolPermissionId("permission-1"),
    )

    private class InjectedLedgerFailure(point: RequestLedgerCheckpoint) :
        RuntimeException("Injected failure at $point")

    private companion object {
        const val NOW = 100L
    }
}

private suspend inline fun <reified T : Throwable> assertSuspendFails(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (throwable: Throwable) {
        if (throwable is T) return throwable
        throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
    }
    throw AssertionError("Expected ${T::class.java.name} to be thrown")
}

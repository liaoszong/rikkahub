package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.request.RequestKind
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestDispatchSessionTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RequestLedgerDAO
    private lateinit var repository: RequestLedgerRepository
    private var now = 1_000L
    private var requestAuditSequence = 0
    private var toolAuditSequence = 0
    private var failAt: RequestLedgerCheckpoint? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.requestLedgerDao()
        repository = newRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulDispatchCommitsOutputBeforeSucceededAndReleasesLease() = runTest {
        val session = openSession()

        session.dispatchObserver.onDispatch()
        session.markResponseStarted()
        val firstResponseRevision = dao.getAttempt(ATTEMPT_ID)!!.stateRevision
        session.markResponseStarted()
        assertEquals(firstResponseRevision, dao.getAttempt(ATTEMPT_ID)!!.stateRevision)
        session.markResultReceived("checkpoint")
        session.commitOutputAndSucceed(outputCommand(session))

        val request = dao.getRequest(REQUEST_ID)!!
        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("succeeded", request.requestState)
        assertEquals("result_committed", request.billableBoundary)
        assertNull(request.activeAttemptId)
        assertNull(request.leaseOwner)
        assertEquals("succeeded", attempt.attemptState)
        assertEquals(1, dao.getOutputs(REQUEST_ID).size)
    }

    @Test
    fun dispatchPersistenceFailureLeavesRequestNotSent() = runTest {
        val session = openSession()
        session.prepareDispatch()
        failAt = RequestLedgerCheckpoint.AFTER_ATTEMPT_STATE_CAS

        assertSuspendFails<InjectedDispatchFailure> {
            session.dispatchObserver.onDispatch()
        }

        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("dispatching", attempt.attemptState)
        assertEquals("not_sent", attempt.billableBoundary)
        assertEquals("not_sent", dao.getRequest(REQUEST_ID)!!.billableBoundary)
    }

    @Test
    fun sentFailureIsUnknownAndCannotRetryWithoutChargeAcceptance() = runTest {
        val session = openSession()
        session.dispatchObserver.onDispatch()
        session.markUnknownOutcome()

        assertEquals("unknown_outcome", dao.getAttempt(ATTEMPT_ID)!!.attemptState)
        assertEquals("unknown", dao.getRequest(REQUEST_ID)!!.billableBoundary)
        assertSuspendFails<RequestLedgerRetryRejected> {
            RequestDispatchSession.open(
                repository = repository,
                request = requestSpec(),
                owner = "worker-2",
                leaseDurationMillis = LEASE_DURATION,
                attemptId = RequestAttemptId("attempt-2"),
                idempotencyKey = "idempotency-2",
                requestFingerprint = FINGERPRINT,
            )
        }
        assertEquals(1, dao.getAttempts(REQUEST_ID).size)
        assertNull(dao.getRequest(REQUEST_ID)!!.leaseOwner)
    }

    @Test
    fun cancellationBeforeDispatchIsSafeAndTerminal() = runTest {
        val session = openSession()

        session.cancel()

        val request = dao.getRequest(REQUEST_ID)!!
        assertEquals("cancelled", request.requestState)
        assertEquals("not_sent", request.billableBoundary)
        assertNull(request.leaseOwner)
    }

    @Test
    fun transportFailureBeforeHandoffRemainsNotSent() = runTest {
        val session = openSession()
        session.prepareDispatch()

        session.finishTransportFailure(cancelled = false)

        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("failed", attempt.attemptState)
        assertEquals("not_sent", attempt.billableBoundary)
        assertNull(dao.getRequest(REQUEST_ID)!!.leaseOwner)
    }

    @Test
    fun transportFailureAfterHandoffWithoutResponseIsUnknown() = runTest {
        val session = openSession()
        session.dispatchObserver.onDispatch()

        session.finishTransportFailure(cancelled = false)

        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("unknown_outcome", attempt.attemptState)
        assertEquals("unknown", attempt.billableBoundary)
        assertNull(dao.getRequest(REQUEST_ID)!!.leaseOwner)
    }

    @Test
    fun transportFailureAfterFirstResponseIsInterrupted() = runTest {
        val session = openSession()
        session.dispatchObserver.onDispatch()
        session.markResponseStarted()

        session.finishTransportFailure(cancelled = false)

        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("interrupted", attempt.attemptState)
        assertEquals("response_started", attempt.billableBoundary)
        assertNull(dao.getRequest(REQUEST_ID)!!.leaseOwner)
    }

    @Test
    fun expiredLeaseCannotReachDispatchBoundary() = runTest {
        val session = openSession(leaseDurationMillis = 100)
        now += 101

        assertSuspendFails<RequestLedgerLeaseConflict> {
            session.dispatchObserver.onDispatch()
        }

        val attempt = dao.getAttempt(ATTEMPT_ID)!!
        assertEquals("prepared", attempt.attemptState)
        assertEquals("not_sent", attempt.billableBoundary)
    }

    @Test
    fun notSentAttemptCanResumeButSentAttemptCannotDispatchAgain() = runTest {
        val first = openSession()
        first.prepareDispatch()
        first.releaseLease()

        val resumed = RequestDispatchSession.open(
            repository = repository,
            request = requestSpec(),
            owner = "worker-2",
            leaseDurationMillis = LEASE_DURATION,
            attemptId = RequestAttemptId(ATTEMPT_ID),
            idempotencyKey = "idempotency-1",
            requestFingerprint = FINGERPRINT,
        )
        resumed.dispatchObserver.onDispatch()
        assertEquals("sent", dao.getAttempt(ATTEMPT_ID)!!.billableBoundary)
        resumed.releaseLease()

        val afterSent = RequestDispatchSession.open(
            repository = repository,
            request = requestSpec(),
            owner = "worker-3",
            leaseDurationMillis = LEASE_DURATION,
            attemptId = RequestAttemptId(ATTEMPT_ID),
            idempotencyKey = "idempotency-1",
            requestFingerprint = FINGERPRINT,
        )
        assertSuspendFails<IllegalStateException> {
            afterSent.dispatchObserver.onDispatch()
        }
        assertEquals("sent", dao.getAttempt(ATTEMPT_ID)!!.billableBoundary)
        afterSent.markUnknownOutcome()
    }

    @Test
    fun runningAndCommittingCallbacksSurviveLeaseRenewal() = runTest {
        val session = openSession(leaseDurationMillis = 100)
        session.dispatchObserver.onDispatch()
        val commandCreatedBeforeRenewal = outputCommand(session)

        now = 1_090
        session.renewLease()
        now = 1_101 // Past the original lease, inside the renewed lease.
        session.markResponseStarted()
        session.markResultReceived("checkpoint")

        now = 1_180
        session.renewLease()
        now = 1_191
        session.commitOutputAndSucceed(commandCreatedBeforeRenewal)

        assertEquals("succeeded", dao.getRequest(REQUEST_ID)!!.requestState)
        assertEquals("result_committed", dao.getAttempt(ATTEMPT_ID)!!.billableBoundary)
    }

    @Test
    fun reclaimedFencingEpochRejectsOldOwnerAndLetsNotSentAttemptResume() = runTest {
        val first = openSession(leaseDurationMillis = 100)
        first.prepareDispatch()
        val firstEpoch = first.lease.fencingEpoch
        now = 1_101

        val second = RequestDispatchSession.open(
            repository = repository,
            request = requestSpec(),
            owner = "worker-2",
            leaseDurationMillis = 100,
            attemptId = RequestAttemptId(ATTEMPT_ID),
            idempotencyKey = "idempotency-1",
            requestFingerprint = FINGERPRINT,
        )

        assertTrue(second.lease.fencingEpoch > firstEpoch)
        assertSuspendFails<RequestLedgerLeaseConflict> { first.renewLease() }
        assertSuspendFails<RequestLedgerLeaseConflict> { first.dispatchObserver.onDispatch() }
        second.dispatchObserver.onDispatch()
        assertEquals("sent", dao.getAttempt(ATTEMPT_ID)!!.billableBoundary)
        second.markUnknownOutcome()
    }

    @Test
    fun heartbeatStopsCleanlyAfterSuccessfulTerminalRelease() = runTest {
        val session = openSession(leaseDurationMillis = 100)

        session.withLeaseHeartbeat(intervalMillis = 10) {
            session.dispatchObserver.onDispatch()
            session.markResponseStarted()
            delay(10)
            now = 1_010
            session.markResultReceived("checkpoint")
            session.commitOutputAndSucceed(outputCommand(session))
        }

        assertEquals("succeeded", dao.getRequest(REQUEST_ID)!!.requestState)
        assertNull(dao.getRequest(REQUEST_ID)!!.leaseOwner)
    }

    @Test
    fun heartbeatRenewalFailureCancelsBlockAndPropagatesLeaseConflict() = runTest {
        val session = openSession(leaseDurationMillis = 100)

        assertSuspendFails<RequestLedgerLeaseConflict> {
            session.withLeaseHeartbeat(intervalMillis = 10) {
                now = 1_101
                awaitCancellation()
            }
        }

        assertEquals("prepared", dao.getAttempt(ATTEMPT_ID)!!.attemptState)
        assertEquals("not_sent", dao.getAttempt(ATTEMPT_ID)!!.billableBoundary)
    }

    private fun newRepository() = RequestLedgerRepository(
        database = database,
        nowMillis = { now },
        requestAuditId = { "request-audit-${++requestAuditSequence}" },
        toolAuditId = { "tool-audit-${++toolAuditSequence}" },
        faultInjector = RequestLedgerFaultInjector { point ->
            if (point == failAt) throw InjectedDispatchFailure(point)
        },
    )

    private suspend fun openSession(leaseDurationMillis: Long = LEASE_DURATION) =
        RequestDispatchSession.open(
            repository = repository,
            request = requestSpec(),
            owner = "worker-1",
            leaseDurationMillis = leaseDurationMillis,
            attemptId = RequestAttemptId(ATTEMPT_ID),
            idempotencyKey = "idempotency-1",
            requestFingerprint = FINGERPRINT,
            transportKind = "provider-http",
        )

    private fun requestSpec() = NewRequestSpec(
        requestId = RequestId(REQUEST_ID),
        intentKey = "chat-step:conversation-1:message-1",
        kind = RequestKind.CHAT_GENERATION,
        inputDigest = "input-digest",
        capabilitySnapshotJson = "{}",
        resolverVersion = 1,
        actor = AuditActor.system("worker-1"),
        conversationId = "conversation-1",
        assistantId = "assistant-1",
        messageId = "message-1",
        providerKind = "openai",
        providerId = "provider-1",
        modelId = "model-1",
        apiSurface = "chat_completions",
    )

    private fun outputCommand(session: RequestDispatchSession) = CommitRequestOutputCommand(
        lease = session.lease,
        attemptId = session.attemptId,
        outputId = RequestOutputId("output-1"),
        outputKind = "message",
        ordinal = 0,
        contentDigest = "output-digest",
        actor = AuditActor.system("worker-1"),
        conversationId = "conversation-1",
        messageId = "message-1",
    )

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private class InjectedDispatchFailure(point: RequestLedgerCheckpoint) :
        RuntimeException("Injected failure at $point")

    companion object {
        private const val REQUEST_ID = "request-1"
        private const val ATTEMPT_ID = "attempt-1"
        private const val FINGERPRINT = "request-fingerprint"
        private const val LEASE_DURATION = 10_000L
    }
}

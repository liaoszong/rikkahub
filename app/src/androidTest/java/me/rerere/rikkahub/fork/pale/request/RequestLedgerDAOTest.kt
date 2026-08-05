package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestLedgerDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RequestLedgerDAO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.requestLedgerDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun failedRequestCanBeClaimedForAuthorizedRetry() = runTest {
        dao.insertRequest(request("request-1", "intent-1", state = "failed"))

        assertEquals(1, dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200))
        val claimed = dao.getRequest("request-1")!!

        assertEquals("worker-1", claimed.leaseOwner)
        assertEquals(1L, claimed.fencingEpoch)
        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "failed", "queued", "not_sent", "not_sent",
                0, "worker-1", 1, 110,
            ),
        )
        assertEquals(
            1,
            dao.transitionRequest(
                "request-1", "failed", "queued", "not_sent", "not_sent",
                0, "worker-1", 1, 110, explicitRetry = true,
            ),
        )
        assertEquals("queued", dao.getRequest("request-1")?.requestState)
    }

    @Test
    fun activationRejectsMissingAndCrossRequestAttemptsWithoutCountDrift() = runTest {
        dao.insertRequest(request("request-1", "intent-1"))
        dao.insertRequest(request("request-2", "intent-2"))
        dao.insertAttempt(attempt("attempt-2", "request-2", ordinal = 1))
        dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200)

        assertEquals(0, dao.activateAttempt("request-1", "missing", "worker-1", 1, 110))
        assertEquals(0, dao.activateAttempt("request-1", "attempt-2", "worker-1", 1, 110))
        assertEquals(0, dao.getRequest("request-1")?.attemptCount)

        dao.insertAttempt(attempt("attempt-1", "request-1", ordinal = 1))
        assertEquals(1, dao.activateAttempt("request-1", "attempt-1", "worker-1", 1, 110))

        val activated = dao.getRequest("request-1")
        assertEquals("attempt-1", activated?.activeAttemptId)
        assertEquals(1, activated?.attemptCount)
    }

    @Test
    fun requestTransitionRequiresLiveFenceAndNeverDowngradesBilling() = runTest {
        dao.insertRequest(request("request-1", "intent-1", state = "queued", boundary = "sent"))
        dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200)

        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "queued", "dispatching", "sent", "not_sent",
                0, "worker-1", 1, 110,
            ),
        )
        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "queued", "dispatching", "sent", "sent",
                0, "worker-1", 0, 110,
            ),
        )
        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "queued", "dispatching", "sent", "sent",
                0, "worker-1", 1, 200,
            ),
        )
        assertEquals(
            1,
            dao.transitionRequest(
                "request-1", "queued", "dispatching", "sent", "sent",
                0, "worker-1", 1, 120,
            ),
        )

        val transitioned = dao.getRequest("request-1")
        assertEquals("dispatching", transitioned?.requestState)
        assertEquals("sent", transitioned?.billableBoundary)
        assertNotNull(transitioned?.billableAt)
        assertNotNull(transitioned?.dispatchAt)
    }

    @Test
    fun attemptTransitionRequiresActiveAttemptAndOwningRequestFence() = runTest {
        dao.insertRequest(request("request-1", "intent-1"))
        dao.insertRequest(request("request-2", "intent-2"))
        dao.insertAttempt(attempt("attempt-1", "request-1", ordinal = 1, boundary = "sent"))
        dao.insertAttempt(attempt("attempt-2", "request-2", ordinal = 1, boundary = "sent"))
        dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200)

        assertEquals(0, transitionAttempt("attempt-1", "request-1", fencingEpoch = 1))
        dao.activateAttempt("request-1", "attempt-1", "worker-1", 1, 110)
        assertEquals(0, transitionAttempt("attempt-2", "request-2", fencingEpoch = 1))
        assertEquals(0, transitionAttempt("attempt-1", "request-1", fencingEpoch = 0))
        assertEquals(
            0,
            transitionAttempt(
                "attempt-1",
                "request-1",
                fencingEpoch = 1,
                nextBoundary = "not_sent",
            ),
        )
        assertEquals(1, transitionAttempt("attempt-1", "request-1", fencingEpoch = 1))

        val transitioned = dao.getAttempt("attempt-1")
        assertEquals("dispatching", transitioned?.attemptState)
        assertEquals("sent", transitioned?.billableBoundary)
    }

    @Test
    fun unknownOutcomeRetryRequiresExplicitChargeAcceptance() = runTest {
        dao.insertRequest(request("request-1", "intent-1", state = "unknown_outcome", boundary = "unknown"))
        dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200)

        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "unknown_outcome", "queued", "unknown", "unknown",
                0, "worker-1", 1, 110, explicitRetry = true,
            ),
        )
        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "unknown_outcome", "queued", "unknown", "unknown",
                0, "worker-1", 1, 110,
                explicitRetry = true,
                providerGuaranteesIdempotency = true,
            ),
        )
        assertEquals(
            1,
            dao.transitionRequest(
                "request-1", "unknown_outcome", "queued", "unknown", "unknown",
                0, "worker-1", 1, 110,
                explicitRetry = true,
                acceptsPossibleCharge = true,
            ),
        )
    }

    @Test
    fun orphanRecoveryCannotRewriteTerminalUncertainStates() = runTest {
        listOf("interrupted", "unknown_outcome").forEachIndexed { index, state ->
            val requestId = "orphan-terminal-$index"
            dao.insertRequest(
                request(
                    requestId = requestId,
                    intentKey = "orphan-terminal-intent-$index",
                    state = state,
                    boundary = "unknown",
                ),
            )
            assertEquals(1, dao.claimRequest(requestId, "worker-$index", now = 100, leaseUntil = 200))

            assertEquals(
                0,
                dao.failOrphanedRequestWithoutAttempt(
                    requestId = requestId,
                    expectedState = state,
                    expectedBoundary = "unknown",
                    expectedStateRevision = 0,
                    owner = "worker-$index",
                    fencingEpoch = 1,
                    now = 110,
                ),
            )
            assertEquals(state, dao.getRequest(requestId)?.requestState)
        }
    }

    @Test
    fun billableFailedRetryRequiresIdempotencyOrChargeAcceptance() = runTest {
        dao.insertRequest(request("request-1", "intent-1", state = "failed", boundary = "sent"))
        dao.claimRequest("request-1", "worker-1", now = 100, leaseUntil = 200)

        assertEquals(
            0,
            dao.transitionRequest(
                "request-1", "failed", "queued", "sent", "sent",
                0, "worker-1", 1, 110, explicitRetry = true,
            ),
        )
        assertEquals(
            1,
            dao.transitionRequest(
                "request-1", "failed", "queued", "sent", "sent",
                0, "worker-1", 1, 110,
                explicitRetry = true,
                providerGuaranteesIdempotency = true,
            ),
        )
    }

    @Test
    fun recoveryFiltersKindAndLeaseBeforeApplyingLimit() = runTest {
        repeat(501) { index ->
            val blockerKind = when (index % 3) {
                0 -> "future_lease"
                1 -> "wrong_kind"
                else -> "wrong_state"
            }
            dao.insertRequest(
                request(
                    requestId = "blocking-$index",
                    intentKey = "blocking-intent-$index",
                    state = if (blockerKind == "wrong_state") "queued" else "running",
                ).copy(
                    requestKind = if (blockerKind == "wrong_kind") "tool_call" else "chat_generation",
                    leaseOwner = if (blockerKind == "future_lease") "live-worker" else null,
                    leaseUntil = if (blockerKind == "future_lease") 10_000 else null,
                    updatedAt = index.toLong(),
                ),
            )
        }
        dao.insertRequest(
            request("recoverable-chat", "recoverable-intent", state = "running").copy(
                leaseOwner = "dead-worker",
                leaseUntil = 100,
                updatedAt = 10_000,
            ),
        )

        val snapshotEnd = checkNotNull(dao.getRecoverableRequestSnapshotEnd(
            kinds = listOf("chat_generation"),
            states = listOf("running"),
            recoveryBefore = 100,
        ))
        val candidates = dao.getRecoverableRequestPage(
            kinds = listOf("chat_generation"),
            states = listOf("running"),
            recoveryBefore = 100,
            afterUpdatedAt = Long.MIN_VALUE,
            afterRequestId = "",
            snapshotUpdatedAt = snapshotEnd.updatedAt,
            snapshotRequestId = snapshotEnd.requestId,
            limit = 500,
        )

        assertEquals(listOf("recoverable-chat"), candidates.map(RequestLedgerEntity::requestId))
    }

    @Test
    fun recoveryKeysetReachesRowsBeyondProductionPageBoundary() = runTest {
        repeat(501) { index ->
            dao.insertRequest(
                request(
                    requestId = "eligible-${index.toString().padStart(3, '0')}",
                    intentKey = "eligible-intent-$index",
                    state = "running",
                ).copy(updatedAt = index.toLong()),
            )
        }

        val snapshotEnd = checkNotNull(dao.getRecoverableRequestSnapshotEnd(
            kinds = listOf("chat_generation"),
            states = listOf("running"),
            recoveryBefore = 1_000,
        ))
        val firstPage = dao.getRecoverableRequestPage(
            kinds = listOf("chat_generation"),
            states = listOf("running"),
            recoveryBefore = 1_000,
            afterUpdatedAt = Long.MIN_VALUE,
            afterRequestId = "",
            snapshotUpdatedAt = snapshotEnd.updatedAt,
            snapshotRequestId = snapshotEnd.requestId,
            limit = 500,
        )
        val firstCursor = firstPage.last()
        val secondPage = dao.getRecoverableRequestPage(
            kinds = listOf("chat_generation"),
            states = listOf("running"),
            recoveryBefore = 1_000,
            afterUpdatedAt = firstCursor.updatedAt,
            afterRequestId = firstCursor.requestId,
            snapshotUpdatedAt = snapshotEnd.updatedAt,
            snapshotRequestId = snapshotEnd.requestId,
            limit = 500,
        )

        assertEquals(500, firstPage.size)
        assertEquals(listOf("eligible-500"), secondPage.map(RequestLedgerEntity::requestId))
    }

    @Test
    fun permissionDecisionCasAllowsOnlyOneDecisionAtSameTimestamp() = runTest {
        dao.insertPermission(permission("permission-1"))

        assertEquals(
            1,
            dao.updatePermissionDecision(
                permissionId = "permission-1",
                expectedDecision = "ask",
                expectedStateRevision = 0,
                decision = "allow",
                reason = "approved",
                revokedAt = null,
                now = 100,
            ),
        )
        assertEquals(
            0,
            dao.updatePermissionDecision(
                permissionId = "permission-1",
                expectedDecision = "ask",
                expectedStateRevision = 0,
                decision = "deny",
                reason = "second click",
                revokedAt = null,
                now = 100,
            ),
        )

        val permission = dao.getPermission("permission-key-1")
        assertEquals("allow", permission?.decision)
        assertEquals(1L, permission?.stateRevision)
    }

    private suspend fun transitionAttempt(
        attemptId: String,
        requestId: String,
        fencingEpoch: Long,
        nextBoundary: String = "sent",
    ): Int = dao.transitionAttempt(
        attemptId = attemptId,
        requestId = requestId,
        expectedState = "prepared",
        nextState = "dispatching",
        expectedBoundary = "sent",
        nextBoundary = nextBoundary,
        expectedStateRevision = 0,
        owner = "worker-1",
        fencingEpoch = fencingEpoch,
        sentAt = 120,
        acknowledgedAt = null,
        firstByteAt = null,
        resultReceivedAt = null,
        commitStartedAt = null,
        finishedAt = null,
        now = 120,
    )

    private fun request(
        requestId: String,
        intentKey: String,
        state: String = "created",
        boundary: String = "not_sent",
    ) = RequestLedgerEntity(
        requestId = requestId,
        intentKey = intentKey,
        requestKind = "chat_generation",
        inputDigest = "input-$requestId",
        capabilitySnapshotJson = "{}",
        resolverVersion = 1,
        approvalState = "not_required",
        requestState = state,
        billableBoundary = boundary,
        createdAt = 10,
        updatedAt = 10,
    )

    private fun attempt(
        attemptId: String,
        requestId: String,
        ordinal: Int,
        boundary: String = "not_sent",
    ) = RequestAttemptEntity(
        attemptId = attemptId,
        requestId = requestId,
        attemptOrdinal = ordinal,
        idempotencyKey = "idempotency-$attemptId",
        attemptState = "prepared",
        billableBoundary = boundary,
        requestFingerprint = "fingerprint-$attemptId",
        preparedAt = 10,
        createdAt = 10,
        updatedAt = 10,
    )

    private fun permission(permissionId: String) = ToolPermissionEntity(
        permissionId = permissionId,
        permissionKey = "permission-key-1",
        principalKind = "assistant",
        principalId = "assistant-1",
        toolName = "tool-1",
        action = "execute",
        schemaDigest = "schema-digest",
        decision = "ask",
        scopeKind = "once",
        constraintsJson = "{}",
        capabilitySnapshotJson = "{}",
        policyVersion = 1,
        decidedAt = 10,
        createdAt = 10,
        updatedAt = 10,
    )
}

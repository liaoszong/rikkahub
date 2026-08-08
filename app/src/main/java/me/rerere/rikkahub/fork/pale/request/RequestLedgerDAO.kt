package me.rerere.rikkahub.fork.pale.request

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RequestLedgerDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(request: RequestLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: RequestAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutput(output: RequestOutputEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvocation(invocation: ToolInvocationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPermission(permission: ToolPermissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendRequestAudit(event: RequestAuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendToolAudit(event: ToolAuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMigrationJournal(journal: RequestMigrationJournalEntity)

    @Query("SELECT * FROM request_ledger WHERE request_id = :requestId")
    suspend fun getRequest(requestId: String): RequestLedgerEntity?

    @Query("SELECT * FROM request_ledger WHERE intent_key = :intentKey")
    suspend fun getRequestByIntentKey(intentKey: String): RequestLedgerEntity?

    @Query("SELECT * FROM request_ledger WHERE request_id = :requestId")
    fun observeRequest(requestId: String): Flow<RequestLedgerEntity?>

    @Query("SELECT * FROM request_ledger ORDER BY updated_at DESC, request_id DESC LIMIT :limit")
    fun observeRecentRequests(limit: Int): Flow<List<RequestLedgerEntity>>

    @Query(
        "SELECT * FROM request_ledger WHERE conversation_id = :conversationId " +
            "ORDER BY created_at DESC, request_id DESC",
    )
    fun observeConversationRequests(conversationId: String): Flow<List<RequestLedgerEntity>>

    @Query(
        "SELECT * FROM request_ledger WHERE conversation_id = :conversationId " +
            "AND message_id = :messageId AND request_kind = 'chat_generation' " +
            "ORDER BY created_at, request_id",
    )
    suspend fun getChatRequestsForMessage(
        conversationId: String,
        messageId: String,
    ): List<RequestLedgerEntity>

    @Query(
        "SELECT * FROM request_ledger WHERE request_kind IN (:kinds) " +
            "AND request_state IN (:states) " +
            "AND (lease_until IS NULL OR lease_until <= :recoveryBefore) " +
            "ORDER BY updated_at DESC, request_id DESC LIMIT 1",
    )
    suspend fun getRecoverableRequestSnapshotEnd(
        kinds: List<String>,
        states: List<String>,
        recoveryBefore: Long,
    ): RequestLedgerEntity?

    @Query(
        "SELECT * FROM request_ledger WHERE request_kind IN (:kinds) " +
            "AND request_state IN (:states) " +
            "AND (lease_until IS NULL OR lease_until <= :recoveryBefore) " +
            "AND (updated_at > :afterUpdatedAt OR " +
            "(updated_at = :afterUpdatedAt AND request_id > :afterRequestId)) " +
            "AND (updated_at < :snapshotUpdatedAt OR " +
            "(updated_at = :snapshotUpdatedAt AND request_id <= :snapshotRequestId)) " +
            "ORDER BY updated_at, request_id LIMIT :limit",
    )
    suspend fun getRecoverableRequestPage(
        kinds: List<String>,
        states: List<String>,
        recoveryBefore: Long,
        afterUpdatedAt: Long,
        afterRequestId: String,
        snapshotUpdatedAt: Long,
        snapshotRequestId: String,
        limit: Int,
    ): List<RequestLedgerEntity>

    @Query(
        "SELECT * FROM request_ledger WHERE parent_request_id = :parentRequestId " +
            "AND request_kind = 'image_generation' ORDER BY created_at, request_id",
    )
    suspend fun getImageRequestsByParent(parentRequestId: String): List<RequestLedgerEntity>

    @Query(
        "SELECT parent.* FROM request_ledger AS parent WHERE parent.request_state IN (:states) " +
            "AND EXISTS (SELECT 1 FROM request_ledger AS child " +
            "WHERE child.parent_request_id = parent.request_id " +
            "AND child.request_kind = 'image_generation') " +
            "ORDER BY parent.updated_at, parent.request_id LIMIT :limit",
    )
    suspend fun getImageParentRequestsByState(states: List<String>, limit: Int): List<RequestLedgerEntity>

    @Query(
        "SELECT parent.* FROM request_ledger AS parent " +
            "WHERE EXISTS (SELECT 1 FROM request_ledger AS child " +
            "WHERE child.parent_request_id = parent.request_id " +
            "AND child.request_kind = 'image_generation') " +
            "ORDER BY parent.updated_at DESC, parent.request_id DESC",
    )
    suspend fun getAllImageParentRequests(): List<RequestLedgerEntity>

    @Query("SELECT * FROM request_attempt WHERE attempt_id = :attemptId")
    suspend fun getAttempt(attemptId: String): RequestAttemptEntity?

    @Query(
        "SELECT * FROM request_attempt WHERE request_id = :requestId " +
            "ORDER BY attempt_ordinal, attempt_id",
    )
    suspend fun getAttempts(requestId: String): List<RequestAttemptEntity>

    @Query(
        "SELECT * FROM request_output WHERE request_id = :requestId " +
            "ORDER BY output_kind, ordinal, output_id",
    )
    suspend fun getOutputs(requestId: String): List<RequestOutputEntity>

    @Query(
        "SELECT * FROM request_output WHERE request_id = :requestId " +
            "AND output_kind = :outputKind AND ordinal = :ordinal",
    )
    suspend fun getOutputBySlot(requestId: String, outputKind: String, ordinal: Int): RequestOutputEntity?

    @Query(
        "SELECT * FROM tool_invocation WHERE request_id = :requestId " +
            "ORDER BY created_at, invocation_id",
    )
    suspend fun getInvocations(requestId: String): List<ToolInvocationEntity>

    @Query("SELECT * FROM tool_invocation WHERE invocation_id = :invocationId")
    suspend fun getInvocation(invocationId: String): ToolInvocationEntity?

    @Query(
        "SELECT * FROM tool_invocation WHERE request_id = :requestId " +
            "AND attempt_id = :attemptId " +
            "AND provider_tool_call_id = :providerToolCallId",
    )
    suspend fun getInvocationByProviderCall(
        requestId: String,
        attemptId: String,
        providerToolCallId: String,
    ): ToolInvocationEntity?

    @Query("SELECT * FROM tool_permission WHERE permission_key = :permissionKey")
    suspend fun getPermission(permissionKey: String): ToolPermissionEntity?

    @Query("SELECT * FROM tool_permission WHERE permission_id = :permissionId")
    suspend fun getPermissionById(permissionId: String): ToolPermissionEntity?

    @Query(
        "SELECT * FROM request_audit_event WHERE request_id = :requestId " +
            "ORDER BY event_seq",
    )
    suspend fun getRequestAudit(requestId: String): List<RequestAuditEventEntity>

    @Query("SELECT DISTINCT request_id FROM request_audit_event WHERE event_kind = 'provider_replay_v1'")
    fun observeProviderReplayRequestIds(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(event_seq), 0) + 1 FROM request_audit_event WHERE request_id = :requestId")
    suspend fun nextRequestAuditSequence(requestId: String): Long

    @Query(
        "SELECT * FROM tool_audit_event WHERE request_id = :requestId " +
            "ORDER BY created_at, event_id",
    )
    suspend fun getToolAudit(requestId: String): List<ToolAuditEventEntity>

    @Query(
        "UPDATE request_ledger SET lease_owner = :owner, lease_until = :leaseUntil, " +
            "fencing_epoch = fencing_epoch + 1, updated_at = :now " +
            "WHERE request_id = :requestId " +
            "AND request_state NOT IN ('succeeded', 'cancelled') " +
            "AND (lease_until IS NULL OR lease_until <= :now OR lease_owner = :owner)",
    )
    suspend fun claimRequest(
        requestId: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE request_ledger SET lease_until = :leaseUntil, updated_at = :now " +
            "WHERE request_id = :requestId AND lease_owner = :owner " +
            "AND fencing_epoch = :fencingEpoch AND lease_until > :now " +
            "AND request_state NOT IN ('succeeded', 'cancelled')",
    )
    suspend fun renewRequestLease(
        requestId: String,
        owner: String,
        fencingEpoch: Long,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE request_ledger SET request_state = :nextState, " +
            "billable_boundary = :nextBoundary, " +
            "billable_at = CASE WHEN billable_at IS NULL AND :nextBoundary != 'not_sent' " +
            "THEN :now ELSE billable_at END, " +
            "dispatch_at = CASE WHEN dispatch_at IS NULL AND :nextBoundary != 'not_sent' " +
            "THEN :now ELSE dispatch_at END, " +
            "terminal_at = CASE " +
            "WHEN :nextState IN ('succeeded', 'failed', 'cancelled', 'interrupted', 'unknown_outcome') " +
            "THEN :now WHEN :nextState = 'queued' THEN NULL ELSE terminal_at END, " +
            "state_revision = state_revision + 1, updated_at = :now " +
            "WHERE request_id = :requestId AND request_state = :expectedState " +
            "AND billable_boundary = :expectedBoundary " +
            "AND state_revision = :expectedStateRevision " +
            "AND lease_owner = :owner AND fencing_epoch = :fencingEpoch " +
            "AND lease_until > :now " +
            "AND (" +
            ":nextBoundary = :expectedBoundary OR :nextBoundary = 'unknown' OR (" +
            ":expectedBoundary != 'unknown' AND " +
            "CASE :nextBoundary WHEN 'not_sent' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'response_started' THEN 2 WHEN 'result_received' THEN 3 " +
            "WHEN 'result_committed' THEN 4 ELSE -1 END > " +
            "CASE :expectedBoundary WHEN 'not_sent' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'response_started' THEN 2 WHEN 'result_received' THEN 3 " +
            "WHEN 'result_committed' THEN 4 ELSE 99 END)) " +
            "AND (" +
            ":nextState = :expectedState OR " +
            "(:expectedState = 'created' AND :nextState IN ('awaiting_approval', 'queued', 'cancelled')) OR " +
            "(:expectedState = 'awaiting_approval' AND :nextState IN ('queued', 'failed', 'cancelled')) OR " +
            "(:expectedState = 'queued' AND :nextState IN " +
            "('waiting_runtime', 'dispatching', 'failed', 'cancelled', 'interrupted')) OR " +
            "(:expectedState = 'waiting_runtime' AND :nextState IN ('dispatching', 'failed', 'cancelled', 'interrupted')) OR " +
            "(:expectedState = 'dispatching' AND :nextState IN ('running', 'failed', 'cancelled', 'interrupted', 'unknown_outcome')) OR " +
            "(:expectedState = 'running' AND :nextState IN ('waiting_user', 'committing', 'failed', 'cancelled', 'interrupted', 'unknown_outcome')) OR " +
            "(:expectedState = 'waiting_user' AND :nextState IN ('running', 'failed', 'cancelled', 'interrupted')) OR " +
            "(:expectedState = 'committing' AND :nextState IN ('succeeded', 'failed')) OR " +
            "(:expectedState = 'unknown_outcome' AND :nextState = 'committing') OR " +
            "(:explicitRetry = 1 AND :nextState = 'queued' AND (" +
            "(:expectedState IN ('failed', 'interrupted') " +
            "AND (:expectedBoundary = 'not_sent' OR :providerGuaranteesIdempotency = 1 " +
            "OR :acceptsPossibleCharge = 1)) OR " +
            "(:expectedState = 'unknown_outcome' AND :acceptsPossibleCharge = 1))))",
    )
    suspend fun transitionRequest(
        requestId: String,
        expectedState: String,
        nextState: String,
        expectedBoundary: String,
        nextBoundary: String,
        expectedStateRevision: Long,
        owner: String,
        fencingEpoch: Long,
        now: Long,
        explicitRetry: Boolean = false,
        providerGuaranteesIdempotency: Boolean = false,
        acceptsPossibleCharge: Boolean = false,
    ): Int

    /**
     * Recovery-only escape hatch for a corrupt request that has no attempt authority.
     *
     * This intentionally bypasses the normal lifecycle transition matrix while retaining the
     * same lease/fencing/revision CAS. It cannot touch a request that has gained an active attempt,
     * cannot change billing evidence, and cannot rewrite an already-terminal row.
     */
    @Query(
        "UPDATE request_ledger SET request_state = 'failed', " +
            "terminal_at = COALESCE(terminal_at, :now), " +
            "state_revision = state_revision + 1, updated_at = :now " +
            "WHERE request_id = :requestId AND request_state = :expectedState " +
            "AND billable_boundary = :expectedBoundary " +
            "AND state_revision = :expectedStateRevision " +
            "AND active_attempt_id IS NULL " +
            "AND request_state NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'interrupted', 'unknown_outcome') " +
            "AND lease_owner = :owner AND fencing_epoch = :fencingEpoch " +
            "AND lease_until > :now",
    )
    suspend fun failOrphanedRequestWithoutAttempt(
        requestId: String,
        expectedState: String,
        expectedBoundary: String,
        expectedStateRevision: Long,
        owner: String,
        fencingEpoch: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE request_ledger SET active_attempt_id = :attemptId, " +
            "attempt_count = attempt_count + 1, updated_at = :now " +
            "WHERE request_id = :requestId AND active_attempt_id IS NULL " +
            "AND lease_owner = :owner AND fencing_epoch = :fencingEpoch " +
            "AND lease_until > :now AND EXISTS (" +
            "SELECT 1 FROM request_attempt AS attempt " +
            "WHERE attempt.attempt_id = :attemptId " +
            "AND attempt.request_id = request_ledger.request_id " +
            "AND attempt.attempt_ordinal = request_ledger.attempt_count + 1 " +
            "AND attempt.attempt_state = 'prepared')",
    )
    suspend fun activateAttempt(
        requestId: String,
        attemptId: String,
        owner: String,
        fencingEpoch: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE request_ledger SET active_attempt_id = NULL, updated_at = :now " +
            "WHERE request_id = :requestId AND active_attempt_id = :attemptId " +
            "AND lease_owner = :owner AND fencing_epoch = :fencingEpoch " +
            "AND lease_until > :now AND EXISTS (" +
            "SELECT 1 FROM request_attempt AS attempt " +
            "WHERE attempt.attempt_id = :attemptId AND attempt.request_id = request_ledger.request_id " +
            "AND attempt.attempt_state IN ('succeeded', 'failed', 'cancelled', 'interrupted', 'unknown_outcome'))",
    )
    suspend fun clearActiveAttempt(
        requestId: String,
        attemptId: String,
        owner: String,
        fencingEpoch: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE request_ledger SET lease_owner = NULL, lease_until = NULL, updated_at = :now " +
            "WHERE request_id = :requestId AND lease_owner = :owner " +
            "AND fencing_epoch = :fencingEpoch",
    )
    suspend fun releaseRequest(
        requestId: String,
        owner: String,
        fencingEpoch: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE request_attempt SET attempt_state = :nextState, " +
            "billable_boundary = :nextBoundary, " +
            "checkpoint_digest = COALESCE(:checkpointDigest, checkpoint_digest), " +
            "sent_at = COALESCE(sent_at, :sentAt), " +
            "acknowledged_at = COALESCE(acknowledged_at, :acknowledgedAt), " +
            "first_byte_at = COALESCE(first_byte_at, :firstByteAt), " +
            "result_received_at = COALESCE(result_received_at, :resultReceivedAt), " +
            "commit_started_at = COALESCE(commit_started_at, :commitStartedAt), " +
            "finished_at = COALESCE(finished_at, :finishedAt), " +
            "state_revision = state_revision + 1, updated_at = :now " +
            "WHERE attempt_id = :attemptId AND request_id = :requestId " +
            "AND attempt_state = :expectedState " +
            "AND billable_boundary = :expectedBoundary " +
            "AND state_revision = :expectedStateRevision " +
            "AND EXISTS (SELECT 1 FROM request_ledger AS request " +
            "WHERE request.request_id = request_attempt.request_id " +
            "AND request.active_attempt_id = request_attempt.attempt_id " +
            "AND request.lease_owner = :owner AND request.fencing_epoch = :fencingEpoch " +
            "AND request.lease_until > :now) " +
            "AND (" +
            ":nextBoundary = :expectedBoundary OR :nextBoundary = 'unknown' OR (" +
            ":expectedBoundary != 'unknown' AND " +
            "CASE :nextBoundary WHEN 'not_sent' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'response_started' THEN 2 WHEN 'result_received' THEN 3 " +
            "WHEN 'result_committed' THEN 4 ELSE -1 END > " +
            "CASE :expectedBoundary WHEN 'not_sent' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'response_started' THEN 2 WHEN 'result_received' THEN 3 " +
            "WHEN 'result_committed' THEN 4 ELSE 99 END)) " +
            "AND (" +
            ":nextState = :expectedState OR " +
            "(:expectedState = 'prepared' AND :nextState IN ('dispatching', 'failed', 'cancelled', 'interrupted')) OR " +
            "(:expectedState = 'dispatching' AND :nextState IN ('running', 'failed', 'cancelled', 'interrupted', 'unknown_outcome')) OR " +
            "(:expectedState = 'running' AND :nextState IN ('committing', 'failed', 'cancelled', 'interrupted', 'unknown_outcome')) OR " +
            "(:expectedState = 'committing' AND :nextState IN ('succeeded', 'failed')))",
    )
    suspend fun transitionAttempt(
        attemptId: String,
        requestId: String,
        expectedState: String,
        nextState: String,
        expectedBoundary: String,
        nextBoundary: String,
        expectedStateRevision: Long,
        owner: String,
        fencingEpoch: Long,
        sentAt: Long?,
        acknowledgedAt: Long?,
        firstByteAt: Long?,
        resultReceivedAt: Long?,
        commitStartedAt: Long?,
        finishedAt: Long?,
        checkpointDigest: String? = null,
        now: Long,
    ): Int

    @Query(
        "UPDATE tool_invocation SET approval_state = :nextApprovalState, " +
            "execution_state = :nextExecutionState, " +
            "permission_id = COALESCE(:permissionId, permission_id), " +
            "result_digest = COALESCE(:resultDigest, result_digest), " +
            "error_kind = :errorKind, error_code = :errorCode, " +
            "approved_at = CASE WHEN approved_at IS NULL AND :nextApprovalState = 'approved' " +
            "THEN :now ELSE approved_at END, " +
            "started_at = CASE WHEN started_at IS NULL AND :nextExecutionState = 'running' " +
            "THEN :now ELSE started_at END, " +
            "finished_at = CASE WHEN :nextExecutionState IN " +
            "('succeeded', 'failed', 'cancelled', 'unknown_outcome') THEN :now ELSE finished_at END, " +
            "state_revision = state_revision + 1, updated_at = :now " +
            "WHERE invocation_id = :invocationId AND request_id = :requestId " +
            "AND approval_state = :expectedApprovalState " +
            "AND execution_state = :expectedExecutionState " +
            "AND state_revision = :expectedStateRevision " +
            "AND EXISTS (SELECT 1 FROM request_ledger AS request " +
            "WHERE request.request_id = tool_invocation.request_id " +
            "AND request.active_attempt_id = tool_invocation.attempt_id " +
            "AND request.lease_owner = :owner AND request.fencing_epoch = :fencingEpoch " +
            "AND request.lease_until > :now) " +
            "AND (" +
            ":nextExecutionState = :expectedExecutionState OR " +
            "(:expectedExecutionState = 'created' AND :nextExecutionState IN " +
            "('waiting_approval', 'ready', 'cancelled')) OR " +
            "(:expectedExecutionState = 'waiting_approval' AND :nextExecutionState IN " +
            "('ready', 'failed', 'cancelled')) OR " +
            "(:expectedExecutionState = 'ready' AND :nextExecutionState IN ('running', 'cancelled')) OR " +
            "(:expectedExecutionState = 'running' AND :nextExecutionState IN " +
            "('committing', 'succeeded', 'failed', 'cancelled', 'unknown_outcome')) OR " +
            "(:expectedExecutionState = 'committing' AND :nextExecutionState IN ('succeeded', 'failed')))"
    )
    suspend fun transitionInvocation(
        invocationId: String,
        requestId: String,
        expectedApprovalState: String,
        nextApprovalState: String,
        expectedExecutionState: String,
        nextExecutionState: String,
        expectedStateRevision: Long,
        owner: String,
        fencingEpoch: Long,
        permissionId: String?,
        resultDigest: String?,
        errorKind: String?,
        errorCode: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE tool_permission SET decision = :decision, reason = :reason, " +
            "decided_at = :now, revoked_at = :revokedAt, " +
            "state_revision = state_revision + 1, updated_at = :now " +
            "WHERE permission_id = :permissionId AND decision = :expectedDecision " +
            "AND state_revision = :expectedStateRevision",
    )
    suspend fun updatePermissionDecision(
        permissionId: String,
        expectedDecision: String,
        expectedStateRevision: Long,
        decision: String,
        reason: String?,
        revokedAt: Long?,
        now: Long,
    ): Int

    @Query(
        "SELECT * FROM request_migration_journal WHERE source_kind = :sourceKind " +
            "AND source_id = :sourceId",
    )
    suspend fun getMigrationJournal(sourceKind: String, sourceId: String): RequestMigrationJournalEntity?

    @Query(
        "UPDATE request_migration_journal SET lease_owner = :owner, lease_until = :leaseUntil, " +
            "fencing_epoch = fencing_epoch + 1, attempts = attempts + 1, updated_at = :now " +
            "WHERE journal_id = :journalId AND phase NOT IN ('complete', 'quarantined') " +
            "AND (lease_until IS NULL OR lease_until <= :now OR lease_owner = :owner)",
    )
    suspend fun claimMigrationJournal(
        journalId: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
    ): Int
}

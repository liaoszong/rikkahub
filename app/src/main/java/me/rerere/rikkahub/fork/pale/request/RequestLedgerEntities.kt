package me.rerere.rikkahub.fork.pale.request

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "request_ledger",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["parent_request_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["request_id", "attempt_id"],
            childColumns = ["request_id", "active_attempt_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["intent_key"], unique = true),
        Index(value = ["parent_request_id"]),
        Index(value = ["conversation_id", "created_at"]),
        Index(value = ["request_state", "updated_at"]),
        Index(value = ["billable_boundary", "updated_at"]),
        Index(value = ["provider_kind", "provider_id", "model_id"]),
        Index(value = ["request_id", "active_attempt_id"]),
        Index(value = ["legacy_request_id"]),
        Index(value = ["remote_request_id"]),
    ],
)
data class RequestLedgerEntity(
    @PrimaryKey
    @ColumnInfo("request_id")
    val requestId: String,
    @ColumnInfo("intent_key")
    val intentKey: String,
    @ColumnInfo("parent_request_id")
    val parentRequestId: String? = null,
    @ColumnInfo("request_kind")
    val requestKind: String,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("message_id")
    val messageId: String? = null,
    @ColumnInfo("part_id")
    val partId: String? = null,
    @ColumnInfo("legacy_node_id")
    val legacyNodeId: String? = null,
    @ColumnInfo("legacy_message_id")
    val legacyMessageId: String? = null,
    @ColumnInfo("legacy_request_id")
    val legacyRequestId: String? = null,
    @ColumnInfo("workspace_id")
    val workspaceId: String? = null,
    @ColumnInfo("mcp_server_id")
    val mcpServerId: String? = null,
    @ColumnInfo("credential_ref_id")
    val credentialRefId: String? = null,
    @ColumnInfo("provider_kind")
    val providerKind: String? = null,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    @ColumnInfo("api_surface")
    val apiSurface: String? = null,
    @ColumnInfo("input_digest")
    val inputDigest: String,
    @ColumnInfo("capability_snapshot_json")
    val capabilitySnapshotJson: String,
    @ColumnInfo("resolver_version")
    val resolverVersion: Int,
    @ColumnInfo("tool_catalog_digest")
    val toolCatalogDigest: String? = null,
    @ColumnInfo("approval_state")
    val approvalState: String,
    @ColumnInfo("request_state")
    val requestState: String,
    @ColumnInfo("billable_boundary")
    val billableBoundary: String,
    @ColumnInfo("attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo("active_attempt_id")
    val activeAttemptId: String? = null,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("fencing_epoch", defaultValue = "0")
    val fencingEpoch: Long = 0,
    @ColumnInfo("state_revision", defaultValue = "0")
    val stateRevision: Long = 0,
    @ColumnInfo("billable_at")
    val billableAt: Long? = null,
    @ColumnInfo("dispatch_at")
    val dispatchAt: Long? = null,
    @ColumnInfo("terminal_at")
    val terminalAt: Long? = null,
    @ColumnInfo("remote_request_id")
    val remoteRequestId: String? = null,
    @ColumnInfo("remote_response_id")
    val remoteResponseId: String? = null,
    @ColumnInfo("usage_json")
    val usageJson: String? = null,
    @ColumnInfo("error_kind")
    val errorKind: String? = null,
    @ColumnInfo("error_code")
    val errorCode: String? = null,
    @ColumnInfo("error_message")
    val errorMessage: String? = null,
    @ColumnInfo("unknown_outcome_reason")
    val unknownOutcomeReason: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "request_attempt",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["request_id", "attempt_ordinal"], unique = true),
        Index(value = ["request_id", "attempt_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["request_id", "attempt_state"]),
        Index(value = ["remote_request_id"]),
        Index(value = ["foreground_task_id"]),
        Index(value = ["attempt_state", "updated_at"]),
    ],
)
data class RequestAttemptEntity(
    @PrimaryKey
    @ColumnInfo("attempt_id")
    val attemptId: String,
    @ColumnInfo("request_id")
    val requestId: String,
    @ColumnInfo("attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo("idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo("attempt_state")
    val attemptState: String,
    @ColumnInfo("billable_boundary")
    val billableBoundary: String,
    @ColumnInfo("transport_kind")
    val transportKind: String? = null,
    @ColumnInfo("request_fingerprint")
    val requestFingerprint: String,
    @ColumnInfo("owner_replica_id")
    val ownerReplicaId: String? = null,
    @ColumnInfo("foreground_task_id")
    val foregroundTaskId: String? = null,
    @ColumnInfo("remote_request_id")
    val remoteRequestId: String? = null,
    @ColumnInfo("remote_response_id")
    val remoteResponseId: String? = null,
    @ColumnInfo("prepared_at")
    val preparedAt: Long,
    @ColumnInfo("sent_at")
    val sentAt: Long? = null,
    @ColumnInfo("acknowledged_at")
    val acknowledgedAt: Long? = null,
    @ColumnInfo("first_byte_at")
    val firstByteAt: Long? = null,
    @ColumnInfo("result_received_at")
    val resultReceivedAt: Long? = null,
    @ColumnInfo("commit_started_at")
    val commitStartedAt: Long? = null,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("checkpoint_digest")
    val checkpointDigest: String? = null,
    @ColumnInfo("error_kind")
    val errorKind: String? = null,
    @ColumnInfo("error_code")
    val errorCode: String? = null,
    @ColumnInfo("error_message")
    val errorMessage: String? = null,
    @ColumnInfo("usage_json")
    val usageJson: String? = null,
    @ColumnInfo("state_revision", defaultValue = "0")
    val stateRevision: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "request_output",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["request_id", "attempt_id"],
            childColumns = ["request_id", "attempt_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["request_id", "output_kind", "ordinal"], unique = true),
        Index(value = ["request_id", "attempt_id"]),
        Index(value = ["attempt_id"]),
        Index(value = ["conversation_id", "message_id", "part_id"]),
        Index(value = ["asset_id"]),
        Index(value = ["source_id"]),
    ],
)
data class RequestOutputEntity(
    @PrimaryKey
    @ColumnInfo("output_id")
    val outputId: String,
    @ColumnInfo("request_id")
    val requestId: String,
    @ColumnInfo("attempt_id")
    val attemptId: String? = null,
    @ColumnInfo("output_kind")
    val outputKind: String,
    @ColumnInfo("ordinal")
    val ordinal: Int,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("message_id")
    val messageId: String? = null,
    @ColumnInfo("part_id")
    val partId: String? = null,
    @ColumnInfo("asset_id")
    val assetId: String? = null,
    @ColumnInfo("source_id")
    val sourceId: String? = null,
    @ColumnInfo("content_digest")
    val contentDigest: String,
    @ColumnInfo("committed_at")
    val committedAt: Long,
)

@Entity(
    tableName = "tool_invocation",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["request_id", "attempt_id"],
            childColumns = ["request_id", "attempt_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolPermissionEntity::class,
            parentColumns = ["permission_id"],
            childColumns = ["permission_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["request_id", "provider_tool_call_id"], unique = true),
        Index(value = ["request_id", "invocation_id"], unique = true),
        Index(value = ["request_id", "attempt_id"]),
        Index(value = ["attempt_id"]),
        Index(value = ["server_id", "tool_name"]),
        Index(value = ["approval_state", "execution_state"]),
        Index(value = ["permission_id"]),
    ],
)
data class ToolInvocationEntity(
    @PrimaryKey
    @ColumnInfo("invocation_id")
    val invocationId: String,
    @ColumnInfo("request_id")
    val requestId: String,
    @ColumnInfo("attempt_id")
    val attemptId: String? = null,
    @ColumnInfo("provider_tool_call_id")
    val providerToolCallId: String,
    @ColumnInfo("server_id")
    val serverId: String? = null,
    @ColumnInfo("tool_name")
    val toolName: String,
    @ColumnInfo("schema_digest")
    val schemaDigest: String,
    @ColumnInfo("input_digest")
    val inputDigest: String,
    @ColumnInfo("side_effect_class")
    val sideEffectClass: String,
    @ColumnInfo("approval_state")
    val approvalState: String,
    @ColumnInfo("execution_state")
    val executionState: String,
    @ColumnInfo("permission_id")
    val permissionId: String? = null,
    @ColumnInfo("result_digest")
    val resultDigest: String? = null,
    @ColumnInfo("error_kind")
    val errorKind: String? = null,
    @ColumnInfo("error_code")
    val errorCode: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("approved_at")
    val approvedAt: Long? = null,
    @ColumnInfo("started_at")
    val startedAt: Long? = null,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "tool_permission",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["source_request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["permission_key"], unique = true),
        Index(value = ["source_request_id"]),
        Index(value = ["principal_kind", "principal_id", "decision"]),
        Index(value = ["server_id", "tool_name", "action"]),
        Index(value = ["scope_kind", "scope_id"]),
        Index(value = ["decision", "expires_at"]),
    ],
)
data class ToolPermissionEntity(
    @PrimaryKey
    @ColumnInfo("permission_id")
    val permissionId: String,
    @ColumnInfo("permission_key")
    val permissionKey: String,
    @ColumnInfo("source_request_id")
    val sourceRequestId: String? = null,
    @ColumnInfo("principal_kind")
    val principalKind: String,
    @ColumnInfo("principal_id")
    val principalId: String,
    @ColumnInfo("server_id")
    val serverId: String? = null,
    @ColumnInfo("tool_name")
    val toolName: String,
    @ColumnInfo("action")
    val action: String,
    @ColumnInfo("schema_digest")
    val schemaDigest: String,
    @ColumnInfo("decision")
    val decision: String,
    @ColumnInfo("scope_kind")
    val scopeKind: String,
    @ColumnInfo("scope_id")
    val scopeId: String? = null,
    @ColumnInfo("constraints_json")
    val constraintsJson: String,
    @ColumnInfo("capability_snapshot_json")
    val capabilitySnapshotJson: String,
    @ColumnInfo("policy_version")
    val policyVersion: Int,
    @ColumnInfo("reason")
    val reason: String? = null,
    @ColumnInfo("decided_at")
    val decidedAt: Long,
    @ColumnInfo("expires_at")
    val expiresAt: Long? = null,
    @ColumnInfo("revoked_at")
    val revokedAt: Long? = null,
    @ColumnInfo("state_revision", defaultValue = "0")
    val stateRevision: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "request_audit_event",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["request_id", "attempt_id"],
            childColumns = ["request_id", "attempt_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolInvocationEntity::class,
            parentColumns = ["request_id", "invocation_id"],
            childColumns = ["request_id", "invocation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolInvocationEntity::class,
            parentColumns = ["invocation_id"],
            childColumns = ["invocation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolPermissionEntity::class,
            parentColumns = ["permission_id"],
            childColumns = ["permission_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["request_id", "event_seq"], unique = true),
        Index(value = ["request_id", "created_at"]),
        Index(value = ["request_id", "attempt_id"]),
        Index(value = ["request_id", "invocation_id"]),
        Index(value = ["event_kind", "created_at"]),
        Index(value = ["attempt_id", "created_at"]),
        Index(value = ["invocation_id", "created_at"]),
        Index(value = ["permission_id", "created_at"]),
    ],
)
data class RequestAuditEventEntity(
    @PrimaryKey
    @ColumnInfo("event_id")
    val eventId: String,
    @ColumnInfo("request_id")
    val requestId: String,
    @ColumnInfo("event_seq")
    val eventSeq: Long,
    @ColumnInfo("attempt_id")
    val attemptId: String? = null,
    @ColumnInfo("invocation_id")
    val invocationId: String? = null,
    @ColumnInfo("permission_id")
    val permissionId: String? = null,
    @ColumnInfo("event_kind")
    val eventKind: String,
    @ColumnInfo("actor_kind")
    val actorKind: String,
    @ColumnInfo("actor_id")
    val actorId: String? = null,
    @ColumnInfo("payload_digest")
    val payloadDigest: String,
    @ColumnInfo("payload_json")
    val payloadJson: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "tool_audit_event",
    foreignKeys = [
        ForeignKey(
            entity = RequestLedgerEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolInvocationEntity::class,
            parentColumns = ["request_id", "invocation_id"],
            childColumns = ["request_id", "invocation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolInvocationEntity::class,
            parentColumns = ["invocation_id"],
            childColumns = ["invocation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ToolPermissionEntity::class,
            parentColumns = ["permission_id"],
            childColumns = ["permission_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["request_id", "created_at"]),
        Index(value = ["request_id", "invocation_id"]),
        Index(value = ["invocation_id", "created_at"]),
        Index(value = ["permission_id", "created_at"]),
        Index(value = ["event_kind", "created_at"]),
    ],
)
data class ToolAuditEventEntity(
    @PrimaryKey
    @ColumnInfo("event_id")
    val eventId: String,
    @ColumnInfo("request_id")
    val requestId: String? = null,
    @ColumnInfo("invocation_id")
    val invocationId: String? = null,
    @ColumnInfo("permission_id")
    val permissionId: String? = null,
    @ColumnInfo("event_kind")
    val eventKind: String,
    @ColumnInfo("actor_kind")
    val actorKind: String,
    @ColumnInfo("actor_id")
    val actorId: String? = null,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("payload_digest")
    val payloadDigest: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "request_migration_journal",
    indices = [
        Index(value = ["source_kind", "source_id"], unique = true),
        Index(value = ["phase", "lease_until"]),
    ],
)
data class RequestMigrationJournalEntity(
    @PrimaryKey
    @ColumnInfo("journal_id")
    val journalId: String,
    @ColumnInfo("source_kind")
    val sourceKind: String,
    @ColumnInfo("source_id")
    val sourceId: String,
    @ColumnInfo("phase")
    val phase: String,
    @ColumnInfo("source_digest")
    val sourceDigest: String? = null,
    @ColumnInfo("expected_count")
    val expectedCount: Int? = null,
    @ColumnInfo("migrated_count", defaultValue = "0")
    val migratedCount: Int = 0,
    @ColumnInfo("cursor_json")
    val cursorJson: String? = null,
    @ColumnInfo("checkpoint_digest")
    val checkpointDigest: String? = null,
    @ColumnInfo("legacy_retained", defaultValue = "1")
    val legacyRetained: Boolean = true,
    @ColumnInfo("attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo("last_error_code")
    val lastErrorCode: String? = null,
    @ColumnInfo("last_error_detail")
    val lastErrorDetail: String? = null,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("fencing_epoch", defaultValue = "0")
    val fencingEpoch: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

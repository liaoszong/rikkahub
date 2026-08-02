package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stable metadata for one group of sibling messages in the v2 conversation graph. */
@Entity(
    tableName = "message_branch_group",
    primaryKeys = ["conversation_id", "branch_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "legacy_order"]),
    ],
)
data class MessageBranchGroupEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("branch_group_id")
    val branchGroupId: String,
    @ColumnInfo("legacy_node_index")
    val legacyNodeIndex: Int? = null,
    @ColumnInfo("legacy_order")
    val legacyOrder: Int? = null,
    @ColumnInfo("created_at")
    val createdAt: String,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo("legacy_inferred", defaultValue = "0")
    val legacyInferred: Boolean = false,
)

/** One durable message in the real parent-linked conversation tree. */
@Entity(
    tableName = "conversation_message",
    primaryKeys = ["conversation_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageBranchGroupEntity::class,
            parentColumns = ["conversation_id", "branch_group_id"],
            childColumns = ["conversation_id", "branch_group_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ConversationMessageEntity::class,
            parentColumns = ["conversation_id", "message_id"],
            childColumns = ["conversation_id", "parent_message_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "branch_group_id", "sibling_ordinal"], unique = true),
        Index(value = ["conversation_id", "parent_message_id"]),
        Index(value = ["conversation_id", "branch_group_id"]),
        Index(value = ["request_id"]),
        Index(value = ["origin_conversation_id", "origin_message_id"]),
        Index(value = ["conversation_id", "state"]),
    ],
)
data class ConversationMessageEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("parent_message_id")
    val parentMessageId: String? = null,
    @ColumnInfo("branch_group_id")
    val branchGroupId: String,
    @ColumnInfo("sibling_ordinal")
    val siblingOrdinal: Int,
    @ColumnInfo("origin_conversation_id")
    val originConversationId: String? = null,
    @ColumnInfo("origin_message_id")
    val originMessageId: String? = null,
    @ColumnInfo("legacy_message_id")
    val legacyMessageId: String? = null,
    @ColumnInfo("request_id")
    val requestId: String? = null,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("provider_response_id")
    val providerResponseId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: String,
    @ColumnInfo("finished_at")
    val finishedAt: String? = null,
    @ColumnInfo("usage_json")
    val usageJson: String? = null,
    @ColumnInfo("annotations_json", defaultValue = "[]")
    val annotationsJson: String = "[]",
    @ColumnInfo("translation")
    val translation: String? = null,
    @ColumnInfo("envelope_extras_json")
    val envelopeExtrasJson: String? = null,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo("content_digest")
    val contentDigest: String,
    @ColumnInfo("legacy_inferred", defaultValue = "0")
    val legacyInferred: Boolean = false,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
)

/** A stable, independently addressable top-level content part of one message. */
@Entity(
    tableName = "message_part",
    primaryKeys = ["conversation_id", "part_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationMessageEntity::class,
            parentColumns = ["conversation_id", "message_id"],
            childColumns = ["conversation_id", "message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "message_id", "ordinal"], unique = true),
        Index(value = ["asset_id"]),
        Index(value = ["tool_invocation_id"]),
    ],
)
data class MessagePartEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("part_id")
    val partId: String,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("ordinal")
    val ordinal: Int,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("schema_version", defaultValue = "1")
    val schemaVersion: Int = 1,
    @ColumnInfo("payload_json")
    val payloadJson: String,
    @ColumnInfo("payload_digest")
    val payloadDigest: String,
    @ColumnInfo("asset_id")
    val assetId: String? = null,
    @ColumnInfo("tool_invocation_id")
    val toolInvocationId: String? = null,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
)

/** Restartable state for post-open conversion of one legacy conversation. */
@Entity(
    tableName = "conversation_migration_journal",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["phase", "lease_until"]),
    ],
)
data class ConversationMigrationJournalEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("phase", defaultValue = "PENDING")
    val phase: String = ConversationV2Values.MIGRATION_PENDING,
    @ColumnInfo("source_revision", defaultValue = "0")
    val sourceRevision: Long = 0,
    @ColumnInfo("legacy_source_digest")
    val legacySourceDigest: String? = null,
    @ColumnInfo("legacy_projection_digest")
    val legacyProjectionDigest: String? = null,
    @ColumnInfo("v2_projection_digest")
    val v2ProjectionDigest: String? = null,
    @ColumnInfo("next_node_index", defaultValue = "0")
    val nextNodeIndex: Int = 0,
    @ColumnInfo("previous_selected_message_id")
    val previousSelectedMessageId: String? = null,
    @ColumnInfo("expected_group_count")
    val expectedGroupCount: Int? = null,
    @ColumnInfo("expected_message_count")
    val expectedMessageCount: Int? = null,
    @ColumnInfo("expected_part_count")
    val expectedPartCount: Int? = null,
    @ColumnInfo("written_group_count", defaultValue = "0")
    val writtenGroupCount: Int = 0,
    @ColumnInfo("written_message_count", defaultValue = "0")
    val writtenMessageCount: Int = 0,
    @ColumnInfo("written_part_count", defaultValue = "0")
    val writtenPartCount: Int = 0,
    @ColumnInfo("inference_flags_json", defaultValue = "[]")
    val inferenceFlagsJson: String = "[]",
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
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/** Exact evidence for legacy records that cannot be safely projected into v2. */
@Entity(
    tableName = "conversation_migration_quarantine",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id"]),
    ],
)
data class ConversationMigrationQuarantineEntity(
    @PrimaryKey
    @ColumnInfo("quarantine_id")
    val quarantineId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_id")
    val nodeId: String? = null,
    @ColumnInfo("variant_index")
    val variantIndex: Int? = null,
    @ColumnInfo("payload_digest")
    val payloadDigest: String? = null,
    @ColumnInfo("raw_payload")
    val rawPayload: String? = null,
    @ColumnInfo("reason_code")
    val reasonCode: String,
    @ColumnInfo("detail")
    val detail: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** Durable trigger for rebuilding one conversation's external FTS projection. */
@Entity(
    tableName = "message_fts_outbox",
    indices = [
        Index(value = ["conversation_id", "target_revision", "operation"], unique = true),
        Index(value = ["state", "next_attempt_at"]),
    ],
)
data class MessageFtsOutboxEntity(
    @PrimaryKey
    @ColumnInfo("event_id")
    val eventId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("target_revision")
    val targetRevision: Long,
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("state", defaultValue = "PENDING")
    val state: String = ConversationV2Values.OUTBOX_PENDING,
    @ColumnInfo("attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo("next_attempt_at", defaultValue = "0")
    val nextAttemptAt: Long = 0,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

object ConversationV2Values {
    const val STORAGE_VERSION_LEGACY = 1
    const val STORAGE_VERSION_V2 = 2

    const val MIGRATION_PENDING = "PENDING"
    const val MIGRATION_COPYING = "COPYING"
    const val MIGRATION_VERIFYING = "VERIFYING"
    const val MIGRATION_READY = "READY"
    const val MIGRATION_QUARANTINED = "QUARANTINED"

    const val MESSAGE_DRAFT = "DRAFT"
    const val MESSAGE_STREAMING = "STREAMING"
    const val MESSAGE_COMPLETED = "COMPLETED"
    const val MESSAGE_INTERRUPTED = "INTERRUPTED"
    const val MESSAGE_FAILED = "FAILED"

    const val OUTBOX_UPSERT = "UPSERT"
    const val OUTBOX_DELETE = "DELETE"
    const val OUTBOX_REBUILD = "REBUILD"
    const val OUTBOX_PENDING = "PENDING"
    const val OUTBOX_PROCESSING = "PROCESSING"
    const val OUTBOX_DONE = "DONE"
}

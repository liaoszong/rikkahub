package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable, content-addressed media payload metadata. */
@Entity(
    tableName = "media_blob",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["storage_state"]),
    ],
)
data class MediaBlobEntity(
    @PrimaryKey
    @ColumnInfo("blob_id")
    val blobId: String,
    @ColumnInfo("sha256")
    val sha256: String? = null,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("width")
    val width: Int? = null,
    @ColumnInfo("height")
    val height: Int? = null,
    @ColumnInfo("duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo("storage_state")
    val storageState: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("verified_at")
    val verifiedAt: Long? = null,
)

/** Assigns immutable payload variants to a logical media asset. */
@Entity(
    tableName = "media_asset_blob",
    primaryKeys = ["asset_id", "blob_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaBlobEntity::class,
            parentColumns = ["blob_id"],
            childColumns = ["blob_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["blob_id"]),
        Index(value = ["asset_id", "role"], unique = true),
    ],
)
data class MediaAssetBlobEntity(
    @ColumnInfo("asset_id")
    val assetId: String,
    @ColumnInfo("blob_id")
    val blobId: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** A local, external or remote physical copy of one immutable blob. */
@Entity(
    tableName = "media_replica",
    foreignKeys = [
        ForeignKey(
            entity = MediaBlobEntity::class,
            parentColumns = ["blob_id"],
            childColumns = ["blob_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ManagedFileEntity::class,
            parentColumns = ["file_id"],
            childColumns = ["managed_file_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["blob_id"]),
        Index(value = ["managed_file_id"], unique = true),
        Index(value = ["remote_locator"], unique = true),
        Index(value = ["kind", "state"]),
    ],
)
data class MediaReplicaEntity(
    @PrimaryKey
    @ColumnInfo("replica_id")
    val replicaId: String,
    @ColumnInfo("blob_id")
    val blobId: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("managed_file_id")
    val managedFileId: String? = null,
    @ColumnInfo("remote_locator")
    val remoteLocator: String? = null,
    @ColumnInfo("etag")
    val etag: String? = null,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("encrypted")
    val encrypted: Boolean = false,
    @ColumnInfo("verified_at")
    val verifiedAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/** Ordered edit, derivation and reference-input lineage between logical assets. */
@Entity(
    tableName = "media_relation",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["related_asset_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "relation_kind", "ordinal"], unique = true),
        Index(value = ["asset_id", "related_asset_id", "relation_kind"], unique = true),
        Index(value = ["related_asset_id"]),
    ],
)
data class MediaRelationEntity(
    @PrimaryKey
    @ColumnInfo("relation_id")
    val relationId: String,
    @ColumnInfo("asset_id")
    val assetId: String,
    @ColumnInfo("related_asset_id")
    val relatedAssetId: String,
    @ColumnInfo("relation_kind")
    val relationKind: String,
    @ColumnInfo("ordinal")
    val ordinal: Int,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** Explicit ownership reference used by message deletion and future media GC. */
@Entity(
    tableName = "message_media_ref",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["owner_key", "asset_id"], unique = true),
        Index(value = ["asset_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["message_node_id"]),
        Index(value = ["message_id"]),
        Index(value = ["tool_call_id"]),
    ],
)
data class MessageMediaRefEntity(
    @PrimaryKey
    @ColumnInfo("ref_id")
    val refId: String,
    @ColumnInfo("owner_key")
    val ownerKey: String,
    @ColumnInfo("asset_id")
    val assetId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("message_node_id")
    val messageNodeId: String? = null,
    @ColumnInfo("message_id")
    val messageId: String? = null,
    @ColumnInfo("part_id")
    val partId: String? = null,
    @ColumnInfo("tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** Restartable progress for conservative Room backfill and later file verification. */
@Entity(
    tableName = "media_migration_journal",
    indices = [
        Index(value = ["scope_kind", "scope_key", "stage"], unique = true),
        Index(value = ["state", "updated_at"]),
    ],
)
data class MediaMigrationJournalEntity(
    @PrimaryKey
    @ColumnInfo("journal_id")
    val journalId: String,
    @ColumnInfo("scope_kind")
    val scopeKind: String,
    @ColumnInfo("scope_key")
    val scopeKey: String,
    @ColumnInfo("stage")
    val stage: String,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("detail")
    val detail: String? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

object MediaV2Values {
    const val BLOB_ROLE_ORIGINAL = "original"

    const val BLOB_STAGING = "staging"
    const val BLOB_AVAILABLE = "available"
    const val BLOB_MISSING = "missing"
    const val BLOB_CORRUPT = "corrupt"

    const val REPLICA_LOCAL_MANAGED = "local_managed"

    const val RELATION_EDIT_OF = "edit_of"
    const val RELATION_REFERENCE_INPUT = "reference_input"

    const val JOURNAL_COMPLETE = "complete"
    const val JOURNAL_PENDING = "pending"
    const val JOURNAL_FAILED = "failed"

    const val STAGE_BLOB_BACKFILL = "blob_backfill"
    const val STAGE_REFERENCE_BACKFILL = "reference_backfill"
    const val STAGE_FILE_RELOCATION = "file_relocation"
}

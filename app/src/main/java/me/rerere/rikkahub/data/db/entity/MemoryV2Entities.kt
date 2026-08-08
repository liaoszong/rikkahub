package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_revision_v2",
    primaryKeys = ["memory_id", "revision"],
    indices = [Index(value = ["memory_id", "created_at"])],
)
data class MemoryRevisionV2Entity(
    @ColumnInfo("memory_id") val memoryId: String,
    @ColumnInfo("revision") val revision: Int,
    @ColumnInfo("canonical_statement") val canonicalStatement: String,
    @ColumnInfo("source_refs_json") val sourceRefsJson: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("supersedes_revision") val supersedesRevision: Int? = null,
    @ColumnInfo("event_kind") val eventKind: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(
    tableName = "memory_record_v2",
    indices = [
        Index(value = ["scope_kind", "scope_id", "status"]),
        Index(value = ["type", "status"]),
        Index(value = ["expires_at"]),
        Index(value = ["legacy_id"], unique = true),
    ],
)
data class MemoryRecordV2Entity(
    @PrimaryKey @ColumnInfo("memory_id") val memoryId: String,
    @ColumnInfo("legacy_id") val legacyId: Int? = null,
    @ColumnInfo("type") val type: String,
    @ColumnInfo("scope_kind") val scopeKind: String,
    @ColumnInfo("scope_id") val scopeId: String,
    @ColumnInfo("canonical_statement") val canonicalStatement: String,
    @ColumnInfo("source_refs_json") val sourceRefsJson: String,
    @ColumnInfo("source_trust") val sourceTrust: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("confirmed_at") val confirmedAt: Long? = null,
    @ColumnInfo("last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo("expires_at") val expiresAt: Long? = null,
    @ColumnInfo("confidence") val confidence: Double,
    @ColumnInfo("sensitivity") val sensitivity: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("revision") val revision: Int,
    @ColumnInfo("supersedes_json") val supersedesJson: String = "[]",
    @ColumnInfo("conflicts_with_json") val conflictsWithJson: String = "[]",
    @ColumnInfo("extraction_policy_version") val extractionPolicyVersion: Int,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "memory_audit_event_v2",
    indices = [Index(value = ["memory_id", "created_at"]), Index(value = ["event_kind", "created_at"])],
)
data class MemoryAuditEventV2Entity(
    @PrimaryKey @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("memory_id") val memoryId: String,
    @ColumnInfo("event_kind") val eventKind: String,
    @ColumnInfo("revision") val revision: Int,
    @ColumnInfo("payload_digest") val payloadDigest: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

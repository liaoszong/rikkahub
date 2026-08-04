package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable identity and causal clock owned by one installation within a sync space. */
@Entity(
    tableName = "sync_replica",
    indices = [
        Index(name = "index_sync_replica_space_id", value = ["space_id"]),
        Index(name = "index_sync_replica_updated_at", value = ["updated_at"]),
        Index(
            name = "index_sync_replica_space_epoch_replica",
            value = ["space_id", "sync_epoch", "replica_id"],
            unique = true,
        ),
    ],
)
data class SyncReplicaEntity(
    @PrimaryKey
    @ColumnInfo("replica_id")
    val replicaId: String,
    @ColumnInfo("space_id")
    val spaceId: String,
    @ColumnInfo("sync_epoch")
    val syncEpoch: String,
    @ColumnInfo("device_label")
    val deviceLabel: String? = null,
    @ColumnInfo(name = "operation_counter", defaultValue = "0")
    val operationCounter: Long = 0,
    @ColumnInfo(name = "hlc_physical_ms", defaultValue = "0")
    val hlcPhysicalMs: Long = 0,
    @ColumnInfo(name = "hlc_logical", defaultValue = "0")
    val hlcLogical: Long = 0,
    @ColumnInfo(name = "acknowledged_vector_json", defaultValue = "'{}'")
    val acknowledgedVectorJson: String = "{}",
    @ColumnInfo("last_successful_sync_at")
    val lastSuccessfulSyncAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    init {
        require(replicaId.isNotBlank() && spaceId.isNotBlank() && syncEpoch.isNotBlank())
        require(operationCounter >= 0 && hlcPhysicalMs >= 0 && hlcLogical >= 0)
    }
}

/** Materialized causal head for one syncable logical record. */
@Entity(
    tableName = "sync_record_head",
    primaryKeys = ["space_id", "sync_epoch", "entity_type", "entity_id"],
    indices = [
        Index(name = "index_sync_record_head_operation_id", value = ["operation_id"], unique = true),
        Index(
            name = "index_sync_record_head_dot",
            value = ["dot_replica_id", "dot_counter"],
            unique = true,
        ),
        Index(name = "index_sync_record_head_writer", value = ["writer_replica_id"]),
        Index(name = "index_sync_record_head_updated_at", value = ["updated_at"]),
    ],
)
data class SyncRecordHeadEntity(
    @ColumnInfo("space_id")
    val spaceId: String,
    @ColumnInfo("sync_epoch")
    val syncEpoch: String,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("operation_id")
    val operationId: String,
    @ColumnInfo("dot_replica_id")
    val dotReplicaId: String,
    @ColumnInfo("dot_counter")
    val dotCounter: Long,
    @ColumnInfo("writer_replica_id")
    val writerReplicaId: String,
    @ColumnInfo("causal_vector_json")
    val causalVectorJson: String,
    @ColumnInfo("hlc_physical_ms")
    val hlcPhysicalMs: Long,
    @ColumnInfo("hlc_logical")
    val hlcLogical: Long,
    @ColumnInfo("payload_hash")
    val payloadHash: String? = null,
    @ColumnInfo(name = "tombstone", defaultValue = "0")
    val tombstone: Boolean = false,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    init {
        require(spaceId.isNotBlank() && syncEpoch.isNotBlank())
        require(entityType.isNotBlank() && entityId.isNotBlank() && operationId.isNotBlank())
        require(dotReplicaId.isNotBlank() && writerReplicaId.isNotBlank())
        require(dotCounter > 0 && hlcPhysicalMs >= 0 && hlcLogical >= 0)
    }
}

/** Immutable local operation waiting for transport acknowledgement. */
@Entity(
    tableName = "sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = SyncReplicaEntity::class,
            parentColumns = ["space_id", "sync_epoch", "replica_id"],
            childColumns = ["space_id", "sync_epoch", "replica_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = SyncRecordHeadEntity::class,
            parentColumns = ["space_id", "sync_epoch", "entity_type", "entity_id"],
            childColumns = ["space_id", "sync_epoch", "entity_type", "entity_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            name = "index_sync_outbox_replica_sequence",
            value = ["space_id", "sync_epoch", "replica_id", "sequence"],
            unique = true,
        ),
        Index(
            name = "index_sync_outbox_record",
            value = ["space_id", "sync_epoch", "entity_type", "entity_id"],
        ),
        Index(
            name = "index_sync_outbox_due",
            value = ["space_id", "sync_epoch", "replica_id", "state", "next_attempt_at"],
        ),
        Index(name = "index_sync_outbox_lease", value = ["lease_until"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo("operation_id")
    val operationId: String,
    @ColumnInfo("space_id")
    val spaceId: String,
    @ColumnInfo("sync_epoch")
    val syncEpoch: String,
    @ColumnInfo("replica_id")
    val replicaId: String,
    @ColumnInfo("sequence")
    val sequence: Long,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("base_vector_json")
    val baseVectorJson: String,
    @ColumnInfo("dot_counter")
    val dotCounter: Long,
    @ColumnInfo("hlc_physical_ms")
    val hlcPhysicalMs: Long,
    @ColumnInfo("hlc_logical")
    val hlcLogical: Long,
    @ColumnInfo("payload_hash")
    val payloadHash: String? = null,
    @ColumnInfo(name = "tombstone", defaultValue = "0")
    val tombstone: Boolean = false,
    @ColumnInfo(name = "envelope_bytes", typeAffinity = ColumnInfo.BLOB)
    val envelopeBytes: ByteArray,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo("next_attempt_at")
    val nextAttemptAt: Long,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("remote_etag")
    val remoteEtag: String? = null,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("uploaded_at")
    val uploadedAt: Long? = null,
) {
    init {
        require(operationId.isNotBlank() && spaceId.isNotBlank() && syncEpoch.isNotBlank())
        require(replicaId.isNotBlank() && entityType.isNotBlank() && entityId.isNotBlank())
        require(sequence > 0 && dotCounter == sequence)
        require(hlcPhysicalMs >= 0 && hlcLogical >= 0 && attemptCount >= 0)
        require(state in VALID_STATES)
        require((leaseOwner == null) == (leaseUntil == null))
    }

    private companion object {
        val VALID_STATES = setOf("pending", "in_flight", "uploaded", "failed", "blocked")
    }
}

/** Durable evidence for a causally concurrent local/remote record pair. */
@Entity(
    tableName = "sync_conflict",
    foreignKeys = [
        ForeignKey(
            entity = SyncRecordHeadEntity::class,
            parentColumns = ["space_id", "sync_epoch", "entity_type", "entity_id"],
            childColumns = ["space_id", "sync_epoch", "entity_type", "entity_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_sync_conflict_identity",
            value = [
                "space_id",
                "sync_epoch",
                "entity_type",
                "entity_id",
                "local_operation_id",
                "remote_operation_id",
            ],
            unique = true,
        ),
        Index(name = "index_sync_conflict_state", value = ["resolution_state", "updated_at"]),
        Index(
            name = "index_sync_conflict_record",
            value = ["space_id", "sync_epoch", "entity_type", "entity_id"],
        ),
    ],
)
data class SyncConflictEntity(
    @PrimaryKey
    @ColumnInfo("conflict_id")
    val conflictId: String,
    @ColumnInfo("space_id")
    val spaceId: String,
    @ColumnInfo("sync_epoch")
    val syncEpoch: String,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("local_operation_id")
    val localOperationId: String,
    @ColumnInfo("remote_operation_id")
    val remoteOperationId: String,
    @ColumnInfo("base_vector_json")
    val baseVectorJson: String,
    @ColumnInfo("local_head_json")
    val localHeadJson: String,
    @ColumnInfo("remote_head_json")
    val remoteHeadJson: String,
    @ColumnInfo("classification")
    val classification: String,
    @ColumnInfo("resolution_state")
    val resolutionState: String,
    @ColumnInfo(name = "auto_mergeable", defaultValue = "0")
    val autoMergeable: Boolean = false,
    @ColumnInfo("resolved_operation_id")
    val resolvedOperationId: String? = null,
    @ColumnInfo("detected_at")
    val detectedAt: Long,
    @ColumnInfo("resolved_at")
    val resolvedAt: Long? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    init {
        require(conflictId.isNotBlank() && spaceId.isNotBlank() && syncEpoch.isNotBlank())
        require(entityType.isNotBlank() && entityId.isNotBlank())
        require(localOperationId.isNotBlank() && remoteOperationId.isNotBlank())
        require(localOperationId != remoteOperationId)
        require(resolutionState in VALID_RESOLUTION_STATES)
        require((resolutionState == "open") == (resolvedAt == null))
    }

    private companion object {
        val VALID_RESOLUTION_STATES = setOf("open", "auto_merged", "resolved", "dismissed")
    }
}

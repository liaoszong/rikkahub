package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncRecordHeadEntity
import me.rerere.rikkahub.data.db.entity.SyncReplicaEntity

@Dao
interface SyncV2DAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReplica(replica: SyncReplicaEntity)

    @Query(
        "SELECT * FROM sync_replica WHERE space_id = :spaceId AND sync_epoch = :syncEpoch " +
            "AND replica_id = :replicaId LIMIT 1",
    )
    suspend fun getReplica(spaceId: String, syncEpoch: String, replicaId: String): SyncReplicaEntity?

    @Query(
        "SELECT * FROM sync_replica WHERE space_id = :spaceId AND sync_epoch = :syncEpoch ORDER BY created_at",
    )
    fun observeReplicas(spaceId: String, syncEpoch: String): Flow<List<SyncReplicaEntity>>

    @Query(
        "UPDATE sync_replica SET operation_counter = :nextCounter, " +
            "hlc_physical_ms = :hlcPhysicalMs, hlc_logical = :hlcLogical, updated_at = :updatedAt " +
            "WHERE space_id = :spaceId AND sync_epoch = :syncEpoch AND replica_id = :replicaId " +
            "AND operation_counter = :expectedCounter AND :nextCounter = :expectedCounter + 1",
    )
    suspend fun advanceReplicaClock(
        spaceId: String,
        syncEpoch: String,
        replicaId: String,
        expectedCounter: Long,
        nextCounter: Long,
        hlcPhysicalMs: Long,
        hlcLogical: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE sync_replica SET acknowledged_vector_json = :vectorJson, " +
        "last_successful_sync_at = :syncedAt, updated_at = :syncedAt " +
            "WHERE space_id = :spaceId AND sync_epoch = :syncEpoch AND replica_id = :replicaId",
    )
    suspend fun acknowledgeReplica(
        spaceId: String,
        syncEpoch: String,
        replicaId: String,
        vectorJson: String,
        syncedAt: Long,
    ): Int

    @Upsert
    suspend fun upsertRecordHead(head: SyncRecordHeadEntity)

    @Query(
        "SELECT * FROM sync_record_head WHERE space_id = :spaceId AND sync_epoch = :syncEpoch " +
            "AND entity_type = :entityType AND entity_id = :entityId LIMIT 1",
    )
    suspend fun getRecordHead(
        spaceId: String,
        syncEpoch: String,
        entityType: String,
        entityId: String,
    ): SyncRecordHeadEntity?

    @Query(
        "SELECT * FROM sync_record_head WHERE space_id = :spaceId AND sync_epoch = :syncEpoch " +
            "AND entity_type = :entityType ORDER BY entity_id",
    )
    fun observeRecordHeads(
        spaceId: String,
        syncEpoch: String,
        entityType: String,
    ): Flow<List<SyncRecordHeadEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(operation: SyncOutboxEntity)

    @Transaction
    suspend fun commitLocalOperation(
        expectedPreviousCounter: Long,
        head: SyncRecordHeadEntity,
        operation: SyncOutboxEntity,
    ) {
        require(head.operationId == operation.operationId)
        require(head.spaceId == operation.spaceId && head.syncEpoch == operation.syncEpoch)
        require(head.entityType == operation.entityType && head.entityId == operation.entityId)
        require(head.dotReplicaId == operation.replicaId && head.dotCounter == operation.dotCounter)
        require(operation.sequence == operation.dotCounter)
        require(operation.sequence == Math.addExact(expectedPreviousCounter, 1))
        check(
            advanceReplicaClock(
                spaceId = operation.spaceId,
                syncEpoch = operation.syncEpoch,
                replicaId = operation.replicaId,
                expectedCounter = expectedPreviousCounter,
                nextCounter = operation.sequence,
                hlcPhysicalMs = operation.hlcPhysicalMs,
                hlcLogical = operation.hlcLogical,
                updatedAt = operation.updatedAt,
            ) == 1,
        ) { "Sync replica changed concurrently, is missing, or its counter is exhausted" }
        upsertRecordHead(head)
        insertOutbox(operation)
    }

    @Query("SELECT * FROM sync_outbox WHERE operation_id = :operationId LIMIT 1")
    suspend fun getOutbox(operationId: String): SyncOutboxEntity?

    @Query(
        "SELECT * FROM sync_outbox WHERE space_id = :spaceId AND sync_epoch = :syncEpoch " +
            "AND replica_id = :replicaId " +
            "AND state IN ('pending', 'failed') AND next_attempt_at <= :now " +
            "AND (lease_until IS NULL OR lease_until < :now) ORDER BY sequence LIMIT :limit",
    )
    suspend fun getDueOutbox(
        spaceId: String,
        syncEpoch: String,
        replicaId: String,
        now: Long,
        limit: Int,
    ): List<SyncOutboxEntity>

    @Query(
        "UPDATE sync_outbox SET state = 'in_flight', lease_owner = :owner, lease_until = :leaseUntil, " +
            "attempt_count = attempt_count + 1, updated_at = :now WHERE operation_id = :operationId " +
            "AND state IN ('pending', 'failed') AND next_attempt_at <= :now " +
            "AND (lease_until IS NULL OR lease_until < :now)",
    )
    suspend fun claimOutbox(operationId: String, owner: String, now: Long, leaseUntil: Long): Int

    @Query(
        "UPDATE sync_outbox SET state = 'uploaded', remote_etag = :remoteEtag, uploaded_at = :uploadedAt, " +
            "lease_owner = NULL, lease_until = NULL, last_error = NULL, updated_at = :uploadedAt " +
            "WHERE operation_id = :operationId AND state = 'in_flight' AND lease_owner = :owner",
    )
    suspend fun markOutboxUploaded(
        operationId: String,
        owner: String,
        remoteEtag: String?,
        uploadedAt: Long,
    ): Int

    @Query(
        "UPDATE sync_outbox SET state = 'failed', next_attempt_at = :nextAttemptAt, " +
            "lease_owner = NULL, lease_until = NULL, last_error = :error, updated_at = :updatedAt " +
            "WHERE operation_id = :operationId AND state = 'in_flight' AND lease_owner = :owner",
    )
    suspend fun failOutbox(
        operationId: String,
        owner: String,
        error: String,
        nextAttemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE sync_outbox SET state = 'pending', lease_owner = NULL, lease_until = NULL, " +
            "updated_at = :now WHERE state = 'in_flight' AND lease_until < :now",
    )
    suspend fun releaseExpiredOutboxLeases(now: Long): Int

    @Query("DELETE FROM sync_outbox WHERE state = 'uploaded' AND uploaded_at < :before")
    suspend fun deleteUploadedOutbox(before: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConflict(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflict WHERE conflict_id = :conflictId LIMIT 1")
    suspend fun getConflict(conflictId: String): SyncConflictEntity?

    @Query(
        "SELECT * FROM sync_conflict WHERE space_id = :spaceId AND sync_epoch = :syncEpoch " +
            "AND resolution_state = 'open' " +
            "ORDER BY detected_at, conflict_id",
    )
    fun observeOpenConflicts(spaceId: String, syncEpoch: String): Flow<List<SyncConflictEntity>>

    @Query(
        "UPDATE sync_conflict SET resolution_state = :resolutionState, " +
            "resolved_operation_id = :resolvedOperationId, resolved_at = :resolvedAt, updated_at = :resolvedAt " +
            "WHERE conflict_id = :conflictId AND resolution_state = 'open' " +
            "AND :resolutionState IN ('auto_merged', 'resolved', 'dismissed')",
    )
    suspend fun resolveConflict(
        conflictId: String,
        resolutionState: String,
        resolvedOperationId: String?,
        resolvedAt: Long,
    ): Int
}

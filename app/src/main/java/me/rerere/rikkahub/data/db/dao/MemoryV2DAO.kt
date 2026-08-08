package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryAuditEventV2Entity
import me.rerere.rikkahub.data.db.entity.MemoryRecordV2Entity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionV2Entity

@Dao
interface MemoryV2DAO {
    @Query("SELECT * FROM memory_record_v2 WHERE scope_kind = :scopeKind AND scope_id = :scopeId ORDER BY updated_at DESC")
    fun observeScope(scopeKind: String, scopeId: String): Flow<List<MemoryRecordV2Entity>>

    @Query("SELECT * FROM memory_record_v2 WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun getScope(scopeKind: String, scopeId: String): List<MemoryRecordV2Entity>

    @Query("SELECT * FROM memory_record_v2 WHERE scope_kind = :scopeKind AND scope_id = :scopeId AND status = 'active' ORDER BY updated_at DESC")
    suspend fun getActiveScope(scopeKind: String, scopeId: String): List<MemoryRecordV2Entity>

    @Query("SELECT * FROM memory_record_v2 WHERE memory_id = :memoryId")
    suspend fun get(memoryId: String): MemoryRecordV2Entity?

    @Query("SELECT * FROM memory_record_v2 WHERE legacy_id = :legacyId")
    suspend fun getByLegacyId(legacyId: Int): MemoryRecordV2Entity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: MemoryRecordV2Entity)

    @Query("UPDATE memory_record_v2 SET canonical_statement = :statement, revision = revision + 1, updated_at = :updatedAt WHERE legacy_id = :legacyId")
    suspend fun updateLegacyStatement(legacyId: Int, statement: String, updatedAt: Long): Int

    @Query("UPDATE memory_record_v2 SET status = :status, confirmed_at = CASE WHEN :status = 'active' THEN :updatedAt ELSE confirmed_at END, revision = revision + 1, updated_at = :updatedAt WHERE legacy_id = :legacyId")
    suspend fun updateLegacyStatus(legacyId: Int, status: String, updatedAt: Long): Int

    @Query("UPDATE memory_record_v2 SET status = :status, revision = revision + 1, updated_at = :updatedAt WHERE scope_kind = :scopeKind AND scope_id = :scopeId AND status != 'deleted'")
    suspend fun updateScopeStatus(scopeKind: String, scopeId: String, status: String, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendAudit(event: MemoryAuditEventV2Entity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevision(revision: MemoryRevisionV2Entity): Long

    @Query("SELECT * FROM memory_revision_v2 WHERE memory_id = :memoryId ORDER BY revision ASC")
    suspend fun getRevisions(memoryId: String): List<MemoryRevisionV2Entity>
}

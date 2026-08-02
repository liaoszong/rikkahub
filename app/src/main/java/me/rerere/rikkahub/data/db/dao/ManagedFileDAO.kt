package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.pale.media.MediaStableIds

@Dao
interface ManagedFileDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(file: ManagedFileEntity): Long

    @Query(
        "UPDATE managed_files SET folder = :folder, relative_path = :relativePath, " +
            "display_name = :displayName, mime_type = :mimeType, size_bytes = :sizeBytes, " +
            "created_at = :createdAt, updated_at = :updatedAt " +
            "WHERE id = :id AND file_id = :fileId",
    )
    suspend fun updateStable(
        id: Long,
        fileId: String,
        folder: String,
        relativePath: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM managed_files WHERE id = :id")
    suspend fun getById(id: Long): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE file_id = :fileId")
    suspend fun getByFileId(fileId: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): ManagedFileEntity?

    /**
     * Inserts once and resolves path/id replays to the already committed identity.
     * No conflict path is allowed to replace the numeric primary key or [fileId].
     */
    @Transaction
    suspend fun insertOrGet(file: ManagedFileEntity): ManagedFileEntity {
        MediaStableIds.requireValid(file.fileId, "Managed file id")
        require(file.relativePath.isNotBlank()) { "Managed file path cannot be blank" }
        val insertedId = insertIgnore(file)
        if (insertedId != -1L) return file.copy(id = insertedId)

        getByFileId(file.fileId)?.let { committed ->
            require(committed.relativePath == file.relativePath) {
                "Managed file identity ${file.fileId} is already bound to ${committed.relativePath}"
            }
            return committed
        }
        if (file.id > 0) {
            getById(file.id)?.let { committed ->
                require(committed.relativePath == file.relativePath) {
                    "Managed file row ${file.id} is already bound to ${committed.relativePath}"
                }
                return committed
            }
        }
        return requireNotNull(getByPath(file.relativePath)) {
            "Managed file conflict was reported but no committed row exists: ${file.relativePath}"
        }
    }

    suspend fun update(file: ManagedFileEntity): Int {
        MediaStableIds.requireValid(file.fileId, "Managed file id")
        require(file.id > 0) { "A committed managed file is required" }
        return updateStable(
            id = file.id,
            fileId = file.fileId,
            folder = file.folder,
            relativePath = file.relativePath,
            displayName = file.displayName,
            mimeType = file.mimeType,
            sizeBytes = file.sizeBytes,
            createdAt = file.createdAt,
            updatedAt = file.updatedAt,
        )
    }

    @Query("SELECT * FROM managed_files WHERE folder = :folder ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>>

    @Query(
        "UPDATE media_replica SET state = 'missing', verified_at = NULL, updated_at = :now " +
            "WHERE managed_file_id = (SELECT file_id FROM managed_files WHERE id = :id)",
    )
    suspend fun markReplicaMissingById(id: Long, now: Long): Int

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun deleteRowById(id: Long): Int

    @Query(
        "UPDATE media_replica SET state = 'missing', verified_at = NULL, updated_at = :now " +
            "WHERE managed_file_id IN " +
            "(SELECT file_id FROM managed_files WHERE relative_path = :relativePath)",
    )
    suspend fun markReplicaMissingByPath(relativePath: String, now: Long): Int

    @Query("DELETE FROM managed_files WHERE relative_path = :relativePath")
    suspend fun deleteRowByPath(relativePath: String): Int

    @Query(
        "UPDATE media_replica SET state = 'missing', verified_at = NULL, updated_at = :now " +
            "WHERE managed_file_id IN (SELECT file_id FROM managed_files WHERE folder = :folder)",
    )
    suspend fun markReplicasMissingByFolder(folder: String, now: Long): Int

    @Query("DELETE FROM managed_files WHERE folder = :folder")
    suspend fun deleteRowsByFolder(folder: String): Int

    @Transaction
    suspend fun deleteById(id: Long): Int {
        markReplicaMissingById(id, System.currentTimeMillis())
        return deleteRowById(id)
    }

    @Transaction
    suspend fun deleteByPath(relativePath: String): Int {
        markReplicaMissingByPath(relativePath, System.currentTimeMillis())
        return deleteRowByPath(relativePath)
    }

    @Transaction
    suspend fun deleteByFolder(folder: String): Int {
        markReplicasMissingByFolder(folder, System.currentTimeMillis())
        return deleteRowsByFolder(folder)
    }
}

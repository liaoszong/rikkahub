package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity

@Dao
interface GenMediaDAO {
    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE visibility = 'visible' ORDER BY create_at DESC",
    )
    fun getAll(): PagingSource<Int, MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity ORDER BY create_at DESC")
    fun getAllIncludingHidden(): PagingSource<Int, MediaAssetEntity>

    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE visibility = 'visible' ORDER BY create_at DESC",
    )
    suspend fun getAllMedia(): List<MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity ORDER BY create_at DESC")
    suspend fun getAllMediaIncludingHidden(): List<MediaAssetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(media: MediaAssetEntity): Long

    @Update
    suspend fun update(media: MediaAssetEntity)

    @Query("SELECT * FROM GenMediaEntity WHERE asset_id = :assetId LIMIT 1")
    suspend fun getByAssetId(assetId: String): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE managed_file_id = :managedFileId LIMIT 1")
    suspend fun getByManagedFileId(managedFileId: Long): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE tool_call_id = :toolCallId ORDER BY create_at ASC, id ASC")
    suspend fun getByToolCallId(toolCallId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity WHERE parent_asset_id = :parentAssetId ORDER BY create_at ASC, id ASC")
    suspend fun getDirectVersions(parentAssetId: String): List<MediaAssetEntity>

    @Query(
        "SELECT managed.* FROM managed_files AS managed " +
            "LEFT JOIN GenMediaEntity AS media ON media.path = managed.relative_path " +
            "WHERE managed.folder = :folder AND media.id IS NULL " +
            "ORDER BY managed.created_at ASC LIMIT :limit",
    )
    suspend fun getUnregisteredManagedFiles(folder: String, limit: Int): List<ManagedFileEntity>

    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE storage_state IN ('needs_metadata', 'missing', 'corrupt') " +
            "OR sha256 IS NULL OR width IS NULL OR height IS NULL " +
            "ORDER BY CASE WHEN storage_state = 'needs_metadata' THEN 0 ELSE 1 END, " +
            "updated_at ASC LIMIT :limit",
    )
    suspend fun getAssetsNeedingMetadata(limit: Int): List<MediaAssetEntity>

    @Query(
        "UPDATE GenMediaEntity SET visibility = 'hidden', hidden_at = :hiddenAt, " +
            "updated_at = :hiddenAt WHERE asset_id = :assetId",
    )
    suspend fun hide(assetId: String, hiddenAt: Long): Int

    @Query(
        "UPDATE GenMediaEntity SET visibility = 'visible', hidden_at = NULL, " +
            "updated_at = :restoredAt WHERE asset_id = :assetId",
    )
    suspend fun restore(assetId: String, restoredAt: Long): Int

    /**
     * Registers a file exactly once. The unique path index is the cross-process
     * authority; the transaction turns a concurrent/replayed registration into
     * the already committed row instead of a second gallery item.
     */
    @Transaction
    suspend fun insertOrGet(media: MediaAssetEntity): MediaAssetEntity {
        val insertedId = insertIgnore(media)
        if (insertedId != -1L) return media.copy(id = insertedId.toInt())

        getByAssetId(media.assetId)?.let { committed ->
            require(committed.path == media.path) {
                "Media asset identity ${media.assetId} is already bound to ${committed.path}"
            }
            return committed
        }
        media.managedFileId?.let { managedFileId ->
            getByManagedFileId(managedFileId)?.let { committed ->
                require(committed.path == media.path) {
                    "Managed file $managedFileId is already bound to ${committed.path}"
                }
                return committed
            }
        }
        return requireNotNull(getByPath(media.path)) {
            "Media asset conflict was reported but no committed row exists: ${media.path}"
        }
    }

    @Query("DELETE FROM GenMediaEntity WHERE id = :id")
    suspend fun delete(id: Int)
}

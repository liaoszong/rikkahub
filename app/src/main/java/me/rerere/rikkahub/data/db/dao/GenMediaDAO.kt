package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(media: GenMediaEntity): Long

    @Query("SELECT * FROM genmediaentity WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): GenMediaEntity?

    /**
     * Registers a file exactly once. The unique path index is the cross-process
     * authority; the transaction turns a concurrent/replayed registration into
     * the already committed row instead of a second gallery item.
     */
    @Transaction
    suspend fun insertOrGet(media: GenMediaEntity): GenMediaEntity {
        val insertedId = insertIgnore(media)
        if (insertedId != -1L) return media.copy(id = insertedId.toInt())
        return requireNotNull(getByPath(media.path)) {
            "GenMedia path conflict was reported but no committed row exists: ${media.path}"
        }
    }

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}

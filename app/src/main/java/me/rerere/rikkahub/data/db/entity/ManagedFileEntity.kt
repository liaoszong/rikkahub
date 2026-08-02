package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.pale.media.MediaStableIds

@Entity(
    tableName = "managed_files",
    indices = [
        Index(value = ["file_id"], unique = true),
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"])
    ]
)
data class ManagedFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "file_id", defaultValue = "''")
    val fileId: String = MediaStableIds.newId(),
    @ColumnInfo("folder")
    val folder: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Canonical metadata record for a locally managed media asset.
 *
 * The physical table deliberately keeps its historical [GenMediaEntity] name so existing
 * installations, paging UI and backup files migrate without duplicating the gallery. The
 * [GenMediaEntity] type alias below preserves source compatibility while new code uses the
 * MediaAsset terminology.
 */
@Entity(
    tableName = "GenMediaEntity",
    foreignKeys = [
        ForeignKey(
            entity = ManagedFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["managed_file_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["parent_asset_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["asset_id"], unique = true),
        Index(value = ["managed_file_id"], unique = true),
        Index(value = ["parent_asset_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["tool_call_id"]),
        Index(value = ["visibility", "create_at"]),
        Index(value = ["storage_state"]),
        Index(value = ["media_kind", "lifecycle", "create_at"]),
        Index(value = ["retention_policy", "lifecycle"]),
    ],
)
data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("model_display_name")
    val modelDisplayName: String? = null,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo(name = "type", defaultValue = TYPE_IMAGE_GENERATION)
    val type: String = TYPE_IMAGE_GENERATION,
    @ColumnInfo("source_paths")
    val sourcePaths: String? = null,
    @ColumnInfo("asset_id")
    val assetId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "media_kind", defaultValue = "'image'")
    val mediaKind: String = MEDIA_KIND_IMAGE,
    @ColumnInfo(name = "display_name", defaultValue = "''")
    val displayName: String = "",
    @ColumnInfo(name = "lifecycle", defaultValue = "'active'")
    val lifecycle: String = LIFECYCLE_ACTIVE,
    @ColumnInfo(name = "privacy_scope", defaultValue = "'private'")
    val privacyScope: String = PRIVACY_PRIVATE,
    @ColumnInfo(name = "retention_policy", defaultValue = "'library'")
    val retentionPolicy: String = RETENTION_LIBRARY,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo("managed_file_id")
    val managedFileId: Long? = null,
    @ColumnInfo("origin")
    val origin: String = if (type == TYPE_IMAGE_EDIT) ORIGIN_AI_EDITED else ORIGIN_AI_GENERATED,
    @ColumnInfo("mime_type")
    val mimeType: String = "application/octet-stream",
    @ColumnInfo("size_bytes")
    val sizeBytes: Long = 0,
    @ColumnInfo("width")
    val width: Int? = null,
    @ColumnInfo("height")
    val height: Int? = null,
    @ColumnInfo("sha256")
    val sha256: String? = null,
    @ColumnInfo("storage_state")
    val storageState: String = STORAGE_NEEDS_METADATA,
    @ColumnInfo("visibility")
    val visibility: String = VISIBILITY_VISIBLE,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("message_node_id")
    val messageNodeId: String? = null,
    @ColumnInfo("tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo("parent_asset_id")
    val parentAssetId: String? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long = createAt,
    @ColumnInfo("hidden_at")
    val hiddenAt: Long? = null,
    @ColumnInfo("metadata_version")
    val metadataVersion: Int = METADATA_VERSION,
) {
    companion object {
        const val METADATA_VERSION = 2

        const val MEDIA_KIND_IMAGE = "image"

        const val LIFECYCLE_RESERVED = "reserved"
        const val LIFECYCLE_ACTIVE = "active"
        const val LIFECYCLE_DELETE_PENDING = "delete_pending"
        const val LIFECYCLE_DELETED = "deleted"

        const val PRIVACY_PRIVATE = "private"
        const val PRIVACY_SYNC_ALLOWED = "sync_allowed"
        const val PRIVACY_SHARE_ALLOWED = "share_allowed"

        const val RETENTION_CONVERSATION = "conversation"
        const val RETENTION_LIBRARY = "library"
        const val RETENTION_TEMPORARY = "temporary"

        const val TYPE_IMAGE_GENERATION = "image_generation"
        const val TYPE_IMAGE_EDIT = "image_edit"

        const val ORIGIN_AI_GENERATED = "ai_generated"
        const val ORIGIN_AI_EDITED = "ai_edited"

        const val STORAGE_AVAILABLE = "available"
        const val STORAGE_NEEDS_METADATA = "needs_metadata"
        const val STORAGE_MISSING = "missing"
        const val STORAGE_CORRUPT = "corrupt"

        const val VISIBILITY_VISIBLE = "visible"
        const val VISIBILITY_HIDDEN = "hidden"
    }
}

/** Source-compatible name for the pre-v26 generated-media model. */
typealias GenMediaEntity = MediaAssetEntity

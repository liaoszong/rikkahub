package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canonical, reusable identity for a cited web source. */
@Entity(
    tableName = "citation_source",
    indices = [
        Index(value = ["canonical_url"], unique = true),
        Index(value = ["retrieved_at"]),
        Index(value = ["publisher"]),
        Index(value = ["content_hash"]),
    ],
)
data class CitationSourceEntity(
    @PrimaryKey
    @ColumnInfo("source_id")
    val sourceId: String,
    @ColumnInfo("canonical_url")
    val canonicalUrl: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("publisher")
    val publisher: String? = null,
    @ColumnInfo("retrieved_at")
    val retrievedAt: Long? = null,
    @ColumnInfo("snippet")
    val snippet: String? = null,
    @ColumnInfo("content_hash")
    val contentHash: String? = null,
    @ColumnInfo("metadata_json", defaultValue = "{}")
    val metadataJson: String = "{}",
    @ColumnInfo("record_digest")
    val recordDigest: String,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
)

/** One ordered citation occurrence owned by a stable conversation message. */
@Entity(
    tableName = "message_citation",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationMessageEntity::class,
            parentColumns = ["conversation_id", "message_id"],
            childColumns = ["conversation_id", "message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CitationSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "message_id", "ordinal"], unique = true),
        Index(value = ["conversation_id", "message_id"]),
        Index(value = ["source_id"]),
        Index(value = ["conversation_id", "source_id"]),
    ],
)
data class MessageCitationEntity(
    @PrimaryKey
    @ColumnInfo("citation_id")
    val citationId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("source_id")
    val sourceId: String,
    @ColumnInfo("ordinal")
    val ordinal: Int,
    /** Occurrence-owned presentation fields; never rewritten by another conversation sharing the URL. */
    @ColumnInfo("display_title", defaultValue = "''")
    val displayTitle: String = "",
    @ColumnInfo("display_publisher")
    val displayPublisher: String? = null,
    @ColumnInfo("display_retrieved_at")
    val displayRetrievedAt: Long? = null,
    @ColumnInfo("is_available", defaultValue = "1")
    val isAvailable: Boolean = true,
    @ColumnInfo("text_start")
    val textStart: Int? = null,
    @ColumnInfo("text_end")
    val textEnd: Int? = null,
    @ColumnInfo("text_part_ordinal")
    val textPartOrdinal: Int? = null,
    @ColumnInfo("offset_unit", defaultValue = "unknown")
    val offsetUnit: String = "unknown",
    @ColumnInfo("quote")
    val quote: String? = null,
    @ColumnInfo("provenance")
    val provenance: String,
    @ColumnInfo("provider_metadata_json", defaultValue = "{}")
    val providerMetadataJson: String = "{}",
    @ColumnInfo("record_digest")
    val recordDigest: String,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
)

/** Restart-safe authority switch from legacy annotations_json to normalized citation rows. */
@Entity(
    tableName = "citation_migration_journal",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["phase", "lease_until"])],
)
data class CitationMigrationJournalEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("phase", defaultValue = "PENDING")
    val phase: String = CitationValues.MIGRATION_PENDING,
    @ColumnInfo("source_revision")
    val sourceRevision: Long,
    @ColumnInfo("projection_digest")
    val projectionDigest: String? = null,
    @ColumnInfo("citation_count", defaultValue = "0")
    val citationCount: Int = 0,
    @ColumnInfo("attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

object CitationValues {
    const val MIGRATION_PENDING = "PENDING"
    const val MIGRATION_PROCESSING = "PROCESSING"
    const val MIGRATION_PROJECTED = "PROJECTED"
    const val MIGRATION_READY = "READY"
    const val MIGRATION_QUARANTINED = "QUARANTINED"

    const val PROVENANCE_PROVIDER = "provider"
    const val PROVENANCE_SEARCH_TOOL = "search_tool"
    const val PROVENANCE_IMPORT = "import"
    const val PROVENANCE_LEGACY_MARKDOWN = "legacy_markdown"

    const val OFFSET_PROVIDER_CHARACTER = "provider_character"
    const val OFFSET_MESSAGE_FLATTENED_UTF16 = "message_flattened_utf16"
    const val OFFSET_UNKNOWN = "unknown"
}

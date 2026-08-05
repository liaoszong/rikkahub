package me.rerere.rikkahub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.CitationMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.CitationSourceEntity
import me.rerere.rikkahub.data.db.entity.MessageCitationEntity

@Dao
interface CitationDAO {
    @Upsert
    suspend fun upsertSources(sources: List<CitationSourceEntity>)

    @Upsert
    suspend fun upsertCitations(citations: List<MessageCitationEntity>)

    @Query("SELECT * FROM citation_source WHERE source_id IN (:sourceIds)")
    suspend fun getSources(sourceIds: List<String>): List<CitationSourceEntity>

    @Query("SELECT * FROM message_citation WHERE conversation_id = :conversationId")
    suspend fun getCitations(conversationId: String): List<MessageCitationEntity>

    @Query(
        "SELECT message_id, COUNT(*) AS citation_count FROM message_citation " +
            "WHERE conversation_id = :conversationId AND deleted_at IS NULL " +
            "GROUP BY message_id ORDER BY message_id",
    )
    suspend fun getCitationCountsByMessage(conversationId: String): List<MessageCitationCount>

    @Query(
        "SELECT * FROM message_citation WHERE conversation_id = :conversationId " +
            "AND message_id IN (:messageIds) AND deleted_at IS NULL " +
            "ORDER BY message_id, ordinal, citation_id",
    )
    suspend fun getCitationsForMessages(
        conversationId: String,
        messageIds: List<String>,
    ): List<MessageCitationEntity>

    @Query(
        "SELECT citation.citation_id, citation.conversation_id, citation.message_id, " +
            "citation.source_id, citation.ordinal, citation.text_start, citation.text_end, " +
            "citation.text_part_ordinal, citation.offset_unit, " +
            "citation.display_title, citation.display_publisher, citation.display_retrieved_at, " +
            "citation.is_available, " +
            "citation.quote, citation.provenance, citation.provider_metadata_json, " +
            "citation.record_digest AS citation_record_digest, citation.revision AS citation_revision, " +
            "source.canonical_url, source.title, source.publisher, source.retrieved_at, " +
            "source.snippet, source.content_hash, source.metadata_json, " +
            "source.record_digest AS source_record_digest, source.revision AS source_revision, " +
            "source.deleted_at AS source_deleted_at " +
            "FROM message_citation AS citation INNER JOIN citation_source AS source " +
            "ON source.source_id = citation.source_id " +
            "WHERE citation.conversation_id = :conversationId " +
            "AND citation.deleted_at IS NULL " +
            "ORDER BY citation.message_id, citation.ordinal, citation.citation_id",
    )
    suspend fun getResolvedCitations(conversationId: String): List<ResolvedCitationRow>

    @Query("DELETE FROM message_citation WHERE conversation_id = :conversationId AND citation_id IN (:citationIds)")
    suspend fun deleteCitations(conversationId: String, citationIds: List<String>): Int

    @Query("DELETE FROM message_citation WHERE conversation_id = :conversationId")
    suspend fun deleteConversationCitations(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournal(journal: CitationMigrationJournalEntity)

    @Query("SELECT * FROM citation_migration_journal WHERE conversation_id = :conversationId")
    suspend fun getJournal(conversationId: String): CitationMigrationJournalEntity?

    @Query(
        "INSERT OR IGNORE INTO citation_migration_journal (" +
            "conversation_id, phase, source_revision, citation_count, attempts, updated_at" +
            ") SELECT id, 'PENDING', revision, 0, 0, :now FROM ConversationEntity " +
            "WHERE storage_version = 2 AND deleted_at IS NULL",
    )
    suspend fun seedMissingJournals(now: Long)

    @Query(
        "UPDATE citation_migration_journal SET phase = 'PENDING', projection_digest = NULL, " +
            "citation_count = 0, attempts = 0, lease_owner = NULL, lease_until = NULL, last_error = NULL, " +
            "updated_at = :now WHERE phase IN ('PROJECTED', 'READY') AND EXISTS (" +
            "SELECT 1 FROM ConversationEntity AS conversation " +
            "WHERE conversation.id = citation_migration_journal.conversation_id " +
            "AND conversation.storage_version = 2 AND conversation.deleted_at IS NULL " +
            "AND conversation.revision != citation_migration_journal.source_revision)",
    )
    suspend fun invalidateStaleReadyJournals(now: Long): Int

    @Query(
        "SELECT conversation_id FROM citation_migration_journal " +
        "WHERE phase IN ('PENDING', 'PROCESSING') " +
        "AND (lease_until IS NULL OR lease_until <= :now) " +
            "AND (attempts = 0 OR updated_at <= :retryBefore) " +
        "ORDER BY updated_at, conversation_id LIMIT :limit",
    )
    suspend fun getLeaseCandidates(now: Long, retryBefore: Long, limit: Int): List<String>

    @Query(
        "SELECT conversation_id FROM citation_migration_journal " +
            "WHERE phase = 'PROJECTED' " +
            "AND (lease_until IS NULL OR lease_until <= :now) " +
            "AND (attempts = 0 OR updated_at <= :retryBefore) " +
            "ORDER BY updated_at, conversation_id LIMIT :limit",
    )
    suspend fun getProjectedLeaseCandidates(now: Long, retryBefore: Long, limit: Int): List<String>

    /**
     * Earliest wall-clock instant at which any unfinished journal can be claimed.
     *
     * A crashed process may leave a live lease while a transient failure is gated by the retry
     * backoff. Taking the later boundary prevents an early duplicate claim in both cases.
     */
    @Query(
        "SELECT MIN(MAX(" +
            "COALESCE(lease_until, 0), " +
            "CASE WHEN attempts = 0 THEN 0 ELSE updated_at + :retryBackoffMillis END" +
            ")) FROM citation_migration_journal " +
            "WHERE phase IN ('PENDING', 'PROCESSING', 'PROJECTED')",
    )
    suspend fun getNextEligibleAt(retryBackoffMillis: Long): Long?

    @Query(
        "UPDATE citation_migration_journal SET phase = 'PROCESSING', " +
            "lease_owner = :owner, lease_until = :leaseUntil, attempts = attempts + 1, " +
            "last_error = NULL, updated_at = :now WHERE conversation_id = :conversationId " +
            "AND phase IN ('PENDING', 'PROCESSING') " +
            "AND (lease_until IS NULL OR lease_until <= :now) " +
            "AND (attempts = 0 OR updated_at <= :retryBefore)",
    )
    suspend fun claim(
        conversationId: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
        retryBefore: Long,
    ): Int

    @Query(
        "UPDATE citation_migration_journal SET lease_owner = :owner, lease_until = :leaseUntil, " +
            "attempts = attempts + 1, last_error = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'PROJECTED' " +
            "AND (lease_until IS NULL OR lease_until <= :now) " +
            "AND (attempts = 0 OR updated_at <= :retryBefore)",
    )
    suspend fun claimProjected(
        conversationId: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
        retryBefore: Long,
    ): Int

    @Query(
        "UPDATE citation_migration_journal SET phase = 'PROJECTED', source_revision = :sourceRevision, " +
            "projection_digest = :digest, citation_count = :citationCount, " +
            "lease_until = :leaseUntil, last_error = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'PROCESSING' AND lease_owner = :owner",
    )
    suspend fun markProjected(
        conversationId: String,
        owner: String,
        sourceRevision: Long,
        digest: String,
        citationCount: Int,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE citation_migration_journal SET phase = 'QUARANTINED', last_error = :error, " +
            "lease_owner = NULL, lease_until = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND lease_owner = :owner",
    )
    suspend fun quarantine(conversationId: String, owner: String, error: String, now: Long): Int

    @Query(
        "UPDATE citation_migration_journal SET phase = 'PENDING', last_error = :error, " +
            "lease_owner = NULL, lease_until = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'PROCESSING' AND lease_owner = :owner",
    )
    suspend fun releaseForRetry(conversationId: String, owner: String, error: String, now: Long): Int

    @Query(
        "UPDATE citation_migration_journal SET last_error = :error, lease_owner = NULL, " +
            "lease_until = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'PROJECTED' AND lease_owner = :owner",
    )
    suspend fun releaseProjectedForRetry(
        conversationId: String,
        owner: String,
        error: String,
        now: Long,
    ): Int
}

data class MessageCitationCount(
    @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("citation_count") val citationCount: Int,
)

data class ResolvedCitationRow(
    @ColumnInfo("citation_id") val citationId: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("source_id") val sourceId: String,
    val ordinal: Int,
    @ColumnInfo("display_title") val displayTitle: String,
    @ColumnInfo("display_publisher") val displayPublisher: String?,
    @ColumnInfo("display_retrieved_at") val displayRetrievedAt: Long?,
    @ColumnInfo("is_available") val isAvailable: Boolean,
    @ColumnInfo("text_start") val textStart: Int?,
    @ColumnInfo("text_end") val textEnd: Int?,
    @ColumnInfo("text_part_ordinal") val textPartOrdinal: Int?,
    @ColumnInfo("offset_unit") val offsetUnit: String,
    val quote: String?,
    val provenance: String,
    @ColumnInfo("provider_metadata_json") val providerMetadataJson: String,
    @ColumnInfo("citation_record_digest") val citationRecordDigest: String,
    @ColumnInfo("citation_revision") val citationRevision: Long,
    @ColumnInfo("canonical_url") val canonicalUrl: String,
    val title: String,
    val publisher: String?,
    @ColumnInfo("retrieved_at") val retrievedAt: Long?,
    val snippet: String?,
    @ColumnInfo("content_hash") val contentHash: String?,
    @ColumnInfo("metadata_json") val metadataJson: String,
    @ColumnInfo("source_record_digest") val sourceRecordDigest: String,
    @ColumnInfo("source_revision") val sourceRevision: Long,
    @ColumnInfo("source_deleted_at") val sourceDeletedAt: Long?,
)

package me.rerere.rikkahub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.data.db.entity.MessageMediaRefEntity
import me.rerere.rikkahub.data.db.media.EXACT_V2_OWNER_PREFIX
import me.rerere.rikkahub.data.db.media.conversationMediaReferenceDigest
import me.rerere.rikkahub.data.db.media.hasSameReferenceAuthority

/**
 * Room primitives for ConversationStore-v2 exact media ownership.
 *
 * The interface is inherited by [GenMediaDAO] so exact-reference writes share the same Room
 * transaction as media GC. Conversation READY validation deliberately lives in
 * ConversationMediaReferenceIndexer, where it can reuse ConversationV2ShadowProjector.
 */
interface ConversationMediaReferenceDAO {
    @Query("SELECT asset_id FROM GenMediaEntity WHERE asset_id = :assetId LIMIT 1")
    suspend fun findMediaAssetId(assetId: String): String?

    @Query("SELECT asset_id FROM GenMediaEntity WHERE path = :relativePath LIMIT 1")
    suspend fun findMediaAssetIdByPath(relativePath: String): String?

    @Query(
        "SELECT journal.conversation_id FROM conversation_migration_journal AS journal " +
            "INNER JOIN ConversationEntity AS conversation ON conversation.id = journal.conversation_id " +
            "WHERE journal.phase = 'READY' AND conversation.storage_version = 2 " +
            "AND conversation.deleted_at IS NULL " +
            "AND (:afterConversationId IS NULL OR journal.conversation_id > :afterConversationId) " +
            "ORDER BY journal.conversation_id LIMIT :limit",
    )
    suspend fun getReadyConversationIdsForMediaPage(
        afterConversationId: String?,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT message.conversation_id AS conversation_id, " +
            "message.branch_group_id AS branch_group_id, message.message_id AS message_id, " +
            "message.revision AS message_revision, message.deleted_at AS message_deleted_at, " +
            "part.part_id AS part_id, part.ordinal AS ordinal, part.kind AS kind, " +
            "part.payload_json AS payload_json, part.payload_digest AS payload_digest, " +
            "part.asset_id AS part_asset_id, part.tool_invocation_id AS tool_invocation_id, " +
            "part.revision AS part_revision, part.deleted_at AS part_deleted_at " +
            "FROM conversation_message AS message " +
            "INNER JOIN message_part AS part ON part.conversation_id = message.conversation_id " +
            "AND part.message_id = message.message_id " +
            "WHERE message.conversation_id = :conversationId " +
            "ORDER BY message.branch_group_id, message.sibling_ordinal, message.message_id, " +
            "part.ordinal, part.part_id",
    )
    suspend fun getConversationMediaSourceRows(conversationId: String): List<ConversationMediaSourceRow>

    @Query(
        "SELECT COUNT(*) FROM ConversationEntity AS conversation " +
            "LEFT JOIN conversation_migration_journal AS journal " +
            "ON journal.conversation_id = conversation.id " +
            "WHERE conversation.deleted_at IS NULL AND (journal.conversation_id IS NULL " +
            "OR journal.phase != 'READY' OR conversation.storage_version != 2)",
    )
    suspend fun countConversationsBlockingMediaReferenceCompletion(): Int

    @Query(
        "SELECT COUNT(*) FROM conversation_migration_quarantine AS quarantine " +
            "INNER JOIN ConversationEntity AS conversation " +
            "ON conversation.id = quarantine.conversation_id " +
            "WHERE conversation.deleted_at IS NULL",
    )
    suspend fun countQuarantinedConversationMediaSources(): Int

    @Query(
        "SELECT COUNT(*) FROM GenMediaEntity AS media " +
            "LEFT JOIN media_migration_journal AS journal " +
            "ON journal.scope_kind = 'asset' AND journal.scope_key = media.asset_id " +
            "AND journal.stage = 'reference_backfill' " +
            "WHERE journal.journal_id IS NULL",
    )
    suspend fun countAssetsMissingReferenceBackfillJournal(): Int

    @Query(
        "SELECT COUNT(*) FROM GenMediaEntity AS media " +
            "LEFT JOIN media_migration_journal AS journal " +
            "ON journal.scope_kind = 'asset' AND journal.scope_key = media.asset_id " +
            "AND journal.stage = 'reference_backfill' " +
            "WHERE journal.journal_id IS NULL OR journal.state != 'complete'",
    )
    suspend fun countAssetsRequiringReferenceBackfill(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExactMessageMediaReferenceIgnore(reference: MessageMediaRefEntity): Long

    @Query("SELECT * FROM message_media_ref WHERE ref_id = :refId LIMIT 1")
    suspend fun getMessageMediaReferenceById(refId: String): MessageMediaRefEntity?

    @Query(
        "SELECT * FROM message_media_ref WHERE conversation_id = :conversationId " +
            "AND owner_key GLOB 'rikkahub-media-ref-v2|*' ORDER BY ref_id",
    )
    suspend fun getExactV2References(conversationId: String): List<MessageMediaRefEntity>

    @Query(
        "DELETE FROM message_media_ref WHERE conversation_id = :conversationId " +
            "AND owner_key GLOB 'rikkahub-media-ref-v2|*'",
    )
    suspend fun deleteAllExactV2References(conversationId: String): Int

    @Query(
        "DELETE FROM message_media_ref WHERE conversation_id = :conversationId " +
            "AND owner_key GLOB 'legacy-v1|*'",
    )
    suspend fun deleteLegacyV1References(conversationId: String): Int

    @Query("DELETE FROM message_media_ref WHERE owner_key GLOB 'legacy-v1|*'")
    suspend fun deleteAllLegacyV1References(): Int

    @Query(
        "DELETE FROM message_media_ref WHERE owner_key GLOB 'rikkahub-media-ref-v2|*' " +
            "AND ref_id IN (:refIds)",
    )
    suspend fun deleteExactV2ReferencesByIds(refIds: List<String>): Int

    @Query(
        "SELECT reference.ref_id FROM message_media_ref AS reference " +
            "LEFT JOIN ConversationEntity AS conversation " +
            "ON conversation.id = reference.conversation_id AND conversation.deleted_at IS NULL " +
            "WHERE reference.owner_key GLOB 'rikkahub-media-ref-v2|*' " +
            "AND (reference.conversation_id IS NULL OR conversation.id IS NULL) " +
            "ORDER BY reference.ref_id LIMIT :limit",
    )
    suspend fun getOrphanExactV2ReferenceIds(limit: Int): List<String>

    @Query(
        "SELECT COUNT(*) FROM message_media_ref AS reference " +
            "LEFT JOIN ConversationEntity AS conversation " +
            "ON conversation.id = reference.conversation_id AND conversation.deleted_at IS NULL " +
            "WHERE reference.owner_key GLOB 'rikkahub-media-ref-v2|*' " +
            "AND (reference.conversation_id IS NULL OR conversation.id IS NULL)",
    )
    suspend fun countOrphanExactV2References(): Int

    @Query(
        "SELECT journal.journal_id, journal.scope_key, journal.state, journal.updated_at " +
            "FROM media_migration_journal AS journal " +
            "INNER JOIN GenMediaEntity AS media ON media.asset_id = journal.scope_key " +
            "WHERE journal.scope_kind = 'asset' AND journal.stage = 'reference_backfill' " +
            "ORDER BY journal.journal_id",
    )
    suspend fun getAssetReferenceJournalEpoch(): List<ConversationMediaJournalEpochRow>

    @Query(
        "SELECT MAX(journal.updated_at) FROM media_migration_journal AS journal " +
            "INNER JOIN GenMediaEntity AS media ON media.asset_id = journal.scope_key " +
            "WHERE journal.scope_kind = 'asset' AND journal.stage = 'reference_backfill'",
    )
    suspend fun getMaxAssetReferenceJournalUpdatedAt(): Long?

    @Query(
        "UPDATE media_migration_journal SET state = 'pending', detail = :detail, " +
            "updated_at = :epochUpdatedAt WHERE scope_kind = 'asset' " +
            "AND stage = 'reference_backfill' AND scope_key IN (SELECT asset_id FROM GenMediaEntity)",
    )
    suspend fun markAllAssetReferenceBackfillsPending(epochUpdatedAt: Long, detail: String): Int

    @Query(
        "UPDATE media_migration_journal SET state = 'complete', detail = NULL, " +
            "updated_at = :completedAt WHERE journal_id = :journalId AND scope_kind = 'asset' " +
            "AND stage = 'reference_backfill' AND state = :expectedState " +
            "AND updated_at = :expectedUpdatedAt",
    )
    suspend fun completeAssetReferenceBackfillCas(
        journalId: String,
        expectedState: String,
        expectedUpdatedAt: Long,
        completedAt: Long,
    ): Int

    @Transaction
    suspend fun beginConversationMediaReferenceEpoch(
        requestedNow: Long,
        detail: String,
    ): ConversationMediaJournalEpoch {
        val previousMax = getMaxAssetReferenceJournalUpdatedAt()
        val epochUpdatedAt = when {
            previousMax == null -> requestedNow
            requestedNow > previousMax -> requestedNow
            else -> Math.addExact(previousMax, 1L)
        }
        markAllAssetReferenceBackfillsPending(epochUpdatedAt, detail)
        return ConversationMediaJournalEpoch(epochUpdatedAt, getAssetReferenceJournalEpoch())
    }

    @Transaction
    suspend fun replaceConversationReferences(
        conversationId: String,
        desired: List<MessageMediaRefEntity>,
    ): ConversationMediaReferenceReplaceResult {
        require(desired.all { reference ->
            reference.conversationId == conversationId &&
                reference.ownerKey.startsWith(EXACT_V2_OWNER_PREFIX) &&
                !reference.messageNodeId.isNullOrBlank() &&
                !reference.messageId.isNullOrBlank() &&
                !reference.partId.isNullOrBlank()
        }) { "Exact media references must carry one concrete v2 owner" }
        require(desired.map(MessageMediaRefEntity::refId).distinct().size == desired.size) {
            "Exact media references must have unique deterministic ids"
        }

        var inserted = 0
        desired.forEach { reference ->
            if (insertExactMessageMediaReferenceIgnore(reference) != -1L) inserted++
            val committed = requireNotNull(getMessageMediaReferenceById(reference.refId)) {
                "Media reference identity collided without a committed row: ${reference.refId}"
            }
            require(committed.hasSameReferenceAuthority(reference)) {
                "Media reference identity conflicts with a different owner: ${reference.refId}"
            }
        }

        val desiredIds = desired.mapTo(hashSetOf(), MessageMediaRefEntity::refId)
        val staleIds = getExactV2References(conversationId)
            .asSequence()
            .map(MessageMediaRefEntity::refId)
            .filterNot(desiredIds::contains)
            .toList()
        var deleted = 0
        staleIds.chunked(SQLITE_SAFE_ID_BATCH_SIZE).forEach { chunk ->
            deleted += deleteExactV2ReferencesByIds(chunk)
        }

        val committed = getExactV2References(conversationId)
        require(
            committed.size == desired.size &&
                conversationMediaReferenceDigest(committed) == conversationMediaReferenceDigest(desired),
        ) { "Exact media reference verification failed for conversation $conversationId" }
        return ConversationMediaReferenceReplaceResult(inserted, deleted, committed.size)
    }

    /** Atomic deletion hook for ConversationStore hard/soft deletion paths. */
    @Transaction
    suspend fun deleteConversationOwnedReferences(
        conversationId: String,
    ): Int {
        return deleteAllExactV2References(conversationId) + deleteLegacyV1References(conversationId)
    }

    /** Removes exact refs whose owning conversation no longer exists (or is logically deleted). */
    @Transaction
    suspend fun clearOrphanExactV2References(): Int {
        var deleted = 0
        while (true) {
            val ids = getOrphanExactV2ReferenceIds(SQLITE_SAFE_ID_BATCH_SIZE)
            if (ids.isEmpty()) return deleted
            deleted += deleteExactV2ReferencesByIds(ids)
        }
    }

    /**
     * Completes only the journal rows captured by [beginConversationMediaReferenceEpoch].
     * A new, removed, or version-changed journal makes this a no-op.
     */
    @Transaction
    suspend fun completeConversationMediaReferenceEpoch(
        epoch: ConversationMediaJournalEpoch,
        completedAt: Long,
    ): Boolean {
        if (getAssetReferenceJournalEpoch() != epoch.rows) return false
        epoch.rows.forEach { row ->
            check(
                completeAssetReferenceBackfillCas(
                    journalId = row.journalId,
                    expectedState = row.state,
                    expectedUpdatedAt = row.updatedAt,
                    completedAt = completedAt,
                ) == 1,
            ) { "Reference journal epoch changed while completing ${row.journalId}" }
        }
        return true
    }
}

data class ConversationMediaSourceRow(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("branch_group_id") val branchGroupId: String,
    @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("message_revision") val messageRevision: Long,
    @ColumnInfo("message_deleted_at") val messageDeletedAt: Long?,
    @ColumnInfo("part_id") val partId: String,
    @ColumnInfo("ordinal") val ordinal: Int,
    @ColumnInfo("kind") val kind: String,
    @ColumnInfo("payload_json") val payloadJson: String,
    @ColumnInfo("payload_digest") val payloadDigest: String,
    @ColumnInfo("part_asset_id") val partAssetId: String?,
    @ColumnInfo("tool_invocation_id") val toolInvocationId: String?,
    @ColumnInfo("part_revision") val partRevision: Long,
    @ColumnInfo("part_deleted_at") val partDeletedAt: Long?,
)

data class ConversationMediaJournalEpochRow(
    @ColumnInfo("journal_id") val journalId: String,
    @ColumnInfo("scope_key") val scopeKey: String,
    val state: String,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

data class ConversationMediaJournalEpoch(
    val updatedAt: Long,
    val rows: List<ConversationMediaJournalEpochRow>,
)

data class ConversationMediaReferenceReplaceResult(
    val inserted: Int,
    val deleted: Int,
    val committed: Int,
)

private const val SQLITE_SAFE_ID_BATCH_SIZE = 400

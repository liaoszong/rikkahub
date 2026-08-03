package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationQuarantineEntity
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageFtsOutboxEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity

@Dao
interface ConversationGraphDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBranchGroup(group: MessageBranchGroupEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<ConversationMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParts(parts: List<MessagePartEntity>)

    @Upsert
    suspend fun upsertBranchGroups(groups: List<MessageBranchGroupEntity>)

    @Upsert
    suspend fun upsertMessages(messages: List<ConversationMessageEntity>)

    @Upsert
    suspend fun upsertParts(parts: List<MessagePartEntity>)

    @Query(
        "SELECT * FROM conversation_message " +
            "WHERE conversation_id = :conversationId AND message_id = :messageId",
    )
    suspend fun getMessage(conversationId: String, messageId: String): ConversationMessageEntity?

    @Query(
        "SELECT * FROM conversation_message " +
            "WHERE conversation_id = :conversationId AND parent_message_id IS :parentMessageId " +
            "AND deleted_at IS NULL ORDER BY branch_group_id, sibling_ordinal",
    )
    suspend fun getChildren(
        conversationId: String,
        parentMessageId: String?,
    ): List<ConversationMessageEntity>

    @Query(
        "SELECT * FROM message_part " +
            "WHERE conversation_id = :conversationId AND message_id = :messageId " +
            "AND deleted_at IS NULL ORDER BY ordinal",
    )
    suspend fun getParts(conversationId: String, messageId: String): List<MessagePartEntity>

    @Query(
        "SELECT * FROM message_branch_group WHERE conversation_id = :conversationId " +
            "ORDER BY legacy_order, branch_group_id",
    )
    suspend fun getBranchGroups(conversationId: String): List<MessageBranchGroupEntity>

    @Query(
        "SELECT message.* FROM conversation_message AS message " +
            "INNER JOIN message_branch_group AS branch " +
            "ON branch.conversation_id = message.conversation_id " +
            "AND branch.branch_group_id = message.branch_group_id " +
            "WHERE message.conversation_id = :conversationId " +
            "ORDER BY branch.legacy_order, message.sibling_ordinal, message.message_id",
    )
    suspend fun getMessages(conversationId: String): List<ConversationMessageEntity>

    @Query(
        "SELECT part.* FROM message_part AS part " +
            "INNER JOIN conversation_message AS message " +
            "ON message.conversation_id = part.conversation_id " +
            "AND message.message_id = part.message_id " +
            "INNER JOIN message_branch_group AS branch " +
            "ON branch.conversation_id = message.conversation_id " +
            "AND branch.branch_group_id = message.branch_group_id " +
            "WHERE part.conversation_id = :conversationId " +
            "ORDER BY branch.legacy_order, message.sibling_ordinal, part.ordinal, part.part_id",
    )
    suspend fun getAllParts(conversationId: String): List<MessagePartEntity>

    @Query(
        "SELECT message_id, legacy_message_id FROM conversation_message " +
            "WHERE conversation_id = :conversationId",
    )
    suspend fun getMessageIdentities(conversationId: String): List<ConversationMessageIdentity>

    @Query("SELECT COUNT(*) FROM message_branch_group WHERE conversation_id = :conversationId")
    suspend fun countBranchGroups(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = :conversationId")
    suspend fun countMessages(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM message_part WHERE conversation_id = :conversationId")
    suspend fun countParts(conversationId: String): Int

    @Query("DELETE FROM conversation_message WHERE conversation_id = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Query("DELETE FROM message_branch_group WHERE conversation_id = :conversationId")
    suspend fun deleteBranchGroups(conversationId: String)

    @Query(
        "DELETE FROM message_part WHERE conversation_id = :conversationId " +
            "AND part_id IN (:partIds)",
    )
    suspend fun deletePartsById(conversationId: String, partIds: List<String>)

    @Query(
        "UPDATE conversation_message SET parent_message_id = NULL " +
            "WHERE conversation_id = :conversationId AND parent_message_id IN (:parentMessageIds)",
    )
    suspend fun clearParentReferences(conversationId: String, parentMessageIds: List<String>)

    @Query(
        "DELETE FROM conversation_message WHERE conversation_id = :conversationId " +
            "AND message_id IN (:messageIds)",
    )
    suspend fun deleteMessagesById(conversationId: String, messageIds: List<String>)

    @Query(
        "DELETE FROM message_branch_group WHERE conversation_id = :conversationId " +
            "AND branch_group_id IN (:branchGroupIds)",
    )
    suspend fun deleteBranchGroupsById(conversationId: String, branchGroupIds: List<String>)

    @Query(
        "UPDATE ConversationEntity SET revision = revision + 1, update_at = :updateAt, " +
            "last_writer_replica_id = :writerReplicaId " +
            "WHERE id = :conversationId AND revision = :expectedRevision AND deleted_at IS NULL",
    )
    suspend fun reserveConversationRevision(
        conversationId: String,
        expectedRevision: Long,
        updateAt: Long,
        writerReplicaId: String?,
    ): Int
}

@Dao
interface ConversationMigrationDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournal(journal: ConversationMigrationJournalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournal(journal: ConversationMigrationJournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuarantine(record: ConversationMigrationQuarantineEntity)

    @Query("SELECT * FROM conversation_migration_journal WHERE conversation_id = :conversationId")
    suspend fun getJournal(conversationId: String): ConversationMigrationJournalEntity?

    @Query(
        "SELECT * FROM conversation_migration_journal " +
            "WHERE phase != 'READY' ORDER BY updated_at, conversation_id LIMIT :limit",
    )
    suspend fun getPendingJournals(limit: Int): List<ConversationMigrationJournalEntity>

    @Query(
        "INSERT OR IGNORE INTO conversation_migration_journal (" +
            "conversation_id, phase, source_revision, next_node_index, " +
            "written_group_count, written_message_count, written_part_count, " +
            "inference_flags_json, attempts, updated_at" +
            ") SELECT id, 'PENDING', revision, 0, 0, 0, 0, '[]', 0, :now " +
            "FROM ConversationEntity WHERE storage_version = 1 AND deleted_at IS NULL",
    )
    suspend fun seedMissingJournals(now: Long)

    @Query(
        "SELECT conversation_id FROM conversation_migration_journal " +
            "WHERE phase IN ('PENDING', 'COPYING', 'VERIFYING') " +
            "AND (lease_until IS NULL OR lease_until <= :now) " +
            "ORDER BY updated_at, conversation_id LIMIT :limit",
    )
    suspend fun getLeaseCandidates(now: Long, limit: Int): List<String>

    @Query(
        "UPDATE conversation_migration_journal SET " +
            "phase = CASE WHEN phase = 'PENDING' THEN 'COPYING' ELSE phase END, " +
            "lease_owner = :workerId, lease_until = :leaseUntil, " +
            "attempts = attempts + 1, updated_at = :now " +
            "WHERE conversation_id = :conversationId " +
            "AND phase IN ('PENDING', 'COPYING', 'VERIFYING') " +
            "AND (lease_until IS NULL OR lease_until <= :now)",
    )
    suspend fun claimLease(
        conversationId: String,
        workerId: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "SELECT id, revision, storage_version, active_leaf_message_id, deleted_at " +
            "FROM ConversationEntity WHERE id = :conversationId",
    )
    suspend fun getConversationState(conversationId: String): ConversationV2State?

    @Query(
        "SELECT id, node_index, select_index, length(messages) AS message_length " +
            "FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index, id",
    )
    suspend fun getLegacyNodeHeaders(conversationId: String): List<LegacyMessageNodeHeader>

    @Query("SELECT substr(messages, :start, :length) FROM message_node WHERE id = :nodeId")
    suspend fun getLegacyMessagesChunk(nodeId: String, start: Long, length: Int): String?

    @Query(
        "UPDATE conversation_migration_journal SET phase = 'COPYING', " +
            "source_revision = :sourceRevision, legacy_source_digest = :sourceDigest, " +
            "legacy_projection_digest = NULL, v2_projection_digest = NULL, " +
            "next_node_index = 0, previous_selected_message_id = NULL, " +
            "expected_group_count = :groupCount, expected_message_count = NULL, " +
            "expected_part_count = NULL, written_group_count = 0, " +
            "written_message_count = 0, written_part_count = 0, " +
            "inference_flags_json = '[]', last_error_code = NULL, last_error_detail = NULL, " +
            "lease_until = :leaseUntil, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND lease_owner = :workerId",
    )
    suspend fun resetForSource(
        conversationId: String,
        workerId: String,
        sourceRevision: Long,
        sourceDigest: String,
        groupCount: Int?,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET " +
            "next_node_index = :nextNodeIndex, " +
            "previous_selected_message_id = :previousSelectedMessageId, " +
            "written_group_count = :writtenGroupCount, " +
            "written_message_count = :writtenMessageCount, " +
            "written_part_count = :writtenPartCount, " +
            "inference_flags_json = :inferenceFlagsJson, " +
            "lease_until = :leaseUntil, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'COPYING' " +
            "AND lease_owner = :workerId",
    )
    suspend fun checkpointNode(
        conversationId: String,
        workerId: String,
        nextNodeIndex: Int,
        previousSelectedMessageId: String?,
        writtenGroupCount: Int,
        writtenMessageCount: Int,
        writtenPartCount: Int,
        inferenceFlagsJson: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET phase = 'VERIFYING', " +
            "expected_group_count = :groupCount, expected_message_count = :messageCount, " +
            "expected_part_count = :partCount, legacy_projection_digest = :legacyDigest, " +
            "lease_until = :leaseUntil, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'COPYING' " +
            "AND lease_owner = :workerId",
    )
    suspend fun markVerifying(
        conversationId: String,
        workerId: String,
        groupCount: Int,
        messageCount: Int,
        partCount: Int,
        legacyDigest: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE ConversationEntity SET storage_version = 2, " +
            "active_leaf_message_id = :activeLeafMessageId " +
            "WHERE id = :conversationId AND storage_version = 1 " +
            "AND revision = :sourceRevision AND deleted_at IS NULL",
    )
    suspend fun markConversationReady(
        conversationId: String,
        sourceRevision: Long,
        activeLeafMessageId: String?,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET phase = 'READY', " +
            "v2_projection_digest = :v2Digest, lease_owner = NULL, lease_until = NULL, " +
            "last_error_code = NULL, last_error_detail = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND phase = 'VERIFYING' " +
            "AND lease_owner = :workerId",
    )
    suspend fun markReady(
        conversationId: String,
        workerId: String,
        v2Digest: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET source_revision = :targetRevision, " +
            "updated_at = :now WHERE conversation_id = :conversationId AND phase = 'READY' " +
            "AND source_revision = :expectedRevision",
    )
    suspend fun advanceReadyRevision(
        conversationId: String,
        expectedRevision: Long,
        targetRevision: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET phase = 'QUARANTINED', " +
            "last_error_code = :reasonCode, last_error_detail = :detail, " +
            "lease_owner = NULL, lease_until = NULL, updated_at = :now " +
            "WHERE conversation_id = :conversationId AND lease_owner = :workerId",
    )
    suspend fun markQuarantined(
        conversationId: String,
        workerId: String,
        reasonCode: String,
        detail: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET last_error_code = :errorCode, " +
            "last_error_detail = :detail, lease_owner = NULL, lease_until = :retryAt, " +
            "updated_at = :now WHERE conversation_id = :conversationId " +
            "AND lease_owner = :workerId",
    )
    suspend fun recordTransientFailure(
        conversationId: String,
        workerId: String,
        errorCode: String,
        detail: String?,
        now: Long,
        retryAt: Long,
    ): Int

    @Query(
        "UPDATE conversation_migration_journal SET lease_owner = NULL, lease_until = NULL, " +
            "updated_at = :now WHERE conversation_id = :conversationId " +
            "AND lease_owner = :workerId",
    )
    suspend fun releaseLease(conversationId: String, workerId: String, now: Long): Int

    @Query("DELETE FROM conversation_migration_quarantine WHERE conversation_id = :conversationId")
    suspend fun deleteQuarantine(conversationId: String)
}

data class ConversationMessageIdentity(
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("legacy_message_id")
    val legacyMessageId: String?,
)

data class ConversationV2State(
    val id: String,
    val revision: Long,
    @ColumnInfo("storage_version")
    val storageVersion: Int,
    @ColumnInfo("active_leaf_message_id")
    val activeLeafMessageId: String?,
    @ColumnInfo("deleted_at")
    val deletedAt: Long?,
)

data class LegacyMessageNodeHeader(
    val id: String,
    @ColumnInfo("node_index")
    val nodeIndex: Int,
    @ColumnInfo("select_index")
    val selectIndex: Int,
    @ColumnInfo("message_length")
    val messageLength: Long,
)

@Dao
interface MessageFtsOutboxDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(event: MessageFtsOutboxEntity): Long

    @Query("SELECT * FROM message_fts_outbox WHERE event_id = :eventId")
    suspend fun getEvent(eventId: String): MessageFtsOutboxEntity?

    @Query(
        "SELECT * FROM message_fts_outbox WHERE conversation_id = :conversationId " +
            "ORDER BY created_at DESC, event_id DESC LIMIT 1",
    )
    suspend fun getLatestEvent(conversationId: String): MessageFtsOutboxEntity?

    @Query("SELECT MAX(created_at) FROM message_fts_outbox WHERE conversation_id = :conversationId")
    suspend fun getMaxEventOrder(conversationId: String): Long?

    @Query(
        "SELECT * FROM message_fts_outbox " +
            "WHERE state = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY created_at DESC, event_id DESC LIMIT :limit",
    )
    suspend fun getReadyEvents(now: Long, limit: Int): List<MessageFtsOutboxEntity>

    @Query(
        "SELECT * FROM message_fts_outbox AS candidate " +
            "WHERE ((candidate.state = 'PENDING' AND candidate.next_attempt_at <= :now) " +
            "OR (candidate.state = 'PROCESSING' AND COALESCE(candidate.lease_until, 0) <= :now)) " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM message_fts_outbox AS newer " +
            "WHERE newer.conversation_id = candidate.conversation_id " +
            "AND (newer.created_at > candidate.created_at " +
            "OR (newer.created_at = candidate.created_at AND newer.event_id > candidate.event_id))" +
            ") AND NOT EXISTS (" +
            "SELECT 1 FROM message_fts_outbox AS active " +
            "WHERE active.conversation_id = candidate.conversation_id " +
            "AND active.event_id != candidate.event_id " +
            "AND active.state = 'PROCESSING' AND COALESCE(active.lease_until, 0) > :now" +
            ") ORDER BY candidate.created_at, candidate.event_id LIMIT :limit",
    )
    suspend fun getClaimCandidates(now: Long, limit: Int): List<MessageFtsOutboxEntity>

    @Query(
        "UPDATE message_fts_outbox SET state = 'PROCESSING', lease_owner = :owner, " +
            "lease_until = :leaseUntil, updated_at = :now " +
            "WHERE event_id = :eventId " +
            "AND ((state = 'PENDING' AND next_attempt_at <= :now) " +
            "OR (state = 'PROCESSING' AND COALESCE(lease_until, 0) <= :now)) " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM message_fts_outbox AS newer " +
            "WHERE newer.conversation_id = message_fts_outbox.conversation_id " +
            "AND (newer.created_at > message_fts_outbox.created_at " +
            "OR (newer.created_at = message_fts_outbox.created_at " +
            "AND newer.event_id > message_fts_outbox.event_id))" +
            ") AND NOT EXISTS (" +
            "SELECT 1 FROM message_fts_outbox AS active " +
            "WHERE active.conversation_id = message_fts_outbox.conversation_id " +
            "AND active.event_id != message_fts_outbox.event_id " +
            "AND active.state = 'PROCESSING' AND COALESCE(active.lease_until, 0) > :now" +
            ")",
    )
    suspend fun claim(
        eventId: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        "UPDATE message_fts_outbox SET state = 'DONE', lease_owner = NULL, lease_until = NULL, " +
            "last_error = NULL, updated_at = :now WHERE event_id = :eventId " +
            "AND state = 'PROCESSING' AND lease_owner = :owner",
    )
    suspend fun completeClaim(
        eventId: String,
        owner: String,
        now: Long,
    ): Int

    @Query(
        "DELETE FROM message_fts_outbox WHERE conversation_id = :conversationId " +
            "AND (created_at < :completedEventOrder " +
            "OR (created_at = :completedEventOrder AND event_id < :completedEventId))",
    )
    suspend fun deleteSuperseded(
        conversationId: String,
        completedEventOrder: Long,
        completedEventId: String,
    ): Int

    @Query(
        "UPDATE message_fts_outbox SET state = 'PENDING', attempts = attempts + 1, " +
            "next_attempt_at = :retryAt, lease_owner = NULL, lease_until = NULL, " +
            "last_error = :errorCode, updated_at = :now " +
            "WHERE event_id = :eventId AND state = 'PROCESSING' AND lease_owner = :owner",
    )
    suspend fun retry(
        eventId: String,
        owner: String,
        retryAt: Long,
        errorCode: String,
        now: Long,
    ): Int

    @Query(
        "SELECT MIN(CASE " +
            "WHEN EXISTS (SELECT 1 FROM message_fts_outbox AS active " +
            "WHERE active.conversation_id = candidate.conversation_id " +
            "AND active.event_id != candidate.event_id AND active.state = 'PROCESSING' " +
            "AND COALESCE(active.lease_until, 0) > :now) " +
            "THEN (SELECT MIN(active.lease_until) FROM message_fts_outbox AS active " +
            "WHERE active.conversation_id = candidate.conversation_id " +
            "AND active.event_id != candidate.event_id AND active.state = 'PROCESSING' " +
            "AND COALESCE(active.lease_until, 0) > :now) " +
            "WHEN candidate.state = 'PROCESSING' THEN COALESCE(candidate.lease_until, 0) " +
            "ELSE candidate.next_attempt_at END) FROM message_fts_outbox AS candidate " +
            "WHERE candidate.state != 'DONE' AND NOT EXISTS (" +
            "SELECT 1 FROM message_fts_outbox AS newer " +
            "WHERE newer.conversation_id = candidate.conversation_id " +
            "AND (newer.created_at > candidate.created_at " +
            "OR (newer.created_at = candidate.created_at AND newer.event_id > candidate.event_id))" +
            ")",
    )
    suspend fun getNextWakeAt(now: Long): Long?
}

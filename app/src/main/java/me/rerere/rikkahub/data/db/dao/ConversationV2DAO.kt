package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJournal(journal: ConversationMigrationJournalEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuarantine(record: ConversationMigrationQuarantineEntity)

    @Query("SELECT * FROM conversation_migration_journal WHERE conversation_id = :conversationId")
    suspend fun getJournal(conversationId: String): ConversationMigrationJournalEntity?

    @Query(
        "SELECT * FROM conversation_migration_journal " +
            "WHERE phase != 'READY' ORDER BY updated_at, conversation_id LIMIT :limit",
    )
    suspend fun getPendingJournals(limit: Int): List<ConversationMigrationJournalEntity>
}

@Dao
interface MessageFtsOutboxDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(event: MessageFtsOutboxEntity): Long

    @Query("SELECT * FROM message_fts_outbox WHERE event_id = :eventId")
    suspend fun getEvent(eventId: String): MessageFtsOutboxEntity?

    @Query(
        "SELECT * FROM message_fts_outbox " +
            "WHERE state = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY target_revision DESC, created_at LIMIT :limit",
    )
    suspend fun getReadyEvents(now: Long, limit: Int): List<MessageFtsOutboxEntity>
}

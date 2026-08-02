package me.rerere.rikkahub.data.db.fts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.conversation.ConversationV2Codec
import me.rerere.rikkahub.data.db.conversation.ConversationV2ShadowProjector
import me.rerere.rikkahub.data.db.conversation.ConversationV2Writer
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MessageFtsOutboxProcessorTest {
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var writer: ConversationV2Writer
    private lateinit var processor: MessageFtsOutboxProcessor
    private var clock = 100_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        createFtsTable()
        appScope = AppScope()
        val projector = ConversationV2ShadowProjector(
            database.conversationGraphDao(),
            database.conversationMigrationDao(),
            JsonInstant,
        )
        val ftsManager = MessageFtsManager(database)
        writer = ConversationV2Writer(
            database = database,
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            graphDAO = database.conversationGraphDao(),
            migrationDAO = database.conversationMigrationDao(),
            ftsOutboxDAO = database.messageFtsOutboxDao(),
            projector = projector,
            codec = ConversationV2Codec(JsonInstant),
            json = JsonInstant,
            nowMillis = { ++clock },
        )
        processor = MessageFtsOutboxProcessor(
            database = database,
            outboxDAO = database.messageFtsOutboxDao(),
            conversationDAO = database.conversationDao(),
            projector = projector,
            ftsManager = ftsManager,
            appScope = appScope,
            nowMillis = { ++clock },
            workerId = "instrumentation",
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
    }

    @Test
    fun upsertReplacesProjectionAndCompletesThroughLatestRevision() = runBlocking {
        val inserted = writer.insert(conversation("first", "old text"))

        assertEquals(0, indexedCount(inserted.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_PENDING, latestEvent(inserted).state)
        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())
        assertEquals("old text", indexedText(inserted.id.toString()))

        val updated = writer.update(
            inserted.copy(
                title = "updated",
                messageNodes = listOf(node(message("new text"))),
                updateAt = Instant.ofEpochMilli(3),
            ),
        )
        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())

        assertEquals("new text", indexedText(updated.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_DONE, latestEvent(updated).state)
        assertEquals(1, doneEventCount(updated.id.toString()))
    }

    @Test
    fun expiredLeaseIsRecoveredWithoutDuplicatingProjection() = runBlocking {
        val inserted = writer.insert(conversation("lease", "recover me"))
        val event = latestEvent(inserted)
        val claimTime = ++clock
        assertEquals(
            1,
            database.messageFtsOutboxDao().claim(
                eventId = event.eventId,
                owner = "dead-worker",
                now = claimTime,
                leaseUntil = claimTime + 1_000,
            ),
        )

        assertEquals(MessageFtsDrainResult(0, 0, 0), processor.drainReady())
        clock += 1_001
        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())

        assertEquals("recover me", indexedText(inserted.id.toString()))
        assertEquals(1, indexedCount(inserted.id.toString()))
    }

    @Test
    fun projectionFailureRollsBackAndRetriesFromDurableState() = runBlocking {
        val inserted = writer.insert(conversation("retry", "eventually indexed"))
        database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")

        assertEquals(MessageFtsDrainResult(1, 0, 1), processor.drainReady())
        val failedEvent = latestEvent(inserted)
        assertEquals(ConversationV2Values.OUTBOX_PENDING, failedEvent.state)
        assertEquals(1, failedEvent.attempts)
        assertNotNull(failedEvent.lastError)

        createFtsTable()
        clock = failedEvent.nextAttemptAt + 1
        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())

        assertEquals("eventually indexed", indexedText(inserted.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_DONE, latestEvent(inserted).state)
    }

    @Test
    fun deleteEventOutlivesConversationCascadeAndPurgesFts() = runBlocking {
        val inserted = writer.insert(conversation("delete", "remove me"))
        processor.drainReady()
        assertEquals(1, indexedCount(inserted.id.toString()))

        assertEquals(true, writer.delete(inserted.id.toString()))
        assertNull(database.conversationDao().getConversationById(inserted.id.toString()))
        assertEquals(1, indexedCount(inserted.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_DELETE, latestEvent(inserted).operation)

        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())
        assertEquals(0, indexedCount(inserted.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_DONE, latestEvent(inserted).state)
    }

    @Test
    fun deleteThenSameIdRecreateBeforeDrainKeepsNewIncarnationIndexed() = runBlocking {
        val original = writer.insert(conversation("original", "old incarnation"))
        processor.drainReady()
        assertEquals(true, writer.delete(original.id.toString()))

        val recreated = writer.insert(
            conversation("replacement", "new incarnation").copy(
                id = original.id,
                createAt = Instant.ofEpochMilli(50),
                updateAt = Instant.ofEpochMilli(51),
            ),
        )
        assertEquals(MessageFtsDrainResult(1, 1, 0), processor.drainReady())

        assertEquals("new incarnation", indexedText(recreated.id.toString()))
        assertEquals(ConversationV2Values.OUTBOX_UPSERT, latestEvent(recreated).operation)
        assertEquals(ConversationV2Values.OUTBOX_DONE, latestEvent(recreated).state)
        assertEquals(1, outboxEventCount(recreated.id.toString()))
    }

    @Test
    fun rebuildFailureLeavesDurableGenerationForBackgroundRepair() = runBlocking {
        val inserted = writer.insert(conversation("rebuild", "searchable"))
        processor.drainReady()
        val partId = database.conversationGraphDao()
            .getAllParts(inserted.id.toString())
            .single()
            .partId
        database.conversationGraphDao().deletePartsById(inserted.id.toString(), listOf(partId))

        processor.rebuildAll()

        val event = latestEvent(inserted)
        assertEquals(ConversationV2Values.OUTBOX_REBUILD, event.operation)
        assertEquals(ConversationV2Values.OUTBOX_PENDING, event.state)
        assertEquals(1, event.attempts)
        assertEquals(0, indexedCount(inserted.id.toString()))
    }

    @Test
    fun repeatedRebuildAtSameRevisionRearmsDurableEvent() = runBlocking {
        val inserted = writer.insert(conversation("repeat-rebuild", "indexed twice"))
        processor.drainReady()

        processor.rebuildAll()
        val firstOrder = latestEvent(inserted).createdAt
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM message_fts WHERE conversation_id = ?",
            arrayOf(inserted.id.toString()),
        )
        processor.rebuildAll()

        val secondEvent = latestEvent(inserted)
        assertEquals(ConversationV2Values.OUTBOX_REBUILD, secondEvent.operation)
        assertEquals(ConversationV2Values.OUTBOX_DONE, secondEvent.state)
        assertEquals(true, secondEvent.createdAt > firstOrder)
        assertEquals("indexed twice", indexedText(inserted.id.toString()))
        assertEquals(1, outboxEventCount(inserted.id.toString()))
    }

    private fun conversation(title: String, text: String) = Conversation(
        id = Uuid.parse(conversationId(title)),
        assistantId = ASSISTANT_ID,
        title = title,
        messageNodes = listOf(node(message(text))),
        createAt = Instant.ofEpochMilli(1),
        updateAt = Instant.ofEpochMilli(2),
    )

    private fun node(message: UIMessage) = MessageNode(
        id = NODE_ID,
        messages = listOf(message),
    )

    private fun message(text: String) = UIMessage(
        id = MESSAGE_ID,
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
    )

    private suspend fun latestEvent(conversation: Conversation) =
        requireNotNull(database.messageFtsOutboxDao().getLatestEvent(conversation.id.toString()))

    private fun createFtsTable() {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE message_fts(
                text TEXT,
                node_id TEXT,
                message_id TEXT,
                conversation_id TEXT,
                title TEXT,
                update_at TEXT
            )
            """.trimIndent(),
        )
    }

    private fun indexedCount(conversationId: String): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM message_fts WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun indexedText(conversationId: String): String =
        database.openHelper.writableDatabase.query(
            "SELECT text FROM message_fts WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun doneEventCount(conversationId: String): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM message_fts_outbox WHERE conversation_id = ? AND state = 'DONE'",
            arrayOf(conversationId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun outboxEventCount(conversationId: String): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM message_fts_outbox WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun conversationId(title: String): String {
        val seed = title.fold(0) { total, character -> (total * 31 + character.code) and 0xffff }
        return "10000000-0000-0000-0000-${seed.toString().padStart(12, '0')}"
    }

    private companion object {
        val ASSISTANT_ID = Uuid.parse("40000000-0000-0000-0000-000000000001")
        val NODE_ID = Uuid.parse("30000000-0000-0000-0000-000000000001")
        val MESSAGE_ID = Uuid.parse("20000000-0000-0000-0000-000000000001")
    }
}

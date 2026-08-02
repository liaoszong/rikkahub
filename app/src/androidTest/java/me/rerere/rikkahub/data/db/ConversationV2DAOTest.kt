package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageFtsOutboxEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationV2DAOTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun graphIdentityIsConversationScopedAndRevisionReservationUsesCas() = runBlocking {
        val conversationDao = database.conversationDao()
        val graphDao = database.conversationGraphDao()
        conversationDao.insert(conversation("conversation-a"))
        conversationDao.insert(conversation("conversation-b"))
        graphDao.insertBranchGroup(group("conversation-a", "group"))
        graphDao.insertBranchGroup(group("conversation-b", "group"))

        graphDao.insertMessages(
            listOf(
                message("conversation-a", "shared-message"),
                message("conversation-b", "shared-message"),
            ),
        )

        assertNotNull(graphDao.getMessage("conversation-a", "shared-message"))
        assertNotNull(graphDao.getMessage("conversation-b", "shared-message"))
        assertNull(graphDao.getMessage("conversation-a", "missing"))

        assertEquals(
            1,
            graphDao.reserveConversationRevision(
                conversationId = "conversation-a",
                expectedRevision = 0,
                updateAt = 100,
                writerReplicaId = "replica-a",
            ),
        )
        assertEquals(
            0,
            graphDao.reserveConversationRevision(
                conversationId = "conversation-a",
                expectedRevision = 0,
                updateAt = 101,
                writerReplicaId = "replica-b",
            ),
        )
        assertEquals(1L, conversationDao.getConversationById("conversation-a")?.revision)
    }

    @Test
    fun deleteOutboxDoesNotRequireConversationRow() = runBlocking {
        val outboxDao = database.messageFtsOutboxDao()
        val event = MessageFtsOutboxEntity(
            eventId = "delete-missing-conversation",
            conversationId = "missing-conversation",
            targetRevision = 4,
            operation = "DELETE",
            createdAt = 10,
            updatedAt = 10,
        )

        assertTrueInsert(outboxDao.enqueue(event))
        assertEquals(event, outboxDao.getEvent(event.eventId))
    }

    private fun conversation(id: String) = ConversationEntity(
        id = id,
        assistantId = "assistant",
        title = id,
        nodes = "[]",
        createAt = 1,
        updateAt = 1,
        chatSuggestions = "[]",
        isPinned = false,
    )

    private fun group(conversationId: String, branchGroupId: String) = MessageBranchGroupEntity(
        conversationId = conversationId,
        branchGroupId = branchGroupId,
        createdAt = "2026-01-01T00:00:00",
    )

    private fun message(conversationId: String, messageId: String) = ConversationMessageEntity(
        conversationId = conversationId,
        messageId = messageId,
        branchGroupId = "group",
        siblingOrdinal = 0,
        role = "assistant",
        state = "COMPLETED",
        createdAt = "2026-01-01T00:00:00",
        contentDigest = "digest-$conversationId-$messageId",
    )

    private fun assertTrueInsert(rowId: Long) {
        check(rowId != -1L) { "Expected insert to create an outbox row" }
    }
}

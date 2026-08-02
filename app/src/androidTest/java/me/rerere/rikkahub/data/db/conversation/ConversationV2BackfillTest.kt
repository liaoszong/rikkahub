package me.rerere.rikkahub.data.db.conversation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationV2BackfillTest {
    private lateinit var database: AppDatabase
    private var clock = 10_000L

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
    fun backfillRepairsScopedDuplicateIdsDropsEmptyGroupsAndVerifiesProjection() = runBlocking {
        val conversationId = "10000000-0000-0000-0000-000000000001"
        val rootA = "20000000-0000-0000-0000-000000000001"
        val rootB = "20000000-0000-0000-0000-000000000002"
        val rootNode = node(
            conversationId = conversationId,
            nodeId = "30000000-0000-0000-0000-000000000001",
            index = 0,
            messages = listOf(message(rootA, "root-a"), message(rootB, "root-b")),
            selectIndex = 1,
        )
        val emptyNode = node(
            conversationId = conversationId,
            nodeId = "30000000-0000-0000-0000-000000000002",
            index = 1,
            messages = emptyList(),
            selectIndex = 0,
        )
        val duplicateNode = node(
            conversationId = conversationId,
            nodeId = "30000000-0000-0000-0000-000000000003",
            index = 2,
            messages = listOf(message(rootA, "duplicate-id-child", MessageRole.ASSISTANT)),
            selectIndex = 0,
        )
        insertLegacy(conversationId, listOf(rootNode, emptyNode, duplicateNode))
        val originalPayloads = listOf(rootNode, emptyNode, duplicateNode).associate { it.id to it.messages }

        val result = coordinator("valid").runPending(maxConversations = 1)

        assertEquals(1, result.ready)
        val state = database.conversationMigrationDao().getConversationState(conversationId)
        val journal = database.conversationMigrationDao().getJournal(conversationId)
        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, state?.storageVersion)
        assertEquals(ConversationV2Values.MIGRATION_READY, journal?.phase)
        assertEquals(journal?.legacyProjectionDigest, journal?.v2ProjectionDigest)
        assertNotNull(journal?.legacySourceDigest)
        assertTrue(journal?.inferenceFlagsJson.orEmpty().contains("DUPLICATE_MESSAGE_ID"))
        assertTrue(journal?.inferenceFlagsJson.orEmpty().contains("EMPTY_BRANCH_GROUP_DROPPED"))
        assertEquals(3, journal?.nextNodeIndex)
        assertEquals(2, journal?.expectedGroupCount)
        assertEquals(2, journal?.writtenGroupCount)

        val graphMessages = database.conversationGraphDao().getMessages(conversationId)
        val repaired = graphMessages.single { it.legacyMessageId == rootA }
        assertNotEquals(rootA, repaired.messageId)
        assertEquals(rootB, repaired.parentMessageId)
        assertEquals(repaired.messageId, state?.activeLeafMessageId)
        assertEquals(2, database.conversationGraphDao().countBranchGroups(conversationId))
        assertEquals(3, database.conversationGraphDao().countMessages(conversationId))
        assertEquals(3, database.conversationGraphDao().countParts(conversationId))

        val projection = ConversationV2ShadowProjector(
            database.conversationGraphDao(),
            database.conversationMigrationDao(),
            JsonInstant,
        ).loadReady(conversationId)
        assertNotNull(projection)
        assertEquals(2, projection?.nodes?.size)
        assertEquals(1, projection?.nodes?.first()?.selectedIndex)
        assertEquals("duplicate-id-child", projection?.nodes?.last()?.messages?.single()?.text())

        originalPayloads.forEach { (nodeId, raw) -> assertEquals(raw, legacyPayload(nodeId)) }
    }

    @Test
    fun checkpointResumesWithoutDuplicateRows() = runBlocking {
        val conversationId = "10000000-0000-0000-0000-000000000002"
        insertLegacy(
            conversationId,
            listOf(
                node(
                    conversationId,
                    "30000000-0000-0000-0000-000000000011",
                    0,
                    listOf(message("20000000-0000-0000-0000-000000000011", "one")),
                    0,
                ),
                node(
                    conversationId,
                    "30000000-0000-0000-0000-000000000012",
                    1,
                    listOf(message("20000000-0000-0000-0000-000000000012", "two")),
                    0,
                ),
            ),
        )
        val coordinator = coordinator("resume")

        val first = coordinator.runPending(maxConversations = 1, maxNodesPerConversation = 1)
        assertEquals(1, first.inProgress)
        assertEquals(1, database.conversationMigrationDao().getJournal(conversationId)?.nextNodeIndex)
        assertEquals(ConversationV2Values.STORAGE_VERSION_LEGACY, state(conversationId).storageVersion)

        val second = coordinator.runPending(maxConversations = 1, maxNodesPerConversation = 1)
        assertEquals(1, second.inProgress)
        assertEquals(2, database.conversationMigrationDao().getJournal(conversationId)?.nextNodeIndex)

        val third = coordinator.runPending(maxConversations = 1, maxNodesPerConversation = 1)
        assertEquals(1, third.ready)
        assertEquals(2, database.conversationGraphDao().countBranchGroups(conversationId))
        assertEquals(2, database.conversationGraphDao().countMessages(conversationId))
        assertEquals(2, database.conversationGraphDao().countParts(conversationId))
    }

    @Test
    fun malformedJsonAndInvalidSelectionAreQuarantinedWithoutTouchingLegacyRows() = runBlocking {
        val malformedConversation = "10000000-0000-0000-0000-000000000003"
        val malformed = MessageNodeEntity(
            id = "30000000-0000-0000-0000-000000000021",
            conversationId = malformedConversation,
            nodeIndex = 0,
            messages = "{broken",
            selectIndex = 0,
        )
        insertLegacy(malformedConversation, listOf(malformed))

        val invalidSelectionConversation = "10000000-0000-0000-0000-000000000004"
        val invalidSelection = node(
            invalidSelectionConversation,
            "30000000-0000-0000-0000-000000000022",
            0,
            listOf(message("20000000-0000-0000-0000-000000000021", "only")),
            4,
        )
        insertLegacy(invalidSelectionConversation, listOf(invalidSelection))

        val result = coordinator("quarantine").runPending(maxConversations = 2)

        assertEquals(2, result.quarantined)
        assertEquals("MALFORMED_MESSAGE_JSON", journalError(malformedConversation))
        assertEquals("SELECT_INDEX_OUT_OF_BOUNDS", journalError(invalidSelectionConversation))
        assertEquals(1, quarantineCount(malformedConversation))
        assertEquals(1, quarantineCount(invalidSelectionConversation))
        assertEquals(malformed.messages, legacyPayload(malformed.id))
        assertEquals(invalidSelection.messages, legacyPayload(invalidSelection.id))
        assertEquals(ConversationV2Values.STORAGE_VERSION_LEGACY, state(malformedConversation).storageVersion)
        assertEquals(ConversationV2Values.STORAGE_VERSION_LEGACY, state(invalidSelectionConversation).storageVersion)
    }

    @Test
    fun legacyWriteInvalidatesReadyShadowAndRerunReconcilesNewRevision() = runBlocking {
        val conversationId = "10000000-0000-0000-0000-000000000005"
        val node = node(
            conversationId,
            "30000000-0000-0000-0000-000000000031",
            0,
            listOf(message("20000000-0000-0000-0000-000000000031", "before")),
            0,
        )
        insertLegacy(conversationId, listOf(node))
        val coordinator = coordinator("invalidate")
        assertEquals(1, coordinator.runPending(maxConversations = 1).ready)
        val readyRevision = state(conversationId).revision

        val changed = node.copy(
            messages = JsonInstant.encodeToString(
                listOf(message("20000000-0000-0000-0000-000000000031", "after")),
            ),
        )
        database.messageNodeDao().update(changed)

        val invalidated = state(conversationId)
        assertEquals(ConversationV2Values.STORAGE_VERSION_LEGACY, invalidated.storageVersion)
        assertNull(invalidated.activeLeafMessageId)
        assertTrue(invalidated.revision > readyRevision)
        assertEquals(ConversationV2Values.MIGRATION_PENDING, database.conversationMigrationDao().getJournal(conversationId)?.phase)

        assertEquals(1, coordinator.runPending(maxConversations = 1).ready)
        val projection = ConversationV2ShadowProjector(
            database.conversationGraphDao(),
            database.conversationMigrationDao(),
            JsonInstant,
        ).loadReady(conversationId)
        assertEquals("after", projection?.nodes?.single()?.messages?.single()?.text())
    }

    private fun coordinator(worker: String) = ConversationV2BackfillCoordinator(
        database = database,
        graphDAO = database.conversationGraphDao(),
        migrationDAO = database.conversationMigrationDao(),
        ftsOutboxDAO = database.messageFtsOutboxDao(),
        json = JsonInstant,
        nowMillis = { ++clock },
        workerId = worker,
    )

    private suspend fun insertLegacy(conversationId: String, nodes: List<MessageNodeEntity>) {
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = "40000000-0000-0000-0000-000000000001",
                title = conversationId,
                nodes = "[]",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        database.messageNodeDao().insertAll(nodes)
    }

    private fun node(
        conversationId: String,
        nodeId: String,
        index: Int,
        messages: List<UIMessage>,
        selectIndex: Int,
    ) = MessageNodeEntity(
        id = nodeId,
        conversationId = conversationId,
        nodeIndex = index,
        messages = JsonInstant.encodeToString(messages),
        selectIndex = selectIndex,
    )

    private fun message(
        id: String,
        text: String,
        role: MessageRole = MessageRole.USER,
    ) = UIMessage(
        id = Uuid.parse(id),
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
    )

    private fun UIMessage.text(): String = (parts.single() as UIMessagePart.Text).text

    private suspend fun state(conversationId: String) =
        requireNotNull(database.conversationMigrationDao().getConversationState(conversationId))

    private suspend fun journalError(conversationId: String): String? =
        database.conversationMigrationDao().getJournal(conversationId)?.lastErrorCode

    private fun legacyPayload(nodeId: String): String = database.openHelper.writableDatabase
        .query("SELECT messages FROM message_node WHERE id = ?", arrayOf(nodeId))
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun quarantineCount(conversationId: String): Int = database.openHelper.writableDatabase
        .query(
            "SELECT COUNT(*) FROM conversation_migration_quarantine WHERE conversation_id = ?",
            arrayOf(conversationId),
        )
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}

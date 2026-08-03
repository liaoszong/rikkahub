package me.rerere.rikkahub.data.db.conversation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.entity.MediaMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.MediaV2Values
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageFtsOutboxProcessor
import me.rerere.rikkahub.data.db.media.ConversationMediaReferenceIndexer
import me.rerere.rikkahub.data.db.media.FilesDirManagedMediaPathResolver
import me.rerere.rikkahub.data.db.media.MediaReferenceBackfillScheduler
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.pale.media.MediaStableIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationV2CutoverTest {
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var projector: ConversationV2ShadowProjector
    private lateinit var writer: ConversationV2Writer
    private lateinit var repository: ConversationRepository
    private var clock = 20_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        appScope = AppScope()
        projector = ConversationV2ShadowProjector(
            database.conversationGraphDao(),
            database.conversationMigrationDao(),
            JsonInstant,
        )
        val mediaReferenceIndexer = ConversationMediaReferenceIndexer(
            database = database,
            dao = database.genMediaDao(),
            migrationDAO = database.conversationMigrationDao(),
            shadowProjector = projector,
            json = JsonInstant,
            managedPathResolver = FilesDirManagedMediaPathResolver(context.filesDir),
        )
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
            mediaReferenceIndexer = mediaReferenceIndexer,
            mediaReferenceBackfillScheduler = MediaReferenceBackfillScheduler {},
            nowMillis = { ++clock },
        )
        val ftsManager = MessageFtsManager(database)
        val ftsProcessor = MessageFtsOutboxProcessor(
            database = database,
            outboxDAO = database.messageFtsOutboxDao(),
            conversationDAO = database.conversationDao(),
            projector = projector,
            ftsManager = ftsManager,
            appScope = appScope,
            nowMillis = { ++clock },
            workerId = "cutover-test",
        )
        repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            messageFtsManager = ftsManager,
            messageFtsOutboxProcessor = ftsProcessor,
            conversationV2Writer = writer,
            conversationV2Projector = projector,
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
    }

    @Test
    fun readyReaderServesGetRecentAndRebuildWhilePreservingFavorites() = runBlocking {
        val inserted = writer.insert(conversation("ready", listOf(node(1, message(1, "from-v2")))))
        val nodeId = inserted.messageNodes.single().id
        database.favoriteDao().upsert(
            FavoriteEntity(
                id = "favorite-1",
                type = "node",
                refKey = "node:${inserted.id}:$nodeId",
                refJson = "{}",
                snapshotJson = "{}",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        val byId = requireNotNull(repository.getConversationById(inserted.id))
        val recent = repository.getRecentConversations(inserted.assistantId)
        repository.rebuildAllIndexes()

        assertEquals("from-v2", byId.messageNodes.single().text())
        assertTrue(byId.messageNodes.single().isFavorite)
        assertEquals(byId, recent.single())
        assertEquals("from-v2", indexedText(inserted.id.toString()))
        assertEquals(0L, byId.storageRevision)
    }

    @Test
    fun liveInsertDropsEmptyGroupsAndRecordsNormalization() = runBlocking {
        val source = conversation(
            "live-empty",
            listOf(
                MessageNode(
                    id = Uuid.parse("30000000-0000-0000-0000-000000000098"),
                    messages = emptyList(),
                ),
                node(12, message(12, "kept")),
            ),
        )

        val inserted = writer.insert(source)
        val journal = requireNotNull(
            database.conversationMigrationDao().getJournal(source.id.toString()),
        )

        assertEquals(1, inserted.messageNodes.size)
        assertEquals(1, inserted.currentMessages.size)
        assertEquals(1, database.conversationGraphDao().countBranchGroups(source.id.toString()))
        assertEquals(1, journal.expectedGroupCount)
        assertEquals(1, journal.writtenGroupCount)
        assertTrue(journal.inferenceFlagsJson.contains(INFERENCE_EMPTY_BRANCH_GROUP_DROPPED))
    }

    @Test
    fun pendingConversationFallsBackThenRegularUpdatePromotesImmediatelyToReady() = runBlocking {
        val original = conversation("pending", listOf(node(2, message(2, "legacy"))))
        val encoded = ConversationV2Codec(JsonInstant).encode(original)
        database.conversationDao().insert(original.toLegacyEntity(revision = 7))
        database.messageNodeDao().insertAll(encoded.legacyNodes)
        database.conversationMigrationDao().upsertJournal(
            ConversationMigrationJournalEntity(
                conversationId = original.id.toString(),
                sourceRevision = 7,
                updatedAt = ++clock,
            ),
        )

        val loaded = requireNotNull(repository.getConversationById(original.id))
        val persisted = writer.update(loaded.copy(title = "promoted"))
        val state = requireNotNull(
            database.conversationMigrationDao().getConversationState(original.id.toString()),
        )

        assertEquals("legacy", loaded.messageNodes.single().text())
        assertEquals(7L, loaded.storageRevision)
        assertEquals(8L, persisted.storageRevision)
        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, state.storageVersion)
        assertEquals(8L, state.revision)
        assertEquals(
            ConversationV2Values.MIGRATION_READY,
            database.conversationMigrationDao().getJournal(original.id.toString())?.phase,
        )
        assertEquals(
            "legacy",
            projector.loadReady(original.id.toString())?.nodes?.single()?.messages?.single()?.text(),
        )
    }

    @Test
    fun readyDamageFailsClosedWithoutLegacyFallback() = runBlocking {
        val inserted = writer.insert(conversation("damaged", listOf(node(3, message(3, "intact")))))
        val partId = database.conversationGraphDao()
            .getAllParts(inserted.id.toString())
            .single()
            .partId
        database.conversationGraphDao().deletePartsById(inserted.id.toString(), listOf(partId))

        assertThrows(ConversationV2IntegrityException::class.java) {
            runBlocking { repository.getConversationById(inserted.id) }
        }
        Unit
    }

    @Test
    fun readyLegacySourceDamageFailsClosedWithoutRevisionChange() = runBlocking {
        val inserted = writer.insert(
            conversation("legacy-damaged", listOf(node(8, message(8, "original")))),
        )
        val encoded = ConversationV2Codec(JsonInstant).encode(inserted)
        val legacyNode = encoded.legacyNodes.single()
        database.messageNodeDao().update(
            legacyNode.copy(messages = legacyNode.messages.replace("original", "tampered")),
        )
        val unchangedState = requireNotNull(
            database.conversationMigrationDao().getConversationState(inserted.id.toString()),
        )

        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, unchangedState.storageVersion)
        assertEquals(0L, unchangedState.revision)
        assertThrows(ConversationV2IntegrityException::class.java) {
            runBlocking { repository.getConversationById(inserted.id) }
        }
        Unit
    }

    @Test
    fun internalDualWriteStaysReadyAndExternalLegacyWriteDowngradesOnce() = runBlocking {
        val coordinator = coordinator()
        coordinator.installLegacyInvalidationTriggers()
        coordinator.installLegacyInvalidationTriggers()
        assertEquals(4, installedTriggerCount())

        val inserted = writer.insert(conversation("trigger", listOf(node(4, message(4, "one")))))
        val updated = writer.update(
            inserted.copy(messageNodes = listOf(node(4, message(4, "two")))),
        )
        val internallyWritten = requireNotNull(
            database.conversationMigrationDao().getConversationState(inserted.id.toString()),
        )
        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, internallyWritten.storageVersion)
        assertEquals(1L, internallyWritten.revision)
        assertEquals(
            ConversationV2Values.MIGRATION_READY,
            database.conversationMigrationDao().getJournal(inserted.id.toString())?.phase,
        )
        assertNull(lastWriterMarker(inserted.id.toString()))

        val legacyNode = ConversationV2Codec(JsonInstant).encode(updated).legacyNodes.single()
        database.messageNodeDao().update(
            legacyNode.copy(messages = legacyNode.messages.replace("two", "external")),
        )
        val once = requireNotNull(
            database.conversationMigrationDao().getConversationState(inserted.id.toString()),
        )
        database.messageNodeDao().update(legacyNode.copy(messages = legacyNode.messages.replace("two", "again")))
        val twice = requireNotNull(
            database.conversationMigrationDao().getConversationState(inserted.id.toString()),
        )

        assertEquals(ConversationV2Values.STORAGE_VERSION_LEGACY, once.storageVersion)
        assertEquals(2L, once.revision)
        assertEquals(once.revision, twice.revision)
        assertEquals(
            ConversationV2Values.MIGRATION_PENDING,
            database.conversationMigrationDao().getJournal(inserted.id.toString())?.phase,
        )

        val external = requireNotNull(repository.getConversationById(inserted.id))
        val promoted = writer.update(external)
        val readyAgain = requireNotNull(
            database.conversationMigrationDao().getConversationState(inserted.id.toString()),
        )
        assertEquals(3L, promoted.storageRevision)
        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, readyAgain.storageVersion)
        assertEquals(3L, readyAgain.revision)
        assertEquals(
            ConversationV2Values.MIGRATION_READY,
            database.conversationMigrationDao().getJournal(inserted.id.toString())?.phase,
        )
        assertEquals(
            "again",
            projector.loadReady(inserted.id.toString())?.nodes?.single()?.messages?.single()?.text(),
        )
    }

    @Test
    fun staleRevisionRaisesTypedConflict() = runBlocking {
        val inserted = writer.insert(conversation("cas", listOf(node(5, message(5, "zero")))))
        writer.update(inserted.copy(title = "winner"))

        val error = assertThrows(ConversationV2WriteConflictException::class.java) {
            runBlocking { writer.update(inserted.copy(title = "stale")) }
        }

        assertEquals(0L, error.expectedRevision)
        assertEquals(1L, error.actualRevision)
    }

    @Test
    fun differentialWriteKeepsStableIdsAndUnchangedEntityRevisions() = runBlocking {
        val original = conversation(
            "diff",
            listOf(
                node(6, message(6, "same")),
                node(7, message(7, "before")),
            ),
        )
        val inserted = writer.insert(original)
        val oldGroups = database.conversationGraphDao()
            .getBranchGroups(inserted.id.toString())
            .associateBy { it.branchGroupId }
        val oldMessages = database.conversationGraphDao()
            .getMessages(inserted.id.toString())
            .associateBy { it.messageId }
        val oldParts = database.conversationGraphDao()
            .getAllParts(inserted.id.toString())
            .associateBy { it.messageId }

        writer.update(
            inserted.copy(
                messageNodes = listOf(
                    node(6, message(6, "same")),
                    node(7, message(7, "after")),
                ),
            ),
        )
        val newGroups = database.conversationGraphDao()
            .getBranchGroups(inserted.id.toString())
            .associateBy { it.branchGroupId }
        val newMessages = database.conversationGraphDao()
            .getMessages(inserted.id.toString())
            .associateBy { it.messageId }
        val newParts = database.conversationGraphDao()
            .getAllParts(inserted.id.toString())
            .associateBy { it.messageId }
        val unchangedMessageId = messageId(6)
        val changedMessageId = messageId(7)

        assertEquals(oldGroups, newGroups)
        assertEquals(oldMessages.getValue(unchangedMessageId), newMessages.getValue(unchangedMessageId))
        assertEquals(oldParts.getValue(unchangedMessageId), newParts.getValue(unchangedMessageId))
        assertEquals(
            oldMessages.getValue(changedMessageId).revision + 1,
            newMessages.getValue(changedMessageId).revision,
        )
        assertEquals(oldParts.getValue(changedMessageId).partId, newParts.getValue(changedMessageId).partId)
        assertEquals(
            oldParts.getValue(changedMessageId).revision + 1,
            newParts.getValue(changedMessageId).revision,
        )
    }

    @Test
    fun legacyBackfillThenNoOpLiveUpdateKeepsPartIdsAndAllEntityRevisions() = runBlocking {
        val legacy = conversation(
            "backfill-noop",
            listOf(
                node(9, message(9, "first")),
                MessageNode(
                    id = Uuid.parse("30000000-0000-0000-0000-000000000099"),
                    messages = emptyList(),
                ),
                node(10, message(10, "second")),
            ),
        )
        database.conversationDao().insert(legacy.toLegacyEntity(revision = 0))
        database.messageNodeDao().insertAll(rawLegacyNodes(legacy))
        assertEquals(1, coordinator().runPending(maxConversations = 1).ready)
        val loaded = requireNotNull(repository.getConversationById(legacy.id))
        val before = graphIdentity(legacy.id.toString())

        val saved = writer.update(loaded)

        val after = graphIdentity(legacy.id.toString())
        assertEquals(2, loaded.messageNodes.size)
        assertEquals(2, loaded.currentMessages.size)
        assertEquals(2, saved.messageNodes.size)
        assertEquals(2, saved.currentMessages.size)
        assertEquals(before.partIds, after.partIds)
        assertEquals(before.groupRevisions, after.groupRevisions)
        assertEquals(before.messages, after.messages)
        assertEquals(before.messageRevisions, after.messageRevisions)
        assertEquals(before.partRevisions, after.partRevisions)
        assertTrue(
            database.conversationMigrationDao()
                .getJournal(legacy.id.toString())
                ?.inferenceFlagsJson
                .orEmpty()
                .contains(INFERENCE_EMPTY_BRANCH_GROUP_DROPPED),
        )
    }

    @Test
    fun backfilledUnknownEnvelopeExtrasSurviveNoOpUpdateWithoutRevisionChange() = runBlocking {
        val legacy = conversation("envelope-extra", listOf(node(11, message(11, "known"))))
        val rawMessage = JsonInstant
            .encodeToJsonElement(UIMessage.serializer(), legacy.messageNodes.single().messages.single())
            .jsonObject
            .toMutableMap()
            .apply { put("providerTrace", JsonPrimitive("opaque-value")) }
        val rawNode = MessageNodeEntity(
            id = legacy.messageNodes.single().id.toString(),
            conversationId = legacy.id.toString(),
            nodeIndex = 0,
            messages = JsonArray(listOf(JsonObject(rawMessage))).toString(),
            selectIndex = 0,
        )
        database.conversationDao().insert(legacy.toLegacyEntity(revision = 0))
        database.messageNodeDao().insert(rawNode)
        assertEquals(1, coordinator().runPending(maxConversations = 1).ready)
        val before = database.conversationGraphDao().getMessages(legacy.id.toString()).single()
        val loaded = requireNotNull(repository.getConversationById(legacy.id))

        writer.update(loaded)

        val after = database.conversationGraphDao().getMessages(legacy.id.toString()).single()
        assertTrue(before.envelopeExtrasJson.orEmpty().contains("providerTrace"))
        assertEquals(before.envelopeExtrasJson, after.envelopeExtrasJson)
        assertEquals(before.revision, after.revision)
    }

    @Test
    fun readyMetadataPatchAdvancesJournalWithoutRewritingGraph() = runBlocking {
        val inserted = writer.insert(conversation("metadata", listOf(node(21, message(21, "stable")))))
        val beforeGraph = graphIdentity(inserted.id.toString())
        val folderId = Uuid.parse("50000000-0000-0000-0000-000000000021")

        val patched = writer.patchMetadata(
            inserted,
            ConversationMetadataPatch(
                title = ConversationMetadataField.Set("renamed"),
                chatSuggestions = ConversationMetadataField.Set(listOf("next")),
                isPinned = ConversationMetadataField.Set(true),
                folderId = ConversationMetadataField.Set(folderId),
            ),
        )

        val afterGraph = graphIdentity(inserted.id.toString())
        val journal = requireNotNull(
            database.conversationMigrationDao().getJournal(inserted.id.toString()),
        )
        val stored = requireNotNull(repository.getConversationById(inserted.id))
        val latestFts = requireNotNull(
            database.messageFtsOutboxDao().getLatestEvent(inserted.id.toString()),
        )
        assertEquals(beforeGraph, afterGraph)
        assertEquals(1L, patched.storageRevision)
        assertEquals(1L, journal.sourceRevision)
        assertEquals("renamed", stored.title)
        assertEquals(listOf("next"), stored.chatSuggestions)
        assertTrue(stored.isPinned)
        assertEquals(folderId, stored.folderId)
        assertEquals(1L, latestFts.targetRevision)
        assertNull(lastWriterMarker(inserted.id.toString()))
    }

    @Test
    fun readyMetadataPatchDoesNotWriteUnselectedSnapshotFields() = runBlocking {
        val inserted = writer.insert(
            conversation("metadata-scope", listOf(node(24, message(24, "stable")))).copy(
                chatSuggestions = listOf("durable"),
                updateAt = Instant.ofEpochMilli(2400),
            ),
        )
        val staleSnapshot = inserted.copy(
            chatSuggestions = listOf("stale"),
            customSystemPrompt = "stale prompt",
            updateAt = Instant.ofEpochMilli(9999),
        )

        val patched = writer.patchMetadata(
            staleSnapshot,
            ConversationMetadataPatch(title = ConversationMetadataField.Set("renamed only")),
        )

        val stored = requireNotNull(repository.getConversationById(inserted.id))
        assertEquals("renamed only", stored.title)
        assertEquals(listOf("durable"), stored.chatSuggestions)
        assertEquals(null, stored.customSystemPrompt)
        assertEquals(Instant.ofEpochMilli(2400), stored.updateAt)
        assertEquals("renamed only", patched.title)
        assertEquals(listOf("durable"), patched.chatSuggestions)
        assertEquals(null, patched.customSystemPrompt)
        assertEquals(Instant.ofEpochMilli(2400), patched.updateAt)
    }

    @Test
    fun staleMetadataPatchIsRejectedByRevisionCas() = runBlocking {
        val inserted = writer.insert(conversation("metadata-cas", listOf(node(22, message(22, "stable")))))
        writer.patchMetadata(
            inserted,
            ConversationMetadataPatch(title = ConversationMetadataField.Set("winner")),
        )

        assertThrows(ConversationV2WriteConflictException::class.java) {
            runBlocking {
                writer.patchMetadata(
                    inserted,
                    ConversationMetadataPatch(isPinned = ConversationMetadataField.Set(true)),
                )
            }
        }
        Unit
    }

    @Test
    fun legacyMetadataPatchPromotesBeforeApplyingPatch() = runBlocking {
        val legacy = conversation("metadata-legacy", listOf(node(23, message(23, "legacy"))))
        database.conversationDao().insert(legacy.toLegacyEntity(revision = 4))
        database.messageNodeDao().insertAll(rawLegacyNodes(legacy))
        val loaded = requireNotNull(repository.getConversationById(legacy.id))

        val patched = writer.patchMetadata(
            loaded,
            ConversationMetadataPatch(isPinned = ConversationMetadataField.Set(true)),
        )

        val state = requireNotNull(
            database.conversationMigrationDao().getConversationState(legacy.id.toString()),
        )
        assertEquals(ConversationV2Values.STORAGE_VERSION_V2, state.storageVersion)
        assertEquals(5L, state.revision)
        assertEquals(5L, patched.storageRevision)
        assertTrue(requireNotNull(repository.getConversationById(legacy.id)).isPinned)
        assertEquals("legacy", projector.loadReady(legacy.id.toString())?.nodes?.single()?.messages?.single()?.text())
    }

    @Test
    fun writerMaintainsExactMediaReferencesAcrossInsertUpdateAndDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val relativePath = "chat_generated_images/writer-${System.nanoTime()}.png"
        val imageFile = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val assetId = "asset-writer-${System.nanoTime()}"
        try {
            database.genMediaDao().insertOrGet(
                MediaAssetEntity(
                    path = relativePath,
                    modelId = "test-image-model",
                    prompt = "writer integration",
                    createAt = 1,
                    assetId = assetId,
                    storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    updatedAt = 1,
                ),
            )
            database.genMediaDao().insertJournalIgnore(
                MediaMigrationJournalEntity(
                    journalId = MediaStableIds.derived(
                        "media-journal",
                        "asset",
                        assetId,
                        MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    ),
                    scopeKind = "asset",
                    scopeKey = assetId,
                    stage = MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    state = MediaV2Values.JOURNAL_COMPLETE,
                    updatedAt = 2,
                ),
            )
            val initialMessage = UIMessage(
                id = Uuid.parse(messageId(91)),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Image(imageFile.toURI().toString(), assetId = assetId)),
                createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
            )
            val inserted = writer.insert(conversation("writer-media", listOf(node(91, initialMessage))))
            val insertedRefs = database.genMediaDao().getExactV2References(inserted.id.toString())

            assertEquals(1, insertedRefs.size)
            assertEquals(assetId, insertedRefs.single().assetId)
            assertEquals(messageId(91), insertedRefs.single().messageId)
            assertEquals(
                MediaV2Values.JOURNAL_COMPLETE,
                database.genMediaDao().getJournal(
                    "asset",
                    assetId,
                    MediaV2Values.STAGE_REFERENCE_BACKFILL,
                )?.state,
            )

            val updated = writer.update(
                inserted.copy(messageNodes = listOf(node(91, message(91, "image removed")))),
            )
            assertTrue(database.genMediaDao().getExactV2References(updated.id.toString()).isEmpty())
            assertEquals(
                MediaV2Values.JOURNAL_COMPLETE,
                database.genMediaDao().getJournal(
                    "asset",
                    assetId,
                    MediaV2Values.STAGE_REFERENCE_BACKFILL,
                )?.state,
            )

            assertTrue(writer.delete(updated.id.toString()))
            assertTrue(database.genMediaDao().getExactV2References(updated.id.toString()).isEmpty())
            assertNull(database.conversationDao().getConversationById(updated.id.toString()))
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun legacyPromotionCreatesExactMediaReferenceBeforeReadyCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val relativePath = "chat_generated_images/legacy-writer-${System.nanoTime()}.png"
        val imageFile = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val assetId = "asset-legacy-writer-${System.nanoTime()}"
        try {
            database.genMediaDao().insertOrGet(
                MediaAssetEntity(
                    path = relativePath,
                    modelId = "legacy-image-model",
                    prompt = "legacy promotion",
                    createAt = 1,
                    assetId = assetId,
                    storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    updatedAt = 1,
                ),
            )
            database.genMediaDao().insertJournalIgnore(
                MediaMigrationJournalEntity(
                    journalId = MediaStableIds.derived(
                        "media-journal",
                        "asset",
                        assetId,
                        MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    ),
                    scopeKind = "asset",
                    scopeKey = assetId,
                    stage = MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    state = MediaV2Values.JOURNAL_PENDING,
                    updatedAt = 1,
                ),
            )
            val imageMessage = UIMessage(
                id = Uuid.parse(messageId(92)),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Image(imageFile.toURI().toString(), assetId = assetId)),
                createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
            )
            val legacy = conversation("legacy-writer-media", listOf(node(92, imageMessage)))
            database.conversationDao().insert(legacy.toLegacyEntity(revision = 3))
            database.messageNodeDao().insertAll(rawLegacyNodes(legacy))

            val promoted = writer.update(requireNotNull(repository.getConversationById(legacy.id)))
            val refs = database.genMediaDao().getExactV2References(promoted.id.toString())

            assertEquals(
                ConversationV2Values.STORAGE_VERSION_V2,
                database.conversationMigrationDao()
                    .getConversationState(promoted.id.toString())
                    ?.storageVersion,
            )
            assertEquals(1, refs.size)
            assertEquals(assetId, refs.single().assetId)
            assertEquals(messageId(92), refs.single().messageId)
        } finally {
            imageFile.delete()
        }
    }

    private fun coordinator() = ConversationV2BackfillCoordinator(
        database = database,
        graphDAO = database.conversationGraphDao(),
        migrationDAO = database.conversationMigrationDao(),
        ftsOutboxDAO = database.messageFtsOutboxDao(),
        json = JsonInstant,
        nowMillis = { ++clock },
        workerId = "cutover-test",
    )

    private fun conversation(title: String, nodes: List<MessageNode>) = Conversation(
        id = Uuid.parse(conversationId(title)),
        assistantId = ASSISTANT_ID,
        title = title,
        messageNodes = nodes,
        createAt = Instant.ofEpochMilli(1),
        updateAt = Instant.ofEpochMilli(2),
    )

    private fun node(seed: Int, message: UIMessage) = MessageNode(
        id = Uuid.parse("30000000-0000-0000-0000-${seed.toString().padStart(12, '0')}"),
        messages = listOf(message),
    )

    private fun message(seed: Int, text: String) = UIMessage(
        id = Uuid.parse(messageId(seed)),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
    )

    private fun messageId(seed: Int) =
        "20000000-0000-0000-0000-${seed.toString().padStart(12, '0')}"

    private fun conversationId(title: String): String {
        val seed = title.fold(0) { total, character -> (total * 31 + character.code) and 0xffff }
        return "10000000-0000-0000-0000-${seed.toString().padStart(12, '0')}"
    }

    private fun Conversation.toLegacyEntity(revision: Long) = ConversationEntity(
        id = id.toString(),
        assistantId = assistantId.toString(),
        title = title,
        nodes = "[]",
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
        chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
        isPinned = isPinned,
        revision = revision,
    )

    private fun MessageNode.text(): String =
        (messages.single().parts.single() as UIMessagePart.Text).text

    private fun UIMessage.text(): String =
        (parts.single() as UIMessagePart.Text).text

    private fun rawLegacyNodes(conversation: Conversation): List<MessageNodeEntity> =
        conversation.messageNodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversation.id.toString(),
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex,
            )
        }

    private fun installedTriggerCount(): Int = database.openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'conversation_v2_invalidate_%'",
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun lastWriterMarker(conversationId: String): String? =
        database.openHelper.writableDatabase.query(
            "SELECT last_writer_replica_id FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun indexedText(conversationId: String): String = database.openHelper.writableDatabase.query(
        "SELECT text FROM message_fts WHERE conversation_id = ?",
        arrayOf(conversationId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private suspend fun graphIdentity(conversationId: String) = GraphIdentity(
        partIds = database.conversationGraphDao().getAllParts(conversationId).map { it.partId },
        groupRevisions = database.conversationGraphDao()
            .getBranchGroups(conversationId)
            .associate { it.branchGroupId to it.revision },
        messageRevisions = database.conversationGraphDao()
            .getMessages(conversationId)
            .associate { it.messageId to it.revision },
        messages = database.conversationGraphDao()
            .getMessages(conversationId)
            .associateBy { it.messageId },
        partRevisions = database.conversationGraphDao()
            .getAllParts(conversationId)
            .associate { it.partId to it.revision },
    )

    private data class GraphIdentity(
        val partIds: List<String>,
        val groupRevisions: Map<String, Long>,
        val messages: Map<String, me.rerere.rikkahub.data.db.entity.ConversationMessageEntity>,
        val messageRevisions: Map<String, Long>,
        val partRevisions: Map<String, Long>,
    )

    private companion object {
        val ASSISTANT_ID: Uuid = Uuid.parse("40000000-0000-0000-0000-000000000001")
    }
}

package me.rerere.rikkahub.data.db.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.conversation.ConversationV2ShadowProjector
import me.rerere.rikkahub.data.db.conversation.digestConversationV2Graph
import me.rerere.rikkahub.data.db.conversation.digestLegacyConversationSource
import me.rerere.rikkahub.data.db.conversation.loadConversationV2Graph
import me.rerere.rikkahub.data.db.conversation.sha256Hex
import me.rerere.rikkahub.data.db.conversation.toCanonicalJson
import me.rerere.rikkahub.data.db.dao.MediaAssetDeleteResult
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationQuarantineEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.entity.MediaMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.MediaV2Values
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageMediaRefEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.pale.media.MediaStableIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationMediaReferenceIndexerTest {
    private lateinit var database: AppDatabase
    private lateinit var filesRoot: File
    private lateinit var indexer: ConversationMediaReferenceIndexer

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        filesRoot = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("conversation-media-test-${System.nanoTime()}").apply { mkdirs() }
        val migrationDAO = database.conversationMigrationDao()
        indexer = ConversationMediaReferenceIndexer(
            database = database,
            dao = database.genMediaDao(),
            migrationDAO = migrationDAO,
            shadowProjector = ConversationV2ShadowProjector(
                database.conversationGraphDao(),
                migrationDAO,
                JsonInstant,
            ),
            json = JsonInstant,
            managedPathResolver = FilesDirManagedMediaPathResolver(filesRoot),
        )
    }

    @After
    fun tearDown() {
        filesRoot.deleteRecursively()
        database.close()
    }

    @Test
    fun validatedReadyGraphResolvesHistoricalPathAndNestedToolImagesWithExactV2Owners() = runBlocking {
        val historical = filesRoot.resolve("images/historical.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        insertAsset("asset-historical", "images/historical.png")
        insertAsset("asset-output", "images/output.png")
        insertAsset("asset-progress", "images/progress.png")
        insertConversation("conversation-ready")
        insertGroup("conversation-ready", "group-ready")
        insertMessage("conversation-ready", "message-ready", "group-ready", siblingOrdinal = 0)
        insertPart(
            "conversation-ready",
            "message-ready",
            "part-image",
            ordinal = 0,
            part = UIMessagePart.Image(historical.toURI().toString()),
        )
        insertPart(
            "conversation-ready",
            "message-ready",
            "part-tool",
            ordinal = 1,
            part = UIMessagePart.Tool(
                toolCallId = "outer-tool",
                toolName = "render",
                input = "{}",
                output = listOf(
                    UIMessagePart.Image("https://example.test/output", assetId = "asset-output"),
                ),
                progress = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "inner-tool",
                        toolName = "preview",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Image("https://example.test/progress", assetId = "asset-progress"),
                        ),
                    ),
                ),
            ),
        )
        promoteReady("conversation-ready", "message-ready")
        requireNotNull(
            ConversationV2ShadowProjector(
                database.conversationGraphDao(),
                database.conversationMigrationDao(),
                JsonInstant,
            ).loadReady("conversation-ready"),
        )

        val first = indexer.replaceReadyConversationReferences("conversation-ready", now = 100)
        val replay = indexer.replaceReadyConversationReferences("conversation-ready", now = 200)
        val refs = database.genMediaDao().getExactV2References("conversation-ready")

        assertEquals(first.failure, ConversationMediaIndexStatus.COMPLETE, first.status)
        assertEquals(3, refs.size)
        assertEquals(0, replay.insertedReferences)
        assertEquals(0, replay.deletedReferences)
        assertEquals(setOf("asset-historical", "asset-output", "asset-progress"), refs.map { it.assetId }.toSet())
        assertTrue(refs.all {
            it.messageNodeId == "group-ready" && it.messageId == stableMessageId("message-ready")
        })
        assertEquals("part-tool", refs.single { it.assetId == "asset-output" }.partId)
        assertEquals("outer-tool", refs.single { it.assetId == "asset-output" }.toolCallId)
        assertEquals("inner-tool", refs.single { it.assetId == "asset-progress" }.toolCallId)
    }

    @Test
    fun fakeReadyAndEachReadyIntegrityCorruptionStayIncompleteAndPending() = runBlocking {
        insertAsset("asset-corrupt", "images/corrupt.png")
        insertConversation("conversation-corrupt")
        insertGroup("conversation-corrupt", "group-corrupt")
        insertMessage("conversation-corrupt", "message-corrupt", "group-corrupt", siblingOrdinal = 0)
        insertPart(
            "conversation-corrupt",
            "message-corrupt",
            "part-corrupt",
            part = UIMessagePart.Image("https://example.test/corrupt", assetId = "asset-corrupt"),
        )
        database.conversationMigrationDao().upsertJournal(
            ConversationMigrationJournalEntity(
                conversationId = "conversation-corrupt",
                phase = ConversationV2Values.MIGRATION_READY,
                sourceRevision = 0,
                updatedAt = 1,
            ),
        )

        val fake = indexer.replaceReadyConversationReferences("conversation-corrupt", now = 10)
        assertEquals(ConversationMediaIndexStatus.INCOMPLETE, fake.status)
        assertTrue(database.genMediaDao().getExactV2References("conversation-corrupt").isEmpty())
        assertReferenceJournalState("asset-corrupt", MediaV2Values.JOURNAL_PENDING)

        val corruptions = listOf(
            "UPDATE conversation_migration_journal SET expected_part_count = 999 " +
                "WHERE conversation_id = 'conversation-corrupt'",
            "UPDATE conversation_migration_journal SET v2_projection_digest = 'bad' " +
                "WHERE conversation_id = 'conversation-corrupt'",
            "UPDATE conversation_migration_journal SET lease_owner = 'stale-worker', lease_until = 999 " +
                "WHERE conversation_id = 'conversation-corrupt'",
            "UPDATE ConversationEntity SET active_leaf_message_id = NULL " +
                "WHERE id = 'conversation-corrupt'",
        )
        corruptions.forEachIndexed { index, sql ->
            promoteReady("conversation-corrupt", "message-corrupt", updatedAt = 20L + index)
            database.openHelper.writableDatabase.execSQL(sql)
            val result = indexer.replaceReadyConversationReferences("conversation-corrupt", now = 30L + index)
            assertEquals(ConversationMediaIndexStatus.INCOMPLETE, result.status)
            assertReferenceJournalState("asset-corrupt", MediaV2Values.JOURNAL_PENDING)
        }
    }

    @Test
    fun explicitMissingAssetDoesNotFallBackAndManagedPathMustMatchAssertedAsset() = runBlocking {
        insertAsset("asset-a", "images/a.png")
        insertAsset("asset-b", "images/b.png")
        insertConversation("conversation-strict")
        insertGroup("conversation-strict", "group-strict")
        insertMessage("conversation-strict", "message-strict", "group-strict", siblingOrdinal = 0)
        insertPart(
            "conversation-strict",
            "message-strict",
            "part-missing",
            ordinal = 0,
            part = UIMessagePart.Image("images/a.png", assetId = "asset-missing"),
        )
        insertPart(
            "conversation-strict",
            "message-strict",
            "part-mismatch",
            ordinal = 1,
            part = UIMessagePart.Image("images/b.png", assetId = "asset-a"),
        )
        insertPart(
            "conversation-strict",
            "message-strict",
            "part-invalid-local",
            ordinal = 2,
            part = UIMessagePart.Image("content://provider/image/1", assetId = "asset-a"),
        )
        promoteReady("conversation-strict", "message-strict")

        val result = indexer.replaceReadyConversationReferences("conversation-strict", now = 100)

        assertEquals(ConversationMediaIndexStatus.INCOMPLETE, result.status)
        assertEquals(3, result.unresolvedImages)
        assertTrue(database.genMediaDao().getExactV2References("conversation-strict").isEmpty())
        assertReferenceJournalState("asset-a", MediaV2Values.JOURNAL_PENDING)
        assertReferenceJournalState("asset-b", MediaV2Values.JOURNAL_PENDING)
    }

    @Test
    fun ordinaryLocalUploadIsNotAnAssetButMissingManagedGalleryImageFailsClosed() = runBlocking {
        insertAsset("asset-unrelated", "images/unrelated.png")
        completeReferenceJournal("asset-unrelated", now = 10)
        val upload = filesRoot.resolve("uploads/input.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        insertConversation("conversation-upload")
        insertGroup("conversation-upload", "group-upload")
        insertMessage("conversation-upload", "message-upload", "group-upload", siblingOrdinal = 0)
        insertPart(
            "conversation-upload",
            "message-upload",
            "part-upload",
            part = UIMessagePart.Image(upload.toURI().toString()),
        )
        promoteReady("conversation-upload", "message-upload")

        val uploadResult = indexer.replaceReadyConversationReferences("conversation-upload", now = 20)

        assertEquals(uploadResult.failure, ConversationMediaIndexStatus.COMPLETE, uploadResult.status)
        assertEquals(0, uploadResult.unresolvedImages)
        assertTrue(database.genMediaDao().getExactV2References("conversation-upload").isEmpty())
        assertReferenceJournalState("asset-unrelated", MediaV2Values.JOURNAL_COMPLETE)

        insertConversation("conversation-missing-gallery")
        insertGroup("conversation-missing-gallery", "group-missing-gallery")
        insertMessage(
            "conversation-missing-gallery",
            "message-missing-gallery",
            "group-missing-gallery",
            siblingOrdinal = 0,
        )
        insertPart(
            "conversation-missing-gallery",
            "message-missing-gallery",
            "part-missing-gallery",
            part = UIMessagePart.Image(
                filesRoot.resolve("chat_generated_images/missing.png").toURI().toString(),
            ),
        )
        promoteReady("conversation-missing-gallery", "message-missing-gallery")

        val missingResult = indexer.replaceReadyConversationReferences("conversation-missing-gallery", now = 30)

        assertEquals(ConversationMediaIndexStatus.INCOMPLETE, missingResult.status)
        assertEquals(1, missingResult.unresolvedImages)
        assertReferenceJournalState("asset-unrelated", MediaV2Values.JOURNAL_PENDING)
    }

    @Test
    fun replacingMoreThanOneThousandStaleExactRefsUsesBoundedDeleteBatches() = runBlocking {
        insertAsset("asset-many", "images/many.png")
        insertConversation("conversation-many")
        repeat(1_105) { index ->
            database.genMediaDao().insertMessageRefIgnore(
                MessageMediaRefEntity(
                    refId = "many-$index",
                    ownerKey = "$EXACT_V2_OWNER_PREFIX$index",
                    assetId = "asset-many",
                    conversationId = "conversation-many",
                    messageNodeId = "group-many",
                    messageId = "message-many",
                    partId = "part-$index",
                    createdAt = 1,
                ),
            )
        }

        val result = database.genMediaDao().replaceConversationReferences("conversation-many", emptyList())

        assertEquals(1_105, result.deleted)
        assertTrue(database.genMediaDao().getExactV2References("conversation-many").isEmpty())
    }

    @Test
    fun journalEpochCasRejectsNewOrVersionChangedJournalWithoutCompletingEither() = runBlocking {
        insertAsset("asset-epoch-a", "images/epoch-a.png")
        val epoch = database.genMediaDao().beginConversationMediaReferenceEpoch(100, "scan")
        insertAsset("asset-epoch-b", "images/epoch-b.png")

        assertFalse(database.genMediaDao().completeConversationMediaReferenceEpoch(epoch, 200))
        assertReferenceJournalState("asset-epoch-a", MediaV2Values.JOURNAL_PENDING)
        assertReferenceJournalState("asset-epoch-b", MediaV2Values.JOURNAL_PENDING)

        val secondEpoch = database.genMediaDao().beginConversationMediaReferenceEpoch(300, "scan-2")
        val journal = requireNotNull(
            database.genMediaDao().getJournal("asset", "asset-epoch-a", MediaV2Values.STAGE_REFERENCE_BACKFILL),
        )
        database.genMediaDao().updateJournalState(
            journalId = journal.journalId,
            state = MediaV2Values.JOURNAL_PENDING,
            detail = "raced",
            updatedAt = secondEpoch.updatedAt + 1,
        )
        assertFalse(database.genMediaDao().completeConversationMediaReferenceEpoch(secondEpoch, 400))
        assertReferenceJournalState("asset-epoch-a", MediaV2Values.JOURNAL_PENDING)
        assertReferenceJournalState("asset-epoch-b", MediaV2Values.JOURNAL_PENDING)
    }

    @Test
    fun globalBackfillBlocksUntilEveryConversationIsRealReadyThenCompletesCapturedJournals() = runBlocking {
        insertAsset("asset-global", "images/global.png")
        insertConversation("conversation-ready")
        insertGroup("conversation-ready", "group-ready")
        insertMessage("conversation-ready", "message-ready", "group-ready", siblingOrdinal = 0)
        insertPart(
            "conversation-ready",
            "message-ready",
            "part-ready",
            part = UIMessagePart.Image("https://example.test/global", assetId = "asset-global"),
        )
        promoteReady("conversation-ready", "message-ready")
        insertConversation("conversation-pending")
        database.conversationMigrationDao().upsertJournal(
            ConversationMigrationJournalEntity(
                conversationId = "conversation-pending",
                phase = ConversationV2Values.MIGRATION_PENDING,
                sourceRevision = 0,
                updatedAt = 1,
            ),
        )
        database.conversationMigrationDao().insertQuarantine(
            ConversationMigrationQuarantineEntity(
                quarantineId = "quarantine-pending",
                conversationId = "conversation-pending",
                reasonCode = "MALFORMED_PAYLOAD",
                createdAt = 1,
            ),
        )

        val blocked = indexer.backfillReadyConversations(now = 100, pageSize = 1)
        assertEquals(ConversationMediaBackfillStatus.BLOCKED, blocked.status)
        assertReferenceJournalState("asset-global", MediaV2Values.JOURNAL_PENDING)

        promoteReady("conversation-pending", activeLeafMessageId = null, updatedAt = 200)
        database.conversationMigrationDao().deleteQuarantine("conversation-pending")
        val complete = indexer.backfillReadyConversations(now = 300, pageSize = 1)

        assertEquals(ConversationMediaBackfillStatus.COMPLETE, complete.status)
        assertEquals(2, complete.readyConversations)
        assertReferenceJournalState("asset-global", MediaV2Values.JOURNAL_COMPLETE)
        assertEquals(1, database.genMediaDao().getExactV2References("conversation-ready").size)
    }

    @Test
    fun successfulCompletionLetsGcDeleteOnlyAfterLastExactReferenceIsGone() = runBlocking {
        val asset = insertAsset("asset-gc", "images/gc.png")
        database.genMediaDao().insertMessageRefIgnore(
            exactRef("legacy-gc", "conversation-gc", "asset-gc").copy(ownerKey = "legacy-v1|gc"),
        )
        insertConversation("conversation-gc")
        insertGroup("conversation-gc", "group-gc")
        insertMessage("conversation-gc", "message-gc", "group-gc", siblingOrdinal = 0)
        insertPart(
            "conversation-gc",
            "message-gc",
            "part-gc",
            part = UIMessagePart.Image("https://example.test/gc", assetId = "asset-gc"),
        )
        promoteReady("conversation-gc", "message-gc")
        assertEquals(ConversationMediaBackfillStatus.COMPLETE, indexer.backfillReadyConversations(100).status)
        assertNull(database.genMediaDao().getMessageMediaReferenceById("legacy-gc"))

        assertEquals(MediaAssetDeleteResult.DEFERRED_REFERENCED, database.genMediaDao().delete(asset.id, now = 110))

        replacePartWithText("conversation-gc", "part-gc", "image removed")
        bumpConversationRevision("conversation-gc")
        promoteReady("conversation-gc", "message-gc", updatedAt = 200)
        assertEquals(ConversationMediaBackfillStatus.COMPLETE, indexer.backfillReadyConversations(300).status)
        assertTrue(database.genMediaDao().getExactV2References("conversation-gc").isEmpty())
        assertEquals(MediaAssetDeleteResult.DELETED, database.genMediaDao().delete(asset.id, now = 310))
        assertNull(database.genMediaDao().getByAssetId("asset-gc"))
        assertEquals(
            ConversationMediaBackfillStatus.COMPLETE,
            indexer.backfillReadyConversations(400).status,
        )
    }

    @Test
    fun deletionHelperRemovesConversationOwnedRefsAndBackfillClearsOrphanExactRefs() = runBlocking {
        insertAsset("asset-delete", "images/delete.png")
        insertConversation("conversation-delete")
        database.genMediaDao().insertMessageRefIgnore(exactRef("exact-delete", "conversation-delete", "asset-delete"))
        database.genMediaDao().insertMessageRefIgnore(
            exactRef("legacy-delete", "conversation-delete", "asset-delete").copy(ownerKey = "legacy-v1|delete"),
        )

        assertEquals(2, indexer.deleteConversationExactRefs("conversation-delete"))
        assertNull(database.genMediaDao().getMessageMediaReferenceById("exact-delete"))
        assertNull(database.genMediaDao().getMessageMediaReferenceById("legacy-delete"))
        assertReferenceJournalState("asset-delete", MediaV2Values.JOURNAL_PENDING)

        database.genMediaDao().insertMessageRefIgnore(exactRef("exact-orphan", "missing-conversation", "asset-delete"))
        val result = indexer.backfillReadyConversations(now = 200)

        // conversation-delete has no READY journal, so completion remains blocked; orphan cleanup still commits.
        assertEquals(ConversationMediaBackfillStatus.BLOCKED, result.status)
        assertNull(database.genMediaDao().getMessageMediaReferenceById("exact-orphan"))
        assertNull(database.genMediaDao().getMessageMediaReferenceById("legacy-delete"))
    }

    @Test
    fun mediaDeleteDefersWhenReferenceJournalIsMissing() = runBlocking {
        val asset = database.genMediaDao().insertOrGet(mediaAsset("asset-no-journal", "images/no-journal.png"))

        val result = database.genMediaDao().delete(asset.id, now = 10)

        assertEquals(MediaAssetDeleteResult.DEFERRED_REFERENCED, result)
        val deferred = database.genMediaDao().getByAssetId(asset.assetId)
        assertNotNull(deferred)
        assertEquals(MediaAssetEntity.LIFECYCLE_DELETE_PENDING, deferred?.lifecycle)
        assertFalse(deferred?.visibility == MediaAssetEntity.VISIBILITY_VISIBLE)
    }

    private suspend fun insertAsset(assetId: String, path: String): MediaAssetEntity {
        val asset = database.genMediaDao().insertOrGet(mediaAsset(assetId, path))
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
                detail = "message_scan_required",
                updatedAt = 1,
            ),
        )
        return asset
    }

    private fun mediaAsset(assetId: String, path: String) = MediaAssetEntity(
        path = path,
        modelId = "test-model",
        prompt = "test",
        createAt = 1,
        assetId = assetId,
        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
        updatedAt = 1,
    )

    private suspend fun insertConversation(conversationId: String) {
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
                title = conversationId,
                nodes = "[]",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
                storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
            ),
        )
    }

    private suspend fun insertGroup(conversationId: String, branchGroupId: String) {
        database.conversationGraphDao().insertBranchGroup(
            MessageBranchGroupEntity(
                conversationId = conversationId,
                branchGroupId = branchGroupId,
                createdAt = "2026-08-02T00:00:00Z",
            ),
        )
    }

    private suspend fun insertMessage(
        conversationId: String,
        messageId: String,
        branchGroupId: String,
        siblingOrdinal: Int,
        parentMessageId: String? = null,
    ) {
        database.conversationGraphDao().insertMessages(
            listOf(
                ConversationMessageEntity(
                    conversationId = conversationId,
                    messageId = stableMessageId(messageId),
                    parentMessageId = parentMessageId?.let(::stableMessageId),
                    branchGroupId = branchGroupId,
                    siblingOrdinal = siblingOrdinal,
                    role = "assistant",
                    state = ConversationV2Values.MESSAGE_COMPLETED,
                    createdAt = "2026-08-02T00:00:00",
                    contentDigest = "content-${stableMessageId(messageId)}",
                ),
            ),
        )
    }

    private suspend fun insertPart(
        conversationId: String,
        messageId: String,
        partId: String,
        ordinal: Int = 0,
        part: UIMessagePart,
    ) {
        val payload = JsonInstant.encodeToJsonElement(UIMessagePart.serializer(), part)
        val canonical = payload.toCanonicalJson()
        database.conversationGraphDao().insertParts(
            listOf(
                MessagePartEntity(
                    conversationId = conversationId,
                    partId = partId,
                    messageId = stableMessageId(messageId),
                    ordinal = ordinal,
                    kind = (payload.jsonObject["type"] as JsonPrimitive).content,
                    payloadJson = canonical,
                    payloadDigest = sha256Hex(canonical),
                    assetId = (part as? UIMessagePart.Image)?.assetId,
                    toolInvocationId = (part as? UIMessagePart.Tool)?.toolCallId,
                ),
            ),
        )
    }

    private suspend fun promoteReady(
        conversationId: String,
        activeLeafMessageId: String?,
        updatedAt: Long = 1,
    ) {
        val storedActiveLeafMessageId = activeLeafMessageId?.let(::stableMessageId)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ConversationEntity SET storage_version = 2, active_leaf_message_id = ? WHERE id = ?",
            arrayOf(storedActiveLeafMessageId, conversationId),
        )
        val graphDAO = database.conversationGraphDao()
        val groups = graphDAO.getBranchGroups(conversationId)
        val legacyNodes = groups.mapIndexed { index, group ->
            MessageNodeEntity(
                id = "$conversationId-legacy-$index",
                conversationId = conversationId,
                nodeIndex = index,
                messages = "[]",
                selectIndex = 0,
            )
        }
        database.messageNodeDao().deleteByConversation(conversationId)
        if (legacyNodes.isNotEmpty()) database.messageNodeDao().insertAll(legacyNodes)
        val state = requireNotNull(database.conversationMigrationDao().getConversationState(conversationId))
        val graph = loadConversationV2Graph(graphDAO, conversationId, storedActiveLeafMessageId)
        val graphDigest = digestConversationV2Graph(graph)
        database.conversationMigrationDao().upsertJournal(
            ConversationMigrationJournalEntity(
                conversationId = conversationId,
                phase = ConversationV2Values.MIGRATION_READY,
                sourceRevision = state.revision,
                legacySourceDigest = digestLegacyConversationSource(conversationId, legacyNodes),
                legacyProjectionDigest = graphDigest,
                v2ProjectionDigest = graphDigest,
                nextNodeIndex = graph.groups.size,
                previousSelectedMessageId = storedActiveLeafMessageId,
                expectedGroupCount = graph.groups.size,
                expectedMessageCount = graph.messages.size,
                expectedPartCount = graph.parts.size,
                writtenGroupCount = graph.groups.size,
                writtenMessageCount = graph.messages.size,
                writtenPartCount = graph.parts.size,
                updatedAt = updatedAt,
            ),
        )
    }

    private fun replacePartWithText(conversationId: String, partId: String, text: String) {
        val payload = JsonObject(
            mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(text),
                "metadata" to kotlinx.serialization.json.JsonNull,
            ),
        )
        val canonical = payload.toCanonicalJson()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE message_part SET kind = ?, payload_json = ?, payload_digest = ?, " +
                "asset_id = NULL, tool_invocation_id = NULL, revision = revision + 1 " +
                "WHERE conversation_id = ? AND part_id = ?",
            arrayOf("text", canonical, sha256Hex(canonical), conversationId, partId),
        )
    }

    private fun bumpConversationRevision(conversationId: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ConversationEntity SET revision = revision + 1 WHERE id = ?",
            arrayOf(conversationId),
        )
    }

    private fun exactRef(refId: String, conversationId: String, assetId: String) = MessageMediaRefEntity(
        refId = refId,
        ownerKey = "$EXACT_V2_OWNER_PREFIX$refId",
        assetId = assetId,
        conversationId = conversationId,
        messageNodeId = "group-$refId",
        messageId = "message-$refId",
        partId = "part-$refId",
        createdAt = 1,
    )

    private suspend fun assertReferenceJournalState(assetId: String, expected: String) {
        assertEquals(
            expected,
            database.genMediaDao().getJournal(
                "asset",
                assetId,
                MediaV2Values.STAGE_REFERENCE_BACKFILL,
            )?.state,
        )
    }

    private suspend fun completeReferenceJournal(assetId: String, now: Long) {
        val journal = requireNotNull(
            database.genMediaDao().getJournal(
                "asset",
                assetId,
                MediaV2Values.STAGE_REFERENCE_BACKFILL,
            ),
        )
        database.genMediaDao().updateJournalState(
            journalId = journal.journalId,
            state = MediaV2Values.JOURNAL_COMPLETE,
            detail = null,
            updatedAt = now,
        )
    }

    private fun stableMessageId(alias: String): String = UUID.nameUUIDFromBytes(
        alias.toByteArray(StandardCharsets.UTF_8),
    ).toString()
}

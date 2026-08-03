package me.rerere.rikkahub.data.db.conversation

import androidx.room.withTransaction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.ConversationGraphDAO
import me.rerere.rikkahub.data.db.dao.ConversationMigrationDAO
import me.rerere.rikkahub.data.db.dao.MessageFtsOutboxDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageFtsOutboxEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity
import me.rerere.rikkahub.data.db.media.ConversationMediaReferenceIndexer
import me.rerere.rikkahub.data.db.media.MediaReferenceBackfillScheduler
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationV2Writer internal constructor(
    private val database: AppDatabase,
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val graphDAO: ConversationGraphDAO,
    private val migrationDAO: ConversationMigrationDAO,
    private val ftsOutboxDAO: MessageFtsOutboxDAO,
    private val projector: ConversationV2ShadowProjector,
    private val codec: ConversationV2Codec,
    private val json: Json,
    private val mediaReferenceIndexer: ConversationMediaReferenceIndexer,
    private val mediaReferenceBackfillScheduler: MediaReferenceBackfillScheduler,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun insert(conversation: Conversation): Conversation {
        val encoded = codec.encode(conversation)
        val persisted = database.withTransaction {
            val conversationId = conversation.id.toString()
            val revision = 0L
            conversationDAO.insert(
                conversation.toEntity(
                    revision = revision,
                    storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
                    activeLeafMessageId = encoded.graph.activeLeafMessageId,
                    internalMarker = CONVERSATION_V2_INTERNAL_WRITER_MARKER,
                ),
            )
            replaceLegacyProjection(conversationId, encoded)
            val writtenGraph = reconcileGraph(conversationId, encoded.graph)
            writeReadyJournal(
                conversationId,
                revision,
                encoded,
                writtenGraph,
                previousAttempts = 0,
                previousInferenceFlagsJson = null,
            )
            mediaReferenceIndexer.replaceReadyConversationReferencesInTransaction(
                conversationId = conversationId,
                now = nowMillis(),
            )
            clearInternalMarker(conversationId)
            projector.loadReady(conversationId)
                ?: throw ConversationV2IntegrityException(conversationId, "Inserted READY graph is unavailable")
            conversation.normalized(encoded, revision)
        }
        mediaReferenceBackfillScheduler.requestBackfill()
        return persisted
    }

    suspend fun update(conversation: Conversation): Conversation {
        val encoded = codec.encode(conversation)
        val persisted = database.withTransaction {
            val conversationId = conversation.id.toString()
            val state = migrationDAO.getConversationState(conversationId)
                ?: throw ConversationV2WriteConflictException(
                    conversationId,
                    expectedRevision = conversation.storageRevision,
                    actualRevision = null,
                )
            val journal = migrationDAO.getJournal(conversationId)
            val ready = journal?.phase == ConversationV2Values.MIGRATION_READY
            when (state.storageVersion) {
                ConversationV2Values.STORAGE_VERSION_V2 -> {
                    val readyJournal = journal?.takeIf {
                        it.phase == ConversationV2Values.MIGRATION_READY
                    }
                    if (readyJournal == null) {
                        throw ConversationV2IntegrityException(
                            conversationId,
                            "Cannot update v2 storage without a READY journal",
                        )
                    }
                    projector.loadForState(state, readyJournal)
                    updateReady(conversation, encoded, state.revision, readyJournal)
                }

                ConversationV2Values.STORAGE_VERSION_LEGACY -> {
                    if (ready) {
                        throw ConversationV2IntegrityException(
                            conversationId,
                            "Cannot update legacy storage with a READY journal",
                        )
                    }
                    promoteLegacy(conversation, encoded, state.revision, journal)
                }

                else -> throw ConversationV2IntegrityException(
                    conversationId,
                    "Cannot update unknown storage version ${state.storageVersion}",
                )
            }
        }
        mediaReferenceBackfillScheduler.requestBackfill()
        return persisted
    }

    internal suspend fun patchMetadata(
        conversation: Conversation,
        patch: ConversationMetadataPatch,
    ): Conversation {
        val patched = patch.applyTo(conversation)
        val persisted = database.withTransaction {
            val conversationId = conversation.id.toString()
            val state = migrationDAO.getConversationState(conversationId)
                ?: throw ConversationV2WriteConflictException(
                    conversationId,
                    expectedRevision = conversation.storageRevision,
                    actualRevision = null,
                )
            if (state.revision != conversation.storageRevision) {
                throw ConversationV2WriteConflictException(
                    conversationId,
                    expectedRevision = conversation.storageRevision,
                    actualRevision = state.revision,
                )
            }
            val journal = migrationDAO.getJournal(conversationId)
            when (state.storageVersion) {
                ConversationV2Values.STORAGE_VERSION_V2 -> {
                    val readyJournal = journal?.takeIf {
                        it.phase == ConversationV2Values.MIGRATION_READY
                    } ?: throw ConversationV2IntegrityException(
                        conversationId,
                        "Cannot patch v2 metadata without a READY journal",
                    )
                    projector.loadForState(state, readyJournal)
                    patchReadyMetadata(conversation, patch, state.revision, readyJournal)
                }

                ConversationV2Values.STORAGE_VERSION_LEGACY -> {
                    if (journal?.phase == ConversationV2Values.MIGRATION_READY) {
                        throw ConversationV2IntegrityException(
                            conversationId,
                            "Cannot patch legacy metadata with a READY journal",
                        )
                    }
                    promoteLegacy(
                        patched,
                        codec.encode(patched),
                        state.revision,
                        journal,
                    )
                }

                else -> throw ConversationV2IntegrityException(
                    conversationId,
                    "Cannot patch unknown storage version ${state.storageVersion}",
                )
            }
        }
        mediaReferenceBackfillScheduler.requestBackfill()
        return persisted
    }

    suspend fun delete(conversationId: String): Boolean {
        val deleted = database.withTransaction {
            val state = migrationDAO.getConversationState(conversationId) ?: return@withTransaction false
            val targetRevision = Math.addExact(state.revision, 1L)
            enqueueFtsEvent(
                namespace = "fts-delete",
                conversationId = conversationId,
                targetRevision = targetRevision,
                operation = ConversationV2Values.OUTBOX_DELETE,
            )
            mediaReferenceIndexer.deleteConversationExactRefsInTransaction(conversationId)
            conversationDAO.deleteById(conversationId)
            true
        }
        if (deleted) mediaReferenceBackfillScheduler.requestBackfill()
        return deleted
    }

    private suspend fun updateReady(
        conversation: Conversation,
        encoded: EncodedConversationV2,
        actualRevision: Long,
        previousJournal: ConversationMigrationJournalEntity,
    ): Conversation {
        val conversationId = conversation.id.toString()
        val targetRevision = claimRevision(
            conversationId = conversationId,
            expectedRevision = conversation.storageRevision,
            actualRevision = actualRevision,
            storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
        )
        updateClaimedEntity(
            conversation.toEntity(
                revision = targetRevision,
                storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
                activeLeafMessageId = encoded.graph.activeLeafMessageId,
                internalMarker = CONVERSATION_V2_INTERNAL_WRITER_MARKER,
            ),
        )
        replaceLegacyProjection(conversationId, encoded)
        val writtenGraph = reconcileGraph(conversationId, encoded.graph)
        writeReadyJournal(
            conversationId,
            targetRevision,
            encoded,
            writtenGraph,
            previousJournal.attempts,
            previousJournal.inferenceFlagsJson,
        )
        mediaReferenceIndexer.replaceReadyConversationReferencesInTransaction(
            conversationId = conversationId,
            now = nowMillis(),
        )
        clearInternalMarker(conversationId)
        projector.loadReady(conversationId)
            ?: throw ConversationV2IntegrityException(conversationId, "Updated READY graph is unavailable")
        return conversation.normalized(encoded, targetRevision)
    }

    private suspend fun promoteLegacy(
        conversation: Conversation,
        encoded: EncodedConversationV2,
        actualRevision: Long,
        previousJournal: ConversationMigrationJournalEntity?,
    ): Conversation {
        val conversationId = conversation.id.toString()
        val targetRevision = claimRevision(
            conversationId = conversationId,
            expectedRevision = conversation.storageRevision,
            actualRevision = actualRevision,
            storageVersion = ConversationV2Values.STORAGE_VERSION_LEGACY,
        )
        updateClaimedEntity(
            conversation.toEntity(
                revision = targetRevision,
                storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
                activeLeafMessageId = encoded.graph.activeLeafMessageId,
                internalMarker = CONVERSATION_V2_INTERNAL_WRITER_MARKER,
            ),
        )
        replaceLegacyProjection(conversationId, encoded)
        val writtenGraph = reconcileGraph(conversationId, encoded.graph)
        writeReadyJournal(
            conversationId,
            targetRevision,
            encoded,
            writtenGraph,
            previousJournal?.attempts ?: 0,
            previousJournal?.inferenceFlagsJson,
        )
        mediaReferenceIndexer.replaceReadyConversationReferencesInTransaction(
            conversationId = conversationId,
            now = nowMillis(),
        )
        clearInternalMarker(conversationId)
        projector.loadReady(conversationId)
            ?: throw ConversationV2IntegrityException(conversationId, "Promoted READY graph is unavailable")
        return conversation.normalized(encoded, targetRevision)
    }

    private suspend fun patchReadyMetadata(
        conversation: Conversation,
        patch: ConversationMetadataPatch,
        actualRevision: Long,
        readyJournal: ConversationMigrationJournalEntity,
    ): Conversation {
        val conversationId = conversation.id.toString()
        val currentEntity = conversationDAO.getConversationById(conversationId)
            ?: throw ConversationV2WriteConflictException(
                conversationId,
                expectedRevision = conversation.storageRevision,
                actualRevision = null,
            )
        val updatedEntity = currentEntity.withMetadataPatch(patch)
        if (updatedEntity == currentEntity) {
            projector.loadReady(conversationId)
                ?: throw ConversationV2IntegrityException(conversationId, "Unchanged metadata READY graph is unavailable")
            return conversation.withMetadataFrom(currentEntity)
        }

        val targetRevision = claimRevision(
            conversationId = conversationId,
            expectedRevision = conversation.storageRevision,
            actualRevision = actualRevision,
            storageVersion = ConversationV2Values.STORAGE_VERSION_V2,
        )
        val claimedEntity = updatedEntity.copy(
            revision = targetRevision,
            lastWriterReplicaId = CONVERSATION_V2_INTERNAL_WRITER_MARKER,
        )
        updateClaimedEntity(claimedEntity)
        if (
            migrationDAO.advanceReadyRevision(
                conversationId = conversationId,
                expectedRevision = readyJournal.sourceRevision,
                targetRevision = targetRevision,
                now = nowMillis(),
            ) != 1
        ) {
            throw ConversationV2IntegrityException(
                conversationId,
                "READY journal revision could not advance with metadata",
            )
        }
        if (updatedEntity.title != currentEntity.title) {
            enqueueFtsEvent(
                namespace = "fts-metadata-write",
                conversationId = conversationId,
                targetRevision = targetRevision,
                operation = ConversationV2Values.OUTBOX_UPSERT,
            )
        }
        clearInternalMarker(conversationId)
        projector.loadReady(conversationId)
            ?: throw ConversationV2IntegrityException(conversationId, "Metadata-patched READY graph is unavailable")
        return conversation.withMetadataFrom(claimedEntity)
    }

    private suspend fun claimRevision(
        conversationId: String,
        expectedRevision: Long,
        actualRevision: Long,
        storageVersion: Int,
    ): Long {
        if (actualRevision != expectedRevision) {
            throw ConversationV2WriteConflictException(conversationId, expectedRevision, actualRevision)
        }
        if (
            conversationDAO.claimInternalWrite(
                id = conversationId,
                expectedRevision = expectedRevision,
                expectedStorageVersion = storageVersion,
                marker = CONVERSATION_V2_INTERNAL_WRITER_MARKER,
            ) != 1
        ) {
            val current = migrationDAO.getConversationState(conversationId)
            throw ConversationV2WriteConflictException(
                conversationId,
                expectedRevision,
                current?.revision,
            )
        }
        return Math.addExact(expectedRevision, 1L)
    }

    private suspend fun updateClaimedEntity(entity: ConversationEntity) {
        if (conversationDAO.update(entity) != 1) {
            throw ConversationV2WriteConflictException(entity.id, entity.revision - 1, null)
        }
    }

    private suspend fun replaceLegacyProjection(
        conversationId: String,
        encoded: EncodedConversationV2,
    ) {
        messageNodeDAO.deleteByConversation(conversationId)
        if (encoded.legacyNodes.isNotEmpty()) messageNodeDAO.insertAll(encoded.legacyNodes)
    }

    private suspend fun reconcileGraph(
        conversationId: String,
        candidate: ConversationV2Graph,
    ): ConversationV2Graph {
        val oldGroups = graphDAO.getBranchGroups(conversationId).associateBy { it.branchGroupId }
        val oldMessages = graphDAO.getMessages(conversationId).associateBy { it.messageId }
        val oldParts = graphDAO.getAllParts(conversationId).associateBy { it.partId }

        val partsChangedByMessage = changedPartMessageIds(candidate.parts, oldParts.values)
        val groups = candidate.groups.map { incoming -> incoming.mergeRevision(oldGroups[incoming.branchGroupId]) }
        val messages = candidate.messages.map { incoming ->
            incoming.mergeRevision(
                old = oldMessages[incoming.messageId],
                partsChanged = incoming.messageId in partsChangedByMessage,
            )
        }
        val parts = candidate.parts.map { incoming -> incoming.mergeRevision(oldParts[incoming.partId]) }
        val desiredGroupIds = groups.mapTo(mutableSetOf(), MessageBranchGroupEntity::branchGroupId)
        val desiredMessageIds = messages.mapTo(mutableSetOf(), ConversationMessageEntity::messageId)
        val desiredPartIds = parts.mapTo(mutableSetOf(), MessagePartEntity::partId)
        val removedPartIds = oldParts.keys - desiredPartIds
        val removedMessageIds = oldMessages.keys - desiredMessageIds
        val removedGroupIds = oldGroups.keys - desiredGroupIds

        if (removedPartIds.isNotEmpty()) graphDAO.deletePartsById(conversationId, removedPartIds.toList())
        if (removedMessageIds.isNotEmpty()) {
            graphDAO.clearParentReferences(conversationId, removedMessageIds.toList())
            graphDAO.deleteMessagesById(conversationId, removedMessageIds.toList())
        }
        if (groups.isNotEmpty()) graphDAO.upsertBranchGroups(groups)
        if (messages.isNotEmpty()) graphDAO.upsertMessages(messages)
        if (parts.isNotEmpty()) graphDAO.upsertParts(parts)
        if (removedGroupIds.isNotEmpty()) {
            graphDAO.deleteBranchGroupsById(conversationId, removedGroupIds.toList())
        }

        val written = loadConversationV2Graph(graphDAO, conversationId, candidate.activeLeafMessageId)
        val expected = ConversationV2Graph(candidate.activeLeafMessageId, groups, messages, parts)
        if (digestConversationV2Graph(written) != digestConversationV2Graph(expected)) {
            throw ConversationV2IntegrityException(conversationId, "Incremental graph write did not reconcile")
        }
        return written
    }

    private suspend fun writeReadyJournal(
        conversationId: String,
        revision: Long,
        encoded: EncodedConversationV2,
        graph: ConversationV2Graph,
        previousAttempts: Int,
        previousInferenceFlagsJson: String?,
    ) {
        val digest = digestConversationV2Graph(graph)
        migrationDAO.deleteQuarantine(conversationId)
        migrationDAO.upsertJournal(
            ConversationMigrationJournalEntity(
                conversationId = conversationId,
                phase = ConversationV2Values.MIGRATION_READY,
                sourceRevision = revision,
                legacySourceDigest = encoded.legacySourceDigest,
                legacyProjectionDigest = digest,
                v2ProjectionDigest = digest,
                nextNodeIndex = graph.groups.size,
                previousSelectedMessageId = graph.activeLeafMessageId,
                expectedGroupCount = graph.groups.size,
                expectedMessageCount = graph.messages.size,
                expectedPartCount = graph.parts.size,
                writtenGroupCount = graph.groups.size,
                writtenMessageCount = graph.messages.size,
                writtenPartCount = graph.parts.size,
                inferenceFlagsJson = mergeInferenceFlags(
                    previousInferenceFlagsJson,
                    encoded.inferenceFlags,
                ),
                attempts = previousAttempts,
                updatedAt = nowMillis(),
            ),
        )
        enqueueFtsEvent(
            namespace = "fts-live-write",
            conversationId = conversationId,
            targetRevision = revision,
            operation = ConversationV2Values.OUTBOX_UPSERT,
        )
    }

    private suspend fun enqueueFtsEvent(
        namespace: String,
        conversationId: String,
        targetRevision: Long,
        operation: String,
    ) {
        val now = nowMillis()
        val previousOrder = ftsOutboxDAO.getMaxEventOrder(conversationId)
        val eventOrder = previousOrder?.let { maxOf(now, Math.addExact(it, 1L)) } ?: now
        val inserted = ftsOutboxDAO.enqueue(
            MessageFtsOutboxEntity(
                eventId = deterministicConversationV2Id(
                    namespace,
                    conversationId,
                    eventOrder.toString(),
                    operation,
                ),
                conversationId = conversationId,
                targetRevision = targetRevision,
                operation = operation,
                createdAt = eventOrder,
                updatedAt = eventOrder,
            ),
        )
        check(inserted != -1L) { "Unable to enqueue FTS projection event for $conversationId" }
    }

    private suspend fun clearInternalMarker(conversationId: String) {
        if (
            conversationDAO.clearInternalWriter(
                conversationId,
                CONVERSATION_V2_INTERNAL_WRITER_MARKER,
            ) != 1
        ) {
            throw ConversationV2IntegrityException(conversationId, "Internal writer marker was lost")
        }
    }

    private fun mergeInferenceFlags(previousJson: String?, current: Set<String>): String {
        val previous = previousJson
            ?.let { value -> runCatching { json.decodeFromString<List<String>>(value) }.getOrNull() }
            .orEmpty()
        return json.encodeToString((previous + current).distinct().sorted())
    }

    private fun Conversation.normalized(encoded: EncodedConversationV2, revision: Long): Conversation = copy(
        messageNodes = encoded.normalizedMessageNodes,
        storageRevision = revision,
    )

    private fun Conversation.toEntity(
        revision: Long,
        storageVersion: Int,
        activeLeafMessageId: String?,
        internalMarker: String,
    ) = ConversationEntity(
        id = id.toString(),
        assistantId = assistantId.toString(),
        title = title,
        nodes = "[]",
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
        chatSuggestions = json.encodeToString(chatSuggestions),
        isPinned = isPinned,
        customSystemPrompt = customSystemPrompt.orEmpty(),
        modeInjectionIds = json.encodeToString(modeInjectionIds),
        lorebookIds = json.encodeToString(lorebookIds),
        workspaceCwd = workspaceCwd.orEmpty(),
        folderId = folderId?.toString().orEmpty(),
        revision = revision,
        activeLeafMessageId = activeLeafMessageId,
        storageVersion = storageVersion,
        lastWriterReplicaId = internalMarker,
    )

    private fun ConversationEntity.withMetadataPatch(patch: ConversationMetadataPatch): ConversationEntity = copy(
        assistantId = patch.assistantId.resolveTo(assistantId) { it.toString() },
        title = patch.title.resolveTo(title),
        chatSuggestions = patch.chatSuggestions.resolveTo(chatSuggestions) { json.encodeToString(it) },
        isPinned = patch.isPinned.resolveTo(isPinned),
        customSystemPrompt = patch.customSystemPrompt.resolveTo(customSystemPrompt) { it.orEmpty() },
        modeInjectionIds = patch.modeInjectionIds.resolveTo(modeInjectionIds) { json.encodeToString(it) },
        lorebookIds = patch.lorebookIds.resolveTo(lorebookIds) { json.encodeToString(it) },
        workspaceCwd = patch.workspaceCwd.resolveTo(workspaceCwd) { it.orEmpty() },
        folderId = patch.folderId.resolveTo(folderId) { it?.toString().orEmpty() },
    )

    private fun Conversation.withMetadataFrom(entity: ConversationEntity): Conversation = copy(
        assistantId = Uuid.parse(entity.assistantId),
        title = entity.title,
        chatSuggestions = json.decodeFromString(entity.chatSuggestions),
        isPinned = entity.isPinned,
        createAt = Instant.ofEpochMilli(entity.createAt),
        updateAt = Instant.ofEpochMilli(entity.updateAt),
        customSystemPrompt = entity.customSystemPrompt.ifEmpty { null },
        modeInjectionIds = json.decodeFromString(entity.modeInjectionIds),
        lorebookIds = json.decodeFromString(entity.lorebookIds),
        workspaceCwd = entity.workspaceCwd.ifEmpty { null },
        folderId = entity.folderId.ifEmpty { null }?.let(Uuid::parse),
        storageRevision = entity.revision,
    )

    private fun <T> ConversationMetadataField<T>.resolveTo(current: T): T = when (this) {
        ConversationMetadataField.Keep -> current
        is ConversationMetadataField.Set -> value
    }

    private fun <T, R> ConversationMetadataField<T>.resolveTo(current: R, map: (T) -> R): R = when (this) {
        ConversationMetadataField.Keep -> current
        is ConversationMetadataField.Set -> map(value)
    }
}

class ConversationV2WriteConflictException(
    val conversationId: String,
    val expectedRevision: Long,
    val actualRevision: Long?,
) : IllegalStateException(
    "Conversation $conversationId write conflict: expected revision $expectedRevision, actual $actualRevision",
)

private fun MessageBranchGroupEntity.mergeRevision(old: MessageBranchGroupEntity?): MessageBranchGroupEntity {
    if (old == null) return this
    val withProvenance = copy(
        legacyNodeIndex = old.legacyNodeIndex,
        legacyInferred = old.legacyInferred,
    )
    return if (withProvenance.copy(revision = old.revision) == old) {
        old
    } else {
        withProvenance.copy(revision = Math.addExact(old.revision, 1L))
    }
}

private fun ConversationMessageEntity.mergeRevision(
    old: ConversationMessageEntity?,
    partsChanged: Boolean,
): ConversationMessageEntity {
    if (old == null) return this
    val withProvenance = copy(
        originConversationId = old.originConversationId,
        originMessageId = old.originMessageId,
        legacyMessageId = old.legacyMessageId,
        providerId = old.providerId,
        providerResponseId = old.providerResponseId,
        envelopeExtrasJson = envelopeExtrasJson ?: old.envelopeExtrasJson,
        legacyInferred = old.legacyInferred,
        usageJson = if (usageJson == null && old.usageJson == "null") "null" else usageJson,
    )
    val metadataUnchanged = withProvenance.copy(
        revision = old.revision,
        contentDigest = old.contentDigest,
    ) == old
    return if (metadataUnchanged && !partsChanged) {
        old
    } else {
        withProvenance.copy(revision = Math.addExact(old.revision, 1L))
    }
}

private fun changedPartMessageIds(
    incoming: List<MessagePartEntity>,
    old: Collection<MessagePartEntity>,
): Set<String> {
    val incomingByMessage = incoming.groupBy(MessagePartEntity::messageId)
    val oldByMessage = old.groupBy(MessagePartEntity::messageId)
    return (incomingByMessage.keys + oldByMessage.keys).filterTo(mutableSetOf()) { messageId ->
        val incomingParts = incomingByMessage[messageId].orEmpty()
        val oldParts = oldByMessage[messageId].orEmpty()
        if (incomingParts.size != oldParts.size) {
            true
        } else {
            val oldById = oldParts.associateBy(MessagePartEntity::partId)
            incomingParts.any { part ->
                val previous = oldById[part.partId]
                previous == null || part.copy(revision = previous.revision) != previous
            }
        }
    }
}

private fun MessagePartEntity.mergeRevision(old: MessagePartEntity?): MessagePartEntity = when {
    old == null -> this
    copy(revision = old.revision) == old -> old
    else -> copy(revision = Math.addExact(old.revision, 1L))
}

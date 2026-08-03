package me.rerere.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.conversation.ConversationV2ShadowProjector
import me.rerere.rikkahub.data.db.conversation.ConversationV2Writer
import me.rerere.rikkahub.data.db.conversation.ConversationV2WriteConflictException
import me.rerere.rikkahub.data.db.conversation.ConversationMetadataPatch
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageFtsOutboxProcessor
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
    private val messageFtsOutboxProcessor: MessageFtsOutboxProcessor,
    private val conversationV2Writer: ConversationV2Writer,
    private val conversationV2Projector: ConversationV2ShadowProjector,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        private const val MESSAGE_CHUNK_SIZE = 256 * 1024
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        val conversationIds = conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map(ConversationEntity::id)
        return conversationIds.mapNotNull { conversationId ->
            loadFullConversation(conversationId)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        return loadFullConversation(uuid.toString())
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation): Conversation {
        requireNoBase64(conversation)
        val persisted = conversationV2Writer.insert(conversation)
        messageFtsOutboxProcessor.requestDrain()
        return persisted
    }

    suspend fun updateConversation(conversation: Conversation): Conversation {
        requireNoBase64(conversation)
        val persisted = conversationV2Writer.update(conversation)
        messageFtsOutboxProcessor.requestDrain()
        return persisted
    }

    internal suspend fun deleteConversationById(conversationId: Uuid): Boolean {
        val deleted = conversationV2Writer.delete(conversationId.toString())
        if (deleted) {
            messageFtsOutboxProcessor.requestDrain()
        }
        return deleted
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsOutboxProcessor.rebuildAll(onProgress)
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        requireNoBase64(conversation)
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            revision = conversation.storageRevision,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            storageRevision = conversationEntity.revision,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    internal suspend fun patchConversationMetadata(
        conversation: Conversation,
        patch: ConversationMetadataPatch,
    ): Conversation {
        val persisted = conversationV2Writer.patchMetadata(conversation, patch)
        messageFtsOutboxProcessor.requestDrain()
        return persisted
    }

    /**
     * Revision-aware repair boundary for a single durable tool part. Cold-start recovery must not
     * write a stale whole-conversation snapshot over a concurrently resumed chat session.
     */
    suspend fun updateToolResult(
        conversationId: Uuid,
        requestId: String,
        toolCallId: String,
        transform: (UIMessagePart.Tool) -> UIMessagePart.Tool,
    ): Boolean {
        var lastConflict: ConversationV2WriteConflictException? = null
        repeat(3) {
            val current = getConversationById(conversationId) ?: return false
            var matches = 0
            val updatedNodes = current.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                if (part is UIMessagePart.Tool && part.requestId == requestId &&
                                    part.toolCallId == toolCallId
                                ) {
                                    matches++
                                    transform(part)
                                } else {
                                    part
                                }
                            },
                        )
                    },
                )
            }
            if (matches == 0) return false
            check(matches == 1) { "Tool result identity is not unique in conversation $conversationId" }
            val updated = current.copy(messageNodes = updatedNodes, updateAt = Instant.now())
            if (updatedNodes == current.messageNodes) return true
            try {
                updateConversation(updated)
                return true
            } catch (conflict: ConversationV2WriteConflictException) {
                lastConflict = conflict
            }
        }
        throw checkNotNull(lastConflict)
    }

    suspend fun getConversationIdsInFolder(folderId: Uuid): List<Uuid> =
        conversationDAO.getIdsByFolder(folderId.toString()).map(Uuid::parse)

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            storageRevision = entity.revision,
        )
    }

    private suspend fun loadFullConversation(conversationId: String): Conversation? = database.withTransaction {
        val entity = conversationDAO.getConversationById(conversationId) ?: return@withTransaction null
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()
        val v2Projection = conversationV2Projector.loadReady(conversationId)
        val nodes = (v2Projection?.asLegacyMessageNodes() ?: loadLegacyMessageNodes(conversationId))
            .map { node -> node.copy(isFavorite = node.id in favoriteNodeIds) }
        conversationEntityToConversation(entity, nodes)
    }

    private suspend fun loadLegacyMessageNodes(conversationId: String): List<MessageNode> =
        messageNodeDAO.getNodeHeadersOfConversation(conversationId).map { header ->
            val serializedMessages = readChunkedText(MESSAGE_CHUNK_SIZE) { start, length ->
                messageNodeDAO.getMessagesChunk(header.id, start, length)
            } ?: throw ConversationReadIntegrityException(
                conversationId = conversationId,
                nodeId = header.id,
            )
            val messages = JsonInstant.decodeFromString<List<UIMessage>>(serializedMessages)
            MessageNode(
                id = Uuid.parse(header.id),
                messages = messages,
                selectIndex = header.selectIndex,
            )
        }

    private fun requireNoBase64(conversation: Conversation) {
        require(conversation.messageNodes.none { node -> node.messages.any { it.hasBase64Part() } })
    }
}

internal class ConversationReadIntegrityException(
    conversationId: String,
    nodeId: String,
) : IllegalStateException("Message node $nodeId disappeared while reading conversation $conversationId")

internal suspend fun readChunkedText(
    chunkSize: Int,
    loadChunk: suspend (start: Int, length: Int) -> String?,
): String? {
    require(chunkSize > 0)
    val result = StringBuilder()
    var start = 1 // SQLite substr() is one-based.
    while (true) {
        val chunk = loadChunk(start, chunkSize) ?: return null
        result.append(chunk)
        // SQLite substr(TEXT, start, length) counts Unicode code points, while
        // String.length counts UTF-16 code units. Advancing by String.length
        // skips characters whenever a chunk contains supplementary code points.
        val consumedCodePoints = chunk.codePointCount(0, chunk.length)
        check(consumedCodePoints <= chunkSize) {
            "Chunk loader returned $consumedCodePoints code points for a $chunkSize-code-point request"
        }
        if (consumedCodePoints < chunkSize) return result.toString()
        start = Math.addExact(start, consumedCodePoints)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
    val revision: Long = 0,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

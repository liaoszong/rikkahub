package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.model.ModelFeature
import me.rerere.ai.model.effectiveCapabilitySnapshot
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.db.conversation.ConversationMetadataField
import me.rerere.rikkahub.data.db.conversation.ConversationMetadataPatch
import me.rerere.rikkahub.data.db.conversation.ConversationV2WriteConflictException
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.resolveBackgroundTextModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.media.MediaAssetMaterializer
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.privacy.AgentNetworkPolicy
import me.rerere.rikkahub.fork.pale.request.ChatGenerationLedgerContext
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

private fun logChatError(operation: String, error: Throwable) {
    Logging.logErrorToLogcat(
        tag = TAG,
        domain = "chat",
        operation = operation,
        error = error,
    )
}

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal object ChatGenerationForegroundPolicy {
    fun requiresForSend(answer: Boolean): Boolean = answer

    fun requiresForRegeneration(
        messageRole: MessageRole,
        regenerateAssistantMessage: Boolean,
    ): Boolean = messageRole == MessageRole.USER || regenerateAssistantMessage
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

class ReversibleConversationDeletion internal constructor(
    val conversation: Conversation,
    internal val files: List<Uri>,
    internal val token: Uuid = Uuid.random(),
)

private data class ConversationDeletionOutcome(
    val conversation: Conversation?,
    val files: List<Uri>,
    val deletedFromStore: Boolean,
)

internal fun Conversation.withToolApproval(
    requestId: String,
    toolCallId: String,
    approvalState: ToolApprovalState,
): Conversation {
    val matchingParts = messageNodes.flatMap { node ->
        node.messages.flatMap { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>()
        }
    }.filter { tool ->
        tool.toolCallId == toolCallId && tool.requestId == requestId
    }
    check(matchingParts.size == 1) {
        "Tool approval identity must resolve exactly one invocation: " +
            "requestId=$requestId toolCallId=$toolCallId matches=${matchingParts.size}"
    }
    check(matchingParts.single().isPending) { "Tool invocation is no longer awaiting approval" }
    return copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            if (part is UIMessagePart.Tool &&
                                part.requestId == requestId &&
                                part.toolCallId == toolCallId
                            ) {
                                part.copy(approvalState = approvalState)
                            } else {
                                part
                            }
                        },
                    )
                },
            )
        },
    )
}

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val mediaAssetMaterializer: MediaAssetMaterializer,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val generationForegroundController: ChatGenerationForegroundController,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val sessionRegistryLock = Any()
    private val folderMutationMutex = Mutex()
    private val conversationDeletionMutex = Mutex()
    private val unavailableConversationIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val pendingConversationDeletions = ConcurrentHashMap<Uuid, ReversibleConversationDeletion>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        synchronized(sessionRegistryLock) {
            if (conversationId in unavailableConversationIds) {
                throw ConversationDeletedException(conversationId)
            }
            sessions[conversationId]?.let { existing ->
                if (!existing.isClosed) return@synchronized existing
                sessions.remove(conversationId, existing)
                existing.cleanup()
                _sessionsVersion.update { version -> version + 1L }
            }

            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(
                    id = conversationId,
                    assistantId = settings.getCurrentAssistant().id,
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
            ).also { created ->
                sessions[conversationId] = created
                _sessionsVersion.update { version -> version + 1L }
                Log.i(TAG, "createSession: $conversationId (total: ${sessions.size})")
            }
        }

    private fun discardSession(session: ConversationSession): Boolean {
        val removed = synchronized(sessionRegistryLock) {
            sessions.remove(session.id, session)
        }
        if (!removed) return false
        session.cleanup()
        _sessionsVersion.update { version -> version + 1L }
        return true
    }

    private fun acquireGenerationSession(conversationId: Uuid): ConversationGenerationLease {
        while (true) {
            val session = getOrCreateSession(conversationId)
            session.tryAcquireGeneration(logChange = false)?.let { return it }
            if (conversationId in unavailableConversationIds) throw ConversationDeletedException(conversationId)
            if (session.isClosed) {
                discardSession(session)
                continue
            }
            throw ConversationDeletedException(conversationId)
        }
    }

    private fun acquireSession(conversationId: Uuid, logChange: Boolean = true): ConversationSession {
        while (true) {
            val session = getOrCreateSession(conversationId)
            if (session.tryAcquire(logChange)) return session
            discardSession(session)
        }
    }

    private suspend fun <T> withConversationPersistenceLock(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T {
        while (true) {
            val session = getOrCreateSession(conversationId)
            try {
                return session.withPersistenceLock { block(session) }
            } catch (_: ConversationSessionClosedException) {
                discardSession(session)
            }
        }
    }

    private fun removeSession(session: ConversationSession) {
        val conversationId = session.id
        if (sessions[conversationId] !== session) return
        if (!session.tryCloseIfIdle()) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (discardSession(session)) {
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun acquireConversationReference(conversationId: Uuid): ConversationSessionLease =
        ConversationSessionLease(acquireSession(conversationId))

    private fun launchForegroundMetadataGeneration(
        conversationId: Uuid,
        senderName: String,
        block: suspend () -> Unit
    ): Job {
        val foregroundLeaseReady = CompletableDeferred<ChatGenerationForegroundLease>()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            val conversationLease = acquireConversationReference(conversationId)
            val foregroundLease = foregroundLeaseReady.await()
            try {
                foregroundLease.awaitReady()
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                addError(error, conversationId, title = context.getString(R.string.error_title_generation))
            } finally {
                conversationLease.close()
            }
        }
        val foregroundLease = try {
            generationForegroundController.start(
                conversationId = conversationId,
                senderName = senderName,
                cancelExecution = {
                    job.cancel(CancellationException("Cancelled metadata generation from the notification"))
                },
            )
        } catch (error: Throwable) {
            job.cancel()
            addError(error, conversationId, title = context.getString(R.string.error_title_generation))
            return job
        }
        job.invokeOnCompletion { foregroundLease.close() }
        foregroundLeaseReady.complete(foregroundLease)
        job.start()
        return job
    }

    private fun launchReplacingGenerationJob(
        conversationId: Uuid,
        requiresForeground: Boolean = true,
        block: suspend (
            previousJob: Job?,
            foregroundLease: ChatGenerationForegroundLease?,
        ) -> Unit,
    ): Job {
        val generationLease = try {
            acquireGenerationSession(conversationId)
        } catch (_: ConversationDeletedException) {
            return Job().also { it.cancel() }
        }
        val replacedJob = CompletableDeferred<Job?>()
        val foregroundLeaseReady = CompletableDeferred<ChatGenerationForegroundLease?>()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            block(replacedJob.await(), foregroundLeaseReady.await())
        }
        val foregroundLease = if (requiresForeground) {
            try {
                generationForegroundController.start(
                    conversationId = conversationId,
                    senderName = resolveGenerationSenderName(conversationId),
                    cancelExecution = {
                        job.cancel(CancellationException("Cancelled from the chat generation notification"))
                    },
                )
            } catch (error: Throwable) {
                job.cancel()
                generationLease.close()
                addError(error, conversationId, title = context.getString(R.string.error_title_generation))
                return job
            }
        } else {
            null
        }
        foregroundLease?.let { lease ->
            job.invokeOnCompletion { lease.close() }
        }
        foregroundLeaseReady.complete(foregroundLease)
        try {
            replacedJob.complete(generationLease.attach(job))
            job.start()
            return job
        } catch (_: ConversationGenerationRejectedException) {
            job.cancel()
            return job
        } catch (error: Throwable) {
            job.cancel()
            throw error
        } finally {
            generationLease.close()
        }
    }

    private fun resolveGenerationSenderName(conversationId: Uuid): String {
        val settings = settingsStore.settingsFlow.value
        val conversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        if (assistant.useAssistantAvatar) {
            return assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        }
        return settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.displayName
            ?: assistant.name.ifEmpty { context.getString(R.string.notification_live_update_title) }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationDeletedFlow(conversationId: Uuid): StateFlow<Boolean> {
        val session = sessions[conversationId] ?: return MutableStateFlow(false)
        return session.deleted
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val assistantId = withConversationPersistenceLock(conversationId) { session ->
            if (session.hasDurableStateLoaded()) return@withConversationPersistenceLock session.state.value.assistantId
            val persisted = conversationRepo.getConversationById(conversationId)
            val initialized = if (persisted != null) {
                persisted
            } else {
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true,
                ).updateCurrentMessages(assistant.presetMessages)
            }
            updateConversationLocked(session, initialized, durable = true)
            initialized.assistantId
        }
        settingsStore.updateAssistant(assistantId)
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        launchReplacingGenerationJob(
            conversationId = conversationId,
            requiresForeground = ChatGenerationForegroundPolicy.requiresForSend(answer),
        ) { previousJob, foregroundLease ->
            try {
                if (answer) checkNotNull(foregroundLease).awaitReady()
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                var supersededFiles = emptyList<String>()
                mutateAndSaveConversation(conversationId) { current ->
                    val assistant = settings.getAssistantById(current.assistantId)
                        ?: settings.getCurrentAssistant()
                    val processedContent = preprocessUserInputParts(content, assistant)
                    val node = UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode()
                    val materialized = mediaAssetMaterializer.materializeMessage(
                        conversationId = conversationId.toString(),
                        messageNodeId = node.id.toString(),
                        message = node.currentMessage,
                    )
                    supersededFiles = materialized.supersededFiles
                    current.copy(
                        messageNodes = current.messageNodes + node.copy(
                            messages = listOf(materialized.message),
                        ),
                    )
                }
                filesManager.deleteChatFiles(supersededFiles.map(String::toUri))

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                logChatError(operation = "send_message", error = e)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val requiresForeground = ChatGenerationForegroundPolicy.requiresForRegeneration(
            messageRole = message.role,
            regenerateAssistantMessage = regenerateAssistantMsg,
        )
        launchReplacingGenerationJob(
            conversationId = conversationId,
            requiresForeground = requiresForeground,
        ) { previousJob, foregroundLease ->
            try {
                if (requiresForeground) checkNotNull(foregroundLease).awaitReady()
                runCatching { previousJob?.join() }
                if (message.role == MessageRole.USER) {
                    mutateAndSaveConversation(conversationId) { current ->
                        val indexAt = current.messageNodes.indexOfFirst { node ->
                            node.messages.any { it.id == message.id }
                        }
                        if (indexAt < 0) throw NotFoundException("Message not found")
                        current.copy(messageNodes = current.messageNodes.subList(0, indexAt + 1))
                    }
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val conversation = getConversationFlow(conversationId).value
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        persistCurrentConversation(conversationId)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        requestId: String,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        launchReplacingGenerationJob(conversationId) { previousJob, foregroundLease ->
            try {
                checkNotNull(foregroundLease).awaitReady()
                runCatching { previousJob?.join() }
                val newApprovalState = when {
                    answer != null -> {
                        val target = getConversationFlow(conversationId).value.messageNodes
                            .asSequence()
                            .flatMap { it.currentMessage.parts.asSequence() }
                            .filterIsInstance<UIMessagePart.Tool>()
                            .singleOrNull { it.requestId == requestId && it.toolCallId == toolCallId }
                            ?: error("Tool approval target not found")
                        require(target.toolName == "ask_user") {
                            "Only ask_user accepts an answered approval"
                        }
                        ToolApprovalState.Answered(answer)
                    }
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                val updatedConversation = mutateAndSaveConversation(conversationId) { current ->
                    current.withToolApproval(requestId, toolCallId, newApprovalState)
                }

                // Check if there are still pending tools
                val hasPendingTools = updatedConversation.messageNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateChatSuggestions(conversationId, emptyList())

            // memory tool
            val modelProvider = model.findProvider(settings.providers)
            if (ModelFeature.TOOL_CALLING !in
                model.effectiveCapabilitySnapshot(modelProvider).features) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val prepared = prepareResponseTarget(conversationId, messageRange)
            val conversation = prepared.conversation

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = prepared.messages,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                toolExecutionContextId = conversationId.toString(),
                memories = if (!settings.agentPrivacyPolicy.memoryEnabled) {
                    emptyList()
                } else if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch && settings.agentPrivacyPolicy.networkEnabled &&
                        !settings.agentPrivacyPolicy.localOnly
                    ) {
                        addAll(createSearchTools(settings, context, conversationId.toString()))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    val credentialRefId = mcpManager.prepareToolCredentialEvidence(serverId)
                                    mcpManager.callTool(
                                        serverId = serverId,
                                        toolName = tool.name,
                                        args = it.jsonObject,
                                        expectedCredentialRefId = credentialRefId,
                                    )
                                },
                                executeWithContext = { args, executionContext ->
                                    mcpManager.callTool(
                                        serverId = serverId,
                                        toolName = tool.name,
                                        args = args.jsonObject,
                                        expectedCredentialRefId = executionContext.credentialRefId,
                                    )
                                },
                                ledgerAuthorityId = serverId.toString(),
                                ledgerCredentialRefResolver = {
                                    mcpManager.prepareToolCredentialEvidence(serverId)
                                },
                                ledgerSideEffectClass = "unknown",
                            )
                        )
                    }
                },
                ledgerContext = ChatGenerationLedgerContext(
                    conversationId = conversationId.toString(),
                    assistantId = assistant.id.toString(),
                    responseMessageId = prepared.responseMessage.id.toString(),
                    workspaceId = assistant.workspaceId?.toString(),
                    persistCurrentConversation = {
                        persistCurrentConversation(conversationId)
                    },
                    persistMessages = { durableMessages ->
                        mutateAndSaveConversation(conversationId) { current ->
                            current.updateCurrentMessages(durableMessages)
                        }
                    },
                    loadResponseMessage = {
                        getConversationFlow(conversationId).value
                            .getMessageNodeByMessageId(prepared.responseMessage.id)
                            ?.messages
                            ?.firstOrNull { it.id == prepared.responseMessage.id }
                    },
                ),
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = updateConversationState(conversationId) { current ->
                    current.copy(
                        messageNodes = current.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now(),
                    )
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        updateConversationState(conversationId) { current ->
                            current.updateCurrentMessages(chunk.messages)
                        }

                        // Image generation emits only a small number of slot transitions. Persist
                        // them immediately so switching screens or process recreation never turns
                        // the gallery back into an empty tool card.
                        val hasImageGenerationProgress = chunk.messages.lastOrNull()?.parts?.any { part ->
                            part is UIMessagePart.Tool &&
                                part.toolName == "generate_image" &&
                                part.progress.isNotEmpty()
                        } == true
                        if (hasImageGenerationProgress) {
                            persistCurrentConversation(conversationId)
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            // Cancellation has already been mapped into RequestLedger by the provider-step
            // coordinator. Preserve structured cancellation instead of reporting normal success.
            if (it is CancellationException) throw it

            logChatError(operation = "complete_generation", error = it)
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
        }.onSuccess {
            val finalConversation = materializeAndPersistCurrentConversation(conversationId)

            launchForegroundMetadataGeneration(conversationId, senderName) {
                coroutineScope {
                    launch { generateTitle(conversationId, finalConversation) }
                    launch { generateSuggestion(conversationId, finalConversation) }
                }
            }
        }
    }

    private data class PreparedResponseTarget(
        val conversation: Conversation,
        val messages: List<UIMessage>,
        val responseMessage: UIMessage,
    )

    /**
     * Persists the assistant branch identity before a potentially billable provider handoff.
     * Tool-approval resumes reuse their existing assistant message; fresh sends/regenerations get
     * a new stable alternative so the ledger never depends on a transient streaming chunk ID.
     */
    private suspend fun prepareResponseTarget(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>?,
    ): PreparedResponseTarget {
        if (messageRange != null) {
            require(messageRange.start == 0) { "Regeneration ranges must start at the conversation root" }
        }
        val current = getConversationFlow(conversationId).value
        val resumableResponse = if (messageRange == null) {
            current.currentMessages.lastOrNull()?.takeIf { message ->
                message.role == MessageRole.ASSISTANT && message.getTools().any { it.canResumeExecution }
            }
        } else {
            null
        }
        val responseMessage = resumableResponse ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
        )
        val preparedConversation = if (resumableResponse != null) {
            current
        } else {
            mutateAndSaveConversation(conversationId) { conversation ->
                if (messageRange == null) {
                    conversation.copy(
                        messageNodes = conversation.messageNodes + responseMessage.toMessageNode(),
                    )
                } else {
                    val targetIndex = messageRange.endInclusive + 1
                    val targetNode = conversation.messageNodes.getOrNull(targetIndex)
                        ?: error("Regeneration target node $targetIndex no longer exists")
                    check(targetNode.role == MessageRole.ASSISTANT) {
                        "Regeneration target must be an assistant message"
                    }
                    val alternatives = targetNode.messages + responseMessage
                    conversation.copy(
                        messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                            if (index == targetIndex) {
                                node.copy(messages = alternatives, selectIndex = alternatives.lastIndex)
                            } else {
                                node
                            }
                        },
                    )
                }
            }
        }
        val messages = if (messageRange == null) {
            preparedConversation.currentMessages
        } else {
            preparedConversation.currentMessages.subList(0, messageRange.endInclusive + 2)
        }
        check(messages.last().id == responseMessage.id) {
            "Prepared response branch is not the generation target"
        }
        return PreparedResponseTarget(preparedConversation, messages, responseMessage)
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        updateConversationState(conversationId) { conversation ->
            var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

            conversation.copy(messageNodes = messagesNodes)
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
            executionState = ToolExecutionState.INTERRUPTED,
            requestId = tool.requestId.ifBlank { Uuid.random().toString() },
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        mutateAndSaveConversation(conversationId) { current ->
            val lastNode = current.messageNodes.lastOrNull() ?: return@mutateAndSaveConversation current
            val lastMessage = lastNode.currentMessage
            val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
            if (updatedMessage == lastMessage) return@mutateAndSaveConversation current
            current.copy(
                messageNodes = current.messageNodes.dropLast(1) + lastNode.copy(
                    messages = lastNode.messages.map { message ->
                        if (message.id == lastMessage.id) updatedMessage else message
                    },
                ),
            )
        }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return
        val expectedTitle = conversation.title
        val graphAnchor = conversation.currentMessages.lastOrNull()?.id

        runCatching {
            settingsStore.awaitCredentialReady()
            val settings = settingsStore.settingsFlow.first()
            val model = settings.resolveBackgroundTextModel(
                preferredId = settings.titleModelId,
                fallbackId = settings.fastModelId,
            ) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            AgentNetworkPolicy.requireProviderAllowed(provider, settings.agentPrivacyPolicy)

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            patchConversationMetadataIf(
                conversationId = conversationId,
                predicate = { current ->
                    current.title == expectedTitle && current.currentMessages.lastOrNull()?.id == graphAnchor
                },
            ) {
                ConversationMetadataPatch(
                    title = ConversationMetadataField.Set(
                        result.choices[0].message?.toText()?.trim().orEmpty(),
                    ),
                )
            }
        }.onFailure {
            if (it is CancellationException) throw it
            logChatError(operation = "generate_title", error = it)
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        val graphAnchor = conversation.currentMessages.lastOrNull()?.id
        runCatching {
            settingsStore.awaitCredentialReady()
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.resolveBackgroundTextModel(
                preferredId = settings.suggestionModelId,
                fallbackId = settings.fastModelId,
            ) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            AgentNetworkPolicy.requireProviderAllowed(provider, settings.agentPrivacyPolicy)

            val accepted = patchConversationMetadataIf(
                conversationId = conversationId,
                predicate = { current -> current.currentMessages.lastOrNull()?.id == graphAnchor },
            ) {
                ConversationMetadataPatch(
                    chatSuggestions = ConversationMetadataField.Set(emptyList()),
                )
            }
            if (accepted == null) return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            patchConversationMetadataIf(
                conversationId = conversationId,
                predicate = { current ->
                    current.currentMessages.lastOrNull()?.id == graphAnchor &&
                        current.chatSuggestions.isEmpty()
                },
            ) {
                ConversationMetadataPatch(
                    chatSuggestions = ConversationMetadataField.Set(suggestions.take(10)),
                )
            }
        }.onFailure {
            if (it is CancellationException) throw it
            logChatError(operation = "generate_suggestions", error = it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        settingsStore.awaitCredentialReady()
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        AgentNetworkPolicy.requireProviderAllowed(provider, settings.agentPrivacyPolicy)

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val chunks = splitMessages(messagesToCompress)
        val compressedSummaries = coroutineScope {
            chunks.map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Append rebuildable summary projections. Original nodes and branches remain authoritative.
        val summaryNodes = compressedSummaries.zip(chunks).mapIndexed { index, (summary, sources) ->
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(
                        text = summary,
                        metadata = buildJsonObject {
                            put("context_provenance", "structured_compaction")
                            put("summary_revision", 1)
                            put("summary_chunk", index)
                            put("source_refs", JsonArray(sources.map { JsonPrimitive("message:${it.id}") }))
                        },
                    )
                ),
            ).toMessageNode()
        }
        mutateAndSaveConversation(conversationId) { current ->
            check(current.messageNodes == conversation.messageNodes) {
                "Conversation changed while history compression was running"
            }
            current.copy(
                messageNodes = current.messageNodes + summaryNodes,
                chatSuggestions = emptyList(),
            )
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversationLocked(
        session: ConversationSession,
        conversation: Conversation,
        durable: Boolean = false,
    ): Conversation {
        require(conversation.id == session.id)
        val previous = session.state.value
        check(conversation.storageRevision >= previous.storageRevision) {
            "Refusing stale conversation state for ${session.id}: " +
                "incoming=${conversation.storageRevision}, current=${previous.storageRevision}"
        }
        session.state.value = conversation
        if (session.hasDurableStateLoaded()) checkFilesDelete(conversation, previous)
        if (durable) session.markDurableStateLoaded()
        return conversation
    }

    internal suspend fun updateConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation = withConversationPersistenceLock(conversationId) { session ->
        val current = currentConversationForMutationLocked(session)
        val updated = update(current)
        require(updated.id == conversationId) { "Conversation state update changed its id" }
        session.state.value = updated
        checkFilesDelete(updated, current)
        updated
    }

    suspend fun updateConversationTitle(conversationId: Uuid, title: String): Conversation =
        patchConversationMetadata(conversationId) {
            ConversationMetadataPatch(
                title = ConversationMetadataField.Set(title),
            )
        }

    private suspend fun updateChatSuggestions(
        conversationId: Uuid,
        suggestions: List<String>,
    ): Conversation = patchConversationMetadata(conversationId) {
        ConversationMetadataPatch(
            chatSuggestions = ConversationMetadataField.Set(suggestions),
        )
    }

    suspend fun toggleConversationPin(conversationId: Uuid): Conversation =
        patchConversationMetadata(conversationId) { current ->
            ConversationMetadataPatch(
                isPinned = ConversationMetadataField.Set(!current.isPinned),
            )
        }

    suspend fun moveConversationToAssistant(
        conversationId: Uuid,
        targetAssistantId: Uuid,
    ): Conversation = folderMutationMutex.withLock {
        patchConversationMetadata(conversationId) {
            ConversationMetadataPatch(
                assistantId = ConversationMetadataField.Set(targetAssistantId),
                folderId = ConversationMetadataField.Set(null),
            )
        }
    }

    suspend fun updateConversationInjections(
        conversationId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ): Conversation = patchConversationMetadata(conversationId) {
        ConversationMetadataPatch(
            modeInjectionIds = ConversationMetadataField.Set(modeInjectionIds),
            lorebookIds = ConversationMetadataField.Set(lorebookIds),
        )
    }

    /**
     * Converts an editor snapshot into a field-scoped metadata command. The snapshots are used
     * only to identify the fields the editor changed; messageNodes and unrelated metadata are
     * never copied back into the durable conversation.
     */
    suspend fun updateConversationContextMetadata(
        conversationId: Uuid,
        baseline: Conversation,
        edited: Conversation,
    ): Conversation {
        require(baseline.id == conversationId && edited.id == conversationId)
        return patchConversationMetadata(conversationId) {
            ConversationMetadataPatch(
                customSystemPrompt = baseline.customSystemPrompt.changedTo(
                    edited.customSystemPrompt,
                ),
                modeInjectionIds = baseline.modeInjectionIds.changedTo(edited.modeInjectionIds),
                lorebookIds = baseline.lorebookIds.changedTo(edited.lorebookIds),
                workspaceCwd = baseline.workspaceCwd.changedTo(edited.workspaceCwd),
            )
        }
    }

    private fun <T> T.changedTo(edited: T): ConversationMetadataField<T> =
        if (this == edited) ConversationMetadataField.Keep else ConversationMetadataField.Set(edited)

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 文件夹更新与正文保存共用会话持久化锁，并通过 metadata CAS 推进 revision，
     * 因此不会被活跃生成持有的旧 Conversation 快照写回覆盖。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        folderMutationMutex.withLock {
            val folder = folderId?.let { targetId ->
                folderRepository.getFolderById(targetId)
                    ?: throw NotFoundException("Folder not found")
            }
            patchConversationMetadata(conversationId) { current ->
                if (folder != null && folder.assistantId != current.assistantId) {
                    throw BadRequestException("Folder belongs to another assistant")
                }
                ConversationMetadataPatch(
                    folderId = ConversationMetadataField.Set(folderId),
                )
            }
        }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 与移动操作共用 folderMutationMutex；先逐会话执行 revision-aware metadata patch，
     * 全部成功后才删除文件夹记录。中途失败会保留文件夹，不产生悬空 folderId。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        folderMutationMutex.withLock {
            conversationRepo.getConversationIdsInFolder(folderId).forEach { conversationId ->
                try {
                    patchConversationMetadata(conversationId) {
                        ConversationMetadataPatch(
                            folderId = ConversationMetadataField.Set(null),
                        )
                    }
                } catch (_: ConversationDeletedException) {
                    // A concurrent centralized delete already owns this id.
                } catch (error: ConversationV2WriteConflictException) {
                    // The row can disappear after getIdsByFolder() but before its session lock is acquired.
                    if (conversationRepo.existsConversationById(conversationId)) throw error
                }
            }
            folderRepository.deleteFolder(folderId)
        }
    }

    suspend fun deleteConversation(conversationId: Uuid): Boolean = conversationDeletionMutex.withLock {
        deleteConversationLocked(conversationId)
    }

    private suspend fun deleteConversationLocked(conversationId: Uuid): Boolean {
        pendingConversationDeletions[conversationId]?.let { pending ->
            return finalizePendingDeletionLocked(conversationId, pending)
        }
        if (conversationId in unavailableConversationIds) return false
        val outcome = performConversationDeletionLocked(
            conversationId = conversationId,
            preserveSessionForUndo = false,
        ) ?: return false
        filesManager.deleteChatFiles(outcome.files)
        return outcome.deletedFromStore || outcome.conversation != null
    }

    suspend fun deleteConversationReversibly(
        conversationId: Uuid,
    ): ReversibleConversationDeletion? = conversationDeletionMutex.withLock {
        pendingConversationDeletions[conversationId]?.let { return@withLock it }
        if (conversationId in unavailableConversationIds) return@withLock null
        val outcome = performConversationDeletionLocked(
            conversationId = conversationId,
            preserveSessionForUndo = true,
        ) ?: return@withLock null
        val conversation = outcome.conversation ?: run {
            filesManager.deleteChatFiles(outcome.files)
            return@withLock null
        }
        val deletion = ReversibleConversationDeletion(
            conversation = conversation,
            files = outcome.files,
        )
        pendingConversationDeletions[conversationId] = deletion
        deletion
    }

    suspend fun finalizeDeletedConversation(deletion: ReversibleConversationDeletion): Boolean =
        conversationDeletionMutex.withLock {
            val conversationId = deletion.conversation.id
            val pending = pendingConversationDeletions[conversationId]
                ?: return@withLock false
            if (pending.token != deletion.token) return@withLock false
            finalizePendingDeletionLocked(conversationId, pending)
        }

    suspend fun restoreDeletedConversation(
        deletion: ReversibleConversationDeletion,
    ): Conversation? = conversationDeletionMutex.withLock {
        val conversationId = deletion.conversation.id
        val pending = pendingConversationDeletions[conversationId]
        if (pending?.token != deletion.token) return@withLock null
        if (conversationRepo.existsConversationById(conversationId)) return@withLock null
        val restored = folderMutationMutex.withLock {
            val snapshot = deletion.conversation
            val settings = settingsStore.settingsFlow.value
            val assistantId = settings.getAssistantById(snapshot.assistantId)?.id
                ?: settings.getCurrentAssistant().id
            val folder = snapshot.folderId
                ?.let { folderId -> folderRepository.getFolderById(folderId) }
                ?.takeIf { candidate -> candidate.assistantId == assistantId }
            val normalizedSnapshot = snapshot.copy(
                assistantId = assistantId,
                folderId = folder?.id,
            )
            val retainedSession = sessions[conversationId]?.takeUnless { it.isClosed }
            if (retainedSession == null) {
                conversationRepo.insertConversation(normalizedSnapshot)
            } else {
                try {
                    retainedSession.withPersistenceLock {
                        conversationRepo.insertConversation(normalizedSnapshot).also { persisted ->
                            // Undo starts a new durable incarnation whose revision restarts at zero.
                            retainedSession.state.value = persisted
                            retainedSession.markDurableStateLoaded()
                            retainedSession.markRestored()
                            retainedSession.resumeGenerationJobs()
                        }
                    }
                } catch (_: ConversationSessionClosedException) {
                    discardSession(retainedSession)
                    conversationRepo.insertConversation(normalizedSnapshot)
                }
            }
        }
        pendingConversationDeletions.remove(conversationId, pending)
        synchronized(sessionRegistryLock) {
            unavailableConversationIds.remove(conversationId)
        }
        restored
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        conversationDeletionMutex.withLock {
            val ids = buildSet {
                addAll(conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id })
                addAll(
                    sessions.values
                        .filter { it.state.value.assistantId == assistantId }
                        .map { it.id },
                )
                addAll(
                    pendingConversationDeletions.values
                        .filter { it.conversation.assistantId == assistantId }
                        .map { it.conversation.id },
                )
            }
            ids.forEach { deleteConversationLocked(it) }
        }
    }

    private suspend fun performConversationDeletionLocked(
        conversationId: Uuid,
        preserveSessionForUndo: Boolean,
    ): ConversationDeletionOutcome? {
        val session = synchronized(sessionRegistryLock) {
            check(conversationId !in unavailableConversationIds)
            unavailableConversationIds.add(conversationId)
            sessions[conversationId]
        }
        return try {
            withContext(NonCancellable) {
                session?.blockGenerationJobs()?.join()

                val outcome = if (session != null && !session.isClosed) {
                    try {
                        session.withPersistenceLock {
                            collectAndDeleteConversation(conversationId, session).also {
                                session.markDeleted()
                                if (!preserveSessionForUndo) discardSession(session)
                            }
                        }
                    } catch (_: ConversationSessionClosedException) {
                        collectAndDeleteConversation(conversationId, session).also {
                            discardSession(session)
                        }
                    }
                } else {
                    collectAndDeleteConversation(conversationId, session).also {
                        if (session != null) discardSession(session)
                    }
                }

                if (!outcome.deletedFromStore && outcome.conversation == null) {
                    synchronized(sessionRegistryLock) {
                        unavailableConversationIds.remove(conversationId)
                    }
                    session?.takeUnless { it.isClosed }?.let {
                        it.markRestored()
                        it.resumeGenerationJobs()
                    }
                    null
                } else {
                    outcome
                }
            }
        } catch (error: Throwable) {
            synchronized(sessionRegistryLock) {
                unavailableConversationIds.remove(conversationId)
            }
            session?.takeUnless { it.isClosed }?.let {
                it.markRestored()
                it.resumeGenerationJobs()
            }
            throw error
        }
    }

    private fun finalizePendingDeletionLocked(
        conversationId: Uuid,
        pending: ReversibleConversationDeletion,
    ): Boolean {
        if (!pendingConversationDeletions.remove(conversationId, pending)) return false
        filesManager.deleteChatFiles(pending.files)
        sessions[conversationId]?.let(::discardSession)
        return true
    }

    private suspend fun collectAndDeleteConversation(
        conversationId: Uuid,
        session: ConversationSession?,
    ): ConversationDeletionOutcome {
        val durable = conversationRepo.getConversationById(conversationId)
        val inMemory = session?.state?.value
        val conversation = when {
            session?.hasDurableStateLoaded() == true -> inMemory
            durable != null -> durable
            else -> inMemory
        }
        val files = buildList {
            addAll(durable?.files.orEmpty())
            addAll(inMemory?.files.orEmpty())
        }.distinct()
        return ConversationDeletionOutcome(
            conversation = conversation,
            files = files,
            deletedFromStore = conversationRepo.deleteConversationById(conversationId),
        )
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.i(
                TAG,
                "event=operation domain=chat operation=delete_detached_files " +
                    "outcome=succeeded itemCount=${deletedFiles.size}",
            )
        }
    }

    private suspend fun persistCurrentConversation(conversationId: Uuid) {
        withConversationPersistenceLock(conversationId) { session ->
            currentConversationForMutationLocked(session)
            persistConversationLocked(session, session.state.value)
        }
    }

    private suspend fun materializeAndPersistCurrentConversation(conversationId: Uuid): Conversation {
        var supersededFiles = emptyList<String>()
        val persisted = withConversationPersistenceLock(conversationId) { session ->
            val current = currentConversationForMutationLocked(session)
            val result = mediaAssetMaterializer.materializeConversation(current)
            if (result.failures.isNotEmpty()) {
                Log.w(
                    TAG,
                    "event=operation domain=media operation=materialize_conversation " +
                        "outcome=partial_failure failure_count=${result.failures.size}",
                )
            }
            supersededFiles = result.supersededFiles
            if (result.conversation != current) session.state.value = result.conversation
            persistConversationLocked(session, session.state.value)
        }
        filesManager.deleteChatFiles(supersededFiles.map(String::toUri))
        return persisted
    }

    private suspend fun persistConversationLocked(
        session: ConversationSession,
        conversation: Conversation,
    ): Conversation {
        require(conversation.id == session.id) {
            "Conversation id ${conversation.id} does not match persistence scope ${session.id}"
        }
        val stateAtStart = session.state.value
        check(conversation == stateAtStart) {
            "Refusing detached conversation snapshot for ${conversation.id}"
        }
        check(conversation.storageRevision == stateAtStart.storageRevision) {
            "Refusing stale conversation snapshot for ${conversation.id}: " +
                "snapshot=${conversation.storageRevision}, state=${stateAtStart.storageRevision}"
        }
        val exists = conversationRepo.existsConversationById(conversation.id)

        val persistedConversation = if (!exists) {
            conversationRepo.insertConversation(conversation)
        } else {
            conversationRepo.updateConversation(conversation)
        }
        applyPersistedConversation(session, stateAtStart, persistedConversation)
        return persistedConversation
    }

    private fun applyPersistedConversation(
        session: ConversationSession,
        stateAtStart: Conversation,
        persisted: Conversation,
    ) {
        val current = session.state.value
        check(current == stateAtStart) {
            "Conversation state changed outside the session serialization domain for ${session.id}"
        }
        session.state.value = persisted
        checkFilesDelete(persisted, current)
        session.markDurableStateLoaded()
    }

    private suspend fun currentConversationForMutationLocked(session: ConversationSession): Conversation {
        val inMemory = session.state.value
        if (session.hasDurableStateLoaded()) return inMemory
        val persisted = conversationRepo.getConversationById(session.id)
        if (persisted != null) {
            updateConversationLocked(session, persisted, durable = true)
            return persisted
        }
        session.markDurableStateLoaded()
        return inMemory
    }

    private suspend fun mutateAndSaveConversation(
        conversationId: Uuid,
        transform: suspend (Conversation) -> Conversation,
    ): Conversation {
        return withConversationPersistenceLock(conversationId) { session ->
            currentConversationForMutationLocked(session)
            val previous = session.state.value
            val updated = transform(previous)
            require(updated.id == conversationId) {
                "Conversation transform changed id ${previous.id} to ${updated.id}"
            }
            if (updated == previous) return@withConversationPersistenceLock previous
            session.state.value = updated
            try {
                persistConversationLocked(session, updated)
                checkFilesDelete(updated, previous)
                session.state.value
            } catch (error: Throwable) {
                session.state.compareAndSet(updated, previous)
                throw error
            }
        }
    }

    private suspend fun patchConversationMetadata(
        conversationId: Uuid,
        patchFactory: (Conversation) -> ConversationMetadataPatch,
    ): Conversation = requireNotNull(
        patchConversationMetadataIf(
            conversationId = conversationId,
            predicate = { true },
            patchFactory = patchFactory,
        ),
    )

    private suspend fun patchConversationMetadataIf(
        conversationId: Uuid,
        predicate: (Conversation) -> Boolean,
        patchFactory: (Conversation) -> ConversationMetadataPatch,
    ): Conversation? = withConversationPersistenceLock(conversationId) { session ->
        val current = currentConversationForMutationLocked(session)
        if (!predicate(current)) return@withConversationPersistenceLock null
        val patch = patchFactory(current)
        val patched = patch.applyTo(current)
        if (patched == current) return@withConversationPersistenceLock current

        if (!conversationRepo.existsConversationById(conversationId)) {
            session.state.value = patched
            return@withConversationPersistenceLock try {
                persistConversationLocked(session, patched)
            } catch (error: Throwable) {
                session.state.compareAndSet(patched, current)
                throw error
            }
        }

        val persisted = conversationRepo.patchConversationMetadata(current, patch)
        updateConversationLocked(session, persisted, durable = true)
        persisted
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                val finalTranslation = getConversationFlow(conversationId).value
                    .findMessage(message.id)
                    ?.translation
                    ?: return@launch
                mutateAndSaveConversation(conversationId) { current ->
                    current.withMessageTranslation(message.id, finalTranslation)
                }
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private suspend fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        updateConversationState(conversationId) { current ->
            current.withMessageTranslation(messageId, translationText)
        }
    }

    private fun Conversation.findMessage(messageId: Uuid): UIMessage? = messageNodes
        .asSequence()
        .flatMap { node -> node.messages.asSequence() }
        .firstOrNull { message -> message.id == messageId }

    private fun Conversation.withMessageTranslation(
        messageId: Uuid,
        translation: String?,
    ): Conversation {
        var changed = false
        val updatedNodes = messageNodes.map { node ->
            if (node.messages.none { it.id == messageId }) return@map node
            val updatedMessages = node.messages.map { message ->
                if (message.id == messageId && message.translation != translation) {
                    changed = true
                    message.copy(translation = translation)
                } else {
                    message
                }
            }
            node.copy(messages = updatedMessages)
        }
        return if (changed) copy(messageNodes = updatedNodes) else this
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val settings = settingsStore.settingsFlow.first()
        mutateAndSaveConversation(conversationId) { current ->
            val assistant = settings.getAssistantById(current.assistantId)
                ?: settings.getCurrentAssistant()
            val processedParts = preprocessUserInputParts(parts, assistant)
            var edited = false
            val updatedNodes = current.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node
                }
                edited = true
                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                    ),
                    selectIndex = node.messages.size,
                )
            }
            if (edited) current.copy(messageNodes = updatedNodes) else current
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val forkConversationId = Uuid.random()
        val copiedFileIds = mutableListOf<Long>()
        try {
            val copiedNodes = currentConversation.messageNodes
                .subList(0, targetNodeIndex + 1)
                .map { node ->
                    node.copy(
                        id = Uuid.random(),
                        messages = node.messages.map { message ->
                            message.copy(
                                parts = message.parts.map { part ->
                                    part.copyWithForkedFileUrl(copiedFileIds::add)
                                },
                            )
                        },
                    )
                }

            val forkConversation = Conversation(
                id = forkConversationId,
                assistantId = currentConversation.assistantId,
                messageNodes = copiedNodes,
                customSystemPrompt = currentConversation.customSystemPrompt,
                modeInjectionIds = currentConversation.modeInjectionIds,
                lorebookIds = currentConversation.lorebookIds,
            )

            return mutateAndSaveConversation(forkConversation.id) { forkConversation }
        } catch (error: Throwable) {
            val durableLookup = withContext(NonCancellable) {
                runCatching { conversationRepo.getConversationById(forkConversationId) }
            }
            durableLookup.getOrNull()?.let { committed ->
                return committed
            }
            durableLookup.exceptionOrNull()?.let { lookupError ->
                error.addSuppressed(lookupError)
                // The insert outcome is unknown; preserving copied files is fail-safe.
                throw error
            }
            withContext(NonCancellable) {
                copiedFileIds.asReversed().forEach { fileId ->
                    runCatching {
                        check(filesManager.delete(fileId)) {
                            "Fork attachment rollback failed for managed file $fileId"
                        }
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            throw error
        }
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        mutateAndSaveConversation(conversationId) { current ->
            val targetNode = current.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@mutateAndSaveConversation current
            current.copy(
                messageNodes = current.messageNodes.map { node ->
                    if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
                },
            )
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        mutateAndSaveConversation(conversationId) { current ->
            val updated = buildConversationAfterMessageDelete(current, messageId)
            if (updated == null) {
                if (failIfMissing) throw NotFoundException("Message not found")
                current
            } else {
                updated
            }
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private suspend fun UIMessagePart.copyWithForkedFileUrl(
        onManagedFileCopied: (Long) -> Unit,
    ): UIMessagePart {
        suspend fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.saveManagedFromUri(
                folder = FileFolders.UPLOAD,
                uri = url.toUri(),
            )
            onManagedFileCopied(copied.id)
            return filesManager.getFile(copied).toUri().toString()
        }
        return copyForConversationFork(::copyLocalFileIfNeeded)
    }

    suspend fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutateAndSaveConversation(conversationId) { current ->
            current.withMessageTranslation(messageId, null)
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}

/**
 * Gallery images carry a stable assetId and are shared by reference across a fork. Ordinary
 * attachments are copied, including files nested inside tool output/progress, so deleting either
 * conversation cannot invalidate the other one's attachment path.
 */
internal suspend fun UIMessagePart.copyForConversationFork(
    copyLocalFile: suspend (String) -> String,
): UIMessagePart = when (this) {
    is UIMessagePart.Image -> if (assetId.isNullOrBlank()) copy(url = copyLocalFile(url)) else this
    is UIMessagePart.Document -> if (assetId.isNullOrBlank()) copy(url = copyLocalFile(url)) else this
    is UIMessagePart.Video -> if (assetId.isNullOrBlank()) copy(url = copyLocalFile(url)) else this
    is UIMessagePart.Audio -> if (assetId.isNullOrBlank()) copy(url = copyLocalFile(url)) else this
    is UIMessagePart.Tool -> copy(
        output = output.map { part -> part.copyForConversationFork(copyLocalFile) },
        progress = progress.map { part -> part.copyForConversationFork(copyLocalFile) },
    )
    else -> this
}

class ConversationDeletedException(id: Uuid) :
    IllegalStateException("Conversation $id has been deleted in this process")

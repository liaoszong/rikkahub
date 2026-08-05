package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.credential.effectiveProviderCredentialReference
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.fork.pale.request.ChatGenerationLedgerContext
import me.rerere.rikkahub.fork.pale.request.ChatProviderStepCoordinator
import me.rerere.rikkahub.fork.pale.request.ChatProviderStepOpenResult
import me.rerere.rikkahub.fork.pale.request.ChatProviderStepSession
import me.rerere.rikkahub.fork.pale.request.ToolExecutionLedgerCoordinator
import me.rerere.rikkahub.fork.pale.request.ToolExecutionLedgerSession
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeStarted
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val chatProviderStepCoordinator: ChatProviderStepCoordinator,
    private val toolExecutionLedgerCoordinator: ToolExecutionLedgerCoordinator,
    private val settingsStore: SettingsStore,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        toolExecutionContextId: String? = null,
        ledgerContext: ChatGenerationLedgerContext? = null,
    ): Flow<GenerationChunk> = flow {
        settingsStore.awaitCredentialReady()
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            logSafeStarted(TAG, "chat_generation", "provider_step")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools")
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                val providerStep = generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    ledgerContext = ledgerContext,
                )
                try {
                    messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                        finishedAt = Clock.System.now()
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                    )
                    emit(GenerationChunk.Messages(messages))
                    providerStep?.commitDurableOutput(messages.last())
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    val stableToolRequestId = tool.requestId.ifBlank {
                        providerStep?.stableToolRequestId(tool.toolCallId).orEmpty()
                    }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(
                                approvalState = ToolApprovalState.Pending,
                                requestId = stableToolRequestId,
                            )
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool.copy(requestId = stableToolRequestId)
                        }

                        else -> tool.copy(requestId = stableToolRequestId)
                    }
                }

                // If any tools were updated to Pending, update the message and break
                val lastMessage = messages.last()
                val updatedParts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                    } else {
                        part
                    }
                }
                messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)

                if (providerStep != null && ledgerContext != null) {
                    // Freeze the exact parent result before creating child tool requests. If child
                    // preparation fails, startup recovery can finish this local commit without
                    // ever replaying the already completed provider request.
                    withContext(NonCancellable) {
                        ledgerContext.persistMessages(messages)
                        providerStep.markResultReceived(messages.last())
                        updatedTools.forEach { tool ->
                            val toolDef = toolsInternal.find { it.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found while freezing its ledger identity")
                            toolExecutionLedgerCoordinator.prepare(
                                parentRequestId = providerStep.requestId,
                                context = ledgerContext,
                                tool = tool,
                                definition = toolDef,
                            )
                        }
                        providerStep.commitDurableOutput(messages.last())
                    }
                } else {
                    providerStep?.commitDurableOutput(messages.last())
                }
                emit(GenerationChunk.Messages(messages))

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                    toolsToProcess = updatedTools
                } catch (failure: Throwable) {
                    providerStep?.releaseForLocalRepair(failure)
                    throw failure
                }
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                    ?: error("Tool ${tool.toolName} not found")
                var toolLedgerSession: ToolExecutionLedgerSession? = ledgerContext?.takeIf {
                    tool.approvalState is ToolApprovalState.Denied ||
                        tool.approvalState is ToolApprovalState.Answered
                }?.let {
                    toolExecutionLedgerCoordinator.openExecution(
                        context = it,
                        tool = tool,
                        definition = toolDef,
                    )
                }
                var toolCrossedExternalBoundary = false
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        toolLedgerSession?.startLocal()
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            ),
                            executionState = ToolExecutionState.FAILED,
                            requestId = tool.requestId.ifBlank { Uuid.random().toString() },
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        toolLedgerSession?.startLocal()
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            ),
                            executionState = ToolExecutionState.SUCCEEDED,
                            requestId = tool.requestId.ifBlank { Uuid.random().toString() },
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        val runningTool = tool.copy(
                            executionState = ToolExecutionState.RUNNING,
                            requestId = tool.requestId.ifBlank { Uuid.random().toString() },
                        )
                        val runningMessage = messages.last().copy(
                            parts = messages.last().parts.map { part ->
                                if (part is UIMessagePart.Tool && part.toolCallId == runningTool.toolCallId) {
                                    runningTool
                                } else {
                                    part
                                }
                            },
                        )
                        messages = messages.dropLast(1) + runningMessage
                        // Persist RUNNING before invoking a side-effecting tool. A process death
                        // then fails closed instead of treating the approved invocation as fresh.
                        ledgerContext?.persistMessages(messages)
                        emit(GenerationChunk.Messages(messages))
                        if (toolLedgerSession == null && ledgerContext != null) {
                            toolLedgerSession = toolExecutionLedgerCoordinator.openExecution(
                                context = ledgerContext,
                                tool = runningTool,
                                definition = toolDef,
                            )
                        }

                        val argsResult = runCatching {
                            json.parseToJsonElement(runningTool.input.ifBlank { "{}" })
                        }
                        if (argsResult.isFailure) {
                            toolLedgerSession?.startLocal()
                            val failure = argsResult.exceptionOrNull()!!
                            executedTools += runningTool.asFailedToolResult(
                                IllegalArgumentException(
                                    "Invalid tool arguments JSON",
                                    failure,
                                ),
                            )
                        } else {
                            val args = argsResult.getOrThrow()
                            if (toolLedgerSession != null) {
                                try {
                                    if (toolDef.ledgerOwnsExternalDispatch) {
                                        toolLedgerSession.startExternal()
                                        toolCrossedExternalBoundary = true
                                    } else {
                                        toolLedgerSession.startLocal()
                                    }
                                } catch (cancellation: CancellationException) {
                                    toolLedgerSession.finishCancellation(
                                        externalBoundaryCrossed = toolCrossedExternalBoundary,
                                    )
                                    throw cancellation
                                }
                            }
                            try {
                            logSafeStarted(TAG, "tool_execution", "execute_tool")
                            val executeTool: suspend () -> List<UIMessagePart> = {
                                toolDef.executeWithContext?.invoke(
                                    args,
                                    ToolExecutionContext(
                                        toolCallId = runningTool.toolCallId,
                                        messages = messages,
                                        emitProgress = { progress ->
                                            val progressMessage = messages.last().copy(
                                                parts = messages.last().parts.map { part ->
                                                    if (part is UIMessagePart.Tool && part.toolCallId == runningTool.toolCallId) {
                                                        part.copy(progress = progress)
                                                    } else {
                                                        part
                                                    }
                                                }
                                            )
                                            messages = messages.dropLast(1) + progressMessage
                                            emit(GenerationChunk.Messages(messages))
                                        },
                                        contextId = toolExecutionContextId,
                                        executionRequestId = runningTool.requestId,
                                        credentialRefId = toolLedgerSession?.credentialRefId,
                                    ),
                                ) ?: toolDef.execute(args)
                            }
                            val result = toolLedgerSession?.withLeaseHeartbeat(executeTool) ?: executeTool()
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += runningTool.copy(
                                output = maybeTruncateToolOutput(runningTool.toolCallId, result, hasShellAccess),
                                progress = emptyList(),
                                executionState = ToolExecutionState.SUCCEEDED,
                            )
                            } catch (failure: Throwable) {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (failure is CancellationException) {
                                toolLedgerSession?.finishCancellation(
                                    externalBoundaryCrossed = toolCrossedExternalBoundary,
                                )
                                throw failure
                            }
                            logSafeError(
                                tag = TAG,
                                domain = "tool_execution",
                                operation = "execute_tool",
                                error = failure,
                                requestId = runningTool.requestId,
                            )
                            executedTools += runningTool.asFailedToolResult(failure)
                            }
                        }
                    }
                }

                // Each tool result crosses its own durable barrier. This prevents a later parallel
                // tool cancellation from losing an earlier completed side effect and its evidence.
                executedTools.lastOrNull { it.toolCallId == tool.toolCallId }?.let { result ->
                    val resultMessage = messages.last().copy(
                        parts = messages.last().parts.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == result.toolCallId) {
                                result
                            } else {
                                part
                            }
                        },
                    )
                    try {
                        messages = messages.dropLast(1) + resultMessage
                        if (ledgerContext != null && toolLedgerSession != null) {
                            // Once a tool returned a definitive result, cancellation may not erase
                            // that fact or downgrade it to UNKNOWN. Finish the single-tool durable
                            // barrier independently of the collector's lifecycle.
                            withContext(NonCancellable) {
                                ledgerContext.persistMessages(messages)
                                toolLedgerSession.commitDurableResult(
                                    result = result,
                                    persistConversation = {},
                                )
                            }
                        } else {
                            ledgerContext?.persistMessages(messages)
                        }
                        emit(GenerationChunk.Messages(messages))
                    } catch (failure: Throwable) {
                        val cleanupFailure = if (failure is CancellationException) {
                            runCatching {
                                toolLedgerSession?.finishCancellation(
                                    externalBoundaryCrossed = toolCrossedExternalBoundary,
                                )
                            }.exceptionOrNull()
                        } else {
                            runCatching {
                                toolLedgerSession?.releaseForLocalRepair(failure)
                            }.exceptionOrNull()
                        }
                        cleanupFailure?.let(failure::addSuppressed)
                        throw failure
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            val durableToolMessages = messages.transforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings,
            )
            messages = durableToolMessages
            emit(GenerationChunk.Messages(durableToolMessages))
            // Tool output is part of the next provider request identity. It must be durable before
            // the loop is allowed to cross another billable transport boundary.
            ledgerContext?.persistCurrentConversation()
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        ledgerContext: ChatGenerationLedgerContext? = null,
    ): ChatProviderStepSession? {
        val providerMessages = if (
            messages.lastOrNull()?.role == MessageRole.ASSISTANT &&
            messages.lastOrNull()?.parts?.isEmpty() == true
        ) {
            messages.dropLast(1)
        } else {
            messages
        }
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, providerMessages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(providerMessages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val baseParams = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        val openResult = ledgerContext?.let {
            chatProviderStepCoordinator.openTextStep(
                context = it,
                messages = internalMessages,
                params = baseParams,
                provider = provider,
                tools = tools,
                credentialRefId = settings.effectiveProviderCredentialReference(
                    provider = provider,
                    customHeaders = baseParams.customHeaders,
                ),
            )
        }
        val providerStep = when (openResult) {
            is ChatProviderStepOpenResult.Dispatch -> openResult.step
            is ChatProviderStepOpenResult.RepairCommit -> {
                try {
                    messages = messages.map { message ->
                        if (message.id == openResult.durableMessage.id) openResult.durableMessage else message
                    }
                    onUpdateMessages(messages)
                    openResult.step.commitDurableOutput(openResult.durableMessage)
                    return openResult.step
                } catch (failure: Throwable) {
                    openResult.step.releaseForLocalRepair(failure)
                    throw failure
                }
            }

            is ChatProviderStepOpenResult.AlreadySucceeded -> {
                messages = messages.map { message ->
                    if (message.id == openResult.durableMessage.id) openResult.durableMessage else message
                }
                onUpdateMessages(messages)
                return openResult.step
            }

            null -> null
        }
        val params = baseParams.copy(
            dispatchObserver = providerStep?.dispatchObserver ?: baseParams.dispatchObserver,
        )

        suspend fun executeProvider() {
            if (stream) {
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params
                ).collect {
                    providerStep?.markResponseStarted()
                    messages = messages.handleMessageChunk(chunk = it, model = model)
                    it.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(usage = message.usage.merge(usage))
                            } else {
                                message
                            }
                        }
                    }
                    onUpdateMessages(messages)
                }
            } else {
                val chunk = providerImpl.generateText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params,
                )
                providerStep?.markResponseStarted()
                messages = messages.handleMessageChunk(chunk = chunk, model = model)
                chunk.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(
                                usage = message.usage.merge(usage)
                            )
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        }

        try {
            if (providerStep != null) {
                providerStep.prepareDispatch()
                providerStep.withLeaseHeartbeat {
                    executeProvider()
                }
            } else {
                executeProvider()
            }
        } catch (failure: Throwable) {
            if (providerStep != null) {
                withContext(NonCancellable) {
                    runCatching { providerStep.finishTransportFailure(failure) }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        return providerStep
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        logSafeStarted(TAG, "tool_execution", "persist_truncated_output")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    private fun UIMessagePart.Tool.asFailedToolResult(failure: Throwable): UIMessagePart.Tool = copy(
        output = listOf(
            UIMessagePart.Text(
                json.encodeToString(
                    buildJsonObject {
                        put(
                            "error",
                            JsonPrimitive(
                                Logging.safeErrorMessage(
                                    domain = "tool_execution",
                                    operation = "execute_tool",
                                    error = failure,
                                )
                            ),
                        )
                    },
                ),
            ),
        ),
        progress = emptyList(),
        executionState = ToolExecutionState.FAILED,
    )

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: (suspend (String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

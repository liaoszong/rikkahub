package me.rerere.rikkahub.fork.pale.request

import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.model.effectiveCapabilitySnapshot
import me.rerere.ai.model.resolveTextApiSurface
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderDispatchObserver
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.pale.id.RequestAttemptId
import me.rerere.pale.id.RequestId
import me.rerere.pale.id.RequestOutputId
import me.rerere.pale.request.RequestKind
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState

private const val CHAT_OUTPUT_KIND = "chat_step"

internal fun chatOutputId(requestId: RequestId): RequestOutputId = RequestOutputId(
    UUID.nameUUIDFromBytes(
        "pale.6:chat-output:v1:${requestId.value}".toByteArray(Charsets.UTF_8),
    ).toString().lowercase(Locale.ROOT),
)

/** Stable host context for every paid provider step that contributes to one assistant message. */
data class ChatGenerationLedgerContext(
    val conversationId: String,
    val assistantId: String,
    val responseMessageId: String,
    val workspaceId: String? = null,
    val persistCurrentConversation: suspend () -> Unit,
    val persistMessages: suspend (List<UIMessage>) -> Unit,
    val loadResponseMessage: suspend () -> UIMessage?,
)

sealed interface ChatProviderStepOpenResult {
    data class Dispatch(val step: ChatProviderStepSession) : ChatProviderStepOpenResult
    data class RepairCommit(
        val step: ChatProviderStepSession,
        val durableMessage: UIMessage,
    ) : ChatProviderStepOpenResult

    data class AlreadySucceeded(
        val step: ChatProviderStepSession,
        val durableMessage: UIMessage,
    ) : ChatProviderStepOpenResult
}

class ChatProviderStepBlocked(message: String) : IllegalStateException(message)

/**
 * Freezes a provider step into the durable RequestLedger before any transport can own it.
 * The root request ID is derived from the persisted response message; later tool-loop requests
 * are deterministic children derived from their exact frozen provider input.
 */
class ChatProviderStepCoordinator(
    private val repository: RequestLedgerRepository,
    private val json: Json,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_MILLIS,
    private val processOwnerId: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
) {
    suspend fun openTextStep(
        context: ChatGenerationLedgerContext,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        provider: ProviderSetting,
        tools: List<Tool>,
    ): ChatProviderStepOpenResult {
        val apiSurface = params.model.resolveTextApiSurface(provider)
        val capabilitySnapshot = params.model.effectiveCapabilitySnapshot(provider)
        val toolCatalogDigest = digestToolCatalog(tools)
        val inputDigest = digestProviderInput(
            messages = messages,
            params = params,
            provider = provider,
            apiSurface = apiSurface.name,
            toolCatalogDigest = toolCatalogDigest,
        )
        val rootRequestId = stableRequestId(
            "chat-root:v1:${context.conversationId}:${context.responseMessageId}",
        )
        val actor = AuditActor.system("chat:$processOwnerId")
        val requests = repository.getChatRequestsForMessage(
            context.conversationId,
            context.responseMessageId,
        )
        val chain = linearizeRequestChain(requests, rootRequestId)
        val exact = chain.singleOrNull { it.inputDigest == inputDigest }
            ?: chain.filter { it.inputDigest == inputDigest }.takeIf { it.size > 1 }?.let {
                throw RequestLedgerIdentityConflict("Provider input appears more than once in one chat request chain")
            }
        if (exact != null) {
            return openExistingStep(
                context = context,
                request = exact,
                actor = actor,
                inputDigest = inputDigest,
                toolCatalogDigest = toolCatalogDigest,
                capabilitySnapshotJson = json.encodeToString(capabilitySnapshot),
            )
        }

        val predecessor = chain.lastOrNull()
        if (predecessor != null) {
            val predecessorOutputs = repository.getOutputs(RequestId(predecessor.requestId))
            if (predecessor.requestState() != RequestState.SUCCEEDED ||
                predecessor.billableBoundary() != BillableBoundary.RESULT_COMMITTED ||
                predecessorOutputs.none { it.outputKind == CHAT_OUTPUT_KIND }
            ) {
                throw ChatProviderStepBlocked(
                    "Cannot create a child provider request before ${predecessor.requestId} has durable output",
                )
            }
        }

        val requestId = if (predecessor == null) {
            rootRequestId
        } else {
            stableRequestId("chat-step:v1:${rootRequestId.value}:$inputDigest")
        }
        val spec = requestSpec(
            context = context,
            requestId = requestId,
            parentRequestId = predecessor?.let { RequestId(it.requestId) },
            inputDigest = inputDigest,
            capabilitySnapshotJson = json.encodeToString(capabilitySnapshot),
            toolCatalogDigest = toolCatalogDigest,
            provider = provider,
            model = params.model,
            apiSurface = apiSurface.name.lowercase(Locale.ROOT),
            actor = actor,
        )
        return ChatProviderStepOpenResult.Dispatch(
            openDispatchSession(context, spec, actor, inputDigest),
        )
    }

    private suspend fun openExistingStep(
        context: ChatGenerationLedgerContext,
        request: RequestLedgerEntity,
        actor: AuditActor,
        inputDigest: String,
        toolCatalogDigest: String,
        capabilitySnapshotJson: String,
    ): ChatProviderStepOpenResult {
        val requestId = RequestId(request.requestId)
        val state = request.requestState()
        val boundary = request.billableBoundary()
        if (state == RequestState.SUCCEEDED) {
            check(boundary == BillableBoundary.RESULT_COMMITTED)
            val output = repository.getOutputs(requestId).singleOrNull {
                it.outputKind == CHAT_OUTPUT_KIND && it.ordinal == 0
            } ?: throw RequestLedgerConflict("Succeeded chat request is missing its canonical output")
            val durableMessage = requireDurableMessage(context, output.contentDigest)
            val attemptId = output.attemptId?.let(::RequestAttemptId)
                ?: throw RequestLedgerConflict("Succeeded chat output is missing its attempt identity")
            return ChatProviderStepOpenResult.AlreadySucceeded(
                step = ChatProviderStepSession.alreadyCommitted(
                    coordinator = this,
                    context = context,
                    requestId = requestId,
                    attemptId = attemptId,
                    actor = actor,
                ),
                durableMessage = durableMessage,
            )
        }

        val spec = NewRequestSpec(
            requestId = requestId,
            intentKey = request.intentKey,
            kind = RequestKind.CHAT_GENERATION,
            inputDigest = inputDigest,
            capabilitySnapshotJson = capabilitySnapshotJson,
            resolverVersion = request.resolverVersion,
            actor = actor,
            parentRequestId = request.parentRequestId?.let(::RequestId),
            conversationId = request.conversationId,
            assistantId = request.assistantId,
            messageId = request.messageId,
            workspaceId = request.workspaceId,
            credentialRefId = request.credentialRefId,
            providerKind = request.providerKind,
            providerId = request.providerId,
            modelId = request.modelId,
            apiSurface = request.apiSurface,
            toolCatalogDigest = toolCatalogDigest,
        )
        if (state == RequestState.COMMITTING && boundary == BillableBoundary.RESULT_RECEIVED) {
            val attempt = request.activeAttemptId?.let { repository.getAttempt(RequestAttemptId(it)) }
                ?: throw RequestLedgerConflict("Committing chat request is missing its active attempt")
            val checkpointDigest = attempt.checkpointDigest
                ?: throw RequestLedgerConflict("Committing chat request is missing its result checkpoint")
            val durableMessage = requireDurableMessage(context, expectedDigest = checkpointDigest)
            check(durableMessage.parts.isNotEmpty()) {
                "A persisted empty assistant placeholder cannot repair a received provider result"
            }
            return ChatProviderStepOpenResult.RepairCommit(
                step = openDispatchSession(context, spec, actor, inputDigest),
                durableMessage = durableMessage,
            )
        }
        if ((state == RequestState.CREATED ||
                state == RequestState.QUEUED ||
                state == RequestState.WAITING_RUNTIME ||
                state == RequestState.DISPATCHING ||
                state == RequestState.RUNNING
            ) &&
            boundary == BillableBoundary.NOT_SENT
        ) {
            return ChatProviderStepOpenResult.Dispatch(openDispatchSession(context, spec, actor, inputDigest))
        }
        throw ChatProviderStepBlocked(
            "Provider request ${request.requestId} is $state/$boundary; automatic redispatch is forbidden",
        )
    }

    private suspend fun openDispatchSession(
        context: ChatGenerationLedgerContext,
        request: NewRequestSpec,
        actor: AuditActor,
        requestFingerprint: String,
    ): ChatProviderStepSession {
        val existing = repository.getRequest(request.requestId)
        val activeAttempt = existing?.activeAttemptId?.let {
            repository.getAttempt(RequestAttemptId(it))
        }
        val attemptId = activeAttempt?.let { RequestAttemptId(it.attemptId) } ?: RequestAttemptId.random()
        val idempotencyKey = activeAttempt?.idempotencyKey ?: "pale-chat-${attemptId.value}"
        val session = RequestDispatchSession.open(
            repository = repository,
            request = request,
            owner = "chat:$processOwnerId:${request.requestId.value}",
            leaseDurationMillis = leaseDurationMillis,
            attemptId = attemptId,
            idempotencyKey = idempotencyKey,
            requestFingerprint = activeAttempt?.requestFingerprint ?: requestFingerprint,
            actor = actor,
            transportKind = request.apiSurface,
        )
        return ChatProviderStepSession(
            coordinator = this,
            context = context,
            requestId = request.requestId,
            dispatchSession = session,
            attemptId = session.attemptId,
            actor = actor,
        )
    }

    private fun requestSpec(
        context: ChatGenerationLedgerContext,
        requestId: RequestId,
        parentRequestId: RequestId?,
        inputDigest: String,
        capabilitySnapshotJson: String,
        toolCatalogDigest: String,
        provider: ProviderSetting,
        model: Model,
        apiSurface: String,
        actor: AuditActor,
    ) = NewRequestSpec(
        requestId = requestId,
        intentKey = "chat-provider-step:v1:${context.conversationId}:" +
            "${context.responseMessageId}:$inputDigest",
        kind = RequestKind.CHAT_GENERATION,
        inputDigest = inputDigest,
        capabilitySnapshotJson = capabilitySnapshotJson,
        resolverVersion = CHAT_RESOLVER_VERSION,
        actor = actor,
        parentRequestId = parentRequestId,
        conversationId = context.conversationId,
        assistantId = context.assistantId,
        messageId = context.responseMessageId,
        workspaceId = context.workspaceId,
        providerKind = provider.providerKind(),
        providerId = provider.id.toString(),
        modelId = model.id.toString(),
        apiSurface = apiSurface,
        toolCatalogDigest = toolCatalogDigest,
    )

    private fun linearizeRequestChain(
        requests: List<RequestLedgerEntity>,
        rootRequestId: RequestId,
    ): List<RequestLedgerEntity> {
        if (requests.isEmpty()) return emptyList()
        val root = requests.singleOrNull { it.requestId == rootRequestId.value }
            ?: throw RequestLedgerConflict("Chat request chain is missing its deterministic root")
        val chain = mutableListOf(root)
        val visited = mutableSetOf(root.requestId)
        while (true) {
            val children = requests.filter { it.parentRequestId == chain.last().requestId }
            if (children.isEmpty()) break
            if (children.size != 1) {
                throw RequestLedgerConflict("Chat request chain forked before durable ordering was recorded")
            }
            val child = children.single()
            check(visited.add(child.requestId)) { "Chat request chain contains a cycle" }
            chain += child
        }
        if (chain.size != requests.size) {
            throw RequestLedgerConflict("Chat request chain contains detached requests")
        }
        return chain
    }

    private suspend fun requireDurableMessage(
        context: ChatGenerationLedgerContext,
        expectedDigest: String?,
    ): UIMessage {
        val message = context.loadResponseMessage()
            ?: throw ChatProviderStepBlocked(
                "Durable response ${context.responseMessageId} is unavailable for local request recovery",
            )
        check(message.id.toString() == context.responseMessageId) {
            "Loaded durable response has a different stable identity"
        }
        if (expectedDigest != null && digestOutput(message) != expectedDigest) {
            throw RequestLedgerIdentityConflict("Durable chat output digest no longer matches RequestLedger")
        }
        return message
    }

    internal fun digestOutput(message: UIMessage): String = sha256Json(UIMessage.serializer(), message)

    internal fun stableToolRequestId(
        requestId: RequestId,
        attemptId: RequestAttemptId,
        providerToolCallId: String,
    ): String = stableRequestId(
        "tool:v1:${requestId.value}:${attemptId.value}:$providerToolCallId",
    ).value

    private fun digestProviderInput(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        provider: ProviderSetting,
        apiSurface: String,
        toolCatalogDigest: String,
    ): String = sha256Json(
        ProviderInputDescriptor.serializer(),
        ProviderInputDescriptor(
            providerId = provider.id.toString(),
            providerKind = provider.providerKind(),
            modelId = params.model.id.toString(),
            modelName = params.model.modelId,
            apiSurface = apiSurface,
            providerRoute = provider.toRouteDescriptor(),
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens,
            reasoningLevel = params.reasoningLevel.name,
            customHeaders = params.customHeaders.map {
                HeaderDescriptor(
                    name = it.name.lowercase(Locale.ROOT),
                    valueDigest = sha256String(it.value),
                )
            }.sortedWith(compareBy(HeaderDescriptor::name, HeaderDescriptor::valueDigest)),
            customBody = params.customBody.sortedBy { it.key },
            toolCatalogDigest = toolCatalogDigest,
            messagesDigest = sha256Json(
                ListSerializer(ProviderMessageDescriptor.serializer()),
                messages.map { it.toProviderDescriptor() },
            ),
        ),
    )

    private fun digestToolCatalog(tools: List<Tool>): String = sha256Json(
        ListSerializer(ToolDescriptor.serializer()),
        tools.map { tool ->
            ToolDescriptor(tool.name, tool.description, tool.parameters())
        }.sortedBy { it.name },
    )

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> sha256Json(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): String {
        val digest = MessageDigest.getInstance("SHA-256")
        json.encodeToStream(serializer, value, DigestOutputStream(digest))
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun stableRequestId(identity: String): RequestId = RequestId(
        UUID.nameUUIDFromBytes("pale.6:$identity".toByteArray(Charsets.UTF_8))
            .toString()
            .lowercase(Locale.ROOT),
    )

    private fun ProviderSetting.providerKind(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
    }

    private fun ProviderSetting.toRouteDescriptor(): ProviderRouteDescriptor = when (this) {
        is ProviderSetting.OpenAI -> ProviderRouteDescriptor(
            baseUrl = baseUrl,
            path = chatCompletionsPath,
            encoderOptions = listOf(
                "use_responses=$useResponseApi",
                "include_history_reasoning=$includeHistoryReasoning",
                "managed_by=${managedBy.orEmpty()}",
            ),
            credentialDigest = sha256String(apiKey),
        )

        is ProviderSetting.Google -> ProviderRouteDescriptor(
            baseUrl = baseUrl,
            path = if (vertexAI) "vertex-generate-content" else "generate-content",
            encoderOptions = listOf(
                "vertex=$vertexAI",
                "service_account=$useServiceAccount",
                "location=$location",
                "project=$projectId",
                "service_email=$serviceAccountEmail",
            ),
            credentialDigest = sha256String(
                listOf(apiKey, privateKey).joinToString("\u0000"),
            ),
        )

        is ProviderSetting.Claude -> ProviderRouteDescriptor(
            baseUrl = baseUrl,
            path = "messages",
            encoderOptions = listOf(
                "prompt_cache=$promptCaching",
                "prompt_cache_ttl=${promptCacheTtl.name}",
            ),
            credentialDigest = sha256String(apiKey),
        )
    }

    private fun sha256String(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun UIMessage.toProviderDescriptor(): ProviderMessageDescriptor = ProviderMessageDescriptor(
        role = role,
        parts = parts.map { it.toProviderDescriptor() },
    )

    private fun UIMessagePart.toProviderDescriptor(): ProviderPartDescriptor = when (this) {
        is UIMessagePart.Text -> ProviderPartDescriptor.Text(text, metadata)
        is UIMessagePart.Image -> ProviderPartDescriptor.Image(url, metadata)
        is UIMessagePart.Video -> ProviderPartDescriptor.Video(url, metadata)
        is UIMessagePart.Audio -> ProviderPartDescriptor.Audio(url, metadata)
        is UIMessagePart.Document -> ProviderPartDescriptor.Document(url, fileName, mime, metadata)
        is UIMessagePart.Reasoning -> ProviderPartDescriptor.Reasoning(reasoning, metadata)
        is UIMessagePart.Search -> ProviderPartDescriptor.Search
        is UIMessagePart.ToolCall -> ProviderPartDescriptor.LegacyToolCall(
            toolCallId = toolCallId,
            toolName = toolName,
            arguments = arguments,
            metadata = metadata,
        )

        is UIMessagePart.ToolResult -> ProviderPartDescriptor.LegacyToolResult(
            toolCallId = toolCallId,
            toolName = toolName,
            content = content,
            arguments = arguments,
            metadata = metadata,
        )

        is UIMessagePart.Tool -> ProviderPartDescriptor.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            input = input,
            output = output.map { it.toProviderDescriptor() },
            metadata = metadata,
            executionState = executionState?.name,
        )
    }

    private class DigestOutputStream(private val digest: MessageDigest) : OutputStream() {
        override fun write(value: Int) = digest.update(value.toByte())

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            digest.update(buffer, offset, length)
    }

    companion object {
        private const val CHAT_RESOLVER_VERSION = 1
        private const val DEFAULT_LEASE_MILLIS = 120_000L
    }
}

class ChatProviderStepSession internal constructor(
    private val coordinator: ChatProviderStepCoordinator,
    private val context: ChatGenerationLedgerContext,
    val requestId: RequestId,
    private val dispatchSession: RequestDispatchSession?,
    private val attemptId: RequestAttemptId,
    private val actor: AuditActor,
    private var committed: Boolean = false,
) {
    val dispatchObserver: ProviderDispatchObserver = dispatchSession?.dispatchObserver
        ?: ProviderDispatchObserver.NONE

    suspend fun prepareDispatch() = requireDispatchSession().prepareDispatch()

    suspend fun markResponseStarted() = requireDispatchSession().markResponseStarted()

    suspend fun markResultReceived(message: UIMessage) =
        requireDispatchSession().markResultReceived(coordinator.digestOutput(message))

    suspend fun <T> withLeaseHeartbeat(block: suspend () -> T): T =
        requireDispatchSession().withLeaseHeartbeat(block = block)

    fun stableToolRequestId(providerToolCallId: String): String =
        coordinator.stableToolRequestId(requestId, attemptId, providerToolCallId)

    suspend fun commitDurableOutput(message: UIMessage) {
        if (committed) return
        val dispatchSession = requireDispatchSession()
        try {
            context.persistCurrentConversation()
            markResultReceived(message)
            dispatchSession.commitOutputAndSucceed(
                CommitRequestOutputCommand(
                    lease = dispatchSession.lease,
                    attemptId = dispatchSession.attemptId,
                    outputId = chatOutputId(requestId),
                    outputKind = CHAT_OUTPUT_KIND,
                    ordinal = 0,
                    contentDigest = coordinator.digestOutput(message),
                    actor = actor,
                    conversationId = context.conversationId,
                    messageId = message.id.toString(),
                ),
            )
            committed = true
        } catch (failure: Throwable) {
            // RESULT_RECEIVED is authoritative: release ownership for a local-only repair worker,
            // but never turn this into a provider retry.
            runCatching { dispatchSession.releaseLease() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun finishTransportFailure(failure: Throwable) =
        requireDispatchSession().finishTransportFailure(failure is CancellationException)

    suspend fun releaseForLocalRepair(failure: Throwable) {
        val session = dispatchSession ?: return
        withContext(NonCancellable) {
            runCatching { session.releaseLease() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
    }

    private fun requireDispatchSession(): RequestDispatchSession =
        dispatchSession ?: error("A committed chat provider step has no dispatch session")

    companion object {
        internal fun alreadyCommitted(
            coordinator: ChatProviderStepCoordinator,
            context: ChatGenerationLedgerContext,
            requestId: RequestId,
            attemptId: RequestAttemptId,
            actor: AuditActor,
        ) = ChatProviderStepSession(
            coordinator = coordinator,
            context = context,
            requestId = requestId,
            dispatchSession = null,
            attemptId = attemptId,
            actor = actor,
            committed = true,
        )
    }
}

@Serializable
private data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: InputSchema?,
)

@Serializable
private data class ProviderInputDescriptor(
    val providerId: String,
    val providerKind: String,
    val modelId: String,
    val modelName: String,
    val apiSurface: String,
    val providerRoute: ProviderRouteDescriptor,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val reasoningLevel: String,
    val customHeaders: List<HeaderDescriptor>,
    val customBody: List<me.rerere.ai.provider.CustomBody>,
    val toolCatalogDigest: String,
    val messagesDigest: String,
)

@Serializable
private data class ProviderRouteDescriptor(
    val baseUrl: String,
    val path: String,
    val encoderOptions: List<String>,
    val credentialDigest: String,
)

@Serializable
private data class HeaderDescriptor(
    val name: String,
    val valueDigest: String,
)

@Serializable
private data class ProviderMessageDescriptor(
    val role: MessageRole,
    val parts: List<ProviderPartDescriptor>,
)

/** Provider-visible message identity. UI UUIDs, clocks, progress and host request IDs are omitted. */
@Serializable
private sealed class ProviderPartDescriptor {
    @Serializable
    data class Text(
        val text: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Image(
        val url: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Video(
        val url: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Audio(
        val url: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Reasoning(
        val reasoning: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data object Search : ProviderPartDescriptor()

    @Serializable
    data class LegacyToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class LegacyToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: kotlinx.serialization.json.JsonElement,
        val arguments: kotlinx.serialization.json.JsonElement,
        val metadata: kotlinx.serialization.json.JsonObject?,
    ) : ProviderPartDescriptor()

    @Serializable
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<ProviderPartDescriptor>,
        val metadata: kotlinx.serialization.json.JsonObject?,
        val executionState: String?,
    ) : ProviderPartDescriptor()
}

private fun RequestLedgerEntity.requestState(): RequestState =
    RequestState.valueOf(requestState.uppercase(Locale.ROOT))

private fun RequestLedgerEntity.billableBoundary(): BillableBoundary =
    BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))

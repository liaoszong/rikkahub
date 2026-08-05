package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.model.ModelFeature
import me.rerere.ai.model.effectiveCapabilitySnapshot
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.providerAuthHeaders
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.STREAM_PART_ID_METADATA_KEY
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.AttachmentBudgetTracker
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.rethrowIfPayloadBudgetExceeded
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(
                providerAuthHeaders(
                    params.customHeaders,
                    "Authorization" to providerSetting.bearerToken(),
                )
            )
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "event=operation domain=provider operation=generate_text outcome=started itemCount=${messages.size}")

        params.dispatchObserver.onDispatch()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(
                providerAuthHeaders(
                    params.customHeaders,
                    "Authorization" to providerSetting.bearerToken(),
                )
            )
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "event=operation domain=provider operation=stream_text outcome=started itemCount=${messages.size}")

        val citationPartTracker = ResponseTextPartOrdinalTracker()
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                Log.d(TAG, "event=operation domain=provider operation=process_stream_event outcome=started")
                val json = json.parseToJsonElement(data).jsonObject
                val chunk = parseResponseDelta(json, citationPartTracker)
                if (chunk != null) {
                    trySend(chunk).onFailure { e ->
                        Log.w(TAG, "onEvent: chunk dropped (${e?.javaClass?.simpleName})")
                    }
                }
                if (type == "response.completed") {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.e(
                    TAG,
                    "event=operation domain=provider operation=stream_text outcome=failed " +
                        "errorClass=${t?.javaClass?.simpleName ?: "HttpError"} httpStatus=${response?.code}",
                )

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: error response parse failed (${e.javaClass.simpleName})")
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        params.dispatchObserver.onDispatch()
        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        val modelCapabilities = params.model.effectiveCapabilitySnapshot(providerSetting)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            put("input", buildMessages(messages))

            // reasoning
            if (ModelFeature.REASONING in modelCapabilities.features) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                ModelFeature.TOOL_CALLING in modelCapabilities.features && params.tools.isNotEmpty()
            val enabledBuiltInTools = params.model.tools.filter { tool ->
                when (tool) {
                    BuiltInTools.Search -> ModelFeature.WEB_SEARCH in modelCapabilities.features
                    BuiltInTools.UrlContext -> ModelFeature.URL_CONTEXT in modelCapabilities.features
                    BuiltInTools.ImageGeneration ->
                        ModelFeature.IMAGE_GENERATION in modelCapabilities.features
                }
            }
            if (useFunctionTools || enabledBuiltInTools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        }
                    }
                    // built-in tools
                    enabledBuiltInTools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun ProviderSetting.OpenAI.bearerToken(): String? = apiKey
        .takeIf(String::isNotBlank)
        ?.let { "Bearer ${keyRoulette.next(it, id.toString())}" }

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        val attachmentBudget = AttachmentBudgetTracker()
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message, attachmentBudget)
                } else {
                    addUserItems(message, attachmentBudget)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        attachmentBudget: AttachmentBudgetTracker,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, attachmentBudget)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    reasoningMetadata?.reasoningId?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    reasoningMetadata?.encryptedContent?.let {
                                        put("encrypted_content", it)
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, attachmentBudget)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part), attachmentBudget)
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer, attachmentBudget)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64(budgetTracker = attachmentBudget).onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.rethrowIfPayloadBudgetExceeded()
                                                    Log.w(TAG, "encode tool image failed (${it.javaClass.simpleName})")
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer, attachmentBudget)
        }
    }

    private fun JsonArrayBuilder.addUserItems(
        message: UIMessage,
        attachmentBudget: AttachmentBudgetTracker,
    ) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts, attachmentBudget)
        }
    }

    private fun JsonArrayBuilder.addContentItem(
        role: MessageRole,
        parts: List<UIMessagePart>,
        attachmentBudget: AttachmentBudgetTracker,
    ) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64(budgetTracker = attachmentBudget).onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.rethrowIfPayloadBudgetExceeded()
                                        Log.w(TAG, "encode message image failed (${it.javaClass.simpleName})")
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseResponseDelta(
        jsonObject: JsonObject,
        citationPartTracker: ResponseTextPartOrdinalTracker,
    ): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                val outputIndex = jsonObject["output_index"]?.jsonPrimitive?.intOrNull
                val contentIndex = jsonObject["content_index"]?.jsonPrimitive?.intOrNull
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull
                citationPartTracker.ordinalFor(
                    outputIndex = outputIndex,
                    contentIndex = contentIndex,
                    itemId = itemId,
                )
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        text = jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                                        metadata = citationPartTracker.partIdFor(
                                            outputIndex = outputIndex,
                                            contentIndex = contentIndex,
                                            itemId = itemId,
                                        )?.let { streamPartId ->
                                            buildJsonObject {
                                                put(STREAM_PART_ID_METADATA_KEY, streamPartId)
                                            }
                                        },
                                    ),
                                ),
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_text.annotation.added" -> {
                val outputIndex = jsonObject["output_index"]?.jsonPrimitive?.intOrNull
                val contentIndex = jsonObject["content_index"]?.jsonPrimitive?.intOrNull
                val annotation = (jsonObject["annotation"] as? JsonObject)
                    ?.let {
                        parseResponseUrlCitationAnnotation(
                            annotation = it,
                            textPartOrdinal = citationPartTracker.ordinalFor(
                                outputIndex = outputIndex,
                                contentIndex = contentIndex,
                                itemId = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull,
                            ),
                            outputIndex = outputIndex,
                            contentIndex = contentIndex,
                        )
                    } ?: return null
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                                annotations = listOf(annotation),
                            ),
                            message = null,
                            finishReason = null,
                        ),
                    ),
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "function_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            toolCallId = id,
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitive?.content
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Image(url = ""))
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(url = result)
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
                )
            }
        }

        return null
    }

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()
        val annotations = arrayListOf<UIMessageAnnotation>()
        var textPartOrdinal = 0

        outputs.forEachIndexed { outputIndex, outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArray ?: error("summary not found")
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEachIndexed { contentIndex, part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val currentTextPartOrdinal = textPartOrdinal++
                                val parsed = parseResponseOutputTextWithCitations(
                                    part = part,
                                    textPartOrdinal = currentTextPartOrdinal,
                                    outputIndex = outputIndex,
                                    contentIndex = contentIndex,
                                ) ?: error("text not found")
                                annotations += parsed.annotations
                                parts.add(
                                    UIMessagePart.Text(
                                        text = parsed.text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                        annotations = annotations,
                    ),
                    finishReason = null,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

internal data class ParsedResponseOutputText(
    val text: String,
    val annotations: List<UIMessageAnnotation.UrlCitation>,
)

internal fun parseResponseOutputTextWithCitations(
    part: JsonObject,
    textPartOrdinal: Int,
    outputIndex: Int,
    contentIndex: Int,
): ParsedResponseOutputText? {
    val text = (part["text"] as? JsonPrimitive)?.contentOrNull ?: return null
    val annotations = (part["annotations"] as? JsonArray).orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.let {
            parseResponseUrlCitationAnnotation(
                annotation = it,
                textPartOrdinal = textPartOrdinal,
                outputIndex = outputIndex,
                contentIndex = contentIndex,
            )
        }
    }
    return ParsedResponseOutputText(text = text, annotations = annotations)
}

internal fun parseResponseUrlCitationAnnotation(
    annotation: JsonObject,
    textPartOrdinal: Int? = null,
    outputIndex: Int? = null,
    contentIndex: Int? = null,
): UIMessageAnnotation.UrlCitation? {
    if ((annotation["type"] as? JsonPrimitive)?.contentOrNull != "url_citation") return null
    val url = (annotation["url"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null
    return UIMessageAnnotation.UrlCitation(
        title = (annotation["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        url = url,
        startIndex = (annotation["start_index"] as? JsonPrimitive)?.intOrNull,
        endIndex = (annotation["end_index"] as? JsonPrimitive)?.intOrNull,
        textPartOrdinal = textPartOrdinal,
        offsetUnit = "provider_character",
        provenance = "provider",
        providerMetadata = if (outputIndex == null && contentIndex == null) {
            annotation
        } else {
            buildJsonObject {
                annotation.forEach { (key, value) -> put(key, value) }
                outputIndex?.let { put("responseOutputIndex", it) }
                contentIndex?.let { put("responseContentIndex", it) }
            }
        },
    )
}

internal class ResponseTextPartOrdinalTracker {
    private val ordinals = linkedMapOf<ResponseTextPartKey, Int>()

    fun ordinalFor(outputIndex: Int?, contentIndex: Int?, itemId: String?): Int? {
        val resolvedContentIndex = contentIndex ?: return null
        val key = ResponseTextPartKey(outputIndex, itemId.orEmpty(), resolvedContentIndex)
        return ordinals.getOrPut(key) { ordinals.size }
    }

    fun partIdFor(outputIndex: Int?, contentIndex: Int?, itemId: String?): String? {
        val resolvedContentIndex = contentIndex ?: return null
        return listOf(outputIndex?.toString().orEmpty(), itemId.orEmpty(), resolvedContentIndex.toString())
            .joinToString(":")
    }
}

private data class ResponseTextPartKey(
    val outputIndex: Int?,
    val itemId: String,
    val contentIndex: Int,
)

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}


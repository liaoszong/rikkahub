package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import me.rerere.ai.context.ContextDigests
import me.rerere.ai.model.ApiSurface
import me.rerere.ai.model.CapabilityMedia
import me.rerere.ai.model.ModelFeature
import me.rerere.ai.model.effectiveCapabilitySnapshot
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
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
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.rethrowIfPayloadBudgetExceeded
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private const val MAX_GOOGLE_SEARCH_SOURCES = 50
private const val MAX_GOOGLE_SEARCH_URL_CHARS = 2_048
private const val MAX_GOOGLE_SEARCH_TITLE_CHARS = 300
private const val MAX_GOOGLE_SEARCH_SUGGESTION_CHARS = 64 * 1_024

internal enum class GoogleMediaKind(
    val topLevelType: String,
    val safeDefault: String,
) {
    AUDIO("audio", "audio/mpeg"),
    VIDEO("video", "video/mp4"),
}

internal data class GoogleMediaMimeResolution(
    val mimeType: String,
    val diagnostic: String? = null,
)

internal fun resolveGoogleMediaMimeType(
    url: String,
    metadata: JsonObject?,
    kind: GoogleMediaKind,
    encodedBase64: String? = null,
): GoogleMediaMimeResolution {
    sniffGoogleMediaMimeType(encodedBase64, kind)?.let {
        return GoogleMediaMimeResolution(it)
    }

    val metadataMime = listOf("mimeType", "mime", "contentType")
        .firstNotNullOfOrNull { key -> (metadata?.get(key) as? JsonPrimitive)?.contentOrNull }
        ?.normalizeMimeType(kind)
    if (metadataMime != null) return GoogleMediaMimeResolution(metadataMime)

    val dataUrlMime = url.takeIf { it.startsWith("data:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.substringBefore(';')
        ?.normalizeMimeType(kind)
    if (dataUrlMime != null) return GoogleMediaMimeResolution(dataUrlMime)

    val extension = url.substringBefore('?').substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    val extensionMime = when (kind) {
        GoogleMediaKind.AUDIO -> when (extension) {
            "wav", "wave" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "ogg", "oga" -> "audio/ogg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            else -> null
        }

        GoogleMediaKind.VIDEO -> when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            else -> null
        }
    }
    if (extensionMime != null) return GoogleMediaMimeResolution(extensionMime)

    return GoogleMediaMimeResolution(
        mimeType = kind.safeDefault,
        diagnostic = "Google media MIME unresolved; kind=${kind.name.lowercase()} " +
            "extension=${extension.ifBlank { "none" }} fallback=${kind.safeDefault}",
    )
}

@OptIn(ExperimentalEncodingApi::class)
private fun sniffGoogleMediaMimeType(encodedBase64: String?, kind: GoogleMediaKind): String? {
    if (encodedBase64.isNullOrEmpty()) return null
    val compact = buildString(capacity = 256) {
        for (character in encodedBase64) {
            if (!character.isWhitespace()) append(character)
            if (length == 256) break
        }
    }.takeIf(String::isNotEmpty) ?: return null
    val prefixText = compact.take(256).let { it.take(it.length - (it.length % 4)) }
    if (prefixText.isEmpty()) return null
    val bytes = runCatching { Base64.decode(prefixText) }.getOrNull() ?: return null

    fun ascii(offset: Int, length: Int): String? = bytes.takeIf { it.size >= offset + length }
        ?.let { String(it, offset, length, Charsets.US_ASCII) }

    return when (kind) {
        GoogleMediaKind.AUDIO -> when {
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> "audio/wav"
            ascii(0, 4) == "fLaC" -> "audio/flac"
            ascii(0, 4) == "OggS" -> "audio/ogg"
            ascii(0, 3) == "ID3" -> "audio/mpeg"
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && (bytes[1].toInt() and 0xe0) == 0xe0 -> {
                if ((bytes[1].toInt() and 0xf6) == 0xf0) "audio/aac" else "audio/mpeg"
            }
            ascii(4, 4) == "ftyp" -> "audio/mp4"
            bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(
                byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()),
            ) -> "audio/webm"
            else -> null
        }

        GoogleMediaKind.VIDEO -> when {
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "AVI " -> "video/x-msvideo"
            ascii(0, 4) == "OggS" -> "video/ogg"
            ascii(4, 4) == "ftyp" && ascii(8, 4)?.startsWith("qt") == true -> "video/quicktime"
            ascii(4, 4) == "ftyp" -> "video/mp4"
            bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(
                byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()),
            ) -> "video/webm"
            else -> null
        }
    }
}

private fun String.normalizeMimeType(kind: GoogleMediaKind): String? =
    substringBefore(';').trim().lowercase().takeIf { it.startsWith("${kind.topLevelType}/") }

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    internal suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        if (request.header("Authorization") != null || request.header("x-goog-api-key") != null) return request
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            if (providerSetting.apiKey.isBlank()) {
                return request
            }
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: response received (${body.length} chars)")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]!!.jsonArray
                            .map { method -> method.jsonPrimitive.content }
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    ModelRegistry.enrichCapabilities(Model(
                        modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                        displayName = modelObject["displayName"]!!.jsonPrimitive.content,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    ))
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildCompletionRequestBody(providerSetting, messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerAuthHeaders(params.customHeaders))
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        params.dispatchObserver.onDispatch()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidates = bodyJson["candidates"]!!.jsonArray
        val usage = bodyJson["usageMetadata"]!!.jsonObject

        val messageChunk = MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = candidates.map { candidate ->
                UIMessageChoice(
                    message = parseMessage(candidate.jsonObject),
                    index = 0,
                    finishReason = null,
                    delta = null
                )
            },
            usage = parseUsageMeta(usage)
        )

        messageChunk
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(providerSetting, messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerAuthHeaders(params.customHeaders))
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        Log.i(TAG, "event=operation domain=provider operation=stream_text outcome=started itemCount=${messages.size}")

        val groundingAccumulator = GoogleGroundingAccumulator()
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    val reason =
                        jsonData["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
                    if (reason != null) {
                        close(RuntimeException("Prompt feedback: $reason"))
                    }
                    val candidates = jsonData["candidates"]?.jsonArray ?: return
                    if (candidates.isEmpty()) return
                    val usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
                    val messageChunk = MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = candidates.mapIndexed { index, candidate ->
                            val candidateObj = candidate.jsonObject
                            val content = candidateObj["content"]?.jsonObject
                            val candidateIndex = candidateObj["index"]?.jsonPrimitive?.intOrNull ?: index
                            val textPartOrdinals = groundingAccumulator.observeTextParts(candidateIndex, content)
                            val groundingMetadata = (candidateObj["groundingMetadata"] as? JsonObject)?.let {
                                groundingAccumulator.accumulate(candidateIndex, it)
                            }
                            val finishReason =
                                candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                            val message = if (content != null || groundingMetadata != null) {
                                parseMessage(buildJsonObject {
                                    put("role", JsonPrimitive("model"))
                                    put("content", content ?: buildJsonObject {
                                        put("parts", JsonArray(emptyList()))
                                    })
                                    groundingMetadata?.let { groundingMetadata ->
                                        put("groundingMetadata", groundingMetadata)
                                    }
                                }, textPartOrdinals, "gemini:$candidateIndex")
                            } else null

                            UIMessageChoice(
                                index = index,
                                delta = message,
                                message = null,
                                finishReason = finishReason
                            )
                        },
                        usage = usage
                    )

                    trySend(messageChunk).onFailure { e ->
                        Log.w(TAG, "onEvent: chunk dropped (${e?.javaClass?.simpleName})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: parse failed (${e.javaClass.simpleName})")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                Log.e(
                    TAG,
                    "event=operation domain=provider operation=stream_text outcome=failed " +
                        "errorClass=${t?.javaClass?.simpleName ?: "HttpError"} httpStatus=${response?.code}",
                )

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: error response parse failed (${e.javaClass.simpleName})")
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
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

    private fun buildCompletionRequestBody(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildJsonObject {
        val effectiveCapabilities = params.model.effectiveCapabilitySnapshot(providerSetting)
        require(ApiSurface.GENERATE_CONTENT in effectiveCapabilities.apiSurfaces) {
            "Model ${params.model.modelId} does not declare Gemini generateContent"
        }
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && CapabilityMedia.IMAGE !in effectiveCapabilities.outputMedia) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString(separator = "\n\n") { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (CapabilityMedia.IMAGE in effectiveCapabilities.outputMedia) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (ModelFeature.REASONING in effectiveCapabilities.features) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Function declarations and provider built-ins share Gemini's single `tools` array.
        // Building it once prevents a later built-in-tools write from silently replacing functions.
        val tools = buildJsonArray {
            if (params.tools.isNotEmpty() && ModelFeature.TOOL_CALLING in effectiveCapabilities.features) {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put(
                                    key = "parameters",
                                    element = json.encodeToJsonElement(tool.parameters())
                                        .removeElements(
                                            listOf(
                                                "const",
                                                "exclusiveMaximum",
                                                "exclusiveMinimum",
                                                "format",
                                                "additionalProperties",
                                                "enum",
                                            )
                                        )
                                )
                            })
                        }
                    })
                })
            }
            params.model.tools.forEach { builtInTool ->
                if (builtInTool in params.disabledBuiltInTools) return@forEach
                val supported = when (builtInTool) {
                    BuiltInTools.Search -> ModelFeature.WEB_SEARCH in effectiveCapabilities.features
                    BuiltInTools.UrlContext -> ModelFeature.URL_CONTEXT in effectiveCapabilities.features
                    BuiltInTools.ImageGeneration ->
                        ModelFeature.IMAGE_GENERATION in effectiveCapabilities.features
                }
                if (!supported) return@forEach
                when (builtInTool) {
                    BuiltInTools.Search -> {
                        add(buildJsonObject {
                            put("googleSearch", buildJsonObject {})
                        })
                    }

                    BuiltInTools.UrlContext -> {
                        add(buildJsonObject {
                            put("urlContext", buildJsonObject {})
                        })
                    }

                    BuiltInTools.ImageGeneration -> throw IllegalArgumentException(
                        "Gemini generateContent does not support image_generation as a tool declaration; " +
                            "use IMAGE output modality instead"
                    )
                }
            }
        }
        if (tools.isNotEmpty()) {
            put("tools", tools)
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(
        message: JsonObject,
        observedTextPartOrdinals: Map<Int, Int>? = null,
        streamPartIdPrefix: String? = null,
    ): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val rawParts = content["parts"]?.jsonArray?.map(JsonElement::jsonObject).orEmpty()
        val parts = rawParts.mapIndexed { providerPartIndex, part ->
            parseMessagePart(
                jsonObject = part,
                streamPartId = streamPartIdPrefix?.let { "$it:$providerPartIndex" },
            )
        }

        val groundingMetadata = message["groundingMetadata"] as? JsonObject
        val annotations = parseSearchGroundingMetadata(
            groundingMetadata,
            observedTextPartOrdinals ?: googleTextPartOrdinals(rawParts),
            rawParts.mapIndexedNotNull { index, element ->
                (element as? JsonObject)?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                    ?.let { index to it }
            }.toMap(),
        ) + listOfNotNull(
            groundingMetadata?.let { metadata ->
                parseGoogleSearchEvent(metadata, streamPartIdPrefix)
            },
        )

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(
        jsonObject: JsonObject?,
        textPartOrdinals: Map<Int, Int>,
        textByProviderPartIndex: Map<Int, String>,
    ): List<UIMessageAnnotation> = parseGoogleSearchGroundingMetadata(
        jsonObject,
        textPartOrdinals,
        textByProviderPartIndex,
    )

    internal fun parseGoogleSearchEvent(
        metadata: JsonObject,
        streamPartIdPrefix: String?,
    ): UIMessageAnnotation.ProviderToolEvent {
        val chunks = (metadata["groundingChunks"] as? JsonArray).orEmpty()
        val sources = chunks.take(MAX_GOOGLE_SEARCH_SOURCES).mapNotNull { element ->
            val web = (element as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
            val url = web["uri"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.take(MAX_GOOGLE_SEARCH_URL_CHARS)
                ?: return@mapNotNull null
            buildJsonObject {
                put("url", url)
                web["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?.take(MAX_GOOGLE_SEARCH_TITLE_CHARS)
                    ?.let { put("title", it) }
            }
        }
        val queries = (metadata["webSearchQueries"] as? JsonArray).orEmpty()
        val renderedSuggestion = ((metadata["searchEntryPoint"] as? JsonObject)
            ?.get("renderedContent") as? JsonPrimitive)
            ?.contentOrNull
            ?.take(MAX_GOOGLE_SEARCH_SUGGESTION_CHARS)
        val bounded = buildJsonObject {
            put("source_count", chunks.size)
            put("sources_truncated", chunks.size > sources.size)
            put("sources", JsonArray(sources))
            put("query_count", queries.size)
            if (queries.isNotEmpty()) put("queries_digest", ContextDigests.sha256(queries.toString()))
            renderedSuggestion?.let { put("search_suggestion_html", it) }
            put(
                "search_suggestion_truncated",
                renderedSuggestion != null &&
                    (((metadata["searchEntryPoint"] as? JsonObject)
                        ?.get("renderedContent") as? JsonPrimitive)?.contentOrNull?.length ?: 0) >
                    renderedSuggestion.length,
            )
        }
        val digest = ContextDigests.sha256(bounded.toString())
        return UIMessageAnnotation.ProviderToolEvent(
            provider = "google",
            toolType = "google_search",
            callId = streamPartIdPrefix?.let { "google-search:$it" } ?: "google-search:${digest.take(16)}",
            status = "completed",
            actionType = "search",
            payloadDigest = digest,
            providerMetadata = bounded,
        )
    }

    private fun parseMessagePart(
        jsonObject: JsonObject,
        streamPartId: String? = null,
    ): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null
                ) else UIMessagePart.Text(
                    text = text,
                    metadata = streamPartId?.let {
                        buildJsonObject { put(STREAM_PART_ID_METADATA_KEY, it) }
                    },
                )
            }

            jsonObject.containsKey("functionCall") -> {
                UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(jsonObject["functionCall"]!!.jsonObject["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = data,
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        val attachmentBudget = AttachmentBudgetTracker()
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message, attachmentBudget)
                    } else {
                        addUserMessage(message, attachmentBudget)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(
        message: UIMessage,
        attachmentBudget: AttachmentBudgetTracker,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toGooglePart(attachmentBudget) }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart(attachmentBudget)) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: UIMessage,
        attachmentBudget: AttachmentBudgetTracker,
    ) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.mapNotNull { it.toGooglePart(attachmentBudget) }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGooglePart(attachmentBudget: AttachmentBudgetTracker): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
        }

        is UIMessagePart.Image -> {
            encodeBase64(withPrefix = false, budgetTracker = attachmentBudget).fold(
                onSuccess = { encoded ->
                    buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", encoded.mimeType)
                            put("data", encoded.base64)
                        })
                        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                            put("thoughtSignature", it)
                        }
                    }
                },
                onFailure = { error ->
                    error.rethrowIfPayloadBudgetExceeded()
                    null
                },
            )
        }

        is UIMessagePart.Video -> {
            googleMediaBase64(attachmentBudget)?.let { base64Data ->
                val mime = resolveGoogleMediaMimeType(url, metadata, GoogleMediaKind.VIDEO, base64Data)
                if (mime.diagnostic != null) {
                    Log.w(TAG, "event=operation domain=provider operation=resolve_video_mime outcome=failed")
                }
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", mime.mimeType)
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            googleMediaBase64(attachmentBudget)?.let { base64Data ->
                val mime = resolveGoogleMediaMimeType(url, metadata, GoogleMediaKind.AUDIO, base64Data)
                if (mime.diagnostic != null) {
                    Log.w(TAG, "event=operation domain=provider operation=resolve_audio_mime outcome=failed")
                }
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", mime.mimeType)
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Video.googleMediaBase64(attachmentBudget: AttachmentBudgetTracker): String? =
        encodeBase64(withPrefix = false, budgetTracker = attachmentBudget).fold(
            onSuccess = { it },
            onFailure = { error ->
                error.rethrowIfPayloadBudgetExceeded()
                null
            },
        )

    private fun UIMessagePart.Audio.googleMediaBase64(attachmentBudget: AttachmentBudgetTracker): String? =
        encodeBase64(withPrefix = false, budgetTracker = attachmentBudget).fold(
            onSuccess = { it },
            onFailure = { error ->
                error.rethrowIfPayloadBudgetExceeded()
                null
            },
        )

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart(attachmentBudget: AttachmentBudgetTracker) = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart(attachmentBudget) }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }
}

internal fun parseGoogleSearchGroundingMetadata(
    jsonObject: JsonObject?,
    textPartOrdinals: Map<Int, Int> = emptyMap(),
    textByProviderPartIndex: Map<Int, String> = emptyMap(),
): List<UIMessageAnnotation> {
    if (jsonObject == null) return emptyList()
    val groundingChunks = jsonObject["groundingChunks"] as? JsonArray ?: JsonArray(emptyList())
    // Keep the provider's original indexes: groundingChunkIndices addresses this raw array.
    // Compacting invalid chunks here could silently bind a support span to the wrong URL.
    val chunks = groundingChunks.map { chunk ->
        val chunkObject = chunk as? JsonObject ?: return@map null
        val web = chunkObject["web"] as? JsonObject ?: return@map null
        val uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: return@map null
        val title = (web["title"] as? JsonPrimitive)?.contentOrNull ?: return@map null
        UIMessageAnnotation.UrlCitation(
            title = title,
            url = uri,
            provenance = "provider",
            providerMetadata = chunkObject,
        )
    }
    val supports = jsonObject["groundingSupports"] as? JsonArray ?: JsonArray(emptyList())
    val supported = supports.flatMap { supportElement ->
        val support = supportElement as? JsonObject ?: return@flatMap emptyList()
        val segment = support["segment"] as? JsonObject
        val providerPartIndex = (segment?.get("partIndex") as? JsonPrimitive)?.intOrNull
        val providerText = providerPartIndex?.let(textByProviderPartIndex::get)
        val rawStart = (segment?.get("startIndex") as? JsonPrimitive)?.intOrNull
        val rawEnd = (segment?.get("endIndex") as? JsonPrimitive)?.intOrNull
        val indices = support["groundingChunkIndices"] as? JsonArray ?: JsonArray(emptyList())
        indices.mapNotNull { indexElement ->
            val index = (indexElement as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            chunks.getOrNull(index)?.copy(
                startIndex = rawStart?.let { providerText?.utf8ByteOffsetToUtf16(it) ?: it },
                endIndex = rawEnd?.let { providerText?.utf8ByteOffsetToUtf16(it) ?: it },
                textPartOrdinal = providerPartIndex?.let(textPartOrdinals::get),
                offsetUnit = if (providerText == null) "utf8_byte" else "utf16_code_unit",
                quote = (segment?.get("text") as? JsonPrimitive)?.contentOrNull,
                providerMetadata = support,
            )
        }
    }
    return supported.ifEmpty { chunks.filterNotNull() }
}

private fun String.utf8ByteOffsetToUtf16(offset: Int): Int {
    val target = offset.coerceAtLeast(0)
    var utf8Bytes = 0
    var utf16Index = 0
    while (utf16Index < length && utf8Bytes < target) {
        val codePoint = codePointAt(utf16Index)
        val charCount = Character.charCount(codePoint)
        val byteCount = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (utf8Bytes + byteCount > target) break
        utf8Bytes += byteCount
        utf16Index += charCount
    }
    return utf16Index
}

/** Gemini streaming indexes grounding chunks across all events for one candidate. */
internal class GoogleGroundingAccumulator {
    private val chunksByCandidate = mutableMapOf<Int, MutableList<JsonElement>>()
    private val textPartOrdinalsByCandidate = mutableMapOf<Int, Map<Int, Int>>()

    fun accumulate(candidateIndex: Int, metadata: JsonObject): JsonObject {
        val accumulated = chunksByCandidate.getOrPut(candidateIndex) { mutableListOf() }
        (metadata["groundingChunks"] as? JsonArray)?.let(accumulated::addAll)
        return buildJsonObject {
            metadata.forEach { (key, value) ->
                if (key != "groundingChunks") put(key, value)
            }
            put("groundingChunks", JsonArray(accumulated.toList()))
        }
    }

    fun observeTextParts(candidateIndex: Int, content: JsonObject?): Map<Int, Int> {
        val observed = (content?.get("parts") as? JsonArray)
            ?.let(::googleTextPartOrdinals)
            .orEmpty()
        if (observed.isNotEmpty()) {
            textPartOrdinalsByCandidate[candidateIndex] =
                textPartOrdinalsByCandidate[candidateIndex].orEmpty() + observed
        }
        return textPartOrdinalsByCandidate[candidateIndex].orEmpty()
    }
}

internal fun googleTextPartOrdinals(parts: List<JsonObject>): Map<Int, Int> = buildMap {
    var ordinal = 0
    parts.forEachIndexed { providerPartIndex, part ->
        val isThought = (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
        if (part["text"] is JsonPrimitive && !isThought) {
            put(providerPartIndex, ordinal++)
        }
    }
}

/**
 * Streaming metadata addresses the provider's raw parts array. Invalid side-channel
 * elements must be ignored without compacting the indexes of the remaining parts.
 */
internal fun googleTextPartOrdinals(parts: JsonArray): Map<Int, Int> = buildMap {
    var ordinal = 0
    parts.forEachIndexed { providerPartIndex, element ->
        val part = element as? JsonObject ?: return@forEachIndexed
        val isThought = (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
        if (part["text"] is JsonPrimitive && !isThought) {
            put(providerPartIndex, ordinal++)
        }
    }
}

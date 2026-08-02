package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import java.net.URI

internal object BackupSettingsSanitizer {
    private val sensitiveKeys = setOf(
        "apikey",
        "xapikey",
        "privatekey",
        "clientsecret",
        "accesstoken",
        "refreshtoken",
        "password",
        "secretaccesskey",
        "accesskeyid",
        "authorization",
        "proxyauthorization",
        "auth",
        "authentication",
        "xauth",
        "authkey",
        "xauthkey",
        "cookie",
        "setcookie",
        "credentials",
        "secret",
        "token",
        "sessiontoken",
    )
    private val sensitiveSuffixes = setOf(
        "apikey",
        "privatekey",
        "secretkey",
        "password",
        "secret",
        "token",
        "credential",
        "credentials",
    )

    private val semanticSecretFields = listOf(
        SemanticField(labelKey = "name", valueKey = "value", kind = SemanticKind.HEADER),
        SemanticField(labelKey = "key", valueKey = "value", kind = SemanticKind.BODY),
        SemanticField(labelKey = "first", valueKey = "second", kind = SemanticKind.HEADER),
    )
    private val safeHeaderValueNames = setOf(
        "accept",
        "acceptencoding",
        "contenttype",
        "useragent",
    )
    private val safeBodyValueNames = setOf(
        "frequency_penalty",
        "max_output_tokens",
        "max_tokens",
        "n",
        "presence_penalty",
        "reasoning_effort",
        "response_format",
        "seed",
        "stop",
        "stream",
        "temperature",
        "thinking_budget",
        "top_k",
        "top_p",
        "translation_options",
        "verbosity",
    ).mapTo(mutableSetOf()) { it.normalizedSecretKey() }
    private val headerContainerKeys = setOf("headers", "customheaders", "mcpheaders", "requestheaders")
    private val bodyContainerKeys = setOf("custombody", "custombodies", "requestbody")
    private val endpointScopeKeys = setOf(
        "authorizationendpoint",
        "baseurl",
        "customurl",
        "endpoint",
        "registrationendpoint",
        "scrapeurl",
        "searchurl",
        "tokenendpoint",
        "url",
        "websocketurl",
    )
    private val literalScopeKeys = setOf(
        "bucket",
        "location",
        "pathstyle",
        "projectid",
        "region",
        "serviceaccountemail",
        "username",
    )

    fun encode(settings: Settings, json: Json): String {
        val element = json.encodeToJsonElement(Settings.serializer(), settings)
        return json.encodeToString(sanitize(element))
    }

    internal fun sanitize(element: JsonElement): JsonElement = sanitizeElement(element, parentKey = null)

    private fun sanitizeElement(element: JsonElement, parentKey: String?): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { sanitizeElement(it, parentKey = null) })
        is JsonObject -> sanitizeObject(element, parentKey)
        else -> element
    }

    /**
     * Restores only redacted secret values from the local settings graph.
     *
     * Arrays are joined by a stable owner id or by a semantic header/body slot. We deliberately
     * do not fall back to list indexes: an older backup may have a different order and must not
     * receive credentials from an unrelated provider, model, assistant, or MCP server.
     */
    internal fun mergeLocalSecrets(restored: JsonElement, local: JsonElement): JsonElement {
        val context = MergeContext(
            restoredScopes = ScopeCatalog.fromSettings(restored),
            localScopes = ScopeCatalog.fromSettings(local),
        )
        return mergeLocalSecrets(restored, local, context)
    }

    private fun mergeLocalSecrets(
        restored: JsonElement,
        local: JsonElement,
        context: MergeContext,
    ): JsonElement = when {
        restored is JsonObject && local is JsonObject -> mergeObjectSecrets(restored, local, context)
        restored is JsonArray && local is JsonArray -> mergeArraySecrets(restored, local, context)
        else -> restored
    }

    private fun sanitizeObject(element: JsonObject, parentKey: String?): JsonObject {
        val containerKind = parentKey.semanticContainerKind()
        val sanitized = element.mapValuesTo(linkedMapOf()) { (key, value) ->
            val semanticContainerSecret = when (containerKind) {
                SemanticKind.HEADER -> !key.isSafeHeaderValueName()
                SemanticKind.BODY -> !key.isSafeBodyValueName()
                null -> false
            }
            if (key.isSensitiveKey() || semanticContainerSecret) {
                JsonPrimitive("")
            } else {
                sanitizeElement(value, parentKey = key)
            }
        }
        element.semanticField(requireValue = true)
            ?.takeIf(SemanticValue::isSecret)
            ?.let { sanitized[it.valueKey] = JsonPrimitive("") }
        return JsonObject(sanitized)
    }

    private fun mergeObjectSecrets(
        restored: JsonObject,
        local: JsonObject,
        context: MergeContext,
    ): JsonObject {
        val restoredIdentity = restored.stableIdentity(context.restoredScopes)
        val localIdentity = local.stableIdentity(context.localScopes)
        if ((restoredIdentity != null || localIdentity != null) && restoredIdentity != localIdentity) {
            return restored
        }
        val restoredScope = restored.credentialScope()
        val localScope = local.credentialScope()
        if (
            (restored.requiresExplicitEndpointScope() || local.requiresExplicitEndpointScope()) &&
            (!restored.hasTrustedEndpointScope() || !local.hasTrustedEndpointScope())
        ) {
            return restored
        }
        if ((restoredScope != null || localScope != null) && restoredScope != localScope) {
            return restored
        }

        val merged = restored.toMutableMap()

        restored.forEach { (key, restoredValue) ->
            val localValue = local[key] ?: return@forEach
            merged[key] = if (key.isSensitiveKey() && restoredValue.isRedactedSecret()) {
                localValue.takeIf {
                    it.hasSecretValue() && secretFieldScopeMatches(key, restored, local)
                } ?: restoredValue
            } else {
                mergeLocalSecrets(restoredValue, localValue, context)
            }
        }

        local.forEach { (key, localValue) ->
            if (
                key !in restored &&
                key.isSensitiveKey() &&
                localValue.hasSecretValue() &&
                secretFieldScopeMatches(key, restored, local)
            ) {
                merged[key] = localValue
            }
        }

        val restoredField = restored.semanticField()?.takeIf(SemanticValue::isSecret)
        val localField = local.semanticField(requireValue = true)
        if (
            restoredField != null &&
            restoredField.sameSlot(localField) &&
            restored[restoredField.valueKey].isRedactedSecret()
        ) {
            local[restoredField.valueKey]
                ?.takeIf { it.hasSecretValue() }
                ?.let { merged[restoredField.valueKey] = it }
        }

        return JsonObject(merged)
    }

    private fun mergeArraySecrets(
        restored: JsonArray,
        local: JsonArray,
        context: MergeContext,
    ): JsonArray {
        val localCandidates = local.mapIndexedNotNull { index, element ->
            element.stableIdentity(context.localScopes)?.let { identity ->
                LocalCandidate(index, identity, element)
            }
        }
        val consumed = mutableSetOf<Int>()

        return JsonArray(restored.map { restoredElement ->
            val identity = restoredElement.stableIdentity(context.restoredScopes) ?: return@map restoredElement
            val candidate = localCandidates.firstOrNull { it.index !in consumed && it.identity == identity }
                ?: return@map restoredElement
            consumed += candidate.index
            mergeLocalSecrets(restoredElement, candidate.element, context)
        })
    }

    private fun JsonElement.stableIdentity(scopes: ScopeCatalog): String? {
        val objectValue = this as? JsonObject ?: return null
        objectValue.primitiveContent("id")?.let { id ->
            val type = objectValue.primitiveContent("type").orEmpty()
            if (objectValue.requiresExplicitEndpointScope() && !objectValue.hasTrustedEndpointScope()) return null
            val scope = when {
                objectValue.isAssistantOwner() -> scopes.assistantScope(objectValue) ?: return null
                objectValue.isModelOwner() -> scopes.modelScopes[id] ?: return null
                else -> objectValue.credentialScope()
            }.orEmpty()
            return "owner:$type:$id:$scope"
        }
        return objectValue.semanticField()?.let { field ->
            "slot:${field.labelKey}:${field.label.normalizedSecretKey()}"
        }
    }

    private fun JsonObject.semanticField(requireValue: Boolean = false): SemanticValue? =
        semanticSecretFields.firstNotNullOfOrNull { field ->
            primitiveContent(field.labelKey)
                ?.takeIf { !requireValue || field.valueKey in this }
                ?.let { label -> SemanticValue(field.labelKey, field.valueKey, label, field.kind) }
        }

    private fun JsonObject.primitiveContent(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString && it.isNotBlank() }
    }

    private fun JsonObject.isAssistantOwner(): Boolean =
        "modelId" !in this && ("customHeaders" in this || "customBodies" in this)

    private fun JsonObject.isModelOwner(): Boolean = "modelId" in this

    private fun JsonObject.requiresExplicitEndpointScope(): Boolean =
        "models" in this ||
            "commonOptions" in this ||
            "secretAccessKey" in this ||
            ("password" in this && "url" in this) ||
            ("apiKey" in this && keys.any { it.normalizedSecretKey() in endpointScopeKeys })

    private fun JsonObject.hasTrustedEndpointScope(): Boolean = entries.any { (rawKey, element) ->
        rawKey.normalizedSecretKey() in endpointScopeKeys &&
            element is JsonPrimitive &&
            element != JsonNull &&
            element.content.isNotBlank()
    }

    private fun JsonObject.credentialScope(): String? {
        val scopeParts = entries.mapNotNull { (rawKey, element) ->
            val key = rawKey.normalizedSecretKey()
            if (key !in endpointScopeKeys && key !in literalScopeKeys) return@mapNotNull null
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (primitive == JsonNull) return@mapNotNull null
            val value = if (key in endpointScopeKeys) {
                normalizeEndpoint(primitive.content)
            } else {
                primitive.content.trim()
            }
            ScopePart(key, value)
        }.sortedBy(ScopePart::key)
        return scopeParts.takeIf(List<ScopePart>::isNotEmpty)?.joinToString("|") { it.encoded() }
    }

    private fun secretFieldScopeMatches(
        key: String,
        restoredOwner: JsonObject,
        localOwner: JsonObject,
    ): Boolean {
        if (key.normalizedSecretKey() != "webserveraccesspassword") return true
        val restoredScope = restoredOwner.webServerScope() ?: return false
        return restoredScope == localOwner.webServerScope()
    }

    private fun JsonObject.webServerScope(): String? {
        val fields = listOf("webServerPort", "webServerLocalhostOnly")
        val scopeParts = fields.mapNotNull { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@mapNotNull null
            if (primitive == JsonNull) return@mapNotNull null
            ScopePart(key.normalizedSecretKey(), primitive.content.trim())
        }
        return scopeParts
            .takeIf { it.size == fields.size }
            ?.joinToString("|") { it.encoded() }
    }

    private fun normalizeEndpoint(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching trimmed.trimEnd('/')
            val rawHost = uri.host ?: return@runCatching trimmed.trimEnd('/')
            val host = rawHost.lowercase().let { if (':' in it) "[$it]" else it }
            val port = uri.port.takeUnless { isDefaultPort(scheme, it) }
                ?.takeIf { it >= 0 }
                ?.let { ":$it" }
                .orEmpty()
            val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$userInfo$host$port$path$query"
        }.getOrElse { trimmed.trimEnd('/') }
    }

    private fun isDefaultPort(scheme: String, port: Int): Boolean = when (scheme) {
        "http", "ws" -> port == 80
        "https", "wss" -> port == 443
        else -> false
    }

    private fun String?.semanticContainerKind(): SemanticKind? {
        val normalized = this?.normalizedSecretKey() ?: return null
        return when {
            normalized in headerContainerKeys -> SemanticKind.HEADER
            normalized in bodyContainerKeys -> SemanticKind.BODY
            else -> null
        }
    }

    private fun String.isSafeHeaderValueName(): Boolean = normalizedSecretKey() in safeHeaderValueNames

    private fun String.isSafeBodyValueName(): Boolean = normalizedSecretKey() in safeBodyValueNames

    private fun JsonElement?.isRedactedSecret(): Boolean = when (this) {
        null, JsonNull -> true
        is JsonPrimitive -> content.isBlank()
        else -> false
    }

    private fun JsonElement.hasSecretValue(): Boolean = !isRedactedSecret()

    private fun String.normalizedSecretKey(): String = lowercase().filter(Char::isLetterOrDigit)

    private fun String.isSensitiveKey(): Boolean {
        val normalized = normalizedSecretKey()
        return normalized in sensitiveKeys || sensitiveSuffixes.any(normalized::endsWith)
    }

    private data class SemanticField(
        val labelKey: String,
        val valueKey: String,
        val kind: SemanticKind,
    )

    private data class SemanticValue(
        val labelKey: String,
        val valueKey: String,
        val label: String,
        val kind: SemanticKind,
    ) {
        fun isSecret(): Boolean = when (kind) {
            SemanticKind.HEADER -> !label.isSafeHeaderValueName()
            SemanticKind.BODY -> !label.isSafeBodyValueName()
        }

        fun sameSlot(other: SemanticValue?): Boolean =
            other != null &&
                kind == other.kind &&
                labelKey == other.labelKey &&
                valueKey == other.valueKey &&
                label.normalizedSecretKey() == other.label.normalizedSecretKey()
    }

    private enum class SemanticKind { HEADER, BODY }

    private data class ScopePart(val key: String, val value: String) {
        fun encoded(): String = "${key.length}:$key=${value.length}:$value"
    }

    private data class ScopeCatalog(
        val defaultModelId: String?,
        val modelScopes: Map<String, String>,
    ) {
        fun assistantScope(assistant: JsonObject): String? {
            val modelId = assistant.primitiveContent("chatModelId") ?: defaultModelId ?: return null
            return modelScopes[modelId]
        }

        companion object {
            fun fromSettings(element: JsonElement): ScopeCatalog {
                val settings = element as? JsonObject ?: return ScopeCatalog(null, emptyMap())
                val defaultModelId = settings.primitiveContent("chatModelId")
                val modelScopes = mutableMapOf<String, String>()
                val ambiguousModelIds = mutableSetOf<String>()
                val providers = settings["providers"] as? JsonArray ?: return ScopeCatalog(defaultModelId, modelScopes)
                for (providerElement in providers) {
                    val provider = providerElement as? JsonObject ?: continue
                    if (provider.requiresExplicitEndpointScope() && !provider.hasTrustedEndpointScope()) continue
                    val providerScope = provider.credentialScope() ?: continue
                    val models = provider["models"] as? JsonArray ?: continue
                    for (modelElement in models) {
                        val model = modelElement as? JsonObject ?: continue
                        val modelId = model.primitiveContent("id") ?: continue
                        val overwrite = model["providerOverwrite"] as? JsonObject
                        val modelScope = if (overwrite == null) {
                            providerScope
                        } else {
                            if (overwrite.requiresExplicitEndpointScope() && !overwrite.hasTrustedEndpointScope()) {
                                continue
                            }
                            overwrite.credentialScope() ?: continue
                        }
                        if (modelId in ambiguousModelIds) continue
                        val previousScope = modelScopes[modelId]
                        if (previousScope != null && previousScope != modelScope) {
                            modelScopes -= modelId
                            ambiguousModelIds += modelId
                        } else {
                            modelScopes[modelId] = modelScope
                        }
                    }
                }
                return ScopeCatalog(defaultModelId, modelScopes)
            }
        }
    }

    private data class MergeContext(
        val restoredScopes: ScopeCatalog,
        val localScopes: ScopeCatalog,
    )

    private data class LocalCandidate(
        val index: Int,
        val identity: String,
        val element: JsonElement,
    )
}

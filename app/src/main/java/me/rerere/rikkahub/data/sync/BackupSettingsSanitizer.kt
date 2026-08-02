package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings

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
        "cookie",
        "setcookie",
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
    )

    private val semanticSecretFields = listOf(
        SemanticField(labelKey = "name", valueKey = "value"),
        SemanticField(labelKey = "key", valueKey = "value"),
        SemanticField(labelKey = "first", valueKey = "second"),
    )

    fun encode(settings: Settings, json: Json): String {
        val element = json.encodeToJsonElement(Settings.serializer(), settings)
        return json.encodeToString(sanitize(element))
    }

    internal fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::sanitize))
        is JsonObject -> sanitizeObject(element)
        else -> element
    }

    /**
     * Restores only redacted secret values from the local settings graph.
     *
     * Arrays are joined by a stable owner id or by a semantic header/body slot. We deliberately
     * do not fall back to list indexes: an older backup may have a different order and must not
     * receive credentials from an unrelated provider, model, assistant, or MCP server.
     */
    internal fun mergeLocalSecrets(restored: JsonElement, local: JsonElement): JsonElement = when {
        restored is JsonObject && local is JsonObject -> mergeObjectSecrets(restored, local)
        restored is JsonArray && local is JsonArray -> mergeArraySecrets(restored, local)
        else -> restored
    }

    private fun sanitizeObject(element: JsonObject): JsonObject {
        val sanitized = element.mapValuesTo(linkedMapOf()) { (key, value) ->
            if (key.isSensitiveKey()) JsonPrimitive("") else sanitize(value)
        }
        element.semanticField(requireValue = true)
            ?.takeIf { it.label.isSensitiveKey() }
            ?.let { sanitized[it.valueKey] = JsonPrimitive("") }
        return JsonObject(sanitized)
    }

    private fun mergeObjectSecrets(restored: JsonObject, local: JsonObject): JsonObject {
        val restoredIdentity = restored.stableIdentity()
        val localIdentity = local.stableIdentity()
        if ((restoredIdentity != null || localIdentity != null) && restoredIdentity != localIdentity) {
            return restored
        }

        val merged = restored.toMutableMap()

        restored.forEach { (key, restoredValue) ->
            val localValue = local[key] ?: return@forEach
            merged[key] = if (key.isSensitiveKey() && restoredValue.isRedactedSecret()) {
                localValue.takeIf { it.hasSecretValue() } ?: restoredValue
            } else {
                mergeLocalSecrets(restoredValue, localValue)
            }
        }

        local.forEach { (key, localValue) ->
            if (key !in restored && key.isSensitiveKey() && localValue.hasSecretValue()) {
                merged[key] = localValue
            }
        }

        val restoredField = restored.semanticField()?.takeIf { it.label.isSensitiveKey() }
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

    private fun mergeArraySecrets(restored: JsonArray, local: JsonArray): JsonArray {
        val localCandidates = local.mapIndexedNotNull { index, element ->
            element.stableIdentity()?.let { identity -> LocalCandidate(index, identity, element) }
        }
        val consumed = mutableSetOf<Int>()

        return JsonArray(restored.map { restoredElement ->
            val identity = restoredElement.stableIdentity() ?: return@map restoredElement
            val candidate = localCandidates.firstOrNull { it.index !in consumed && it.identity == identity }
                ?: return@map restoredElement
            consumed += candidate.index
            mergeLocalSecrets(restoredElement, candidate.element)
        })
    }

    private fun JsonElement.stableIdentity(): String? {
        val objectValue = this as? JsonObject ?: return null
        objectValue.primitiveContent("id")?.let { id ->
            val type = objectValue.primitiveContent("type").orEmpty()
            return "owner:$type:$id"
        }
        return objectValue.semanticField()?.let { field ->
            "slot:${field.labelKey}:${field.label.normalizedSecretKey()}"
        }
    }

    private fun JsonObject.semanticField(requireValue: Boolean = false): SemanticValue? =
        semanticSecretFields.firstNotNullOfOrNull { field ->
            primitiveContent(field.labelKey)
                ?.takeIf { !requireValue || field.valueKey in this }
                ?.let { label -> SemanticValue(field.labelKey, field.valueKey, label) }
        }

    private fun JsonObject.primitiveContent(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString && it.isNotBlank() }
    }

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

    private data class SemanticField(val labelKey: String, val valueKey: String)

    private data class SemanticValue(
        val labelKey: String,
        val valueKey: String,
        val label: String,
    ) {
        fun sameSlot(other: SemanticValue?): Boolean =
            other != null &&
                labelKey == other.labelKey &&
                valueKey == other.valueKey &&
                label.normalizedSecretKey() == other.label.normalizedSecretKey()
    }

    private data class LocalCandidate(
        val index: Int,
        val identity: String,
        val element: JsonElement,
    )
}

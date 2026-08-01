package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private val sensitiveSuffixes = setOf("apikey", "privatekey", "password", "secret", "token")

    fun encode(settings: Settings, json: Json): String {
        val element = json.encodeToJsonElement(Settings.serializer(), settings)
        return json.encodeToString(sanitize(element))
    }

    internal fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::sanitize))
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (key.isSensitiveKey()) JsonPrimitive("") else sanitize(value)
            }
        )
        else -> element
    }

    private fun String.normalizedSecretKey(): String = lowercase().filter(Char::isLetterOrDigit)

    private fun String.isSensitiveKey(): Boolean {
        val normalized = normalizedSecretKey()
        return normalized in sensitiveKeys || sensitiveSuffixes.any(normalized::endsWith)
    }
}

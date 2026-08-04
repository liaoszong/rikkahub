package me.rerere.rikkahub.data.credential

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.ai.mcp.McpOAuthClient

/**
 * Pure JSON projection used at the DataStore boundary.
 *
 * The application-facing Settings object is decoded from [toRuntime], while DataStore only receives
 * [toPersisted]. The store is deliberately injected: Android/Keystore concerns stay in the vault
 * adapter and this traversal remains deterministic and JVM-testable.
 */
internal class CredentialSettingsProjection(
    private val store: CredentialSettingsProjectionStore,
) {
    fun toPersisted(runtime: JsonElement): CredentialSettingsProjectionResult =
        project(runtime, ProjectionDirection.PERSIST)

    fun toRuntime(persisted: JsonElement): CredentialSettingsProjectionResult =
        project(persisted, ProjectionDirection.RUNTIME)

    private fun project(
        source: JsonElement,
        direction: ProjectionDirection,
    ): CredentialSettingsProjectionResult {
        val bindings = mutableListOf<CredentialSettingsBinding>()
        return try {
            val projected = projectElement(
                element = source,
                context = ProjectionContext.root(),
                parentKey = null,
                path = emptyList(),
                direction = direction,
                bindings = bindings,
            )
            CredentialSettingsProjectionResult.Success(projected, bindings)
        } catch (failure: ProjectionFailure) {
            CredentialSettingsProjectionResult.Failure(failure.issue)
        }
    }

    private fun projectElement(
        element: JsonElement,
        context: ProjectionContext,
        parentKey: String?,
        path: List<String>,
        direction: ProjectionDirection,
        bindings: MutableList<CredentialSettingsBinding>,
    ): JsonElement = when (element) {
        is JsonArray -> JsonArray(
            element.map { child ->
                // A semantic list (CustomHeader/Body/MCP headers) identifies the value inside each
                // entry; the list container must not turn the entry's id/name fields into secrets.
                projectElement(child, context, null, path, direction, bindings)
            },
        )

        is JsonObject -> projectObject(element, context, parentKey, path, direction, bindings)
        else -> element
    }

    private fun projectObject(
        element: JsonObject,
        inheritedContext: ProjectionContext,
        parentKey: String?,
        path: List<String>,
        direction: ProjectionDirection,
        bindings: MutableList<CredentialSettingsBinding>,
    ): JsonObject {
        val context = contextFor(element, inheritedContext, parentKey, path)
        val semantic = element.semanticSecret()
        if (semantic?.isSecret == true && element.string("id") == null) {
            fail(CredentialSettingsProjectionIssue.UnstableOwner(path.joinToString(".", "$.")))
        }
        val containerKind = parentKey.semanticContainerKind()

        return JsonObject(element.mapValues { (key, value) ->
            val semanticSecret = semantic?.takeIf { it.valueKey == key && it.isSecret }
            val mapSecretKind = containerKind?.takeIf {
                when (it) {
                    SemanticKind.SECRET -> true
                    SemanticKind.HEADER -> !key.isSafeHeaderName()
                    SemanticKind.BODY -> !key.isSafeBodyName()
                }
            }
            val directKind = when {
                key.isSensitiveKey() -> SemanticKind.SECRET
                key.normalizedKey() == "username" && context.namespace == "settings.webDavConfig" ->
                    SemanticKind.SECRET
                key.normalizedKey() == "username" &&
                    context.namespace == "settings.searchServices" &&
                    element.isSearXngOwner() -> SemanticKind.SECRET
                else -> null
            }
            val kind = semanticSecret?.kind ?: mapSecretKind ?: directKind

            if (kind != null) {
                projectSecret(
                    value = value,
                    context = context,
                    fieldSlot = semanticSecret?.let { "${it.kind.slotName}.value" }
                        ?: mapSecretKind?.let { "${it.slotName}.${key.normalizedKey()}" }
                        ?: key.normalizedKey(),
                    kind = kind,
                    path = path + key,
                    direction = direction,
                    bindings = bindings,
                )
            } else {
                projectElement(value, context, key, path + key, direction, bindings)
            }
        })
    }

    private fun projectSecret(
        value: JsonElement,
        context: ProjectionContext,
        fieldSlot: String,
        kind: SemanticKind,
        path: List<String>,
        direction: ProjectionDirection,
        bindings: MutableList<CredentialSettingsBinding>,
    ): JsonElement {
        if (value == JsonNull) return value
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (raw != null && raw.isBlank()) return value

        val address = CredentialSettingsAddress(
            namespace = context.namespace,
            ownerStableId = context.ownerStableId,
            fieldSlot = fieldSlot,
            kind = kind.slotName,
            audience = context.audienceFor(fieldSlot),
        )
        val jsonPath = path.joinToString(separator = ".", prefix = "$.")

        return when (direction) {
            ProjectionDirection.PERSIST -> {
                if (raw != null && CredentialRefId.isReference(raw)) {
                    bindings += CredentialSettingsBinding(address, raw, revision = null, jsonPath)
                    value
                } else {
                    when (val sealed = store.seal(address, value)) {
                        is CredentialSettingsSealResult.Stored -> {
                            requireReference(sealed.reference, jsonPath)
                            bindings += CredentialSettingsBinding(address, sealed.reference, sealed.revision, jsonPath)
                            JsonPrimitive(sealed.reference)
                        }
                        is CredentialSettingsSealResult.Locked -> fail(
                            CredentialSettingsProjectionIssue.Locked(jsonPath, address, sealed.reason),
                        )
                        is CredentialSettingsSealResult.Failed -> fail(
                            CredentialSettingsProjectionIssue.StoreFailed(jsonPath, address, sealed.reason),
                        )
                    }
                }
            }

            ProjectionDirection.RUNTIME -> {
                if (raw == null || !CredentialRefId.isReference(raw)) return value // journaled migration dual-read
                when (val resolved = store.resolve(raw, address)) {
                    is CredentialSettingsResolveResult.Found -> {
                        bindings += CredentialSettingsBinding(address, raw, resolved.revision, jsonPath)
                        resolved.secret
                    }
                    CredentialSettingsResolveResult.Missing -> fail(
                        CredentialSettingsProjectionIssue.Missing(jsonPath, address, raw),
                    )
                    is CredentialSettingsResolveResult.Locked -> fail(
                        CredentialSettingsProjectionIssue.Locked(jsonPath, address, resolved.reason),
                    )
                    is CredentialSettingsResolveResult.Corrupt -> fail(
                        CredentialSettingsProjectionIssue.Corrupt(jsonPath, address, resolved.reason),
                    )
                }
            }
        }
    }

    private fun contextFor(
        element: JsonObject,
        inherited: ProjectionContext,
        parentKey: String?,
        path: List<String>,
    ): ProjectionContext {
        val namespace = path.firstOrNull()?.let { "settings.$it" } ?: inherited.namespace
        val id = element.string("id")
        val owner = when {
            id != null -> element.string("type")?.let { "$it:$id" } ?: id
            path.size == 1 && parentKey != null -> "singleton:$parentKey"
            else -> inherited.ownerStableId
        }
        val audience = element.audienceFingerprint(namespace, owner) ?: inherited.audience
        val mcpResourceAudience = if (namespace == "settings.mcpServers") {
            element.string("url")?.let {
                endpointAudience("mcp-resource", McpOAuthClient.canonicalResource(it))
            }
                ?: inherited.mcpResourceAudience
        } else {
            inherited.mcpResourceAudience
        }
        val mcpTokenAudience = if (namespace == "settings.mcpServers") {
            element.string("tokenEndpoint")?.let {
                endpointAudience("mcp-token-endpoint", McpOAuthClient.canonicalResource(it))
            }
                ?: inherited.mcpTokenAudience
        } else {
            inherited.mcpTokenAudience
        }
        return ProjectionContext(namespace, owner, audience, mcpResourceAudience, mcpTokenAudience)
    }

    private fun JsonObject.audienceFingerprint(namespace: String, owner: String): String? {
        val parts = entries.mapNotNull { (key, value) ->
            val normalized = key.normalizedKey()
            if (normalized !in audienceKeys) return@mapNotNull null
            val scalar = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            "$normalized=${scalar.length}:$scalar"
        }.sorted()
        return if (parts.isEmpty()) null else "$namespace|$owner|${parts.joinToString("|")}"
    }

    private fun requireReference(reference: String, path: String) {
        if (!CredentialRefId.isReference(reference)) {
            fail(CredentialSettingsProjectionIssue.InvalidReference(path, reference))
        }
    }

    private fun fail(issue: CredentialSettingsProjectionIssue): Nothing = throw ProjectionFailure(issue)

    private class ProjectionFailure(val issue: CredentialSettingsProjectionIssue) : RuntimeException()

    private enum class ProjectionDirection { PERSIST, RUNTIME }

    private data class ProjectionContext(
        val namespace: String,
        val ownerStableId: String,
        val audience: String,
        val mcpResourceAudience: String?,
        val mcpTokenAudience: String?,
    ) {
        fun audienceFor(fieldSlot: String): String {
            if (namespace != "settings.mcpServers") return audience
            return when (fieldSlot) {
                "accesstoken" -> mcpResourceAudience ?: "mcp-resource:missing"
                "refreshtoken", "clientsecret" ->
                    mcpTokenAudience ?: "mcp-token-endpoint:missing"
                else -> audience
            }
        }

        companion object {
            fun root() = ProjectionContext(
                namespace = "settings",
                ownerStableId = "settings",
                audience = "settings",
                mcpResourceAudience = null,
                mcpTokenAudience = null,
            )
        }
    }

    private data class SemanticValue(
        val valueKey: String,
        val kind: SemanticKind,
        val isSecret: Boolean,
    )

    private enum class SemanticKind(val slotName: String) {
        SECRET("secret"),
        HEADER("header"),
        BODY("body"),
    }

    private fun JsonObject.semanticSecret(): SemanticValue? {
        val name = string("name")
        if (name != null && "value" in this) {
            return SemanticValue("value", SemanticKind.HEADER, !name.isSafeHeaderName())
        }
        val key = string("key")
        if (key != null && "value" in this) {
            return SemanticValue("value", SemanticKind.BODY, !key.isSafeBodyName())
        }
        val first = string("first")
        if (first != null && "second" in this) {
            return SemanticValue("second", SemanticKind.HEADER, !first.isSafeHeaderName())
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.isSearXngOwner(): Boolean =
        string("type")?.normalizedKey() == "searxng"

    private fun String?.semanticContainerKind(): SemanticKind? {
        val normalized = this?.normalizedKey() ?: return null
        return when {
            normalized in headerContainerKeys -> SemanticKind.HEADER
            normalized in bodyContainerKeys -> SemanticKind.BODY
            else -> null
        }
    }

    private fun String.isSafeHeaderName(): Boolean = normalizedKey() in safeHeaderValueNames

    private fun String.isSafeBodyName(): Boolean = normalizedKey() in safeBodyValueNames

    private fun String.isSensitiveKey(): Boolean {
        val normalized = normalizedKey()
        return normalized in sensitiveKeys || sensitiveSuffixes.any(normalized::endsWith)
    }

    private fun String.normalizedKey(): String = lowercase().filter(Char::isLetterOrDigit)

    private fun endpointAudience(kind: String, endpoint: String): String {
        val normalized = endpoint.trim()
        return "$kind:${normalized.length}:$normalized"
    }

    private companion object {
        val sensitiveKeys = setOf(
            "apikey", "xapikey", "privatekey", "clientsecret", "accesstoken", "refreshtoken",
            "password", "secretaccesskey", "accesskeyid", "authorization", "proxyauthorization",
            "auth", "authentication", "xauth", "authkey", "xauthkey", "cookie", "setcookie",
            "credentials", "secret", "token", "sessiontoken",
        )
        val sensitiveSuffixes = setOf(
            "apikey", "privatekey", "secretkey", "password", "secret", "token", "credential", "credentials",
        )
        val safeHeaderValueNames = setOf("accept", "acceptencoding", "contenttype", "useragent")
        val safeBodyValueNames = setOf(
            "frequencypenalty", "maxoutputtokens", "maxtokens", "n", "presencepenalty", "reasoningeffort",
            "responseformat", "seed", "stop", "stream", "temperature", "thinkingbudget", "topk", "topp",
            "translationoptions", "verbosity",
        )
        val headerContainerKeys = setOf("headers", "customheaders", "mcpheaders", "requestheaders")
        val bodyContainerKeys = setOf("custombody", "custombodies", "requestbody")
        val audienceKeys = setOf(
            "authorizationendpoint", "baseurl", "bucket", "customurl", "endpoint", "location", "pathstyle",
            "projectid", "region", "registrationendpoint", "scrapeurl", "searchurl", "serviceaccountemail",
            "tokenendpoint", "url", "websocketurl",
        )
    }
}

internal interface CredentialSettingsProjectionStore {
    fun seal(address: CredentialSettingsAddress, secret: JsonElement): CredentialSettingsSealResult

    fun resolve(reference: String, address: CredentialSettingsAddress): CredentialSettingsResolveResult
}

internal data class CredentialSettingsAddress(
    val namespace: String,
    val ownerStableId: String,
    val fieldSlot: String,
    val kind: String,
    val audience: String,
) {
    fun slotId(): CredentialSlotId = CredentialSlotId.of(namespace, ownerStableId, fieldSlot)
}

internal sealed interface CredentialSettingsSealResult {
    data class Stored(val reference: String, val revision: Long) : CredentialSettingsSealResult
    data class Locked(val reason: String) : CredentialSettingsSealResult
    data class Failed(val reason: String) : CredentialSettingsSealResult
}

internal sealed interface CredentialSettingsResolveResult {
    data class Found(val secret: JsonElement, val revision: Long) : CredentialSettingsResolveResult
    data object Missing : CredentialSettingsResolveResult
    data class Locked(val reason: String) : CredentialSettingsResolveResult
    data class Corrupt(val reason: String) : CredentialSettingsResolveResult
}

internal sealed interface CredentialSettingsProjectionResult {
    data class Success(
        val settings: JsonElement,
        val bindings: List<CredentialSettingsBinding>,
    ) : CredentialSettingsProjectionResult

    data class Failure(val issue: CredentialSettingsProjectionIssue) : CredentialSettingsProjectionResult
}

internal data class CredentialSettingsBinding(
    val address: CredentialSettingsAddress,
    val reference: String,
    val revision: Long?,
    val jsonPath: String,
)

internal sealed interface CredentialSettingsProjectionIssue {
    val jsonPath: String

    data class Locked(
        override val jsonPath: String,
        val address: CredentialSettingsAddress,
        val reason: String,
    ) : CredentialSettingsProjectionIssue

    data class Missing(
        override val jsonPath: String,
        val address: CredentialSettingsAddress,
        val reference: String,
    ) : CredentialSettingsProjectionIssue

    data class Corrupt(
        override val jsonPath: String,
        val address: CredentialSettingsAddress,
        val reason: String,
    ) : CredentialSettingsProjectionIssue

    data class StoreFailed(
        override val jsonPath: String,
        val address: CredentialSettingsAddress,
        val reason: String,
    ) : CredentialSettingsProjectionIssue

    data class InvalidReference(
        override val jsonPath: String,
        val reference: String,
    ) : CredentialSettingsProjectionIssue

    data class UnstableOwner(
        override val jsonPath: String,
    ) : CredentialSettingsProjectionIssue
}

package me.rerere.rikkahub.data.imggen

import java.security.MessageDigest
import java.util.Locale
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.credential.CredentialSlotId
import me.rerere.rikkahub.data.credential.effectiveProviderCredentialReference
import me.rerere.rikkahub.data.datastore.Settings

/** Captures the exact Vault slot selected by provider authentication precedence. */
internal fun Settings.freezeImageGenerationCredential(
    provider: ProviderSetting,
    model: Model,
): ImageGenerationCredentialEvidence? {
    val reference = effectiveProviderCredentialReference(provider, model.customHeaders) ?: return null

    model.customHeaders.asReversed().forEach { header ->
        listOf("settings.providers", "settings.assistants").forEach { namespace ->
            val owner = header.id.toString()
            val field = "header.value"
            if (credentialReference(namespace, owner, field) == reference) {
                return ImageGenerationCredentialEvidence(
                    reference = reference,
                    namespace = namespace,
                    ownerStableId = owner,
                    fieldSlot = field,
                    kind = "header",
                    target = ImageGenerationCredentialTarget.MODEL_CUSTOM_HEADER,
                    customHeaderId = header.id.toString(),
                )
            }
        }
    }

    val owner = "${provider.serialType()}:${provider.id}"
    val field = when (provider) {
        is ProviderSetting.Google -> if (provider.vertexAI && provider.useServiceAccount) "privatekey" else "apikey"
        is ProviderSetting.OpenAI,
        is ProviderSetting.Claude,
        -> "apikey"
    }
    check(credentialReference("settings.providers", owner, field) == reference) {
        "The selected image credential reference is not bound to the effective provider"
    }
    return ImageGenerationCredentialEvidence(
        reference = reference,
        namespace = "settings.providers",
        ownerStableId = owner,
        fieldSlot = field,
        kind = "secret",
        target = if (field == "privatekey") {
            ImageGenerationCredentialTarget.GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY
        } else {
            ImageGenerationCredentialTarget.PROVIDER_API_KEY
        },
    )
}

/** Applies only the credential resolved from the frozen ledger proof. */
internal fun bindImageGenerationCredential(
    provider: ProviderSetting,
    model: Model,
    evidence: ImageGenerationCredentialEvidence?,
    secret: String?,
): Pair<ProviderSetting, Model> {
    if (evidence == null) {
        require(secret == null) { "Credential secret exists without ledger evidence" }
        return provider to model
    }
    val replacement = requireNotNull(secret) { "Frozen image credential could not be resolved" }
    require(replacement.isNotBlank()) { "Frozen image credential is blank" }
    return when (evidence.target) {
        ImageGenerationCredentialTarget.PROVIDER_API_KEY -> when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(apiKey = replacement) to model
            is ProviderSetting.Google -> provider.copy(apiKey = replacement) to model
            is ProviderSetting.Claude -> provider.copy(apiKey = replacement) to model
        }
        ImageGenerationCredentialTarget.GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY -> {
            require(provider is ProviderSetting.Google && provider.vertexAI && provider.useServiceAccount) {
                "Frozen service-account credential no longer matches the provider mode"
            }
            provider.copy(privateKey = replacement) to model
        }
        ImageGenerationCredentialTarget.MODEL_CUSTOM_HEADER -> {
            val headerId = requireNotNull(evidence.customHeaderId)
            var replaced = false
            val headers = model.customHeaders.map { header ->
                if (header.id.toString() == headerId) {
                    replaced = true
                    header.copy(value = replacement)
                } else {
                    header
                }
            }
            require(replaced) { "Frozen image credential header no longer exists" }
            provider to model.copy(customHeaders = headers)
        }
    }
}

/**
 * Freezes only settings that affect this concrete image transport. Unrelated provider models are
 * deliberately excluded so their edits cannot invalidate an already-reserved paid request.
 */
internal fun imageTransportConfigurationDigest(model: Model, provider: ProviderSetting): String = sha256String(
    buildString {
        appendCanonical(provider.serialType())
        appendCanonical(provider.id.toString())
        when (provider) {
            is ProviderSetting.OpenAI -> {
                appendCanonical(provider.baseUrl)
                appendCanonical(provider.chatCompletionsPath)
                appendCanonical(provider.useResponseApi.toString())
                appendCanonical(provider.managedBy.orEmpty())
                appendCanonical(sha256String(provider.apiKey))
            }
            is ProviderSetting.Google -> {
                appendCanonical(provider.baseUrl)
                appendCanonical(provider.vertexAI.toString())
                appendCanonical(provider.useServiceAccount.toString())
                appendCanonical(provider.serviceAccountEmail)
                appendCanonical(provider.location)
                appendCanonical(provider.projectId)
                appendCanonical(sha256String(provider.apiKey))
                appendCanonical(sha256String(provider.privateKey))
            }
            is ProviderSetting.Claude -> {
                appendCanonical(provider.baseUrl)
                appendCanonical(provider.promptCaching.toString())
                appendCanonical(provider.promptCacheTtl.name)
                appendCanonical(sha256String(provider.apiKey))
            }
        }
        appendCanonical(model.id.toString())
        appendCanonical(model.modelId)
        appendCanonical(model.type.name)
        model.customHeaders
            .sortedWith(compareBy({ it.id.toString() }, { it.name.lowercase(Locale.ROOT) }))
            .forEach { header ->
                appendCanonical(header.id.toString())
                appendCanonical(header.name.lowercase(Locale.ROOT))
                appendCanonical(sha256String(header.value))
            }
        model.customBodies.sortedBy { it.id.toString() }.forEach { body ->
            appendCanonical(body.id.toString())
            appendCanonical(body.key)
            appendCanonical(body.value.toString())
        }
    },
)

internal fun ImageGenerationCredentialEvidence.slotId(): CredentialSlotId = CredentialSlotId.of(
    namespace = namespace,
    ownerStableId = ownerStableId,
    fieldSlot = fieldSlot,
)

private fun Settings.credentialReference(namespace: String, owner: String, field: String): String? =
    credentialReferencesBySlot[CredentialSlotId.of(namespace, owner, field).value]

private fun ProviderSetting.serialType(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

private fun sha256String(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun StringBuilder.appendCanonical(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(';')
}

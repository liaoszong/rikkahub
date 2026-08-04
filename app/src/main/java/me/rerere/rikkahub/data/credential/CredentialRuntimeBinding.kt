package me.rerere.rikkahub.data.credential

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

/**
 * Returns the immutable Vault reference for the credential that will authenticate this exact
 * provider request. Header precedence mirrors providerAuthHeaders: the last non-blank custom auth
 * header wins over the adapter fallback.
 */
internal fun Settings.effectiveProviderCredentialReference(
    provider: ProviderSetting,
    customHeaders: List<CustomHeader>,
): String? {
    customHeaders.lastOrNull { header ->
        header.value.isNotBlank() && header.name.normalizedHeaderName() in provider.authHeaderNames()
    }?.let { header ->
        val field = "header.value"
        listOf("settings.assistants", "settings.providers").forEach { namespace ->
            credentialReference(namespace, header.id.toString(), field)?.let { return it }
        }
    }

    val owner = "${provider.serialType()}:${provider.id}"
    val field = when (provider) {
        is ProviderSetting.Google -> if (provider.vertexAI && provider.useServiceAccount) "privatekey" else "apikey"
        is ProviderSetting.OpenAI,
        is ProviderSetting.Claude,
        -> "apikey"
    }
    return credentialReference("settings.providers", owner, field)
}

internal fun Settings.effectiveMcpCredentialReference(serverId: String): String? {
    val server = mcpServers.firstOrNull { it.id.toString() == serverId } ?: return null
    val headers = server.commonOptions.headers
    val authorization = headers.lastOrNull {
        it.name.equals("Authorization", ignoreCase = true) && it.value.isNotBlank()
    }
    if (authorization != null) {
        return credentialReference(
            "settings.mcpServers",
            authorization.id.toString(),
            "header.value",
        )
    }
    server.commonOptions.oauth?.takeIf { it.enabled && !it.accessToken.isNullOrBlank() }?.let {
        return credentialReference(
            "settings.mcpServers",
            "${server.serialType()}:${server.id}",
            "accesstoken",
        )
    }
    return headers.asReversed().firstNotNullOfOrNull { header ->
        credentialReference("settings.mcpServers", header.id.toString(), "header.value")
    }
}

private fun Settings.credentialReference(namespace: String, owner: String, field: String): String? =
    credentialReferencesBySlot[CredentialSlotId.of(namespace, owner, field).value]

private fun ProviderSetting.serialType(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

private fun McpServerConfig.serialType(): String = when (this) {
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http"
}

private fun ProviderSetting.authHeaderNames(): Set<String> = when (this) {
    is ProviderSetting.OpenAI -> setOf("authorization")
    is ProviderSetting.Google -> setOf("authorization", "xgoogapikey")
    is ProviderSetting.Claude -> setOf("authorization", "xapikey")
}

private fun String.normalizedHeaderName(): String = lowercase().filter(Char::isLetterOrDigit)

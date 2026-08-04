package me.rerere.rikkahub.data.credential

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpHeader
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class CredentialRuntimeBindingTest {
    @Test
    fun `custom authorization reference wins over provider fallback`() {
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), apiKey = "runtime-provider-secret")
        val header = CustomHeader(name = "Authorization", value = "Bearer runtime-header-secret")
        val providerSlot = CredentialSlotId.of("settings.providers", "openai:${provider.id}", "apikey")
        val headerSlot = CredentialSlotId.of("settings.providers", header.id.toString(), "header.value")
        val settings = Settings(
            credentialReferencesBySlot = mapOf(
                providerSlot.value to "vault:v1:00000000-0000-0000-0000-000000000001",
                headerSlot.value to "vault:v1:00000000-0000-0000-0000-000000000002",
            ),
        )

        assertEquals(
            "vault:v1:00000000-0000-0000-0000-000000000002",
            settings.effectiveProviderCredentialReference(provider, listOf(header)),
        )
    }

    @Test
    fun `vertex service account binds private key rather than api key`() {
        val provider = ProviderSetting.Google(
            id = Uuid.random(),
            vertexAI = true,
            useServiceAccount = true,
            privateKey = "runtime-private-key",
        )
        val privateKeySlot = CredentialSlotId.of("settings.providers", "google:${provider.id}", "privatekey")
        val settings = Settings(
            credentialReferencesBySlot = mapOf(
                privateKeySlot.value to "vault:v1:00000000-0000-0000-0000-000000000003",
            ),
        )

        assertEquals(
            "vault:v1:00000000-0000-0000-0000-000000000003",
            settings.effectiveProviderCredentialReference(provider, emptyList()),
        )
    }

    @Test
    fun `mcp refreshed access token reference is selected after first token creation`() {
        val serverId = Uuid.random()
        val server = McpServerConfig.SseTransportServer(
            id = serverId,
            url = "https://resource.example/mcp",
            commonOptions = McpCommonOptions(
                oauth = McpOAuthState(
                    enabled = true,
                    tokenEndpoint = "https://auth.example/token",
                    accessToken = "new-access-token",
                    refreshToken = "existing-refresh-token",
                ),
            ),
        )
        val accessSlot = CredentialSlotId.of("settings.mcpServers", "sse:$serverId", "accesstoken")
        val accessReference = "vault:v1:00000000-0000-0000-0000-000000000004"
        val settings = Settings(
            mcpServers = listOf(server),
            credentialReferencesBySlot = mapOf(accessSlot.value to accessReference),
        )

        assertEquals(accessReference, settings.effectiveMcpCredentialReference(serverId.toString()))
    }

    @Test
    fun `mcp explicit authorization header reference wins over oauth access token`() {
        val serverId = Uuid.random()
        val header = McpHeader(name = "Authorization", value = "Bearer explicit")
        val server = McpServerConfig.StreamableHTTPServer(
            id = serverId,
            url = "https://resource.example/mcp",
            commonOptions = McpCommonOptions(
                headers = listOf(header),
                oauth = McpOAuthState(enabled = true, accessToken = "oauth-access"),
            ),
        )
        val headerSlot = CredentialSlotId.of("settings.mcpServers", header.id.toString(), "header.value")
        val oauthSlot = CredentialSlotId.of("settings.mcpServers", "streamable_http:$serverId", "accesstoken")
        val headerReference = "vault:v1:00000000-0000-0000-0000-000000000005"
        val settings = Settings(
            mcpServers = listOf(server),
            credentialReferencesBySlot = mapOf(
                headerSlot.value to headerReference,
                oauthSlot.value to "vault:v1:00000000-0000-0000-0000-000000000006",
            ),
        )

        assertEquals(headerReference, settings.effectiveMcpCredentialReference(serverId.toString()))
    }
}

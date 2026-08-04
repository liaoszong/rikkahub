package me.rerere.rikkahub.data.credential

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpHeader
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.CredentialSettingsUnavailableException
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class CredentialSettingsStoreInstrumentedTest {
    @Test
    fun datastorePersistsOnlyReferenceWhileRuntimeProjectionKeepsCredentialUsable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val vault = koin.get<CredentialVault>()
        val original = store.settingsFlowRaw.first()
        val providerId = Uuid.random()
        val canary = "vault-instrumentation-canary-${Uuid.random()}"
        val provider = ProviderSetting.OpenAI(
            id = providerId,
            name = "Vault instrumentation",
            apiKey = canary,
            baseUrl = "https://vault-instrumentation.invalid/v1",
        )
        try {
            store.update(original.copy(providers = original.providers + provider))
            val slotId = CredentialSlotId.of("settings.providers", "openai:$providerId", "apikey")
            val runtime = store.settingsFlowRaw.first { settings ->
                slotId.value in settings.credentialReferencesBySlot
            }
            val runtimeProvider = runtime.providers.single { it.id == providerId } as ProviderSetting.OpenAI
            assertEquals(canary, runtimeProvider.apiKey)

            val reference = runtime.credentialReferencesBySlot.getValue(slotId.value)
            assertTrue(CredentialRefId.isReference(reference))
            val resolved = vault.resolve(reference) as CredentialReadResult.Found
            try {
                assertEquals(
                    canary,
                    Json.parseToJsonElement(resolved.value.secret.toString(Charsets.UTF_8)).jsonPrimitive.content,
                )
            } finally {
                resolved.value.secret.fill(0)
            }

            val preferencesFile = File(context.filesDir, "datastore/settings.preferences_pb")
            assertTrue(preferencesFile.isFile)
            assertFalse(preferencesFile.readBytes().containsSubsequence(canary.toByteArray()))
        } finally {
            store.update(original)
        }
    }

    @Test
    fun endpointChangeRequiresExplicitReentryBeforeSettingsCanBePersisted() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val providerId = Uuid.random()
        val initialSecret = "initial-rebind-canary-${Uuid.random()}"
        val replacementSecret = "replacement-rebind-canary-${Uuid.random()}"
        val initialProvider = ProviderSetting.OpenAI(
            id = providerId,
            name = "Audience rebind instrumentation",
            apiKey = initialSecret,
            baseUrl = "https://old-audience.invalid/v1",
        )
        try {
            store.update(original.copy(providers = original.providers + initialProvider))
            val before = store.settingsFlowRaw.first { settings ->
                settings.providers.any { it.id == providerId }
            }
            val updated = before.copy(
                providers = before.providers.map { provider ->
                    if (provider.id != providerId) provider else (provider as ProviderSetting.OpenAI).copy(
                        // Deliberately keep the resolved old value here. Only the explicit intent
                        // is authorized to become the credential for the new audience.
                        apiKey = initialSecret,
                        baseUrl = "https://new-audience.invalid/v1",
                    )
                },
            )

            val failure = runCatching { store.update(updated) }.exceptionOrNull()
            assertTrue(failure is CredentialSettingsUnavailableException)
            val issue = (failure as CredentialSettingsUnavailableException).issue
                as CredentialSettingsProjectionIssue.StoreFailed
            val previousReference = before.credentialReferencesBySlot.getValue(issue.address.slotId().value)
            val previousRevision = before.credentialRevisions.getValue(issue.jsonPath)
            val candidates = store.credentialAudienceRebindCandidates(before, updated)
            assertEquals(1, candidates.size)
            assertEquals(issue.address, candidates.single().address)
            assertEquals(previousReference, candidates.single().expectedReference)
            assertEquals(previousRevision, candidates.single().expectedRevision)

            store.updateWithCredentialAudienceRebind(
                settings = updated,
                intent = CredentialAudienceRebindIntent(
                    address = issue.address,
                    expectedReference = previousReference,
                    expectedRevision = previousRevision,
                    replacementSecret = Json.parseToJsonElement("\"$replacementSecret\""),
                ),
            )

            val persisted = store.settingsFlowRaw.first { settings ->
                (settings.providers.find { it.id == providerId } as? ProviderSetting.OpenAI)
                    ?.baseUrl == "https://new-audience.invalid/v1"
            }
            val provider = persisted.providers.single { it.id == providerId } as ProviderSetting.OpenAI
            assertEquals(replacementSecret, provider.apiKey)
            assertFalse(persisted.credentialReferencesBySlot.values.contains(previousReference))
            val committedReference = persisted.credentialReferencesBySlot.getValue(issue.address.slotId().value)
            assertEquals(committedReference, persisted.credentialReferences.getValue(issue.jsonPath))
            assertTrue(persisted.credentialRevisions.getValue(issue.jsonPath) >= 1)
        } finally {
            store.update(original)
        }
    }

    @Test
    fun multiSecretEndpointEditCommitsOnlyExplicitReplacementInputsAsOneBatch() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val serverId = Uuid.random()
        val authorizationId = Uuid.random()
        val apiKeyId = Uuid.random()
        val initial = McpServerConfig.StreamableHTTPServer(
            id = serverId,
            url = "https://old-mcp-audience.invalid/mcp",
            commonOptions = McpCommonOptions(
                name = "Vault batch instrumentation",
                headers = listOf(
                    McpHeader("Authorization", "Bearer old-token", authorizationId),
                    McpHeader("X-Api-Key", "old-api-key", apiKeyId),
                ),
            ),
        )
        try {
            store.update(original.copy(mcpServers = original.mcpServers + initial))
            val before = store.settingsFlowRaw.first { settings ->
                settings.mcpServers.any { it.id == serverId }
            }
            val updated = before.copy(
                mcpServers = before.mcpServers.map { server ->
                    if (server.id != serverId) server else (server as McpServerConfig.StreamableHTTPServer).copy(
                        url = "https://new-mcp-audience.invalid/mcp",
                        // These are resolved old values by design; neither may override the intents.
                        commonOptions = server.commonOptions.copy(
                            headers = listOf(
                                McpHeader("Authorization", "Bearer old-token", authorizationId),
                                McpHeader("X-Api-Key", "old-api-key", apiKeyId),
                            ),
                        ),
                    )
                },
            )
            val candidates = store.credentialAudienceRebindCandidates(before, updated)
            assertEquals(2, candidates.size)
            val replacements = mapOf(
                authorizationId.toString() to Json.parseToJsonElement("\"Bearer replacement-token\""),
                apiKeyId.toString() to Json.parseToJsonElement("\"replacement-api-key\""),
            )
            store.updateWithCredentialAudienceRebinds(
                settings = updated,
                intents = candidates.map { candidate ->
                    val ownerId = candidate.address.ownerStableId.substringAfterLast(':')
                    CredentialAudienceRebindIntent(
                        address = candidate.address,
                        expectedReference = candidate.expectedReference,
                        expectedRevision = candidate.expectedRevision,
                        replacementSecret = replacements.getValue(ownerId),
                    )
                },
            )

            val committed = store.settingsFlowRaw.first { settings ->
                (settings.mcpServers.find { it.id == serverId } as? McpServerConfig.StreamableHTTPServer)
                    ?.url == "https://new-mcp-audience.invalid/mcp"
            }.mcpServers.single { it.id == serverId } as McpServerConfig.StreamableHTTPServer
            assertEquals(
                listOf("Bearer replacement-token", "replacement-api-key"),
                committed.commonOptions.headers.map(McpHeader::value),
            )
        } finally {
            store.update(original)
        }
    }

    @Test
    fun oauthRefreshCreatesAccessEvidenceAndRotatesImmutableReferenceAcrossCasRefreshes() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val serverId = Uuid.random()
        val refreshCanary = "refresh-canary-${Uuid.random()}"
        val firstAccessCanary = "access-one-${Uuid.random()}"
        val secondAccessCanary = "access-two-${Uuid.random()}"
        val server = McpServerConfig.SseTransportServer(
            id = serverId,
            url = "https://resource-${serverId}.invalid/mcp",
            commonOptions = McpCommonOptions(
                enable = false,
                name = "OAuth projection integration",
                oauth = McpOAuthState(
                    enabled = true,
                    clientId = "client-$serverId",
                    tokenEndpoint = "https://auth-${serverId}.invalid/token",
                    accessToken = null,
                    refreshToken = refreshCanary,
                    expiresAt = 1L,
                ),
            ),
        )
        try {
            store.update(original.copy(mcpServers = original.mcpServers + server))
            val owner = "sse:$serverId"
            val refreshSlot = CredentialSlotId.of("settings.mcpServers", owner, "refreshtoken")
            val accessSlot = CredentialSlotId.of("settings.mcpServers", owner, "accesstoken")
            val beforeRefresh = store.settingsFlowRaw.first {
                refreshSlot.value in it.credentialReferencesBySlot
            }
            val refreshReference = beforeRefresh.credentialReferencesBySlot.getValue(refreshSlot.value)

            fun withAccess(settings: me.rerere.rikkahub.data.datastore.Settings, token: String) = settings.copy(
                mcpServers = settings.mcpServers.map { config ->
                    if (config.id != serverId) config else config.clone(
                        commonOptions = config.commonOptions.copy(
                            oauth = requireNotNull(config.commonOptions.oauth).copy(accessToken = token),
                        ),
                    )
                },
            )

            store.update(withAccess(beforeRefresh, firstAccessCanary))
            val afterFirstRefresh = store.settingsFlowRaw.first {
                accessSlot.value in it.credentialReferencesBySlot
            }
            val accessReference = afterFirstRefresh.credentialReferencesBySlot.getValue(accessSlot.value)
            assertTrue(CredentialRefId.isReference(accessReference))
            assertEquals(refreshReference, afterFirstRefresh.credentialReferencesBySlot.getValue(refreshSlot.value))

            store.update(withAccess(afterFirstRefresh, secondAccessCanary))
            val afterSecondRefresh = store.settingsFlowRaw.first { settings ->
                val current = settings.mcpServers.find { it.id == serverId }?.commonOptions?.oauth
                current?.accessToken == secondAccessCanary
            }
            assertNotEquals(accessReference, afterSecondRefresh.credentialReferencesBySlot.getValue(accessSlot.value))
            assertEquals(refreshReference, afterSecondRefresh.credentialReferencesBySlot.getValue(refreshSlot.value))
        } finally {
            store.update(original)
        }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return indices.take(size - candidate.size + 1).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }
}

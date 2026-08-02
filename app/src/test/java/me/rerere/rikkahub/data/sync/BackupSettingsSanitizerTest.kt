package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BackupSettingsSanitizerTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `portable settings backup excludes provider and cloud credentials`() {
        val encoded = BackupSettingsSanitizer.encode(
            settings = Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(
                        name = "Portable Provider",
                        apiKey = "provider-secret-value",
                    )
                ),
                webDavConfig = WebDavConfig(
                    url = "https://backup.example.com",
                    username = "backup-user",
                    password = "webdav-secret-value",
                ),
                s3Config = S3Config(
                    endpoint = "https://s3.example.com",
                    accessKeyId = "s3-access-value",
                    secretAccessKey = "s3-secret-value",
                ),
                webServerAccessPassword = "web-password-value",
            ),
            json = json,
        )

        listOf(
            "provider-secret-value",
            "webdav-secret-value",
            "s3-access-value",
            "s3-secret-value",
            "web-password-value",
        ).forEach { secret -> assertFalse("Secret remained in backup: $secret", encoded.contains(secret)) }
        assertTrue(encoded.contains("Portable Provider"))
        assertTrue(encoded.contains("backup-user"))
    }

    @Test
    fun `nested authorization headers and tokens are removed recursively`() {
        val source = json.parseToJsonElement(
            """{
                "headers": {
                    "Authorization": "Bearer secret",
                    "X-API-Key": "nested-secret"
                },
                "customHeaders": [
                    {"name":"Cookie","value":"cookie-canary"},
                    {"name":"X-Auth","value":"x-auth-canary"},
                    {"name":"Authentication","value":"authentication-canary"},
                    {"name":"X-Auth-Key","value":"auth-key-canary"},
                    {"name":"X-Trace-Id","value":"arbitrary-header-canary"},
                    {"name":"Content-Type","value":"application/json"}
                ],
                "customBodies": [
                    {"key":"access_token","value":"body-token-canary"},
                    {"key":"credentials","value":"credentials-canary"},
                    {"key":"gateway_mode","value":"arbitrary-body-canary"},
                    {"key":"temperature","value":0.5}
                ],
                "mcpHeaders": [
                    {"first":"X-Client-Secret","second":"mcp-secret-canary"},
                    {"first":"X-Request-Id","second":"arbitrary-pair-canary"},
                    {"first":"User-Agent","second":"RikkaHub/safe"}
                ],
                "auth": "direct-auth-canary",
                "access_token": "token-value",
                "safe": "kept"
            }""".trimIndent()
        )
        val sanitized = BackupSettingsSanitizer.sanitize(source).toString()

        listOf(
            "Bearer secret",
            "nested-secret",
            "cookie-canary",
            "x-auth-canary",
            "authentication-canary",
            "auth-key-canary",
            "arbitrary-header-canary",
            "body-token-canary",
            "credentials-canary",
            "arbitrary-body-canary",
            "mcp-secret-canary",
            "arbitrary-pair-canary",
            "direct-auth-canary",
            "token-value",
        ).forEach { secret -> assertFalse("Secret remained in nested settings: $secret", sanitized.contains(secret)) }
        listOf("application/json", "RikkaHub/safe", "kept", "0.5").forEach { safeValue ->
            assertTrue("Non-secret value was removed: $safeValue", sanitized.contains(safeValue))
        }
    }

    @Test
    fun `portable backup sanitizes real provider model assistant and mcp containers`() {
        val encoded = BackupSettingsSanitizer.encode(
            settings = Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(
                        id = PROVIDER_ID,
                        apiKey = "provider-api-canary",
                        models = listOf(
                            Model(
                                id = MODEL_ID,
                                customHeaders = listOf(
                                    CustomHeader("Authorization", "model-auth-canary"),
                                    CustomHeader("X-API-Key", "model-api-key-canary"),
                                    CustomHeader("X-Feature-Mode", "model-arbitrary-header-canary"),
                                    CustomHeader("Content-Type", "application/canary-safe"),
                                ),
                                customBodies = listOf(
                                    CustomBody("api_key", JsonPrimitive("model-body-canary")),
                                    CustomBody("gateway_mode", JsonPrimitive("model-arbitrary-body-canary")),
                                    CustomBody("temperature", JsonPrimitive(0.25)),
                                ),
                            )
                        ),
                    )
                ),
                assistants = listOf(
                    Assistant(
                        id = ASSISTANT_ID,
                        customHeaders = listOf(
                            CustomHeader("Cookie", "assistant-cookie-canary"),
                            CustomHeader("Accept", "application/assistant-safe"),
                        ),
                        customBodies = listOf(
                            CustomBody("refresh_token", JsonPrimitive("assistant-token-canary")),
                            CustomBody("verbosity", JsonPrimitive("assistant-safe")),
                        ),
                    )
                ),
                mcpServers = listOf(
                    McpServerConfig.StreamableHTTPServer(
                        id = MCP_ID,
                        commonOptions = McpCommonOptions(
                            headers = listOf(
                                "Authorization" to "mcp-auth-canary",
                                "X-Trace-Id" to "mcp-arbitrary-pair-canary",
                                "User-Agent" to "mcp-user-agent-safe",
                            ),
                            oauth = McpOAuthState(
                                enabled = true,
                                clientSecret = "mcp-client-secret-canary",
                                accessToken = "mcp-access-token-canary",
                                refreshToken = "mcp-refresh-token-canary",
                            ),
                        ),
                    )
                ),
            ),
            json = json,
        )

        listOf(
            "provider-api-canary",
            "model-auth-canary",
            "model-api-key-canary",
            "model-arbitrary-header-canary",
            "model-body-canary",
            "model-arbitrary-body-canary",
            "assistant-cookie-canary",
            "assistant-token-canary",
            "mcp-auth-canary",
            "mcp-arbitrary-pair-canary",
            "mcp-client-secret-canary",
            "mcp-access-token-canary",
            "mcp-refresh-token-canary",
        ).forEach { secret -> assertFalse("Secret remained in portable backup: $secret", encoded.contains(secret)) }
        listOf(
            "application/canary-safe",
            "application/assistant-safe",
            "assistant-safe",
            "mcp-user-agent-safe",
        ).forEach { safeValue -> assertTrue("Non-secret value was removed: $safeValue", encoded.contains(safeValue)) }
    }

    @Test
    fun `legacy restore sanitizes remote secrets then keeps local secrets for equivalent reordered scopes`() {
        val localBase = settingsFixture(
            providerSecret = "local-provider-secret",
            providerBaseUrl = "https://api.example.com/v1",
            modelAuthorization = "local-model-auth",
            modelSafeHeader = "local-model-safe",
            modelBodySecret = "local-model-body-secret",
            assistantCookie = "local-assistant-cookie",
            assistantBodySecret = "local-assistant-body-secret",
            assistantSafeHeader = "local-assistant-safe",
            mcpAuthorization = "local-mcp-auth",
            mcpSafeHeader = "local-mcp-safe",
            mcpUrl = "https://mcp.example.com/api",
        )
        val localSettings = localBase.copy(
            providers = listOf(
                ProviderSetting.OpenAI(id = UNRELATED_PROVIDER_ID, apiKey = "unrelated-local-secret"),
                localBase.providers.single(),
            ),
            webDavConfig = WebDavConfig(
                url = "https://dav.example.com/root",
                username = "backup-user",
                password = "local-webdav-secret",
            ),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "local-s3-access",
                secretAccessKey = "local-s3-secret",
                bucket = "backup-bucket",
                region = "auto",
            ),
            webServerPort = 8080,
            webServerLocalhostOnly = true,
            webServerAccessPassword = "local-web-secret",
        )
        val backedUpBase = settingsFixture(
            providerSecret = "remote-provider-secret",
            providerBaseUrl = "HTTPS://API.EXAMPLE.COM:443/v1/",
            modelAuthorization = "remote-model-auth",
            modelSafeHeader = "remote-model-safe",
            modelBodySecret = "remote-model-body-secret",
            assistantCookie = "remote-assistant-cookie",
            assistantBodySecret = "remote-assistant-body-secret",
            assistantSafeHeader = "remote-assistant-safe",
            mcpAuthorization = "remote-mcp-auth",
            mcpSafeHeader = "remote-mcp-safe",
            mcpUrl = "HTTPS://MCP.EXAMPLE.COM:443/api/",
            secretFirst = true,
        )
        val backedUpSettings = backedUpBase.copy(
            providers = backedUpBase.providers +
                ProviderSetting.OpenAI(id = REMOTE_ONLY_PROVIDER_ID, apiKey = "remote-only-secret"),
            webDavConfig = WebDavConfig(
                url = "HTTPS://DAV.EXAMPLE.COM:443/root/",
                username = "backup-user",
                password = "remote-webdav-secret",
            ),
            s3Config = S3Config(
                endpoint = "HTTPS://S3.EXAMPLE.COM:443/",
                accessKeyId = "remote-s3-access",
                secretAccessKey = "remote-s3-secret",
                bucket = "backup-bucket",
                region = "auto",
            ),
            webServerPort = 8080,
            webServerLocalhostOnly = true,
            webServerAccessPassword = "remote-web-secret",
        )
        val legacyBackup = json.encodeToString(backedUpSettings)
        assertTrue(legacyBackup.contains("remote-provider-secret"))
        assertTrue(legacyBackup.contains("remote-mcp-auth"))

        val restored = decodeRestoredSettingsPreservingLocalSecrets(
            restoredSettingsJson = legacyBackup,
            localSettings = localSettings,
            json = json,
        )

        val provider = restored.providers.first { it.id == PROVIDER_ID } as ProviderSetting.OpenAI
        val model = provider.models.single()
        assertEquals("local-provider-secret", provider.apiKey)
        assertEquals("local-model-auth", model.customHeaders.first { it.name == "Authorization" }.value)
        assertEquals("remote-model-safe", model.customHeaders.first { it.name == "Content-Type" }.value)
        assertEquals(
            "local-model-body-secret",
            (model.customBodies.first { it.key == "api_key" }.value as JsonPrimitive).content,
        )

        val assistant = restored.assistants.single { it.id == ASSISTANT_ID }
        assertEquals("local-assistant-cookie", assistant.customHeaders.first { it.name == "Cookie" }.value)
        assertEquals(
            "local-assistant-body-secret",
            (assistant.customBodies.first { it.key == "access_token" }.value as JsonPrimitive).content,
        )
        assertEquals("remote-assistant-safe", assistant.customHeaders.first { it.name == "Accept" }.value)

        val mcp = restored.mcpServers.single { it.id == MCP_ID }
        assertEquals("local-mcp-auth", mcp.commonOptions.headers.first { it.first == "Authorization" }.second)
        assertEquals("remote-mcp-safe", mcp.commonOptions.headers.first { it.first == "User-Agent" }.second)

        assertEquals("local-webdav-secret", restored.webDavConfig.password)
        assertEquals("local-s3-access", restored.s3Config.accessKeyId)
        assertEquals("local-s3-secret", restored.s3Config.secretAccessKey)
        assertEquals("local-web-secret", restored.webServerAccessPassword)

        val remoteOnly = restored.providers.first { it.id == REMOTE_ONLY_PROVIDER_ID } as ProviderSetting.OpenAI
        assertTrue("A remote-only owner must not receive another provider's secret", remoteOnly.apiKey.isEmpty())
        assertTrue(legacyBackup.contains("remote-only-secret"))
    }

    @Test
    fun `restore fails closed for the same owner id when credential scopes change`() {
        val localSettings = settingsFixture(
            providerSecret = "local-provider-secret",
            providerBaseUrl = "https://api.example.com/v1?tenant=local",
            mcpAuthorization = "local-mcp-auth",
            mcpUrl = "https://mcp.example.com/api",
        ).copy(
            webDavConfig = WebDavConfig(
                url = "https://dav.example.com/root",
                username = "backup-user",
                password = "local-webdav-secret",
            ),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "local-s3-access",
                secretAccessKey = "local-s3-secret",
                bucket = "local-bucket",
                region = "auto",
            ),
            webServerPort = 8080,
            webServerLocalhostOnly = true,
            webServerAccessPassword = "local-web-secret",
        )
        val backedUpSettings = settingsFixture(
            providerSecret = "remote-provider-secret",
            providerBaseUrl = "https://api.example.com/v1?tenant=remote",
            modelAuthorization = "remote-model-auth",
            modelBodySecret = "remote-model-body-secret",
            assistantCookie = "remote-assistant-cookie",
            assistantBodySecret = "remote-assistant-body-secret",
            mcpAuthorization = "remote-mcp-auth",
            mcpUrl = "https://mcp.example.com/other",
        ).copy(
            webDavConfig = WebDavConfig(
                url = "https://other-dav.example.com/root",
                username = "backup-user",
                password = "remote-webdav-secret",
            ),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "remote-s3-access",
                secretAccessKey = "remote-s3-secret",
                bucket = "other-bucket",
                region = "auto",
            ),
            webServerPort = 9090,
            webServerLocalhostOnly = true,
            webServerAccessPassword = "remote-web-secret",
        )

        val restored = decodeRestoredSettingsPreservingLocalSecrets(
            restoredSettingsJson = json.encodeToString(backedUpSettings),
            localSettings = localSettings,
            json = json,
        )

        val provider = restored.providers.single() as ProviderSetting.OpenAI
        val model = provider.models.single()
        assertTrue(provider.apiKey.isEmpty())
        assertTrue(model.customHeaders.first { it.name == "Authorization" }.value.isEmpty())
        assertEquals("", (model.customBodies.first { it.key == "api_key" }.value as JsonPrimitive).content)
        val assistant = restored.assistants.single()
        assertTrue(assistant.customHeaders.first { it.name == "Cookie" }.value.isEmpty())
        assertEquals("", (assistant.customBodies.single().value as JsonPrimitive).content)
        val mcp = restored.mcpServers.single()
        assertTrue(mcp.commonOptions.headers.first { it.first == "Authorization" }.second.isEmpty())
        assertTrue(restored.webDavConfig.password.isEmpty())
        assertTrue(restored.s3Config.accessKeyId.isEmpty())
        assertTrue(restored.s3Config.secretAccessKey.isEmpty())
        assertTrue(restored.webServerAccessPassword.isEmpty())
    }

    @Test
    fun `restore supplies a missing secret property only for the same owner`() {
        val restored = json.parseToJsonElement(
            """{
                "providers": [{
                    "type": "openai",
                    "id": "$PROVIDER_ID",
                    "baseUrl": "https://api.example.com/v1",
                    "models": [],
                    "name": "Restored"
                }]
            }""".trimIndent()
        )
        val local = json.parseToJsonElement(
            """{
                "providers": [
                    {
                        "type": "openai",
                        "id": "$PROVIDER_ID",
                        "baseUrl": "https://api.example.com/v1/",
                        "models": [],
                        "apiKey": "same-owner-secret"
                    },
                    {
                        "type": "openai",
                        "id": "$UNRELATED_PROVIDER_ID",
                        "baseUrl": "https://api.example.com/v1",
                        "models": [],
                        "apiKey": "other-owner-secret"
                    }
                ]
            }""".trimIndent()
        )

        val merged = BackupSettingsSanitizer.mergeLocalSecrets(restored, local).toString()

        assertTrue(merged.contains("same-owner-secret"))
        assertFalse(merged.contains("other-owner-secret"))
    }

    @Test
    fun `assistant secret is not restored when its provider scope cannot be resolved`() {
        val localSettings = settingsFixture(assistantCookie = "local-assistant-cookie")
        val backedUpSettings = settingsFixture(assistantCookie = "remote-assistant-cookie")
            .copy(providers = emptyList())

        val restored = decodeRestoredSettingsPreservingLocalSecrets(
            restoredSettingsJson = json.encodeToString(backedUpSettings),
            localSettings = localSettings,
            json = json,
        )

        assertTrue(restored.assistants.single().customHeaders.first { it.name == "Cookie" }.value.isEmpty())
    }

    @Test
    fun `assistant secret is not restored when its model id resolves to conflicting provider scopes`() {
        val localSettings = settingsFixture(assistantCookie = "local-assistant-cookie")
        val backedUpBase = settingsFixture(assistantCookie = "remote-assistant-cookie")
        val conflictingProvider = ProviderSetting.OpenAI(
            id = UNRELATED_PROVIDER_ID,
            baseUrl = "https://untrusted.example.com/v1",
            models = listOf(Model(id = MODEL_ID)),
        )
        val backedUpSettings = backedUpBase.copy(
            providers = listOf(conflictingProvider, backedUpBase.providers.single()),
        )

        val restored = decodeRestoredSettingsPreservingLocalSecrets(
            restoredSettingsJson = json.encodeToString(backedUpSettings),
            localSettings = localSettings,
            json = json,
        )

        assertTrue(restored.assistants.single().customHeaders.first { it.name == "Cookie" }.value.isEmpty())
    }

    private fun settingsFixture(
        providerSecret: String = "local-provider-secret",
        providerBaseUrl: String = "https://api.example.com/v1",
        modelAuthorization: String = "local-model-auth",
        modelSafeHeader: String = "local-model-safe",
        modelBodySecret: String = "local-model-body-secret",
        assistantCookie: String = "local-assistant-cookie",
        assistantBodySecret: String = "local-assistant-body-secret",
        assistantSafeHeader: String = "local-assistant-safe",
        mcpAuthorization: String = "local-mcp-auth",
        mcpSafeHeader: String = "local-mcp-safe",
        mcpUrl: String = "https://mcp.example.com/api",
        secretFirst: Boolean = false,
    ): Settings {
        val modelHeaders = listOf(
            CustomHeader("Content-Type", modelSafeHeader),
            CustomHeader("Authorization", modelAuthorization),
        ).orderedSecretFirst(secretFirst)
        val assistantHeaders = listOf(
            CustomHeader("Accept", assistantSafeHeader),
            CustomHeader("Cookie", assistantCookie),
        ).orderedSecretFirst(secretFirst)
        val mcpHeaders = listOf(
            "User-Agent" to mcpSafeHeader,
            "Authorization" to mcpAuthorization,
        ).orderedSecretFirst(secretFirst)
        return Settings(
            chatModelId = MODEL_ID,
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = PROVIDER_ID,
                    name = "Portable Provider",
                    apiKey = providerSecret,
                    baseUrl = providerBaseUrl,
                    models = listOf(
                        Model(
                            id = MODEL_ID,
                            customHeaders = modelHeaders,
                            customBodies = listOf(
                                CustomBody("api_key", JsonPrimitive(modelBodySecret)),
                            ),
                        )
                    ),
                )
            ),
            assistants = listOf(
                Assistant(
                    id = ASSISTANT_ID,
                    chatModelId = MODEL_ID,
                    customHeaders = assistantHeaders,
                    customBodies = listOf(
                        CustomBody("access_token", JsonPrimitive(assistantBodySecret)),
                    ),
                )
            ),
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    id = MCP_ID,
                    url = mcpUrl,
                    commonOptions = McpCommonOptions(
                        headers = mcpHeaders,
                    ),
                ),
            ),
        )
    }

    private fun <T> List<T>.orderedSecretFirst(secretFirst: Boolean): List<T> =
        if (secretFirst) asReversed() else this

    private companion object {
        val PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val UNRELATED_PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val REMOTE_ONLY_PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000103")
        val MODEL_ID = Uuid.parse("00000000-0000-0000-0000-000000000201")
        val ASSISTANT_ID = Uuid.parse("00000000-0000-0000-0000-000000000301")
        val MCP_ID = Uuid.parse("00000000-0000-0000-0000-000000000401")
    }
}

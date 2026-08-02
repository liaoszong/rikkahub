package me.rerere.rikkahub.data.sync

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
                    {"name":"Content-Type","value":"application/json"}
                ],
                "customBodies": [
                    {"key":"access_token","value":"body-token-canary"},
                    {"key":"temperature","value":0.5}
                ],
                "mcpHeaders": [
                    {"first":"X-Client-Secret","second":"mcp-secret-canary"},
                    {"first":"X-Trace-Id","second":"trace-safe"}
                ],
                "access_token": "token-value",
                "safe": "kept"
            }""".trimIndent()
        )
        val sanitized = BackupSettingsSanitizer.sanitize(source).toString()

        listOf(
            "Bearer secret",
            "nested-secret",
            "cookie-canary",
            "body-token-canary",
            "mcp-secret-canary",
            "token-value",
        ).forEach { secret -> assertFalse("Secret remained in nested settings: $secret", sanitized.contains(secret)) }
        listOf("application/json", "trace-safe", "kept", "0.5").forEach { safeValue ->
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
                                    CustomHeader("Content-Type", "application/canary-safe"),
                                ),
                                customBodies = listOf(
                                    CustomBody("api_key", JsonPrimitive("model-body-canary")),
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
                                "X-Trace-Id" to "mcp-trace-safe",
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
            "model-body-canary",
            "assistant-cookie-canary",
            "assistant-token-canary",
            "mcp-auth-canary",
            "mcp-client-secret-canary",
            "mcp-access-token-canary",
            "mcp-refresh-token-canary",
        ).forEach { secret -> assertFalse("Secret remained in portable backup: $secret", encoded.contains(secret)) }
        listOf(
            "application/canary-safe",
            "application/assistant-safe",
            "assistant-safe",
            "mcp-trace-safe",
        ).forEach { safeValue -> assertTrue("Non-secret value was removed: $safeValue", encoded.contains(safeValue)) }
    }

    @Test
    fun `restore keeps local secrets by owner id and semantic slot`() {
        val localBase = settingsFixture(
            providerSecret = "local-provider-secret",
            modelAuthorization = "local-model-auth",
            modelSafeHeader = "local-model-safe",
            modelBodySecret = "local-model-body-secret",
            assistantCookie = "local-assistant-cookie",
            assistantBodySecret = "local-assistant-body-secret",
            assistantSafeHeader = "local-assistant-safe",
            mcpAuthorization = "local-mcp-auth",
            mcpSafeHeader = "local-mcp-safe",
        )
        val localSettings = localBase.copy(
            providers = listOf(
                ProviderSetting.OpenAI(id = UNRELATED_PROVIDER_ID, apiKey = "unrelated-local-secret"),
                localBase.providers.single(),
            )
        )
        val backedUpBase = settingsFixture(
            providerSecret = "remote-provider-secret",
            modelAuthorization = "remote-model-auth",
            modelSafeHeader = "remote-model-safe",
            modelBodySecret = "remote-model-body-secret",
            assistantCookie = "remote-assistant-cookie",
            assistantBodySecret = "remote-assistant-body-secret",
            assistantSafeHeader = "remote-assistant-safe",
            mcpAuthorization = "remote-mcp-auth",
            mcpSafeHeader = "remote-mcp-safe",
        )
        val backedUpSettings = backedUpBase.copy(
            providers = backedUpBase.providers +
                ProviderSetting.OpenAI(id = REMOTE_ONLY_PROVIDER_ID, apiKey = "remote-only-secret")
        )
        val portableBackup = BackupSettingsSanitizer.encode(backedUpSettings, json)

        val restored = decodeRestoredSettingsPreservingLocalSecrets(
            restoredSettingsJson = portableBackup,
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
        assertEquals("remote-mcp-safe", mcp.commonOptions.headers.first { it.first == "X-Trace-Id" }.second)

        val remoteOnly = restored.providers.first { it.id == REMOTE_ONLY_PROVIDER_ID } as ProviderSetting.OpenAI
        assertTrue("A remote-only owner must not receive another provider's secret", remoteOnly.apiKey.isEmpty())
        assertFalse(portableBackup.contains("remote-only-secret"))
    }

    @Test
    fun `restore supplies a missing secret property only for the same owner`() {
        val restored = json.parseToJsonElement(
            """{"providers":[{"type":"openai","id":"$PROVIDER_ID","name":"Restored"}]}"""
        )
        val local = json.parseToJsonElement(
            """{"providers":[{"type":"openai","id":"$PROVIDER_ID","apiKey":"same-owner-secret"},""" +
                """{"type":"openai","id":"$UNRELATED_PROVIDER_ID","apiKey":"other-owner-secret"}]}"""
        )

        val merged = BackupSettingsSanitizer.mergeLocalSecrets(restored, local).toString()

        assertTrue(merged.contains("same-owner-secret"))
        assertFalse(merged.contains("other-owner-secret"))
    }

    private fun settingsFixture(
        providerSecret: String = "local-provider-secret",
        modelAuthorization: String = "local-model-auth",
        modelSafeHeader: String = "local-model-safe",
        modelBodySecret: String = "local-model-body-secret",
        assistantCookie: String = "local-assistant-cookie",
        assistantBodySecret: String = "local-assistant-body-secret",
        assistantSafeHeader: String = "local-assistant-safe",
        mcpAuthorization: String = "local-mcp-auth",
        mcpSafeHeader: String = "local-mcp-safe",
    ): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                id = PROVIDER_ID,
                name = "Portable Provider",
                apiKey = providerSecret,
                models = listOf(
                    Model(
                        id = MODEL_ID,
                        customHeaders = listOf(
                            CustomHeader("Content-Type", modelSafeHeader),
                            CustomHeader("Authorization", modelAuthorization),
                        ),
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
                customHeaders = listOf(
                    CustomHeader("Accept", assistantSafeHeader),
                    CustomHeader("Cookie", assistantCookie),
                ),
                customBodies = listOf(
                    CustomBody("access_token", JsonPrimitive(assistantBodySecret)),
                ),
            )
        ),
        mcpServers = listOf(
            McpServerConfig.StreamableHTTPServer(
                id = MCP_ID,
                commonOptions = McpCommonOptions(
                    headers = listOf(
                        "X-Trace-Id" to mcpSafeHeader,
                        "Authorization" to mcpAuthorization,
                    ),
                ),
            )
        ),
    )

    private companion object {
        val PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val UNRELATED_PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val REMOTE_ONLY_PROVIDER_ID = Uuid.parse("00000000-0000-0000-0000-000000000103")
        val MODEL_ID = Uuid.parse("00000000-0000-0000-0000-000000000201")
        val ASSISTANT_ID = Uuid.parse("00000000-0000-0000-0000-000000000301")
        val MCP_ID = Uuid.parse("00000000-0000-0000-0000-000000000401")
    }
}

package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            """{"headers":{"Authorization":"Bearer secret","X-API-Key":"nested-secret"},"access_token":"token-value","safe":"kept"}"""
        )
        val sanitized = BackupSettingsSanitizer.sanitize(source).toString()

        assertFalse(sanitized.contains("Bearer secret"))
        assertFalse(sanitized.contains("nested-secret"))
        assertFalse(sanitized.contains("token-value"))
        assertTrue(sanitized.contains("kept"))
    }
}

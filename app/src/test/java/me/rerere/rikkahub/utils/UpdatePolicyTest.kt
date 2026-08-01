package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val checksum = "a".repeat(64)

    @Test
    fun `version code is authoritative when present`() {
        assertTrue(UpdatePolicy.isNewer(info(version = "2.4.5", versionCode = 173), "2.4.5", 172))
        assertFalse(UpdatePolicy.isNewer(info(version = "9.0.0", versionCode = 171), "2.4.5", 172))
    }

    @Test
    fun `semantic version is fallback for legacy manifests`() {
        assertTrue(UpdatePolicy.isNewer(info(version = "2.4.6", versionCode = null), "2.4.5", 172))
        assertFalse(UpdatePolicy.isNewer(info(version = "2.4.5", versionCode = null), "2.4.5", 172))
    }

    @Test
    fun `selects matching ABI before universal`() {
        val arm64 = download("arm64-v8a")
        val universal = download("universal")
        val selected = UpdatePolicy.selectDownload(
            info(downloads = listOf(universal, arm64)),
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals(arm64, selected)
    }

    @Test
    fun `rejects foreign source and insecure assets`() {
        assertThrows(IllegalStateException::class.java) {
            UpdatePolicy.validate(info(source = "rikkahub/rikkahub"), "paleink/rikkahub")
        }
        assertThrows(IllegalStateException::class.java) {
            UpdatePolicy.validate(
                info(downloads = listOf(download("arm64-v8a").copy(url = "http://example.test/app.apk"))),
                "paleink/rikkahub",
            )
        }
    }

    @Test
    fun `requires sha256 for every published APK`() {
        assertThrows(IllegalStateException::class.java) {
            UpdatePolicy.validate(
                info(downloads = listOf(download("arm64-v8a").copy(sha256 = null))),
                "paleink/rikkahub",
            )
        }
    }

    private fun info(
        source: String = "paleink/rikkahub",
        version: String = "2.4.6",
        versionCode: Int? = 173,
        downloads: List<UpdateDownload> = emptyList(),
    ) = UpdateInfo(
        source = source,
        version = version,
        versionCode = versionCode,
        publishedAt = "2026-08-01T00:00:00Z",
        changelog = "Test",
        downloads = downloads,
    )

    private fun download(abi: String) = UpdateDownload(
        name = "rikkahub-$abi.apk",
        url = "https://updates.paleink.cc/files/rikkahub-$abi.apk",
        abi = abi,
        sha256 = checksum,
    )
}

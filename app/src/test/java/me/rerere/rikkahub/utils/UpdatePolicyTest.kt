package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    @Test
    fun `rejects cross host download and redirect`() {
        assertThrows(IllegalStateException::class.java) {
            UpdatePolicy.validate(
                info(downloads = listOf(download("arm64-v8a").copy(url = "https://evil.example/app.apk"))),
                "paleink/rikkahub",
                "https://updates.paleink.cc/api/v1/stable.json",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            UpdatePolicy.validateResolvedUrl(
                "https://updates.paleink.cc/files/app.apk",
                "https://cdn.example/app.apk",
                "https://updates.paleink.cc/api/v1/stable.json",
            )
        }
    }

    @Test
    fun `signed payload is authoritative and tampering is rejected`() {
        val json = Json { ignoreUnknownKeys = true }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = json.encodeToString(info(downloads = listOf(download("arm64-v8a")))).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        val encoder = Base64.getEncoder()
        val envelope = json.encodeToString(
            SignedUpdateFeed(
                keyId = "test-key",
                signedPayload = encoder.encodeToString(payload),
                signature = encoder.encodeToString(signature),
            )
        )
        val verified = UpdateFeedVerifier.verifyAndDecode(
            envelopeJson = envelope,
            expectedKeyId = "test-key",
            publicKeyDerBase64 = encoder.encodeToString(keyPair.public.encoded),
            json = json,
        )
        assertEquals("2.4.6", verified.version)

        val tampered = json.decodeFromString<SignedUpdateFeed>(envelope).copy(
            signedPayload = encoder.encodeToString(payload + 'x'.code.toByte()),
        )
        assertThrows(IllegalStateException::class.java) {
            UpdateFeedVerifier.verifyAndDecode(
                envelopeJson = json.encodeToString(tampered),
                expectedKeyId = "test-key",
                publicKeyDerBase64 = encoder.encodeToString(keyPair.public.encoded),
                json = json,
            )
        }
    }

    @Test
    fun `apk metadata must match signed feed and permanent signer`() {
        val update = info(downloads = listOf(download("arm64-v8a")))
        UpdateArtifactPolicy.validateMetadata(
            packageName = "me.rerere.rikkahub",
            versionName = update.version,
            versionCode = update.versionCode!!.toLong(),
            signerSha256 = listOf("trusted"),
            info = update,
            expectedPackageName = "me.rerere.rikkahub",
            expectedSignerSha256 = "trusted",
        )
        assertThrows(IllegalStateException::class.java) {
            UpdateArtifactPolicy.validateMetadata(
                packageName = "me.rerere.rikkahub",
                versionName = update.version,
                versionCode = update.versionCode.toLong(),
                signerSha256 = listOf("attacker"),
                info = update,
                expectedPackageName = "me.rerere.rikkahub",
                expectedSignerSha256 = "trusted",
            )
        }
    }

    @Test
    fun `update check gate is single flight and reusable after completion`() {
        val gate = UpdateCheckSingleFlight()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.leave()
        assertTrue(gate.tryEnter())
        gate.leave()
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

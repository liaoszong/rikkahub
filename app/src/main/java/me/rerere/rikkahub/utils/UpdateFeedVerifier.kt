package me.rerere.rikkahub.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
internal data class SignedUpdateFeed(
    val keyId: String,
    val signedPayload: String,
    val signature: String,
)

/** Verifies the exact UTF-8 feed payload before any update field is trusted. */
internal object UpdateFeedVerifier {
    fun verifyAndDecode(
        envelopeJson: String,
        expectedKeyId: String,
        publicKeyDerBase64: String,
        json: Json = Json { ignoreUnknownKeys = true },
    ): UpdateInfo {
        val envelope = json.decodeFromString<SignedUpdateFeed>(envelopeJson)
        check(envelope.keyId == expectedKeyId) { "Unexpected update signing key" }

        val decoder = Base64.getDecoder()
        val payload = runCatching { decoder.decode(envelope.signedPayload) }
            .getOrElse { throw IllegalStateException("Invalid signed update payload", it) }
        val signatureBytes = runCatching { decoder.decode(envelope.signature) }
            .getOrElse { throw IllegalStateException("Invalid update signature encoding", it) }
        val publicKeyBytes = runCatching { decoder.decode(publicKeyDerBase64) }
            .getOrElse { throw IllegalStateException("Invalid embedded update public key", it) }
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update(payload)
        }
        check(verifier.verify(signatureBytes)) { "Update feed signature verification failed" }
        return json.decodeFromString<UpdateInfo>(payload.decodeToString())
    }
}

internal object UpdateArtifactPolicy {
    fun validateMetadata(
        packageName: String,
        versionName: String?,
        versionCode: Long,
        signerSha256: Collection<String>,
        info: UpdateInfo,
        expectedPackageName: String,
        expectedSignerSha256: String,
    ) {
        check(packageName == expectedPackageName) { "Downloaded APK has an unexpected package name" }
        check(info.versionCode != null && versionCode == info.versionCode.toLong()) {
            "Downloaded APK version code does not match the signed update feed"
        }
        check(versionName == info.version) { "Downloaded APK version name does not match the signed update feed" }
        check(signerSha256.any { it.equals(expectedSignerSha256, ignoreCase = true) }) {
            "Downloaded APK is not signed by the trusted PaleInk release certificate"
        }
    }
}

internal class UpdateCheckSingleFlight {
    private val running = AtomicBoolean(false)

    fun tryEnter(): Boolean = running.compareAndSet(false, true)

    fun leave() {
        check(running.compareAndSet(true, false)) { "Update check gate was not held" }
    }
}

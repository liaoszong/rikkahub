package me.rerere.rikkahub.data.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val GCM_NONCE_BYTES = 12
internal const val GCM_TAG_BYTES = 16

interface CredentialWrappingKeyProvider {
    @Throws(CredentialKeyException::class)
    fun getOrCreate(): SecretKey

    /** Decode must never create a replacement key for existing ciphertext. */
    @Throws(CredentialKeyException::class)
    fun getExisting(): SecretKey = getOrCreate()
}

class CredentialKeyException(
    val reason: CredentialLockReason,
    cause: Throwable? = null,
) : Exception("Credential wrapping key unavailable: $reason", cause)

class AndroidKeystoreWrappingKeyProvider(
    private val alias: String = "rikkahub_credential_vault_v1",
) : CredentialWrappingKeyProvider {
    override fun getOrCreate(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (error: Throwable) {
            val name = error.javaClass.name
            val reason = when {
                name.contains("KeyPermanentlyInvalidated") -> CredentialLockReason.KEY_INVALIDATED
                name.contains("UserNotAuthenticated") || name.contains("Locked") -> CredentialLockReason.DEVICE_LOCKED
                else -> CredentialLockReason.KEY_UNAVAILABLE
            }
            throw CredentialKeyException(reason, error)
        }
    }

    override fun getExisting(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (keyStore.getKey(alias, null) as? SecretKey)
                ?: throw CredentialKeyException(CredentialLockReason.KEY_INVALIDATED)
        } catch (error: CredentialKeyException) {
            throw error
        } catch (error: Throwable) {
            val names = generateSequence(error) { it.cause }.joinToString("|") { it.javaClass.name }
            val reason = when {
                names.contains("KeyPermanentlyInvalidated") -> CredentialLockReason.KEY_INVALIDATED
                names.contains("UserNotAuthenticated") || names.contains("Locked") -> CredentialLockReason.DEVICE_LOCKED
                else -> CredentialLockReason.KEY_UNAVAILABLE
            }
            throw CredentialKeyException(reason, error)
        }
    }
}

class InMemoryWrappingKeyProvider(keyBytes: ByteArray) : CredentialWrappingKeyProvider {
    private val key = SecretKeySpec(keyBytes.copyOf(), "AES")

    init {
        require(keyBytes.size in setOf(16, 24, 32))
    }

    override fun getOrCreate(): SecretKey = key

    override fun getExisting(): SecretKey = key
}

internal class AesGcmEngine(
    private val random: SecureRandom = SecureRandom(),
) {
    data class Ciphertext(val nonce: ByteArray, val body: ByteArray, val tag: ByteArray)

    fun newDekBytes(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): Ciphertext {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore rejects caller-provided IVs for randomized encryption keys. Let the
        // provider generate the nonce, then persist it in the authenticated envelope.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = requireNotNull(cipher.iv).also { require(it.size == GCM_NONCE_BYTES) }
        cipher.updateAAD(aad)
        val output = cipher.doFinal(plaintext)
        return Ciphertext(nonce, output.copyOfRange(0, output.size - GCM_TAG_BYTES), output.takeLast(GCM_TAG_BYTES).toByteArray())
    }

    fun decrypt(key: SecretKey, value: Ciphertext, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BYTES * 8, value.nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(value.body + value.tag)
    }
}

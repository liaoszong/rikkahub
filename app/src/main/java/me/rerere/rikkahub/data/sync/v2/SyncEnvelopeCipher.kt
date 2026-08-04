package me.rerere.rikkahub.data.sync.v2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** E2EE envelope for remote Sync v2 records. The caller owns distribution of the 32-byte space key. */
class SyncEnvelopeCipher(
    spaceKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val encryptionKey = derive(spaceKey, "rikkahub-sync-v2/encryption")
    private val contentHashKey = derive(spaceKey, "rikkahub-sync-v2/content-hash")
    private var closed = false

    init {
        require(spaceKey.size == KEY_BYTES) { "Sync space key must contain 256 bits" }
    }

    fun encrypt(path: SyncObjectPath, plaintext: ByteArray): ByteArray {
        checkOpen()
        require(plaintext.size <= MAX_ENVELOPE_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(path))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(VERSION)
                output.write(nonce)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }.also {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    fun decrypt(path: SyncObjectPath, envelope: ByteArray): ByteArray {
        checkOpen()
        require(envelope.size <= MAX_ENVELOPE_BYTES + 128)
        return DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(MessageDigest.isEqual(MAGIC, magic)) { "Sync envelope magic mismatch" }
            require(input.readInt() == VERSION) { "Unsupported sync envelope version" }
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val size = input.readInt().also { require(it in TAG_BYTES..(MAX_ENVELOPE_BYTES + TAG_BYTES)) }
            val ciphertext = ByteArray(size).also(input::readFully)
            require(input.read() == -1) { "Trailing sync envelope bytes" }
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(encryptionKey, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                cipher.updateAAD(aad(path))
                cipher.doFinal(ciphertext)
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
            }
        }
    }

    /** Prevents a storage provider from learning equality with ordinary public SHA-256 hashes. */
    fun keyedContentHash(plaintext: ByteArray): String {
        checkOpen()
        return hmac(contentHashKey, plaintext).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun close() {
        encryptionKey.fill(0)
        contentHashKey.fill(0)
        closed = true
    }

    private fun checkOpen() = check(!closed) { "Sync envelope cipher is closed" }

    private fun aad(path: SyncObjectPath): ByteArray =
        "rikkahub-sync-v2|${path.value}".toByteArray(Charsets.UTF_8)

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val VERSION = 1
        const val MAX_ENVELOPE_BYTES = 16 * 1024 * 1024
        const val CIPHER = "AES/GCM/NoPadding"
        val MAGIC = byteArrayOf(0x52, 0x4b, 0x53, 0x59, 0x4e, 0x43, 0x32, 0x00) // RKSYNC2

        fun derive(spaceKey: ByteArray, label: String): ByteArray {
            require(spaceKey.size == KEY_BYTES)
            return hmac(spaceKey, label.toByteArray(Charsets.UTF_8))
        }

        fun hmac(key: ByteArray, value: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value)
        }
    }
}

package me.rerere.pale.sync

import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Strict deterministic wire codec for the pure protocol contract. Encryption belongs to transport. */
object CanonicalSyncCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
    }

    fun encode(operation: SyncOperationEnvelope): ByteArray =
        json.encodeToString(operation).toByteArray(Charsets.UTF_8)

    /** Rejects unknown fields, non-canonical ordering/default omission, malformed UTF-8 and invalid contracts. */
    fun decodeCanonical(bytes: ByteArray): SyncOperationEnvelope {
        require(bytes.isNotEmpty() && bytes.size <= MAX_OPERATION_BYTES) { "Invalid operation envelope size" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Operation envelope is not valid UTF-8" }
        val decoded = json.decodeFromString<SyncOperationEnvelope>(text)
        require(encode(decoded).contentEquals(bytes)) { "Operation envelope is not canonical" }
        return decoded
    }

    fun hashPayload(payload: ByteArray): ContentHash {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds protocol limit" }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return ContentHash("sha256:" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
    }

    fun verifyPayload(expected: ContentHash, payload: ByteArray): Boolean =
        MessageDigest.isEqual(expected.value.toByteArray(Charsets.US_ASCII), hashPayload(payload).value.toByteArray(Charsets.US_ASCII))

    const val MAX_OPERATION_BYTES: Int = 256 * 1024
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
}

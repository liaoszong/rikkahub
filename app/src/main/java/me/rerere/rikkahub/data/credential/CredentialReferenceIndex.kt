package me.rerere.rikkahub.data.credential

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal data class ActiveCredentialReference(
    val slotId: CredentialSlotId,
    val audience: String,
    val refId: CredentialRefId,
    val credentialRevision: Long,
)

internal class CredentialReferenceIndex(
    private val files: AtomicCredentialFiles,
    private val wrappingKeys: CredentialWrappingKeyProvider,
    private val crypto: AesGcmEngine = AesGcmEngine(),
) {
    // AEAD prevents undetected edits/cross-slot substitution. A privileged attacker capable of restoring a complete,
    // previously valid no-backup directory can still replay it; Android Keystore does not provide a monotonic counter.
    private val magic = byteArrayOf(0x52, 0x4b, 0x43, 0x52, 0x45, 0x46, 0x53, 0x31) // RKCREFS1
    private val fileName = "references.v1"

    fun load(): MutableMap<CredentialSlotId, ActiveCredentialReference> {
        val bytes = files.read(fileName) ?: return linkedMapOf()
        return decodeAuthenticated(bytes).associateByTo(linkedMapOf()) { it.slotId }
    }

    fun store(values: Collection<ActiveCredentialReference>) {
        val sorted = values.sortedBy { it.slotId.value }
        val bytes = encodeAuthenticated(sorted)
        files.writeVerified(fileName, bytes) { candidate ->
            require(decodeAuthenticated(candidate) == sorted)
        }
    }

    private fun encodeAuthenticated(values: List<ActiveCredentialReference>): ByteArray {
        val encrypted = crypto.encrypt(wrappingKeys.getOrCreate(), encode(values), indexAad)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeInt(2)
                output.write(encrypted.nonce)
                output.writeInt(encrypted.body.size)
                output.write(encrypted.body)
                output.write(encrypted.tag)
            }
            bytes.toByteArray()
        }
    }

    private fun decodeAuthenticated(bytes: ByteArray): List<ActiveCredentialReference> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Credential reference index magic mismatch" }
            require(input.readInt() == 2) { "Unsupported credential reference index version" }
            val nonce = ByteArray(GCM_NONCE_BYTES).also(input::readFully)
            val bodySize = input.readInt().also { require(it in 0..(16 * 1024 * 1024)) }
            val body = ByteArray(bodySize).also(input::readFully)
            val tag = ByteArray(GCM_TAG_BYTES).also(input::readFully)
            require(input.read() == -1) { "Trailing authenticated reference index bytes" }
            val plaintext = crypto.decrypt(
                wrappingKeys.getExisting(),
                AesGcmEngine.Ciphertext(nonce, body, tag),
                indexAad,
            )
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }

    private fun encode(values: List<ActiveCredentialReference>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeInt(1)
            output.writeInt(values.size)
            values.forEach { value ->
                output.writeString(value.slotId.value)
                output.writeString(value.audience)
                output.writeString(value.refId.value)
                output.writeLong(value.credentialRevision)
            }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<ActiveCredentialReference> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Credential reference index magic mismatch" }
            require(input.readInt() == 1) { "Unsupported credential reference index version" }
            val count = input.readInt().also { require(it in 0..100_000) }
            val result = List(count) {
                ActiveCredentialReference(
                    slotId = CredentialSlotId.stored(input.readString()),
                    audience = input.readString(),
                    refId = CredentialRefId.stored(input.readString()),
                    credentialRevision = input.readLong().also { require(it >= 1) },
                )
            }
            require(input.read() == -1) { "Trailing credential reference index bytes" }
            require(result.map { it.slotId }.distinct().size == result.size) { "Duplicate credential slot" }
            result
        }

    private val indexAad = "rikkahub-credential-reference-index|schema=2".toByteArray(StandardCharsets.UTF_8)
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= 16 * 1024)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readString(): String {
    val size = readInt().also { require(it in 0..16 * 1024) }
    return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
}

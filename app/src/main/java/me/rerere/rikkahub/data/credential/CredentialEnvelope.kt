package me.rerere.rikkahub.data.credential

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal data class CredentialEnvelopeMetadata(
    val refId: CredentialRefId,
    val slotId: CredentialSlotId,
    val namespace: String,
    val ownerStableId: String,
    val fieldSlot: String,
    val kind: String,
    val audience: String,
    val revision: Long,
) {
    fun aad(): ByteArray = listOf(
        "schema=$ENVELOPE_SCHEMA",
        "credentialId=${refId.value}",
        "kind=$kind",
        "owner=$ownerStableId",
        "slot=${slotId.value}",
        "field=$fieldSlot",
        "namespace=$namespace",
        "audience=$audience",
        "revision=$revision",
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

internal data class DecodedCredentialEnvelope(
    val metadata: CredentialEnvelopeMetadata,
    val secret: ByteArray,
)

internal const val ENVELOPE_SCHEMA = 1
private val ENVELOPE_MAGIC = byteArrayOf(0x52, 0x4b, 0x56, 0x41, 0x55, 0x4c, 0x54, 0x31) // RKVAULT1
private const val MAX_STRING_BYTES = 16 * 1024
private const val MAX_SECRET_BYTES = 4 * 1024 * 1024

internal class CredentialEnvelopeCodec(
    private val wrappingKeys: CredentialWrappingKeyProvider,
    private val crypto: AesGcmEngine = AesGcmEngine(),
) {
    fun encode(metadata: CredentialEnvelopeMetadata, secret: ByteArray): ByteArray {
        require(secret.size <= MAX_SECRET_BYTES)
        val dekBytes = crypto.newDekBytes()
        try {
            val dek = SecretKeySpec(dekBytes, "AES")
            val aad = metadata.aad()
            val wrappedDek = crypto.encrypt(
                wrappingKeys.getOrCreate(),
                dekBytes,
                aad + "\nlayer=dek".toByteArray(),
            )
            val payload = crypto.encrypt(dek, secret, aad + "\nlayer=payload".toByteArray())
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(ENVELOPE_MAGIC)
                    out.writeInt(ENVELOPE_SCHEMA)
                    out.writeMetadata(metadata)
                    out.writeCiphertext(wrappedDek)
                    out.writeCiphertext(payload)
                }
                bytes.toByteArray()
            }
        } finally {
            dekBytes.fill(0)
        }
    }

    fun decode(bytes: ByteArray): DecodedCredentialEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magic = ByteArray(ENVELOPE_MAGIC.size).also(input::readFully)
        require(magic.contentEquals(ENVELOPE_MAGIC)) { "Credential envelope magic mismatch" }
        require(input.readInt() == ENVELOPE_SCHEMA) { "Unsupported credential envelope version" }
        val metadata = input.readMetadata()
        val wrappedDek = input.readCiphertext()
        val payload = input.readCiphertext()
        require(input.read() == -1) { "Trailing credential envelope bytes" }
        val aad = metadata.aad()
        val dekBytes = crypto.decrypt(wrappingKeys.getExisting(), wrappedDek, aad + "\nlayer=dek".toByteArray())
        try {
            require(dekBytes.size == 32) { "Invalid credential DEK" }
            val dek: SecretKey = SecretKeySpec(dekBytes, "AES")
            DecodedCredentialEnvelope(metadata, crypto.decrypt(dek, payload, aad + "\nlayer=payload".toByteArray()))
        } finally {
            dekBytes.fill(0)
        }
    }

    private fun DataOutputStream.writeMetadata(value: CredentialEnvelopeMetadata) {
        writeUtf8(value.refId.value)
        writeUtf8(value.slotId.value)
        writeUtf8(value.namespace)
        writeUtf8(value.ownerStableId)
        writeUtf8(value.fieldSlot)
        writeUtf8(value.kind)
        writeUtf8(value.audience)
        writeLong(value.revision)
    }

    private fun DataInputStream.readMetadata() = CredentialEnvelopeMetadata(
        refId = CredentialRefId.stored(readUtf8()),
        slotId = CredentialSlotId.stored(readUtf8()),
        namespace = readUtf8(),
        ownerStableId = readUtf8(),
        fieldSlot = readUtf8(),
        kind = readUtf8(),
        audience = readUtf8(),
        revision = readLong().also { require(it >= 1) },
    )

    private fun DataOutputStream.writeCiphertext(value: AesGcmEngine.Ciphertext) {
        require(value.nonce.size == GCM_NONCE_BYTES && value.tag.size == GCM_TAG_BYTES)
        write(value.nonce)
        writeInt(value.body.size)
        write(value.body)
        write(value.tag)
    }

    private fun DataInputStream.readCiphertext(): AesGcmEngine.Ciphertext {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(::readFully)
        val size = readInt().also { require(it in 0..MAX_SECRET_BYTES) }
        val body = ByteArray(size).also(::readFully)
        val tag = ByteArray(GCM_TAG_BYTES).also(::readFully)
        return AesGcmEngine.Ciphertext(nonce, body, tag)
    }
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_STRING_BYTES)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readUtf8(): String {
    val size = readInt().also { require(it in 0..MAX_STRING_BYTES) }
    return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
}

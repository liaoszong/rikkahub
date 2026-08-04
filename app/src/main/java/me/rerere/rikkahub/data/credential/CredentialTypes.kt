package me.rerere.rikkahub.data.credential

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@JvmInline
value class CredentialSlotId private constructor(val value: String) {
    companion object {
        fun of(namespace: String, ownerStableId: String, fieldSlot: String): CredentialSlotId {
            val canonical = listOf(namespace, ownerStableId, fieldSlot).joinToString("\u0000") { it.trim() }
            require(canonical.split('\u0000').all(String::isNotEmpty)) { "Credential slot components must not be blank" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return CredentialSlotId("slot_$digest")
        }

        internal fun stored(value: String): CredentialSlotId {
            require(value.matches(Regex("slot_[0-9a-f]{64}"))) { "Invalid stored credential slot id" }
            return CredentialSlotId(value)
        }
    }
}

@JvmInline
value class CredentialRefId private constructor(val value: String) {
    fun referenceString(): String = "$REFERENCE_PREFIX$value"

    companion object {
        const val REFERENCE_PREFIX = "vault:v1:"

        fun new(): CredentialRefId = CredentialRefId(UUID.randomUUID().toString())

        fun isReference(value: String): Boolean = parseReference(value) != null

        fun parseReference(value: String): CredentialRefId? {
            if (!value.startsWith(REFERENCE_PREFIX)) return null
            return runCatching { UUID.fromString(value.removePrefix(REFERENCE_PREFIX)) }
                .getOrNull()
                ?.let { CredentialRefId(it.toString()) }
        }

        internal fun stored(value: String): CredentialRefId =
            CredentialRefId(UUID.fromString(value).toString())
    }
}

data class CredentialAddress(
    val slotId: CredentialSlotId,
    val namespace: String,
    val ownerStableId: String,
    val fieldSlot: String,
    val kind: String,
    val audience: String,
) {
    init {
        require(namespace.isNotBlank())
        require(ownerStableId.isNotBlank())
        require(fieldSlot.isNotBlank())
        require(kind.isNotBlank())
        require(audience.isNotBlank())
        require(slotId == CredentialSlotId.of(namespace, ownerStableId, fieldSlot))
    }
}

data class CredentialValue(
    val refId: CredentialRefId,
    val slotId: CredentialSlotId,
    val kind: String,
    val audience: String,
    val revision: Long,
    val secret: ByteArray,
) {
    init {
        require(revision >= 1)
    }

    override fun equals(other: Any?): Boolean =
        other is CredentialValue &&
            refId == other.refId &&
            slotId == other.slotId &&
            kind == other.kind &&
            audience == other.audience &&
            revision == other.revision &&
            secret.contentEquals(other.secret)

    override fun hashCode(): Int = 31 * refId.hashCode() + secret.contentHashCode()
}

sealed interface CredentialReadResult {
    data class Found(val value: CredentialValue) : CredentialReadResult
    data object Missing : CredentialReadResult
    data class Locked(val reason: CredentialLockReason, val cause: Throwable? = null) : CredentialReadResult
    data class Corrupt(val reason: String, val cause: Throwable? = null) : CredentialReadResult
}

enum class CredentialLockReason {
    DEVICE_LOCKED,
    KEY_INVALIDATED,
    KEY_UNAVAILABLE,
}

sealed interface CredentialWriteResult {
    data class Written(
        val refId: CredentialRefId,
        val reference: String,
        val revision: Long,
    ) : CredentialWriteResult

    data class Conflict(val currentRefId: CredentialRefId?, val currentRevision: Long?) : CredentialWriteResult
    data class Orphaned(
        val refId: CredentialRefId,
        val revision: Long,
        val reason: String,
        val cause: Throwable? = null,
    ) : CredentialWriteResult
    data class Locked(val reason: CredentialLockReason, val cause: Throwable? = null) : CredentialWriteResult
    data class Failed(val reason: String, val cause: Throwable? = null) : CredentialWriteResult
}

data class CredentialExpectation(
    val refId: CredentialRefId?,
    val revision: Long?,
) {
    init {
        require((refId == null) == (revision == null)) { "refId and revision must be provided together" }
        require(revision == null || revision >= 1)
    }

    companion object {
        val ABSENT = CredentialExpectation(null, null)
    }
}

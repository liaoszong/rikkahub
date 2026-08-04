package me.rerere.pale.sync

import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

private val SAFE_ENTITY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val LOWERCASE_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

private fun newUuid(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

private fun requireUuid(value: String, label: String) {
    require(LOWERCASE_UUID.matches(value)) { "$label must be a canonical lowercase UUID" }
    require(UUID.fromString(value).toString() == value) { "$label is not canonical" }
}

/** Identity of one installation. Restoring a backup must create a new value. */
@Serializable
@JvmInline
value class ReplicaId(val value: String) : Comparable<ReplicaId> {
    init { requireUuid(value, "ReplicaId") }
    override fun compareTo(other: ReplicaId): Int = value.compareTo(other.value)

    companion object { fun random(): ReplicaId = ReplicaId(newUuid()) }
}

/** Identity of an isolated sync epoch. A full restore starts a new space. */
@Serializable
@JvmInline
value class SpaceId(val value: String) {
    init { requireUuid(value, "SpaceId") }

    companion object { fun random(): SpaceId = SpaceId(newUuid()) }
}

/** Host-owned stable entity identity. It is opaque and never interpreted as a path. */
@Serializable
@JvmInline
value class SyncEntityId(val value: String) {
    init {
        require(SAFE_ENTITY_ID.matches(value)) {
            "SyncEntityId must be 1..128 safe opaque characters"
        }
    }
}

/** Globally unique immutable operation identity: installation plus durable monotonic counter. */
@Serializable
data class OperationId(
    val replicaId: ReplicaId,
    val counter: Long,
) : Comparable<OperationId> {
    init { require(counter > 0) { "Operation counter must be positive" } }

    val pathSegment: String get() = "${replicaId.value}.$counter"

    override fun compareTo(other: OperationId): Int {
        val replicaOrder = replicaId.compareTo(other.replicaId)
        return if (replicaOrder != 0) replicaOrder else counter.compareTo(other.counter)
    }

    companion object {
        fun parsePathSegment(value: String): OperationId {
            require(value.length <= 64) { "Operation path segment is too long" }
            val separator = value.lastIndexOf('.')
            require(separator > 0 && separator < value.lastIndex) { "Malformed operation path segment" }
            val replica = ReplicaId(value.substring(0, separator))
            val counterText = value.substring(separator + 1)
            require(counterText.isNotEmpty() && counterText[0] in '1'..'9' && counterText.all(Char::isDigit)) {
                "Operation counter must be canonical decimal"
            }
            val counter = counterText.toLongOrNull()
            require(counter != null) { "Operation counter overflow" }
            return OperationId(replica, counter)
                .also { require(it.pathSegment == value) { "Non-canonical operation path segment" } }
        }
    }
}

/** SHA-256 content address used for payloads and independently transferred blobs. */
@Serializable
@JvmInline
value class ContentHash(val value: String) : Comparable<ContentHash> {
    init {
        require(value.matches(Regex("sha256:[0-9a-f]{64}"))) {
            "ContentHash must be canonical sha256 lowercase hex"
        }
    }

    override fun compareTo(other: ContentHash): Int = value.compareTo(other.value)
}

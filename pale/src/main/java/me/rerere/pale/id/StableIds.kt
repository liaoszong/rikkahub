package me.rerere.pale.id

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * Marker implemented by every host-owned identity persisted by PaleInk.
 *
 * IDs are deliberately opaque. New values are UUIDs, while imported historical
 * `legacy-*` values remain valid forever so migrations never rewrite references.
 */
sealed interface StableId {
    val value: String
}

private val VALID_STABLE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

private fun validateStableId(value: String) {
    require(VALID_STABLE_ID.matches(value)) {
        "Stable ID must be 1..128 safe opaque characters"
    }
}

private fun newStableId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

@Serializable
@JvmInline
value class ConversationId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = ConversationId(newStableId()) }
}

@Serializable
@JvmInline
value class MessageNodeId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MessageNodeId(newStableId()) }
}

@Serializable
@JvmInline
value class MessageId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MessageId(newStableId()) }
}

@Serializable
@JvmInline
value class MessagePartId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MessagePartId(newStableId()) }
}

@Serializable
@JvmInline
value class MessageBranchGroupId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MessageBranchGroupId(newStableId()) }
}

@Serializable
@JvmInline
value class MediaAssetId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MediaAssetId(newStableId()) }
}

@Serializable
@JvmInline
value class MediaBlobId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MediaBlobId(newStableId()) }
}

@Serializable
@JvmInline
value class MediaReplicaId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = MediaReplicaId(newStableId()) }
}

@Serializable
@JvmInline
value class ManagedFileId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = ManagedFileId(newStableId()) }
}

@Serializable
@JvmInline
value class RequestId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = RequestId(newStableId()) }
}

@Serializable
@JvmInline
value class RequestAttemptId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = RequestAttemptId(newStableId()) }
}

@Serializable
@JvmInline
value class RequestOutputId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = RequestOutputId(newStableId()) }
}

@Serializable
@JvmInline
value class ToolInvocationId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = ToolInvocationId(newStableId()) }
}

@Serializable
@JvmInline
value class ToolPermissionId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = ToolPermissionId(newStableId()) }
}

@Serializable
@JvmInline
value class RequestAuditEventId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = RequestAuditEventId(newStableId()) }
}

@Serializable
@JvmInline
value class RequestMigrationJournalId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = RequestMigrationJournalId(newStableId()) }
}

@Serializable
@JvmInline
value class ToolAuditEventId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = ToolAuditEventId(newStableId()) }
}

@Serializable
@JvmInline
value class CredentialId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = CredentialId(newStableId()) }
}

@Serializable
@JvmInline
value class SyncReplicaId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = SyncReplicaId(newStableId()) }
}

@Serializable
@JvmInline
value class SyncOperationId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = SyncOperationId(newStableId()) }
}

@Serializable
@JvmInline
value class CitationId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = CitationId(newStableId()) }
}

@Serializable
@JvmInline
value class CitationSourceId(override val value: String) : StableId {
    init { validateStableId(value) }

    companion object { fun random() = CitationSourceId(newStableId()) }
}

package me.rerere.pale.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit allow-list. Device-private state has no representable protocol entity type. */
@Serializable
enum class SyncEntityType {
    @SerialName("conversation") CONVERSATION,
    @SerialName("message_node") MESSAGE_NODE,
    @SerialName("message") MESSAGE,
    @SerialName("assistant") ASSISTANT,
    @SerialName("media_asset") MEDIA_ASSET,
    @SerialName("media_relation") MEDIA_RELATION,
    @SerialName("managed_file") MANAGED_FILE,
    @SerialName("workspace") WORKSPACE,
    @SerialName("citation_source") CITATION_SOURCE,
    @SerialName("message_citation") MESSAGE_CITATION,
}

/**
 * Immutable content-addressed operation. Payload and blobs are transferred separately and must
 * verify against these hashes before the operation is applied.
 */
@Serializable
class SyncOperationEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val spaceId: SpaceId,
    val operationId: OperationId,
    val entityType: SyncEntityType,
    val entityId: SyncEntityId,
    val version: DottedVersion,
    val timestamp: HybridLogicalTimestamp,
    val tombstone: Boolean,
    val payloadHash: ContentHash? = null,
    val blobHashes: List<ContentHash> = emptyList(),
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported Sync protocol version" }
        require(version.dot.replicaId == operationId.replicaId) { "Operation writer and version dot differ" }
        require(version.dot.counter == operationId.counter) { "Operation counter and version dot differ" }
        require(this.blobHashes == this.blobHashes.distinct().sorted()) {
            "Blob hashes must be unique and canonically sorted"
        }
        if (tombstone) {
            require(payloadHash == null) { "Tombstones cannot carry a payload" }
            require(this.blobHashes.isEmpty()) { "Tombstones cannot retain blob references" }
        } else {
            require(payloadHash != null) { "A live operation requires a payload hash" }
        }
    }

    val writer: ReplicaId get() = operationId.replicaId

    fun causalRelationTo(other: SyncOperationEnvelope): CausalRelation {
        require(spaceId == other.spaceId) { "Cannot compare operations from different sync spaces" }
        return version.relationTo(other.version)
    }

    fun concurrentOrderKey(): ConcurrentOrderKey =
        ConcurrentOrderKey(timestamp, operationId.replicaId, operationId.counter)

    fun canGarbageCollectTombstone(
        activeReplicas: Set<ReplicaId>,
        acknowledgements: Map<ReplicaId, VersionVector>,
    ): Boolean {
        if (!tombstone || activeReplicas.isEmpty()) return false
        val deletion = version.asVersionVector()
        return activeReplicas.all { replica -> acknowledgements[replica]?.dominates(deletion) == true }
    }

    override fun equals(other: Any?): Boolean = other is SyncOperationEnvelope &&
        protocolVersion == other.protocolVersion &&
        spaceId == other.spaceId &&
        operationId == other.operationId &&
        entityType == other.entityType &&
        entityId == other.entityId &&
        version == other.version &&
        timestamp == other.timestamp &&
        tombstone == other.tombstone &&
        payloadHash == other.payloadHash &&
        blobHashes == other.blobHashes

    override fun hashCode(): Int = listOf(
        protocolVersion,
        spaceId,
        operationId,
        entityType,
        entityId,
        version,
        timestamp,
        tombstone,
        payloadHash,
        blobHashes,
    ).hashCode()

    companion object { const val PROTOCOL_VERSION: Int = 2 }
}

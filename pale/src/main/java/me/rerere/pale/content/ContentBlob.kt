package me.rerere.pale.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.pale.media.PrivacyScope
import me.rerere.pale.media.RetentionPolicy

/**
 * Provider-neutral description of immutable, potentially large content.
 *
 * This is a domain contract, not a second file store. Android maps it to the existing
 * media/managed-file storage plane; future platforms may provide another physical port.
 */
@Serializable
data class ContentBlob(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val blobId: String,
    val owner: ContentOwnerRef,
    val mimeType: String,
    val byteLength: Long,
    val sha256: String,
    val privacyScope: PrivacyScope = PrivacyScope.PRIVATE,
    val retentionPolicy: RetentionPolicy = RetentionPolicy.CONVERSATION,
    val encryption: ContentEncryption = ContentEncryption(),
    val replicas: List<ContentReplicaRef> = emptyList(),
    val reachability: ContentReachability = ContentReachability(),
    val createdAtEpochMillis: Long,
) {
    init {
        require(schemaVersion > 0) { "ContentBlob schemaVersion must be positive" }
        require(STABLE_ID.matches(blobId)) { "ContentBlob blobId is invalid" }
        require(MIME_TYPE.matches(mimeType)) { "ContentBlob mimeType is invalid" }
        require(byteLength >= 0) { "ContentBlob byteLength cannot be negative" }
        require(SHA_256.matches(sha256)) { "ContentBlob sha256 must be lowercase hex" }
        require(replicas.map(ContentReplicaRef::replicaId).distinct().size == replicas.size) {
            "ContentBlob replica identities must be unique"
        }
        require(createdAtEpochMillis >= 0) { "ContentBlob createdAtEpochMillis cannot be negative" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val STABLE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
        private val MIME_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

@Serializable
data class ContentOwnerRef(
    val kind: ContentOwnerKind,
    val ownerId: String,
    val relation: String? = null,
) {
    init {
        require(ownerId.isNotBlank() && ownerId.length <= 256) { "Content ownerId is invalid" }
        require(relation == null || relation.isNotBlank()) { "Content relation cannot be blank" }
    }
}

@Serializable
enum class ContentOwnerKind {
    @SerialName("conversation") CONVERSATION,
    @SerialName("message") MESSAGE,
    @SerialName("request") REQUEST,
    @SerialName("search_evidence") SEARCH_EVIDENCE,
    @SerialName("memory") MEMORY,
    @SerialName("workspace") WORKSPACE,
}

@Serializable
data class ContentEncryption(
    val state: ContentEncryptionState = ContentEncryptionState.PLATFORM_MANAGED,
    val keyRef: String? = null,
    val algorithm: String? = null,
) {
    init {
        if (state == ContentEncryptionState.ENCRYPTED) {
            require(!keyRef.isNullOrBlank()) { "Encrypted content requires an opaque keyRef" }
            require(!algorithm.isNullOrBlank()) { "Encrypted content requires an algorithm" }
        }
        if (state != ContentEncryptionState.ENCRYPTED) {
            require(keyRef == null) { "Non-encrypted content cannot expose a keyRef" }
        }
    }
}

@Serializable
enum class ContentEncryptionState {
    @SerialName("platform_managed") PLATFORM_MANAGED,
    @SerialName("encrypted") ENCRYPTED,
    @SerialName("not_persisted") NOT_PERSISTED,
}

@Serializable
data class ContentReplicaRef(
    val replicaId: String,
    val kind: ContentReplicaKind,
    val state: ContentReplicaState,
    /** Opaque storage locator; never an API credential or plaintext secret. */
    val locatorRef: String,
    val verifiedAtEpochMillis: Long? = null,
) {
    init {
        require(replicaId.isNotBlank() && replicaId.length <= 160) { "Content replicaId is invalid" }
        require(locatorRef.isNotBlank() && locatorRef.length <= 1024) { "Content locatorRef is invalid" }
        require(verifiedAtEpochMillis == null || verifiedAtEpochMillis >= 0) {
            "Content replica verification time is invalid"
        }
    }
}

@Serializable
enum class ContentReplicaKind {
    @SerialName("managed_file") MANAGED_FILE,
    @SerialName("media_blob") MEDIA_BLOB,
    @SerialName("external_uri") EXTERNAL_URI,
    @SerialName("remote_sync") REMOTE_SYNC,
    @SerialName("ephemeral_cache") EPHEMERAL_CACHE,
}

@Serializable
enum class ContentReplicaState {
    @SerialName("staging") STAGING,
    @SerialName("available") AVAILABLE,
    @SerialName("missing") MISSING,
    @SerialName("corrupt") CORRUPT,
    @SerialName("quarantined") QUARANTINED,
}

@Serializable
data class ContentReachability(
    val strongOwnerRefs: Int = 1,
    val leaseUntilEpochMillis: Long? = null,
    val gcEligibleAtEpochMillis: Long? = null,
) {
    init {
        require(strongOwnerRefs >= 0) { "Content strongOwnerRefs cannot be negative" }
        require(leaseUntilEpochMillis == null || leaseUntilEpochMillis >= 0)
        require(gcEligibleAtEpochMillis == null || gcEligibleAtEpochMillis >= 0)
        require(strongOwnerRefs == 0 || gcEligibleAtEpochMillis == null) {
            "Reachable content cannot be marked GC eligible"
        }
    }
}

package me.rerere.rikkahub.data.sync.v2

/**
 * Narrow remote object contract used by Sync v2.
 *
 * Backends must expose real conditional writes. An adapter must return [Unsupported] instead of
 * emulating compare-and-set with HEAD + PUT, because that race would permit two owners to publish
 * the same operation sequence or overwrite a device head.
 */
interface ObjectSyncTransport {
    suspend fun get(path: SyncObjectPath): SyncRemoteObject?

    suspend fun createImmutable(
        path: SyncObjectPath,
        body: ByteArray,
        contentType: String = SYNC_CONTENT_TYPE,
    ): SyncConditionalWriteResult

    suspend fun compareAndSet(
        path: SyncObjectPath,
        expectedEtag: String,
        body: ByteArray,
        contentType: String = SYNC_CONTENT_TYPE,
    ): SyncConditionalWriteResult

    suspend fun list(prefix: SyncObjectPrefix, cursor: String? = null): SyncObjectPage
}

@JvmInline
value class SyncObjectPath(val value: String) {
    init {
        require(value.length in 1..512)
        require(!value.startsWith('/') && !value.endsWith('/'))
        require(".." !in value.split('/'))
        require(value.matches(Regex("[A-Za-z0-9._/-]+")))
    }
}

@JvmInline
value class SyncObjectPrefix(val value: String) {
    init {
        require(value.length in 1..480)
        require(!value.startsWith('/') && value.endsWith('/'))
        require(".." !in value.split('/'))
        require(value.matches(Regex("[A-Za-z0-9._/-]+")))
    }
}

data class SyncRemoteObject(
    val path: SyncObjectPath,
    val etag: String,
    val body: ByteArray,
) {
    init {
        require(etag.isNotBlank())
    }
}

data class SyncObjectMetadata(
    val path: SyncObjectPath,
    val etag: String,
    val size: Long,
) {
    init {
        require(etag.isNotBlank())
        require(size >= 0)
    }
}

data class SyncObjectPage(
    val objects: List<SyncObjectMetadata>,
    val nextCursor: String? = null,
)

sealed interface SyncConditionalWriteResult {
    data class Written(val etag: String) : SyncConditionalWriteResult {
        init {
            require(etag.isNotBlank())
        }
    }

    data class PreconditionFailed(val currentEtag: String? = null) : SyncConditionalWriteResult

    /** Backend did not prove atomic create/CAS semantics. Sync must stop without uploading ops. */
    data class Unsupported(val reason: String) : SyncConditionalWriteResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

object SyncRemoteLayout {
    private const val ROOT = "rikkahub-sync/v2"

    fun operation(spaceId: String, replicaId: String, counter: Long): SyncObjectPath {
        requireSegment(spaceId)
        requireSegment(replicaId)
        require(counter >= 1)
        return SyncObjectPath("$ROOT/$spaceId/ops/$replicaId/${counter.toString().padStart(20, '0')}.json.enc")
    }

    fun deviceHead(spaceId: String, replicaId: String): SyncObjectPath {
        requireSegment(spaceId)
        requireSegment(replicaId)
        return SyncObjectPath("$ROOT/$spaceId/heads/$replicaId.json.enc")
    }

    fun operationsPrefix(spaceId: String, replicaId: String): SyncObjectPrefix {
        requireSegment(spaceId)
        requireSegment(replicaId)
        return SyncObjectPrefix("$ROOT/$spaceId/ops/$replicaId/")
    }

    fun blob(spaceId: String, keyedContentHash: String): SyncObjectPath {
        requireSegment(spaceId)
        require(keyedContentHash.matches(Regex("[a-f0-9]{64}")))
        return SyncObjectPath("$ROOT/$spaceId/blobs/$keyedContentHash.enc")
    }

    private fun requireSegment(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,96}")))
    }
}

const val SYNC_CONTENT_TYPE = "application/vnd.rikkahub.sync-v2"

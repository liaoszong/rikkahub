package me.rerere.rikkahub.data.sync.v2

import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Proves that a configured backend really honors create-only and compare-and-set writes.
 * Sync v2 must not publish operations until this probe returns [SyncTransportSupport.Verified].
 */
class SyncTransportVerifier(
    private val transport: ObjectSyncTransport,
) {
    suspend fun verify(): SyncTransportSupport = runCatching {
        val path = SyncObjectPath(
            "rikkahub-sync/v2/_probe/${UUID.randomUUID().toString().replace("-", "")}.bin"
        )
        val original = UUID.randomUUID().toString().encodeToByteArray()
        val replacement = UUID.randomUUID().toString().encodeToByteArray()

        val create = transport.createImmutable(path, original)
        val createdEtag = when (create) {
            is SyncConditionalWriteResult.Written -> create.etag
            is SyncConditionalWriteResult.PreconditionFailed ->
                return SyncTransportSupport.Unsupported("Fresh capability probe path already existed")

            is SyncConditionalWriteResult.Unsupported -> return SyncTransportSupport.Unsupported(create.reason)
        }

        when (val duplicate = transport.createImmutable(path, replacement)) {
            is SyncConditionalWriteResult.PreconditionFailed -> Unit
            is SyncConditionalWriteResult.Unsupported -> return SyncTransportSupport.Unsupported(duplicate.reason)
            is SyncConditionalWriteResult.Written -> {
                return SyncTransportSupport.Unsupported("Backend ignored If-None-Match: *")
            }
        }

        val updatedEtag = when (val update = transport.compareAndSet(path, createdEtag, replacement)) {
            is SyncConditionalWriteResult.Written -> update.etag
            is SyncConditionalWriteResult.Unsupported -> return SyncTransportSupport.Unsupported(update.reason)
            is SyncConditionalWriteResult.PreconditionFailed -> {
                return SyncTransportSupport.Unsupported("Backend rejected a valid If-Match update")
            }
        }

        val updated = transport.get(path)
            ?: return SyncTransportSupport.Unsupported("Capability probe disappeared after valid CAS")
        if (updated.etag != updatedEtag || !updated.body.contentEquals(replacement)) {
            return SyncTransportSupport.Unsupported("Valid CAS result was not durably observable")
        }

        when (val stale = transport.compareAndSet(path, createdEtag, original)) {
            is SyncConditionalWriteResult.PreconditionFailed -> Unit
            is SyncConditionalWriteResult.Unsupported -> return SyncTransportSupport.Unsupported(stale.reason)
            is SyncConditionalWriteResult.Written -> {
                return SyncTransportSupport.Unsupported("Backend accepted a stale If-Match ETag")
            }
        }

        val persisted = transport.get(path)
            ?: return SyncTransportSupport.Unsupported("Capability probe disappeared after conditional writes")
        if (persisted.etag != updatedEtag || !persisted.body.contentEquals(replacement)) {
            return SyncTransportSupport.Unsupported("Rejected conditional write changed the remote object")
        }
        SyncTransportSupport.Verified
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        SyncTransportSupport.Unsupported(
            "Conditional-write verification failed: ${error::class.simpleName ?: "unknown error"}"
        )
    }
}

sealed interface SyncTransportSupport {
    data object Verified : SyncTransportSupport

    data class Unsupported(val reason: String) : SyncTransportSupport {
        init {
            require(reason.isNotBlank())
        }
    }
}

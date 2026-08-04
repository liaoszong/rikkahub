package me.rerere.rikkahub.data.sync.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.sync.webdav.WebDavClient
import me.rerere.rikkahub.data.sync.webdav.WebDavConditionalPutResult

/** WebDAV adapter. It never emulates If-Match or If-None-Match with a racy HEAD + PUT pair. */
class WebDavObjectSyncTransport(
    private val client: WebDavClient,
) : ObjectSyncTransport {
    private val collectionMutex = Mutex()
    private val knownCollections = mutableSetOf<String>()

    override suspend fun get(path: SyncObjectPath): SyncRemoteObject? =
        client.getVersioned(path.value).getOrThrow()?.let { remote ->
            SyncRemoteObject(path = path, etag = remote.etag, body = remote.body)
        }

    override suspend fun createImmutable(
        path: SyncObjectPath,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult {
        ensureParentCollections(path)
        return client.putConditional(
            path = path.value,
            data = body,
            contentType = contentType,
            ifNoneMatch = true,
        ).getOrThrow().toSyncResult()
    }

    override suspend fun compareAndSet(
        path: SyncObjectPath,
        expectedEtag: String,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult {
        ensureParentCollections(path)
        return client.putConditional(
            path = path.value,
            data = body,
            contentType = contentType,
            ifMatch = expectedEtag,
        ).getOrThrow().toSyncResult()
    }

    override suspend fun list(prefix: SyncObjectPrefix, cursor: String?): SyncObjectPage {
        require(cursor == null) { "WebDAV transport does not expose paginated PROPFIND cursors" }
        val resources = client.list(prefix.value.trimEnd('/')).getOrThrow()
        return SyncObjectPage(
            objects = resources.filterNot { it.isCollection }.map { remote ->
                SyncObjectMetadata(
                    path = SyncObjectPath(prefix.value + remote.displayName),
                    etag = requireNotNull(remote.etag) {
                        "WebDAV PROPFIND response omitted ETag for ${remote.displayName}"
                    },
                    size = remote.contentLength,
                )
            },
        )
    }

    private suspend fun ensureParentCollections(path: SyncObjectPath) {
        val parents = path.value.split('/').dropLast(1).runningFold("") { parent, segment ->
            if (parent.isEmpty()) segment else "$parent/$segment"
        }.drop(1)
        collectionMutex.withLock {
            parents.forEach { collection ->
                if (knownCollections.add(collection)) {
                    runCatching { client.mkcol(collection).getOrThrow() }
                        .onFailure { knownCollections.remove(collection) }
                        .getOrThrow()
                }
            }
        }
    }
}

private fun WebDavConditionalPutResult.toSyncResult(): SyncConditionalWriteResult = when (this) {
    is WebDavConditionalPutResult.PreconditionFailed ->
        SyncConditionalWriteResult.PreconditionFailed(currentEtag)

    is WebDavConditionalPutResult.Written -> SyncConditionalWriteResult.Written(etag)
}

package me.rerere.rikkahub.data.sync.v2

import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3ConditionalPutResult

/** S3 adapter that preserves the backend's atomic conditional-write semantics. */
class S3ObjectSyncTransport(
    private val client: S3Client,
) : ObjectSyncTransport {
    override suspend fun get(path: SyncObjectPath): SyncRemoteObject? =
        client.getObjectVersioned(path.value).getOrThrow()?.let { remote ->
            SyncRemoteObject(path = path, etag = remote.etag, body = remote.body)
        }

    override suspend fun createImmutable(
        path: SyncObjectPath,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult = client.putObjectConditional(
        key = path.value,
        data = body,
        contentType = contentType,
        ifNoneMatch = true,
    ).getOrThrow().toSyncResult()

    override suspend fun compareAndSet(
        path: SyncObjectPath,
        expectedEtag: String,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult = client.putObjectConditional(
        key = path.value,
        data = body,
        contentType = contentType,
        ifMatch = expectedEtag,
    ).getOrThrow().toSyncResult()

    override suspend fun list(prefix: SyncObjectPrefix, cursor: String?): SyncObjectPage {
        val result = client.listObjects(
            prefix = prefix.value,
            continuationToken = cursor,
        ).getOrThrow()
        return SyncObjectPage(
            objects = result.objects.map { remote ->
                SyncObjectMetadata(
                    path = SyncObjectPath(remote.key),
                    etag = requireNotNull(remote.etag) { "S3 LIST response omitted ETag for ${remote.key}" },
                    size = remote.size,
                )
            },
            nextCursor = result.nextContinuationToken,
        )
    }
}

private fun S3ConditionalPutResult.toSyncResult(): SyncConditionalWriteResult = when (this) {
    is S3ConditionalPutResult.PreconditionFailed ->
        SyncConditionalWriteResult.PreconditionFailed(currentEtag)

    is S3ConditionalPutResult.Written -> SyncConditionalWriteResult.Written(etag)
}

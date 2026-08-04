package me.rerere.rikkahub.data.sync.v2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTransportVerifierTest {
    @Test
    fun `accepts backend with real create-only and compare-and-set semantics`() = runBlocking {
        assertEquals(
            SyncTransportSupport.Verified,
            SyncTransportVerifier(FakeObjectTransport()).verify(),
        )
    }

    @Test
    fun `fails closed when backend ignores create-only condition`() = runBlocking {
        val result = SyncTransportVerifier(
            FakeObjectTransport(ignoreCreateCondition = true),
        ).verify()

        assertTrue(result is SyncTransportSupport.Unsupported)
        assertTrue((result as SyncTransportSupport.Unsupported).reason.contains("If-None-Match"))
    }

    @Test
    fun `fails closed when backend ignores compare-and-set condition`() = runBlocking {
        val result = SyncTransportVerifier(
            FakeObjectTransport(ignoreCompareAndSet = true),
        ).verify()

        assertTrue(result is SyncTransportSupport.Unsupported)
        assertTrue((result as SyncTransportSupport.Unsupported).reason.contains("If-Match"))
    }

    @Test
    fun `fails closed when backend rejects every compare-and-set`() = runBlocking {
        val result = SyncTransportVerifier(
            FakeObjectTransport(rejectAllCompareAndSet = true),
        ).verify()

        assertTrue(result is SyncTransportSupport.Unsupported)
        assertTrue((result as SyncTransportSupport.Unsupported).reason.contains("valid If-Match"))
    }
}

private class FakeObjectTransport(
    private val ignoreCreateCondition: Boolean = false,
    private val ignoreCompareAndSet: Boolean = false,
    private val rejectAllCompareAndSet: Boolean = false,
) : ObjectSyncTransport {
    private data class Stored(val body: ByteArray, val etag: String)

    private val objects = mutableMapOf<SyncObjectPath, Stored>()
    private var revision = 0L

    override suspend fun get(path: SyncObjectPath): SyncRemoteObject? = objects[path]?.let {
        SyncRemoteObject(path, it.etag, it.body.copyOf())
    }

    override suspend fun createImmutable(
        path: SyncObjectPath,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult {
        if (!ignoreCreateCondition && path in objects) {
            return SyncConditionalWriteResult.PreconditionFailed(objects[path]?.etag)
        }
        return write(path, body)
    }

    override suspend fun compareAndSet(
        path: SyncObjectPath,
        expectedEtag: String,
        body: ByteArray,
        contentType: String,
    ): SyncConditionalWriteResult {
        val current = objects[path]
        if (rejectAllCompareAndSet) {
            return SyncConditionalWriteResult.PreconditionFailed(current?.etag)
        }
        if (!ignoreCompareAndSet && current?.etag != expectedEtag) {
            return SyncConditionalWriteResult.PreconditionFailed(current?.etag)
        }
        return write(path, body)
    }

    override suspend fun list(prefix: SyncObjectPrefix, cursor: String?): SyncObjectPage =
        SyncObjectPage(emptyList())

    private fun write(path: SyncObjectPath, body: ByteArray): SyncConditionalWriteResult.Written {
        val etag = "W/\"etag-${++revision}\""
        objects[path] = Stored(body.copyOf(), etag)
        return SyncConditionalWriteResult.Written(etag)
    }
}

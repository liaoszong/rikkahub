package me.rerere.rikkahub.data.content

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.pale.content.ContentBlob
import me.rerere.pale.content.ContentOwnerRef
import me.rerere.pale.content.ContentReplicaKind
import me.rerere.pale.content.ContentReplicaRef
import me.rerere.pale.content.ContentReplicaState
import me.rerere.rikkahub.data.files.FileFolders

class LocalContentBlobStore(private val context: Context) {
    /** Deletes only store-owned blobs whose platform-managed retention window has elapsed. */
    suspend fun pruneExpired(
        nowEpochMillis: Long = System.currentTimeMillis(),
        retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    ): Int = withContext(Dispatchers.IO) {
        require(retentionMillis > 0)
        val directory = File(context.filesDir, FileFolders.CONTENT_BLOBS)
        if (!directory.exists()) return@withContext 0
        directory.listFiles().orEmpty().count { file ->
            val ownedBlob = file.isFile && file.name.matches(BLOB_FILE_PATTERN)
            val staleStaging = file.isFile && file.name.endsWith(".staging") &&
                nowEpochMillis - file.lastModified() >= STAGING_RETENTION_MILLIS
            val expiredBlob = ownedBlob && nowEpochMillis - file.lastModified() >= retentionMillis
            (staleStaging || expiredBlob) && file.delete()
        }
    }

    suspend fun putJson(owner: ContentOwnerRef, bytes: ByteArray): ContentBlob = withContext(Dispatchers.IO) {
        val digest = sha256(bytes)
        val blobId = "sha256:$digest"
        val directory = File(context.filesDir, FileFolders.CONTENT_BLOBS).apply { mkdirs() }
        val target = File(directory, "$digest.json")
        if (!target.exists()) {
            val staging = File(directory, ".$digest.${System.nanoTime()}.staging")
            staging.outputStream().use { it.write(bytes) }
            check(staging.length() == bytes.size.toLong()) { "ContentBlob staging write was incomplete" }
            if (!staging.renameTo(target) && !target.exists()) {
                staging.delete()
                error("ContentBlob atomic commit failed")
            }
            if (staging.exists()) staging.delete()
        }
        check(sha256(target.readBytes()) == digest) { "ContentBlob digest verification failed" }
        val now = System.currentTimeMillis()
        ContentBlob(
            blobId = blobId,
            owner = owner,
            mimeType = "application/json",
            byteLength = bytes.size.toLong(),
            sha256 = digest,
            replicas = listOf(
                ContentReplicaRef(
                    replicaId = "local:$digest",
                    kind = ContentReplicaKind.MANAGED_FILE,
                    state = ContentReplicaState.AVAILABLE,
                    locatorRef = "${FileFolders.CONTENT_BLOBS}/$digest.json",
                    verifiedAtEpochMillis = now,
                )
            ),
            createdAtEpochMillis = now,
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(64) {
            MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    companion object {
        const val DEFAULT_RETENTION_MILLIS = 24L * 60L * 60L * 1000L
        private const val STAGING_RETENTION_MILLIS = 60L * 60L * 1000L
        private val BLOB_FILE_PATTERN = Regex("[0-9a-f]{64}\\.json")
    }
}

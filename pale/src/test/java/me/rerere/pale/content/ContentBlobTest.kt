package me.rerere.pale.content

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentBlobTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round trip preserves immutable storage contract`() {
        val blob = ContentBlob(
            blobId = "sha256:${"a".repeat(64)}",
            owner = ContentOwnerRef(ContentOwnerKind.SEARCH_EVIDENCE, "evidence-1"),
            mimeType = "application/json",
            byteLength = 42,
            sha256 = "a".repeat(64),
            replicas = listOf(
                ContentReplicaRef(
                    replicaId = "replica-1",
                    kind = ContentReplicaKind.MANAGED_FILE,
                    state = ContentReplicaState.AVAILABLE,
                    locatorRef = "managed-file:42",
                ),
            ),
            createdAtEpochMillis = 1234,
        )

        assertEquals(blob, json.decodeFromString<ContentBlob>(json.encodeToString(blob)))
    }

    @Test
    fun `reachable content cannot be marked for collection`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentReachability(strongOwnerRefs = 1, gcEligibleAtEpochMillis = 10)
        }
    }
}

package me.rerere.pale.sync

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOperationContractTest {
    private val writer = ReplicaId("00000000-0000-4000-8000-000000000001")
    private val peer = ReplicaId("00000000-0000-4000-8000-000000000002")
    private val space = SpaceId("10000000-0000-4000-8000-000000000001")
    private val payload = "{\"title\":\"hello\"}".toByteArray()
    private val payloadHash = CanonicalSyncCodec.hashPayload(payload)

    @Test
    fun `canonical codec is deterministic strict and hash verified`() {
        val operation = liveOperation()
        val first = CanonicalSyncCodec.encode(operation)
        val second = CanonicalSyncCodec.encode(operation)

        assertTrue(first.contentEquals(second))
        assertEquals(operation, CanonicalSyncCodec.decodeCanonical(first))
        assertTrue(CanonicalSyncCodec.verifyPayload(payloadHash, payload))
        assertFalse(CanonicalSyncCodec.verifyPayload(payloadHash, "changed".toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalSyncCodec.decodeCanonical(first + ' '.code.toByte())
        }
        val unknownField = first.toString(Charsets.UTF_8).dropLast(1) + ",\"credential\":\"secret\"}"
        assertThrows(SerializationException::class.java) {
            CanonicalSyncCodec.decodeCanonical(unknownField.toByteArray())
        }
    }

    @Test
    fun `operation identity must equal dotted writer event`() {
        assertThrows(IllegalArgumentException::class.java) {
            liveOperation(
                version = DottedVersion(VersionVector.EMPTY, VersionDot(peer, 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            liveOperation(
                version = DottedVersion(VersionVector.EMPTY, VersionDot(writer, 2)),
            )
        }
    }

    @Test
    fun `live operations require payload while tombstones retain no content`() {
        assertThrows(IllegalArgumentException::class.java) {
            liveOperation(payloadHash = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tombstone(payloadHash = payloadHash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tombstone(blobHashes = listOf(payloadHash))
        }
    }

    @Test
    fun `blob references must be sorted and unique`() {
        val first = CanonicalSyncCodec.hashPayload("a".toByteArray())
        val second = CanonicalSyncCodec.hashPayload("b".toByteArray())
        val sorted = listOf(first, second).sorted()

        assertEquals(sorted, liveOperation(blobHashes = sorted).blobHashes)
        assertThrows(IllegalArgumentException::class.java) {
            liveOperation(blobHashes = sorted.reversed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            liveOperation(blobHashes = listOf(first, first))
        }
    }

    @Test
    fun `tombstone GC waits for every active replica acknowledgement`() {
        val deletion = tombstone()
        val acknowledged = deletion.version.asVersionVector()

        assertFalse(deletion.canGarbageCollectTombstone(setOf(writer, peer), mapOf(writer to acknowledged)))
        assertTrue(
            deletion.canGarbageCollectTombstone(
                activeReplicas = setOf(writer, peer),
                acknowledgements = mapOf(writer to acknowledged, peer to acknowledged),
            ),
        )
        assertFalse(liveOperation().canGarbageCollectTombstone(setOf(writer), mapOf(writer to acknowledged)))
    }

    @Test
    fun `different sync epochs cannot be causally compared`() {
        val otherSpace = liveOperation(spaceId = SpaceId.random())
        assertThrows(IllegalArgumentException::class.java) { liveOperation().causalRelationTo(otherSpace) }
    }

    @Test
    fun `secret request and device permission entity names are unrepresentable`() {
        val encoded = CanonicalSyncCodec.encode(liveOperation()).toString(Charsets.UTF_8)
        listOf("credential", "active_request", "device_permission", "tool_permission").forEach { forbidden ->
            val tampered = encoded.replace("\"conversation\"", "\"$forbidden\"")
            assertThrows(SerializationException::class.java) {
                CanonicalSyncCodec.decodeCanonical(tampered.toByteArray())
            }
        }
    }

    private fun liveOperation(
        spaceId: SpaceId = space,
        version: DottedVersion = DottedVersion(VersionVector.EMPTY, VersionDot(writer, 1)),
        payloadHash: ContentHash? = this.payloadHash,
        blobHashes: List<ContentHash> = emptyList(),
    ) = SyncOperationEnvelope(
        spaceId = spaceId,
        operationId = OperationId(writer, 1),
        entityType = SyncEntityType.CONVERSATION,
        entityId = SyncEntityId("conversation-1"),
        version = version,
        timestamp = HybridLogicalTimestamp(1_000, 0),
        tombstone = false,
        payloadHash = payloadHash,
        blobHashes = blobHashes,
    )

    private fun tombstone(
        payloadHash: ContentHash? = null,
        blobHashes: List<ContentHash> = emptyList(),
    ) = SyncOperationEnvelope(
        spaceId = space,
        operationId = OperationId(writer, 1),
        entityType = SyncEntityType.CONVERSATION,
        entityId = SyncEntityId("conversation-1"),
        version = DottedVersion(VersionVector.EMPTY, VersionDot(writer, 1)),
        timestamp = HybridLogicalTimestamp(1_000, 0),
        tombstone = true,
        payloadHash = payloadHash,
        blobHashes = blobHashes,
    )
}

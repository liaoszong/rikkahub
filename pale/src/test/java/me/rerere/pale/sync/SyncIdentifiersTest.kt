package me.rerere.pale.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncIdentifiersTest {
    @Test
    fun `replica and restored space identities are independent canonical UUIDs`() {
        val replica = ReplicaId.random()
        val firstSpace = SpaceId.random()
        val restoredSpace = SpaceId.random()

        assertEquals(replica.value.lowercase(), replica.value)
        assertNotEquals(firstSpace, restoredSpace)
        assertThrows(IllegalArgumentException::class.java) { ReplicaId("../device") }
        assertThrows(IllegalArgumentException::class.java) { SpaceId(firstSpace.value.uppercase()) }
    }

    @Test
    fun `operation path identity round trips without ambiguous counters`() {
        val id = OperationId(ReplicaId.random(), 42)

        assertEquals(id, OperationId.parsePathSegment(id.pathSegment))
        assertThrows(IllegalArgumentException::class.java) {
            OperationId.parsePathSegment("${id.replicaId.value}.042")
        }
        assertThrows(IllegalArgumentException::class.java) { OperationId(id.replicaId, 0) }
    }

    @Test
    fun `entity and content identities reject paths and noncanonical hashes`() {
        assertEquals("legacy-message:42", SyncEntityId("legacy-message:42").value)
        assertThrows(IllegalArgumentException::class.java) { SyncEntityId("messages/42") }
        assertThrows(IllegalArgumentException::class.java) { ContentHash("sha256:ABC") }
    }
}

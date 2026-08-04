package me.rerere.pale.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CausalityTest {
    private val alpha = ReplicaId("00000000-0000-4000-8000-000000000001")
    private val beta = ReplicaId("00000000-0000-4000-8000-000000000002")

    @Test
    fun `version vectors distinguish causality from concurrency`() {
        val base = VersionVector.from(mapOf(alpha to 1))
        val after = VersionVector.from(mapOf(alpha to 2, beta to 1))
        val concurrent = VersionVector.from(mapOf(alpha to 1, beta to 2))
        val otherBranch = VersionVector.from(mapOf(alpha to 2, beta to 1))

        assertEquals(CausalRelation.BEFORE, base.relationTo(after))
        assertEquals(CausalRelation.AFTER, after.relationTo(base))
        assertEquals(CausalRelation.CONCURRENT, concurrent.relationTo(otherBranch))
        assertEquals(CausalRelation.EQUAL, after.relationTo(after))
        assertEquals(VersionVector.from(mapOf(alpha to 2, beta to 2)), after.merge(concurrent))
    }

    @Test
    fun `dotted versions include the event without using HLC`() {
        val left = DottedVersion(
            context = VersionVector.from(mapOf(alpha to 1)),
            dot = VersionDot(alpha, 2),
        )
        val right = DottedVersion(
            context = VersionVector.from(mapOf(alpha to 1)),
            dot = VersionDot(beta, 1),
        )

        assertEquals(CausalRelation.CONCURRENT, left.relationTo(right))
        assertThrows(IllegalArgumentException::class.java) {
            DottedVersion(VersionVector.from(mapOf(alpha to 2)), VersionDot(alpha, 2))
        }
    }

    @Test
    fun `HLC tick and observation remain monotonic under clock rollback`() {
        val local = HybridLogicalTimestamp(1_000, 4)

        assertEquals(HybridLogicalTimestamp(1_000, 5), local.tick(900))
        assertEquals(
            HybridLogicalTimestamp(1_100, 3),
            local.observe(HybridLogicalTimestamp(1_100, 2), nowMillis = 950),
        )
        assertEquals(
            HybridLogicalTimestamp(1_200, 0),
            local.observe(HybridLogicalTimestamp(1_100, 2), nowMillis = 1_200),
        )
    }

    @Test
    fun `concurrent LWW order ties by replica only after timestamp`() {
        val timestamp = HybridLogicalTimestamp(1_000, 1)
        val left = ConcurrentOrderKey(timestamp, alpha, 5)
        val right = ConcurrentOrderKey(timestamp, beta, 1)

        assertEquals(true, left < right)
    }

    @Test
    fun `noncanonical vector entries are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            VersionVector(listOf(VersionEntry(beta, 1), VersionEntry(alpha, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VersionVector(listOf(VersionEntry(alpha, 1), VersionEntry(alpha, 2)))
        }
    }

    @Test
    fun `replica state issues monotonic crash-durable operation identities`() {
        val initial = ReplicaProtocolState(replicaId = alpha)
        val first = initial.issue(nowMillis = 1_000, causalContext = VersionVector.EMPTY)
        val second = first.state.issue(
            nowMillis = 900,
            causalContext = first.version.asVersionVector(),
        )

        assertEquals(OperationId(alpha, 1), first.operationId)
        assertEquals(OperationId(alpha, 2), second.operationId)
        assertEquals(HybridLogicalTimestamp(1_000, 1), second.timestamp)
        assertEquals(2, second.state.lastIssuedCounter)
        assertThrows(IllegalArgumentException::class.java) {
            initial.issue(1_000, VersionVector.from(mapOf(alpha to 2)))
        }
    }
}

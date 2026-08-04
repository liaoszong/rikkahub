package me.rerere.pale.sync

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class VersionEntry(
    val replicaId: ReplicaId,
    val counter: Long,
) {
    init { require(counter > 0) { "Version counter must be positive" } }
}

/** Canonical sparse version vector. Entries must be unique and sorted by ReplicaId. */
@Serializable
class VersionVector(val entries: List<VersionEntry> = emptyList()) {
    init {
        require(this.entries.zipWithNext().all { (left, right) -> left.replicaId < right.replicaId }) {
            "Version vector entries must be unique and sorted by ReplicaId"
        }
    }

    operator fun get(replicaId: ReplicaId): Long =
        entries.binarySearchBy(replicaId) { it.replicaId }
            .takeIf { it >= 0 }
            ?.let { entries[it].counter }
            ?: 0L

    fun with(replicaId: ReplicaId, counter: Long): VersionVector {
        require(counter >= 0) { "Version counter cannot be negative" }
        val values = entries.associate { it.replicaId to it.counter }.toMutableMap()
        if (counter == 0L) values.remove(replicaId) else values[replicaId] = counter
        return from(values)
    }

    fun merge(other: VersionVector): VersionVector {
        val replicas = entries.map { it.replicaId }.toSet() + other.entries.map { it.replicaId }
        return from(replicas.associateWith { replica -> max(this[replica], other[replica]) })
    }

    fun dominates(other: VersionVector): Boolean =
        other.entries.all { this[it.replicaId] >= it.counter }

    fun relationTo(other: VersionVector): CausalRelation {
        val thisDominates = dominates(other)
        val otherDominates = other.dominates(this)
        return when {
            thisDominates && otherDominates -> CausalRelation.EQUAL
            thisDominates -> CausalRelation.AFTER
            otherDominates -> CausalRelation.BEFORE
            else -> CausalRelation.CONCURRENT
        }
    }

    override fun equals(other: Any?): Boolean = other is VersionVector && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "VersionVector(entries=$entries)"

    companion object {
        val EMPTY = VersionVector()

        fun from(values: Map<ReplicaId, Long>): VersionVector = VersionVector(
            values.entries
                .onEach { require(it.value >= 0) { "Version counter cannot be negative" } }
                .filter { it.value > 0 }
                .sortedBy { it.key }
                .map { VersionEntry(it.key, it.value) },
        )
    }
}

@Serializable
data class VersionDot(
    val replicaId: ReplicaId,
    val counter: Long,
) {
    init { require(counter > 0) { "Dot counter must be positive" } }
}

/** One event plus its causal past. HLC is deliberately not involved in this comparison. */
@Serializable
data class DottedVersion(
    val context: VersionVector,
    val dot: VersionDot,
) {
    init {
        require(dot.counter > context[dot.replicaId]) {
            "Dotted event must be newer than its causal context for the writer"
        }
    }

    fun asVersionVector(): VersionVector = context.with(dot.replicaId, dot.counter)

    fun relationTo(other: DottedVersion): CausalRelation =
        asVersionVector().relationTo(other.asVersionVector())
}

@Serializable
enum class CausalRelation { BEFORE, AFTER, EQUAL, CONCURRENT }

/** Hybrid logical clock used only for deterministic LWW ordering after concurrency is proven. */
@Serializable
data class HybridLogicalTimestamp(
    val physicalMillis: Long,
    val logical: Long,
) : Comparable<HybridLogicalTimestamp> {
    init {
        require(physicalMillis >= 0) { "HLC physical time cannot be negative" }
        require(logical >= 0) { "HLC logical counter cannot be negative" }
    }

    override fun compareTo(other: HybridLogicalTimestamp): Int {
        val physicalOrder = physicalMillis.compareTo(other.physicalMillis)
        return if (physicalOrder != 0) physicalOrder else logical.compareTo(other.logical)
    }

    fun tick(nowMillis: Long): HybridLogicalTimestamp {
        require(nowMillis >= 0)
        return if (nowMillis > physicalMillis) {
            HybridLogicalTimestamp(nowMillis, 0)
        } else {
            HybridLogicalTimestamp(physicalMillis, Math.addExact(logical, 1))
        }
    }

    fun observe(remote: HybridLogicalTimestamp, nowMillis: Long): HybridLogicalTimestamp {
        require(nowMillis >= 0)
        val nextPhysical = max(nowMillis, max(physicalMillis, remote.physicalMillis))
        val nextLogical = when {
            nextPhysical == physicalMillis && nextPhysical == remote.physicalMillis ->
                Math.addExact(max(logical, remote.logical), 1)
            nextPhysical == physicalMillis -> Math.addExact(logical, 1)
            nextPhysical == remote.physicalMillis -> Math.addExact(remote.logical, 1)
            else -> 0
        }
        return HybridLogicalTimestamp(nextPhysical, nextLogical)
    }
}

/** Total-order key for concurrent scalar LWW; never use it as a causal relation. */
data class ConcurrentOrderKey(
    val timestamp: HybridLogicalTimestamp,
    val replicaId: ReplicaId,
    val operationCounter: Long,
) : Comparable<ConcurrentOrderKey> {
    init { require(operationCounter > 0) }

    override fun compareTo(other: ConcurrentOrderKey): Int {
        val timeOrder = timestamp.compareTo(other.timestamp)
        if (timeOrder != 0) return timeOrder
        val replicaOrder = replicaId.compareTo(other.replicaId)
        return if (replicaOrder != 0) replicaOrder else operationCounter.compareTo(other.operationCounter)
    }
}

/**
 * Pure persisted portion of `sync_replica`. Callers must atomically persist [IssuedOperation.state]
 * with the outbox record before exposing its operation ID; this prevents counter reuse after crash.
 */
@Serializable
data class ReplicaProtocolState(
    val replicaId: ReplicaId,
    val lastIssuedCounter: Long = 0,
    val clock: HybridLogicalTimestamp = HybridLogicalTimestamp(0, 0),
    val acknowledged: VersionVector = VersionVector.EMPTY,
) {
    init { require(lastIssuedCounter >= 0) { "Issued counter cannot be negative" } }

    fun issue(nowMillis: Long, causalContext: VersionVector): IssuedOperation {
        require(causalContext[replicaId] <= lastIssuedCounter) {
            "Causal context contains an unissued local counter"
        }
        val nextCounter = Math.addExact(lastIssuedCounter, 1)
        val nextClock = clock.tick(nowMillis)
        val nextState = copy(lastIssuedCounter = nextCounter, clock = nextClock)
        return IssuedOperation(
            state = nextState,
            operationId = OperationId(replicaId, nextCounter),
            version = DottedVersion(causalContext, VersionDot(replicaId, nextCounter)),
            timestamp = nextClock,
        )
    }

    fun observe(remoteClock: HybridLogicalTimestamp, nowMillis: Long): ReplicaProtocolState =
        copy(clock = clock.observe(remoteClock, nowMillis))

    fun acknowledge(vector: VersionVector): ReplicaProtocolState =
        copy(acknowledged = acknowledged.merge(vector))
}

data class IssuedOperation(
    val state: ReplicaProtocolState,
    val operationId: OperationId,
    val version: DottedVersion,
    val timestamp: HybridLogicalTimestamp,
)

package me.rerere.rikkahub.data.db.entity

import org.junit.Assert.assertThrows
import org.junit.Test

class SyncV2EntityContractTest {
    @Test
    fun `outbox rejects unknown state and partial lease`() {
        assertThrows(IllegalArgumentException::class.java) { outbox(state = "unknown") }
        assertThrows(IllegalArgumentException::class.java) { outbox(leaseOwner = "worker") }
    }

    @Test
    fun `conflict resolution timestamp must match state`() {
        assertThrows(IllegalArgumentException::class.java) {
            conflict(resolutionState = "open", resolvedAt = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            conflict(resolutionState = "resolved", resolvedAt = null)
        }
    }

    private fun outbox(
        state: String = "pending",
        leaseOwner: String? = null,
    ) = SyncOutboxEntity(
        operationId = "operation-1",
        spaceId = "space-a",
        syncEpoch = "epoch-a",
        replicaId = "replica-a",
        sequence = 1,
        entityType = "conversation",
        entityId = "conversation-a",
        baseVectorJson = "{}",
        dotCounter = 1,
        hlcPhysicalMs = 1,
        hlcLogical = 0,
        envelopeBytes = byteArrayOf(1),
        state = state,
        nextAttemptAt = 1,
        leaseOwner = leaseOwner,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun conflict(
        resolutionState: String,
        resolvedAt: Long?,
    ) = SyncConflictEntity(
        conflictId = "conflict-a",
        spaceId = "space-a",
        syncEpoch = "epoch-a",
        entityType = "conversation",
        entityId = "conversation-a",
        localOperationId = "operation-a",
        remoteOperationId = "operation-b",
        baseVectorJson = "{}",
        localHeadJson = "{}",
        remoteHeadJson = "{}",
        classification = "concurrent_edit",
        resolutionState = resolutionState,
        detectedAt = 1,
        resolvedAt = resolvedAt,
        updatedAt = 1,
    )
}

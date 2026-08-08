package me.rerere.pale.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2Test {
    @Test
    fun `external search content cannot become user fact`() {
        val decision = MemoryWritePolicy.evaluate(
            MemoryCandidate("m1", MemoryType.FACT, scope(), "User lives in Paris", listOf("web:1"), MemorySourceTrust.EXTERNAL_UNTRUSTED, 0.99)
        )
        assertEquals(MemoryCandidateDecision.REJECT_EXTERNAL_FACT, decision)
    }

    @Test
    fun `memory off prevents both ordinary and prohibition reads`() {
        val records = listOf(record("m1"), record("m2", MemoryType.PROHIBITION))
        val result = MemorySelector.select(records, MemorySelectionPolicy(setOf(scope()), 1000, false, 10))
        assertTrue(result.entries.isEmpty())
        assertTrue(result.excluded.values.all { it == MemoryExclusionReason.MEMORY_OFF })
    }

    @Test
    fun `selector applies scope expiry conflict and hard token budget`() {
        val records = listOf(
            record("prohibit", MemoryType.PROHIBITION, "Never disclose secrets"),
            record("expired", expiresAt = 5),
            record("conflict", conflicts = listOf("other")),
            record("large", statement = "x".repeat(1000)),
        )
        val result = MemorySelector.select(records, MemorySelectionPolicy(setOf(scope()), 30, nowMillis = 10))
        assertEquals(listOf("prohibit"), result.entries.map { it.memoryId })
        assertEquals(MemoryExclusionReason.EXPIRED, result.excluded["expired"])
        assertEquals(MemoryExclusionReason.CONFLICT, result.excluded["conflict"])
        assertEquals(MemoryExclusionReason.BUDGET, result.excluded["large"])
    }

    private fun scope() = MemoryScope(MemoryScopeKind.ASSISTANT, "assistant-1")

    private fun record(
        id: String,
        type: MemoryType = MemoryType.FACT,
        statement: String = "User prefers concise answers",
        expiresAt: Long? = null,
        conflicts: List<String> = emptyList(),
    ) = MemoryRecord(
        id, type, scope(), statement, listOf("message:1"), MemorySourceTrust.CONVERSATION_USER,
        createdAt = 1, expiresAt = expiresAt, confidence = .9, sensitivity = MemorySensitivity.NORMAL,
        status = MemoryStatus.ACTIVE, revision = 1, conflictsWith = conflicts, extractionPolicyVersion = 1,
    )
}

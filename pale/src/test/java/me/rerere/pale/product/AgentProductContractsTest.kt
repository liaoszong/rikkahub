package me.rerere.pale.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.pale.continuity.ResumeAction
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState

class AgentProductContractsTest {
    @Test
    fun `task projection exposes duplicate cost decision`() {
        val projection = TaskProjector.project(
            "r1", "Search", RequestState.INTERRUPTED, BillableBoundary.SENT,
            nowMillis = 20, startedAt = 10, updatedAt = 15,
            resumeAction = ResumeAction.REQUIRE_DUPLICATE_COST_CONFIRMATION,
            cost = CostSummary("USD", 100),
        )
        assertEquals(TaskDisplayState.NEEDS_DECISION, projection.state)
        assertTrue(projection.cost!!.mayDuplicateOnRetry)
    }

    @Test
    fun `local only privacy is fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { PrivacyPolicy(networkEnabled = true, localOnly = true) }
        assertEquals(PrivacyPolicy(networkEnabled = false, localOnly = true).memoryEnabled, true)
    }
}

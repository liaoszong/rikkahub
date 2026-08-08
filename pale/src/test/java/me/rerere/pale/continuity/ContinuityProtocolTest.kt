package me.rerere.pale.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState

class ContinuityProtocolTest {
    @Test
    fun `sent or unknown outcome never auto retries without proof`() {
        listOf(BillableBoundary.SENT, BillableBoundary.RESPONSE_STARTED, BillableBoundary.UNKNOWN).forEach { boundary ->
            assertEquals(
                ResumeAction.REQUIRE_DUPLICATE_COST_CONFIRMATION,
                ResumePlanner.plan(input(boundary)),
            )
        }
    }

    @Test
    fun `handoff compiler crops deselected refs without copying source text`() {
        val capsule = HandoffCapsuleCompiler.compile(
            HandoffCapsuleDraft(
                sourceConversationId = "conversation-1",
                target = "conversation-2",
                decisions = listOf(HandoffItem("message:1"), HandoffItem("message:2", selected = false)),
                constraints = listOf(HandoffItem("memory:3")),
                evidenceRefs = listOf("evidence:4", "evidence:4"),
                openQuestions = listOf("What remains?"),
            ),
            nowMillis = 100,
        )

        assertEquals(listOf("message:1"), capsule.decisionSourceRefs)
        assertEquals(listOf("memory:3"), capsule.constraintSourceRefs)
        assertEquals(listOf("evidence:4"), capsule.evidenceRefs)
        assertFalse(capsule.capsuleId.contains("What remains?"))
    }

    @Test
    fun `durable outputs commit locally before provider work`() {
        assertEquals(
            ResumeAction.CONTINUE_LOCAL_COMMIT,
            ResumePlanner.plan(input(BillableBoundary.RESPONSE_STARTED).copy(hasUncommittedDurableOutputs = true)),
        )
    }

    @Test
    fun `provider replay is preferred over duplicate dispatch`() {
        assertEquals(
            ResumeAction.CONTINUE_PROVIDER_WITH_REPLAY,
            ResumePlanner.plan(input(BillableBoundary.RESPONSE_STARTED).copy(hasProviderReplay = true)),
        )
    }

    @Test
    fun `unknown replay schema fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReplayEnvelope(99, "provider", "responses", emptyList(), "digest")
        }
    }

    private fun input(boundary: BillableBoundary) = ResumeInput(
        requestState = RequestState.INTERRUPTED,
        billableBoundary = boundary,
        hasCommittedInputs = false,
        hasUncommittedDurableOutputs = false,
        hasProviderReplay = false,
        waitingForUserOrPermission = false,
    )
}

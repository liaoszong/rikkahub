package me.rerere.pale.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEngineTest {
    @Test
    fun `required and tool semantic units are retained atomically`() {
        val sources = listOf(
            source("system", ContextSourceKind.SYSTEM, 20, required = true),
            source("user", ContextSourceKind.CURRENT_USER, 20, required = true),
            source("tool-call", ContextSourceKind.TOOL_PAIR, 30, unit = "tool-1", priority = 10),
            source("tool-result", ContextSourceKind.TOOL_PAIR, 50, unit = "tool-1", priority = 10),
            source("recent", ContextSourceKind.RECENT_DIALOGUE, 70),
        )

        val selection = ContextBudgetPlanner.plan(sources, policy(120))

        assertEquals(listOf("system", "user", "tool-call", "tool-result"), selection.included.map { it.sourceRef })
        assertTrue(selection.excluded.any { it.sourceRef == "recent" })
    }

    @Test
    fun `retrievable oversized content is substituted and original sources remain`() {
        val sources = listOf(
            source("user", ContextSourceKind.CURRENT_USER, 10, required = true),
            source("attachment:raw", ContextSourceKind.ATTACHMENT, 500, retrievable = true),
        )
        val selection = ContextBudgetPlanner.plan(sources, policy(100))
        assertEquals(ContextSelectionReason.RETRIEVAL_SUBSTITUTED, selection.excluded.single().reason)
        assertEquals(2, sources.size)
    }

    @Test
    fun `required overflow fails instead of silently dropping intent`() {
        assertThrows(RequiredContextDoesNotFitException::class.java) {
            ContextBudgetPlanner.plan(
                listOf(source("user", ContextSourceKind.CURRENT_USER, 101, required = true)),
                policy(100),
            )
        }
    }

    private fun policy(inputTokens: Int) = ContextBudgetPolicy(
        modelWindowTokens = inputTokens + 30,
        reservedOutputTokens = 20,
        reservedRepairTokens = 5,
        safetyMarginTokens = 5,
    )

    private fun source(
        ref: String,
        kind: ContextSourceKind,
        tokens: Int,
        unit: String = ref,
        priority: Int = 0,
        required: Boolean = false,
        retrievable: Boolean = false,
    ) = ContextSource(ref, "digest-$ref", kind, tokens, unit, priority, required, retrievable)
}

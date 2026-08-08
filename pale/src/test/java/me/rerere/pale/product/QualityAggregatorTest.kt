package me.rerere.pale.product

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityAggregatorTest {
    @Test
    fun `aggregates only closed privacy safe dimensions`() {
        val result = QualityAggregator.reduce(
            listOf(
                QualityEvent(QualityMetric.RESUME_SUCCESS, 10, "claude", "opus", "local_commit"),
                QualityEvent(QualityMetric.RESUME_SUCCESS, 20, "claude", "opus", "local_commit"),
                QualityEvent(QualityMetric.CONTEXT_OVERFLOW, 15, "openai", "gpt", "required_overflow"),
            )
        )

        val resume = result.single { it.metric == QualityMetric.RESUME_SUCCESS }
        assertEquals(2, resume.count)
        assertEquals(20, resume.lastOccurredAt)
        assertEquals("local_commit", resume.diagnosticCode)
    }
}

package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolPolicyTest {
    @Test
    fun `every supported image count starts in the same provider wave`() {
        (1..8).forEach { count ->
            assertEquals(count, imageGenerationParallelism(count))
        }
    }

    @Test
    fun `out of contract image batches are rejected instead of silently queued`() {
        listOf(9, 100).forEach { count ->
            val failure = runCatching { imageGenerationParallelism(count) }.exceptionOrNull()

            assertTrue("unsupported count $count was accepted", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `empty image batch is rejected`() {
        val failure = runCatching { imageGenerationParallelism(0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}

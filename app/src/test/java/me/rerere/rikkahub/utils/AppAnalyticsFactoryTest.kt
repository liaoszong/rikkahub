package me.rerere.rikkahub.utils

import org.junit.Assert.assertSame
import org.junit.Test

class AppAnalyticsFactoryTest {
    @Test
    fun `debug analytics factory is always no-op`() {
        assertSame(NoOpAppAnalytics, createAppAnalytics())
    }
}

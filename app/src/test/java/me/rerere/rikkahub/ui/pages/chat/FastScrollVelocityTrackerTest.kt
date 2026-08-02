package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollVelocityTrackerTest {
    private val tracker = FastScrollVelocityTracker(
        enterThresholdPxPerSecond = 2_200f,
        exitThresholdPxPerSecond = 1_300f,
        holdDurationNanos = 140_000_000L,
    )

    @Test
    fun `slow drag never exposes the message jumper`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 10f, eventNanos = 1_020_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 18f, eventNanos = 1_040_000_000L))
    }

    @Test
    fun `fast drag enters immediately and exits after hysteresis`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertTrue(tracker.onScroll(deltaPx = 52f, eventNanos = 1_020_000_000L))
        assertTrue(tracker.onScroll(deltaPx = 4f, eventNanos = 1_080_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 4f, eventNanos = 1_180_000_000L))
    }

    @Test
    fun `only a fast fling exposes the message jumper`() {
        assertFalse(tracker.onFling(velocityPxPerSecond = 1_800f, eventNanos = 1_000_000_000L))
        assertTrue(tracker.onFling(velocityPxPerSecond = -3_200f, eventNanos = 1_100_000_000L))

        tracker.reset()

        assertFalse(tracker.isFast)
    }
}

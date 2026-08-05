package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollVelocityTrackerTest {
    private val tracker = FastScrollVelocityTracker(
        enterThresholdPxPerSecond = 2_200f,
        exitThresholdPxPerSecond = 1_300f,
    )

    @Test
    fun `slow drag never exposes the message jumper`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 10f, eventNanos = 1_020_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 18f, eventNanos = 1_040_000_000L))
    }

    @Test
    fun `fast drag enters immediately and slow motion hides immediately`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertTrue(tracker.onScroll(deltaPx = 52f, eventNanos = 1_020_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 4f, eventNanos = 1_040_000_000L))
    }

    @Test
    fun `stalled frame uses real elapsed time instead of inflating velocity`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 200f, eventNanos = 1_200_000_000L))
    }

    @Test
    fun `programmatic scrolling never opens a user scroll session`() {
        assertFalse(
            tracker.onScroll(
                deltaPx = 80f,
                eventNanos = 1_000_000_000L,
                isUserInput = false,
            ),
        )
        assertFalse(
            tracker.onScroll(
                deltaPx = 80f,
                eventNanos = 1_020_000_000L,
                isUserInput = false,
            ),
        )
    }

    @Test
    fun `zero consumed delta and reset hide immediately`() {
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_000_000_000L))
        assertTrue(tracker.onScroll(deltaPx = 52f, eventNanos = 1_020_000_000L))
        assertFalse(tracker.onScroll(deltaPx = 0f, eventNanos = 1_040_000_000L))

        tracker.reset()
        assertFalse(tracker.isFast)
    }
}

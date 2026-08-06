package me.rerere.rikkahub.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeModeRestartCoordinatorTest {
    @Test
    fun `cold start is scheduled before marker clear and process termination`() {
        val events = mutableListOf<String>()
        val coordinator = SafeModeRestartCoordinator(
            scheduleColdStart = { events += "schedule" },
            clearCrashMarker = {
                events += "clear"
                true
            },
            terminateCurrentProcess = { events += "terminate" },
        )

        assertEquals(emptyList<String>(), events)
        coordinator.restart()

        assertEquals(listOf("schedule", "clear", "terminate"), events)
    }

    @Test
    fun `schedule failure preserves marker and current process`() {
        val events = mutableListOf<String>()
        val coordinator = SafeModeRestartCoordinator(
            scheduleColdStart = {
                events += "schedule"
                error("injected schedule failure")
            },
            clearCrashMarker = {
                events += "clear"
                true
            },
            terminateCurrentProcess = { events += "terminate" },
        )

        assertThrows(IllegalStateException::class.java) { coordinator.restart() }

        assertEquals(listOf("schedule"), events)
    }

    @Test
    fun `marker commit failure never terminates the safe process`() {
        val events = mutableListOf<String>()
        val coordinator = SafeModeRestartCoordinator(
            scheduleColdStart = { events += "schedule" },
            clearCrashMarker = {
                events += "clear"
                false
            },
            terminateCurrentProcess = { events += "terminate" },
        )

        assertThrows(IllegalStateException::class.java) { coordinator.restart() }

        assertEquals(listOf("schedule", "clear"), events)
    }
}

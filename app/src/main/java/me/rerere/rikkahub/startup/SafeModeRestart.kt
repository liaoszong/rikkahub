package me.rerere.rikkahub.startup

/**
 * Orders the irreversible safe-mode exit boundary.
 *
 * The cold start must be scheduled before the durable crash marker is cleared. The current
 * process is terminated only after both operations succeed. While this object runs, the process
 * remains in [StartupBootstrapState.SafeMode], so a failed termination cannot expose AppRoutes.
 */
internal class SafeModeRestartCoordinator(
    private val scheduleColdStart: () -> Unit,
    private val clearCrashMarker: () -> Boolean,
    private val terminateCurrentProcess: () -> Unit,
) {
    fun restart() {
        scheduleColdStart()
        check(clearCrashMarker()) { "Unable to durably clear the crash recovery marker" }
        terminateCurrentProcess()
    }
}

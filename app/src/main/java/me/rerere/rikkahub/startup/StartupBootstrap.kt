package me.rerere.rikkahub.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface StartupBootstrapState {
    data object NotStarted : StartupBootstrapState

    data class Running(val attempt: Long) : StartupBootstrapState

    data object Ready : StartupBootstrapState

    /** Restore is complete, but normal runtime activation is intentionally suspended. */
    data object SafeMode : StartupBootstrapState

    data class Failed(
        val attempt: Long,
        val reason: String,
        val retryable: Boolean,
    ) : StartupBootstrapState
}

/**
 * Process-local authority for access to runtime state.
 *
 * Registering Koin definitions is safe before this gate is ready, but constructing Room or
 * entering the normal UI is not. Keeping this authority outside Koin also lets Android
 * components which can be created before the regular graph is usable fail closed.
 */
internal class StartupBootstrapStateStore {
    private val monitor = Any()
    private val mutableState = MutableStateFlow<StartupBootstrapState>(StartupBootstrapState.NotStarted)
    private var nextAttempt = 0L
    private var activationAttempt: Long? = null

    val state: StateFlow<StartupBootstrapState> = mutableState.asStateFlow()

    fun begin(): Long? = synchronized(monitor) {
        when (mutableState.value) {
            StartupBootstrapState.Ready,
            StartupBootstrapState.SafeMode,
            is StartupBootstrapState.Running,
            -> null

            StartupBootstrapState.NotStarted -> {
                val attempt = ++nextAttempt
                activationAttempt = null
                mutableState.value = StartupBootstrapState.Running(attempt)
                attempt
            }

            is StartupBootstrapState.Failed -> {
                val failed = mutableState.value as StartupBootstrapState.Failed
                if (!failed.retryable) return@synchronized null
                val attempt = ++nextAttempt
                activationAttempt = null
                mutableState.value = StartupBootstrapState.Running(attempt)
                attempt
            }
        }
    }

    fun beginRuntimeActivation(attempt: Long): Boolean = synchronized(monitor) {
        val running = mutableState.value as? StartupBootstrapState.Running
        if (running?.attempt != attempt || activationAttempt != null) return@synchronized false
        activationAttempt = attempt
        true
    }

    fun markReady(attempt: Long) {
        synchronized(monitor) {
            val running = mutableState.value as? StartupBootstrapState.Running
            if (running?.attempt == attempt && activationAttempt == attempt) {
                activationAttempt = null
                mutableState.value = StartupBootstrapState.Ready
            }
        }
    }

    fun markSafeMode(attempt: Long) {
        synchronized(monitor) {
            val running = mutableState.value as? StartupBootstrapState.Running
            if (running?.attempt == attempt && activationAttempt == attempt) {
                activationAttempt = null
                mutableState.value = StartupBootstrapState.SafeMode
            }
        }
    }

    fun markFailed(attempt: Long, error: Throwable, retryable: Boolean) {
        synchronized(monitor) {
            val running = mutableState.value as? StartupBootstrapState.Running
            if (running?.attempt == attempt) {
                activationAttempt = null
                mutableState.value = StartupBootstrapState.Failed(
                    attempt = attempt,
                    reason = error::class.java.simpleName.ifBlank { "StartupRestoreFailure" },
                    retryable = retryable,
                )
            }
        }
    }

    fun isRuntimeReady(): Boolean = mutableState.value === StartupBootstrapState.Ready

    fun isSafeModeReady(): Boolean = mutableState.value === StartupBootstrapState.SafeMode

    fun isDatabaseAccessAllowed(): Boolean = synchronized(monitor) {
        mutableState.value === StartupBootstrapState.Ready ||
            (mutableState.value is StartupBootstrapState.Running && activationAttempt != null)
    }

    fun requireDatabaseAccess() {
        check(isDatabaseAccessAllowed()) {
            "Database is unavailable until startup restore completes and runtime activation begins"
        }
    }

    fun requireRuntimeReady() {
        check(isRuntimeReady()) {
            "Runtime data is unavailable until startup restore reaches a terminal success state"
        }
    }
}

internal object StartupBootstrapGate {
    private val processState = StartupBootstrapStateStore()

    val state: StateFlow<StartupBootstrapState> = processState.state

    fun isRuntimeReady(): Boolean = processState.isRuntimeReady()

    fun isSafeModeReady(): Boolean = processState.isSafeModeReady()

    fun requireRuntimeReady() = processState.requireRuntimeReady()

    fun requireDatabaseAccess() = processState.requireDatabaseAccess()

    internal fun stateStore(): StartupBootstrapStateStore = processState
}

/**
 * Runs the complete pre-Room restore boundary on a dedicated dispatcher.
 *
 * [restore] must include both file and settings commit (or their rollback). Runtime activation
 * then runs on [activationDispatcher] while the public gate remains closed. Ready is published
 * only after activation returns successfully, while [start] never waits for either phase.
 */
internal class StartupBootstrapCoordinator(
    private val scope: CoroutineScope,
    private val restoreDispatcher: CoroutineDispatcher,
    private val activationDispatcher: CoroutineDispatcher,
    private val stateStore: StartupBootstrapStateStore,
    private val restore: suspend () -> Unit,
    private val activateRuntime: suspend () -> StartupRuntimeMode,
    private val onRestoreFailure: (Throwable) -> Unit,
    private val onActivationFailure: (Throwable) -> Unit,
) {
    fun start(): Boolean {
        val attempt = stateStore.begin() ?: return false
        scope.launch(restoreDispatcher + CoroutineName("StartupBootstrap")) {
            try {
                restore()
            } catch (cancelled: CancellationException) {
                stateStore.markFailed(attempt, cancelled, retryable = true)
                throw cancelled
            } catch (error: Throwable) {
                stateStore.markFailed(attempt, error, retryable = true)
                runCatching { onRestoreFailure(error) }
                return@launch
            }

            check(stateStore.beginRuntimeActivation(attempt)) {
                "Startup state changed before runtime activation"
            }
            val activationMode = try {
                withContext(activationDispatcher + CoroutineName("StartupRuntimeActivation")) {
                    activateRuntime()
                }
            } catch (cancelled: CancellationException) {
                stateStore.markFailed(attempt, cancelled, retryable = false)
                throw cancelled
            } catch (error: Throwable) {
                // Activation is not retryable in-process because consumers may have started before
                // a later initializer failed. Keep the gate closed and delegate process policy.
                stateStore.markFailed(attempt, error, retryable = false)
                onActivationFailure(error)
                return@launch
            }
            when (activationMode) {
                StartupRuntimeMode.NORMAL -> stateStore.markReady(attempt)
                StartupRuntimeMode.SAFE_MODE -> stateStore.markSafeMode(attempt)
            }
        }
        return true
    }
}

internal enum class StartupRuntimeMode {
    NORMAL,
    SAFE_MODE,
}

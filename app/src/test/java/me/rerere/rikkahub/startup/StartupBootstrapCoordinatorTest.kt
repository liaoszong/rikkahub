package me.rerere.rikkahub.startup

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupBootstrapCoordinatorTest {
    @Test
    fun `start never executes restore work on its caller`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "startup-restore-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val restoreEntered = CountDownLatch(1)
        val allowRestoreToFinish = CountDownLatch(1)
        val runtimeReady = CountDownLatch(1)
        val restoreThread = AtomicReference<String>()
        val callerThread = Thread.currentThread().name
        try {
            val coordinator = StartupBootstrapCoordinator(
                scope = scope,
                restoreDispatcher = dispatcher,
                activationDispatcher = dispatcher,
                stateStore = StartupBootstrapStateStore(),
                restore = {
                    restoreThread.set(Thread.currentThread().name)
                    restoreEntered.countDown()
                    check(allowRestoreToFinish.await(5, TimeUnit.SECONDS))
                },
                activateRuntime = {
                    runtimeReady.countDown()
                    StartupRuntimeMode.NORMAL
                },
                onRestoreFailure = { throw AssertionError("Unexpected restore failure", it) },
                onActivationFailure = { throw AssertionError("Unexpected activation failure", it) },
            )

            assertTrue(coordinator.start())
            assertTrue(restoreEntered.await(5, TimeUnit.SECONDS))
            assertNotEquals(callerThread, restoreThread.get())
            assertTrue(restoreThread.get().startsWith("startup-restore-io"))
            assertFalse(runtimeReady.await(100, TimeUnit.MILLISECONDS))

            allowRestoreToFinish.countDown()
            assertTrue(runtimeReady.await(5, TimeUnit.SECONDS))
        } finally {
            allowRestoreToFinish.countDown()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Ready is published only after main runtime activation succeeds`() {
        val restoreExecutor = Executors.newSingleThreadExecutor()
        val restoreDispatcher = restoreExecutor.asCoroutineDispatcher()
        val activationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "startup-main")
        }
        val activationDispatcher = activationExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val stateStore = StartupBootstrapStateStore()
        val events = CopyOnWriteArrayList<String>()
        val activationEntered = CountDownLatch(1)
        val allowActivationToFinish = CountDownLatch(1)
        try {
            val coordinator = StartupBootstrapCoordinator(
                scope = scope,
                restoreDispatcher = restoreDispatcher,
                activationDispatcher = activationDispatcher,
                stateStore = stateStore,
                restore = {
                    events += "files"
                    assertFalse(stateStore.isRuntimeReady())
                    events += "settings"
                    assertFalse(stateStore.isRuntimeReady())
                },
                activateRuntime = {
                    assertTrue(Thread.currentThread().name.startsWith("startup-main"))
                    assertFalse(stateStore.isRuntimeReady())
                    stateStore.requireDatabaseAccess()
                    events += "runtime"
                    activationEntered.countDown()
                    check(allowActivationToFinish.await(5, TimeUnit.SECONDS))
                    StartupRuntimeMode.NORMAL
                },
                onRestoreFailure = { throw AssertionError("Unexpected restore failure", it) },
                onActivationFailure = { throw AssertionError("Unexpected activation failure", it) },
            )

            assertTrue(coordinator.start())
            assertFalse(coordinator.start())
            assertTrue(activationEntered.await(5, TimeUnit.SECONDS))
            assertTrue(stateStore.state.value is StartupBootstrapState.Running)
            assertFalse(stateStore.isRuntimeReady())

            allowActivationToFinish.countDown()
            assertTrue(waitUntil { stateStore.isRuntimeReady() })
            assertEquals(listOf("files", "settings", "runtime"), events)
            assertTrue(stateStore.state.value === StartupBootstrapState.Ready)
        } finally {
            allowActivationToFinish.countDown()
            scope.cancel()
            restoreDispatcher.close()
            activationDispatcher.close()
            restoreExecutor.shutdownNow()
            activationExecutor.shutdownNow()
        }
    }

    @Test
    fun `restore failure stays closed and a later retry may release runtime`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val stateStore = StartupBootstrapStateStore()
        val failRestore = AtomicBoolean(true)
        val failureObserved = CountDownLatch(1)
        val runtimeReady = CountDownLatch(1)
        val runtimeStarts = AtomicInteger()
        try {
            val coordinator = StartupBootstrapCoordinator(
                scope = scope,
                restoreDispatcher = dispatcher,
                activationDispatcher = dispatcher,
                stateStore = stateStore,
                restore = {
                    if (failRestore.get()) error("injected restore failure")
                },
                activateRuntime = {
                    runtimeStarts.incrementAndGet()
                    runtimeReady.countDown()
                    StartupRuntimeMode.NORMAL
                },
                onRestoreFailure = { failureObserved.countDown() },
                onActivationFailure = { throw AssertionError("Unexpected activation failure", it) },
            )

            assertTrue(coordinator.start())
            assertTrue(failureObserved.await(5, TimeUnit.SECONDS))
            assertTrue(stateStore.state.value is StartupBootstrapState.Failed)
            assertFalse(stateStore.isRuntimeReady())
            assertEquals(0, runtimeStarts.get())
            assertThrows(IllegalStateException::class.java) {
                stateStore.requireRuntimeReady()
            }

            failRestore.set(false)
            assertTrue(coordinator.start())
            assertTrue(runtimeReady.await(5, TimeUnit.SECONDS))
            assertTrue(waitUntil { stateStore.isRuntimeReady() })
            assertEquals(1, runtimeStarts.get())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `runtime activation failure stays closed and cannot retry a partial process`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val stateStore = StartupBootstrapStateStore()
        val restoreFailures = AtomicInteger()
        val activationFailure = AtomicReference<Throwable>()
        val activationFailureObserved = CountDownLatch(1)
        try {
            val coordinator = StartupBootstrapCoordinator(
                scope = scope,
                restoreDispatcher = dispatcher,
                activationDispatcher = dispatcher,
                stateStore = stateStore,
                restore = {},
                activateRuntime = { error("injected runtime activation failure") },
                onRestoreFailure = { restoreFailures.incrementAndGet() },
                onActivationFailure = { error ->
                    activationFailure.set(error)
                    activationFailureObserved.countDown()
                },
            )

            assertTrue(coordinator.start())
            assertTrue(activationFailureObserved.await(5, TimeUnit.SECONDS))
            val failed = stateStore.state.value as StartupBootstrapState.Failed
            assertFalse(failed.retryable)
            assertFalse(stateStore.isRuntimeReady())
            assertEquals(0, restoreFailures.get())
            assertEquals("injected runtime activation failure", activationFailure.get()?.message)
            assertFalse(coordinator.start())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `safe mode outcome never permits Room or publishes normal runtime readiness`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val stateStore = StartupBootstrapStateStore()
        try {
            val coordinator = StartupBootstrapCoordinator(
                scope = scope,
                restoreDispatcher = dispatcher,
                activationDispatcher = dispatcher,
                stateStore = stateStore,
                restore = {},
                activateRuntime = { StartupRuntimeMode.SAFE_MODE },
                onRestoreFailure = { throw AssertionError("Unexpected restore failure", it) },
                onActivationFailure = { throw AssertionError("Unexpected activation failure", it) },
            )

            assertTrue(coordinator.start())
            assertTrue(waitUntil { stateStore.isSafeModeReady() })
            assertFalse(stateStore.isRuntimeReady())
            assertThrows(IllegalStateException::class.java) {
                stateStore.requireDatabaseAccess()
            }
            assertThrows(IllegalStateException::class.java) {
                stateStore.requireRuntimeReady()
            }
            assertFalse(coordinator.start())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun waitUntil(
        timeout: Long = 5,
        unit: TimeUnit = TimeUnit.SECONDS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

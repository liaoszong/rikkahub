package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatGenerationForegroundServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry: ChatGenerationForegroundRegistry
        get() = GlobalContext.get().get()

    @After
    fun cleanup() {
        registry.owners.value.keys.toList().forEach(registry::release)
        context.stopService(Intent(context, ChatGenerationForegroundService::class.java))
    }

    @Test
    fun textOwnerWaitsForRealForegroundServiceReadiness() = runBlocking {
        val controller = GlobalContext.get().get<ChatGenerationForegroundController>()
        val cancellations = AtomicInteger()

        val lease = controller.start(
            conversationId = Uuid.random(),
            senderName = "Foreground integration test",
            cancelExecution = { cancellations.incrementAndGet() },
        )
        lease.awaitReady()

        assertTrue(registry.owners.value.containsKey(lease.ownerId))
        assertEquals(0, cancellations.get())
        lease.close()
        awaitOwnerReleased(lease.ownerId)
        assertEquals(0, cancellations.get())
    }

    @Test
    fun destroyingOwnedServiceCancelsWithoutCreatingAnotherOwner() = runBlocking {
        val controller = GlobalContext.get().get<ChatGenerationForegroundController>()
        val cancellations = AtomicInteger()
        val lease = controller.start(
            conversationId = Uuid.random(),
            senderName = "Foreground interruption test",
            cancelExecution = { cancellations.incrementAndGet() },
        )
        lease.awaitReady()

        assertTrue(context.stopService(Intent(context, ChatGenerationForegroundService::class.java)))
        withTimeout(5_000L) {
            while (cancellations.get() == 0) delay(10L)
        }
        assertEquals(1, cancellations.get())
        assertEquals(setOf(lease.ownerId), registry.owners.value.keys)
        lease.close()
    }

    @Test
    fun releasingBeforePromotionDoesNotCrashServiceOrResurrectOwner() = runBlocking {
        val controller = GlobalContext.get().get<ChatGenerationForegroundController>()
        val released = controller.start(
            conversationId = Uuid.random(),
            senderName = "Released before promotion",
            cancelExecution = {},
        )
        released.close()

        val successor = controller.start(
            conversationId = Uuid.random(),
            senderName = "Promotion successor",
            cancelExecution = {},
        )
        successor.awaitReady()

        assertEquals(setOf(successor.ownerId), registry.owners.value.keys)
        successor.close()
    }

    @Test
    fun replacingOwnerKeepsForegroundHostAndCancellationAuthorityExact() = runBlocking {
        val controller = GlobalContext.get().get<ChatGenerationForegroundController>()
        val firstCancellations = AtomicInteger()
        val successorCancellations = AtomicInteger()
        val first = controller.start(
            conversationId = Uuid.random(),
            senderName = "First owner",
            cancelExecution = { firstCancellations.incrementAndGet() },
        )
        first.awaitReady()
        val successor = controller.start(
            conversationId = Uuid.random(),
            senderName = "Successor owner",
            cancelExecution = { successorCancellations.incrementAndGet() },
        )
        successor.awaitReady()

        first.close()
        assertEquals(setOf(successor.ownerId), registry.owners.value.keys)
        assertTrue(context.stopService(Intent(context, ChatGenerationForegroundService::class.java)))
        withTimeout(5_000L) {
            while (successorCancellations.get() == 0) delay(10L)
        }

        assertEquals(0, firstCancellations.get())
        assertEquals(1, successorCancellations.get())
        successor.close()
    }

    private suspend fun awaitOwnerReleased(ownerId: String) {
        withTimeout(5_000L) {
            while (ownerId in registry.owners.value) delay(10L)
        }
    }
}

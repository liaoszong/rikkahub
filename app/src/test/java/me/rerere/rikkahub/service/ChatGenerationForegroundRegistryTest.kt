package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class ChatGenerationForegroundRegistryTest {
    @Test
    fun `owner is visible before readiness and release is idempotent`() = runBlocking {
        val conversationId = Uuid.random()
        val registry = registry(ids = mutableListOf("owner-1"))
        val owner = registry.register(conversationId, "Assistant", cancelExecution = {})

        assertEquals(owner, registry.owners.value.getValue("owner-1"))
        val ready = async { registry.awaitReady(owner.ownerId) }
        yield()
        assertFalse(ready.isCompleted)

        registry.signalReady(owner.ownerId)
        ready.await()
        registry.release(owner.ownerId)
        registry.release(owner.ownerId)

        assertTrue(registry.owners.value.isEmpty())
    }

    @Test
    fun `cancel and service interruption target only exact live owners`() {
        val firstCancelled = AtomicInteger()
        val secondCancelled = AtomicInteger()
        val ids = mutableListOf("owner-1", "owner-2")
        val registry = registry(ids)
        val first = registry.register(Uuid.random(), "First") { firstCancelled.incrementAndGet() }
        val second = registry.register(Uuid.random(), "Second") { secondCancelled.incrementAndGet() }

        assertTrue(registry.cancel(first.ownerId))
        assertFalse(registry.cancel(first.ownerId))
        assertFalse(registry.cancel("missing"))
        registry.interruptActive(setOf(second.ownerId, "missing"))
        registry.interruptActive(setOf(second.ownerId))

        assertEquals(1, firstCancelled.get())
        assertEquals(1, secondCancelled.get())
    }

    @Test
    fun `stale owner cancellation never reaches a replacement owner`() {
        val firstJob = Job()
        val replacementJob = Job()
        val registry = registry(ids = mutableListOf("owner-1", "owner-2"))
        val first = registry.register(Uuid.random(), "First", firstJob::cancel)

        assertTrue(registry.cancel(first.ownerId))
        registry.release(first.ownerId)
        val replacement = registry.register(Uuid.random(), "Replacement", replacementJob::cancel)

        assertFalse(registry.cancel(first.ownerId))
        assertTrue(firstJob.isCancelled)
        assertFalse(replacementJob.isCancelled)
        assertEquals(setOf(replacement.ownerId), registry.owners.value.keys)
    }

    @Test
    fun `notification state is bounded and never revives a released owner`() {
        val conversationId = Uuid.random()
        val registry = registry(ids = mutableListOf("owner-1"))
        val owner = registry.register(
            conversationId = conversationId,
            senderName = "s".repeat(500),
            cancelExecution = {},
        )

        registry.updateConversationNotification(
            conversationId = conversationId,
            statusText = "x".repeat(500),
            contentText = "prefix-" + "y".repeat(500),
        )
        val updated = registry.owners.value.getValue(owner.ownerId)
        assertEquals(120, updated.senderName.length)
        assertEquals(120, updated.statusText?.length)
        assertEquals(240, updated.contentText?.length)
        assertTrue(updated.contentText.orEmpty().all { it == 'y' })

        registry.release(owner.ownerId)
        registry.updateConversationNotification(conversationId, "late", "late")
        assertTrue(registry.owners.value.isEmpty())
    }

    @Test
    fun `duplicate owner id fails before replacing cancellation authority`() {
        val registry = ChatGenerationForegroundRegistry(
            clock = { 123L },
            ownerIdFactory = { "same-owner" },
        )
        registry.register(Uuid.random(), "First", cancelExecution = {})

        assertThrows(IllegalStateException::class.java) {
            registry.register(Uuid.random(), "Second", cancelExecution = {})
        }
        assertEquals("First", registry.owners.value.getValue("same-owner").senderName)
    }

    private fun registry(ids: MutableList<String>) = ChatGenerationForegroundRegistry(
        clock = { 123L },
        ownerIdFactory = { ids.removeAt(0) },
    )
}

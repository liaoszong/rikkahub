package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `older job completion cannot clear newer job ownership`() = runBlocking {
        val releaseOldJob = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val oldJob = launch(Dispatchers.Unconfined) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    releaseOldJob.await()
                }
            }
        }
        val newJob = SupervisorJob()

        requireNotNull(session.tryAcquireGeneration(logChange = false)).use { lease ->
            lease.attach(oldJob)
        }
        requireNotNull(session.tryAcquireGeneration(logChange = false)).use { lease ->
            assertSame(oldJob, lease.attach(newJob))
        }
        releaseOldJob.complete(Unit)
        oldJob.join()

        assertSame(newJob, session.generationJob.value)
        session.cleanup()
    }

    @Test
    fun `generation epoch rejects a lease retained across delete and restore`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val staleLease = requireNotNull(session.tryAcquireGeneration(logChange = false))
        session.blockGenerationJobs()
        assertNull(session.tryAcquireGeneration(logChange = false))
        session.resumeGenerationJobs()

        val staleJob = SupervisorJob()
        assertThrows(ConversationGenerationRejectedException::class.java) {
            staleLease.attach(staleJob)
        }
        staleLease.close()
        staleJob.cancel()

        val currentJob = SupervisorJob()
        val currentLease = session.tryAcquireGeneration(logChange = false)
        assertNotNull(currentLease)
        currentLease!!.use { it.attach(currentJob) }
        assertSame(currentJob, session.generationJob.value)
        session.cleanup()
    }

    @Test
    fun `delete signal is reversible on the retained session identity`() {
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onIdle = {},
        )

        assertFalse(session.deleted.value)
        session.markDeleted()
        assertTrue(session.deleted.value)
        session.markRestored()
        assertFalse(session.deleted.value)
        session.cleanup()
    }

    @Test
    fun `persistence operations are serialized per conversation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            session.withPersistenceLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch(Dispatchers.Default) {
            session.withPersistenceLock {
                secondEntered.complete(Unit)
            }
        }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
        session.cleanup()
    }

    @Test
    fun `cancelled persistence waiter releases its session reference`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val waiterStarted = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            session.withPersistenceLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val waiter = launch(Dispatchers.Default) {
            waiterStarted.complete(Unit)
            session.withPersistenceLock { error("cancelled waiter entered the lock") }
        }
        waiterStarted.await()
        waiter.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.join()

        assertTrue(session.tryCloseIfIdle())
        assertFalse(session.tryAcquire())
    }

    @Test
    fun `lease releases the exact retained session once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )

        assertTrue(session.tryAcquire(logChange = false))
        val lease = ConversationSessionLease(session)
        lease.close()
        lease.close()

        assertTrue(session.tryCloseIfIdle())
    }
}

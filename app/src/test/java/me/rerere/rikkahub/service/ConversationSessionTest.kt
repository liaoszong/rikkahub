package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertSame
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

        session.setJob(oldJob)
        session.setJob(newJob)
        releaseOldJob.complete(Unit)
        oldJob.join()

        assertSame(newJob, session.generationJob.value)
        session.cleanup()
    }
}

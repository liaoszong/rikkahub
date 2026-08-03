package me.rerere.rikkahub.fork.pale.request

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatProviderStepCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RequestLedgerRepository
    private lateinit var coordinator: ChatProviderStepCoordinator
    private var persisted = 0
    private var durableResponse: UIMessage? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var requestAudit = 0
        repository = RequestLedgerRepository(
            database = database,
            requestAuditId = { "chat-request-audit-${++requestAudit}" },
            toolAuditId = { "chat-tool-audit-unused" },
        )
        coordinator = ChatProviderStepCoordinator(
            repository = repository,
            json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
            processOwnerId = "test-process",
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sameResponseMessageCreatesOneRootAndDeterministicChildSteps() = runTest {
        val context = ledgerContext()
        val firstMessages = listOf(userMessage("draw a cat"))
        val first = coordinator.openTextStep(context, firstMessages, params(), provider(), emptyList()).requireDispatch()
        first.prepareDispatch()
        first.dispatchObserver.onDispatch()
        first.markResponseStarted()
        durableResponse = assistantMessage("tool requested")
        first.commitDurableOutput(assistantMessage("tool requested"))

        val root = repository.getRequest(first.requestId)!!
        assertNull(root.parentRequestId)
        assertEquals("succeeded", root.requestState)
        assertEquals(1, persisted)

        val secondMessages = firstMessages + assistantMessage("tool result: ok")
        val second = coordinator.openTextStep(context, secondMessages, params(), provider(), emptyList()).requireDispatch()
        assertTrue(second.requestId != first.requestId)
        val child = repository.getRequest(second.requestId)!!
        assertEquals(first.requestId.value, child.parentRequestId)
        second.finishTransportFailure(IllegalStateException("request build stopped before handoff"))
        assertEquals("not_sent", repository.getRequest(second.requestId)!!.billableBoundary)

        val replayFailure = runCatching {
            coordinator.openTextStep(context, secondMessages, params(), provider(), emptyList())
        }.exceptionOrNull()
        assertTrue(replayFailure is ChatProviderStepBlocked)
    }

    @Test
    fun succeededProviderStepCannotBeCollectedAndDispatchedAgain() = runTest {
        val context = ledgerContext()
        val messages = listOf(userMessage("hello"))
        val first = coordinator.openTextStep(context, messages, params(), provider(), emptyList()).requireDispatch()
        first.dispatchObserver.onDispatch()
        first.markResponseStarted()
        durableResponse = assistantMessage("done")
        first.commitDurableOutput(assistantMessage("done"))

        val equivalentProviderInputWithDifferentUiIdentity = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("hello")),
            ),
        )
        val failure = runCatching {
            coordinator.openTextStep(
                context,
                equivalentProviderInputWithDifferentUiIdentity,
                params(),
                provider(),
                emptyList(),
            )
        }.exceptionOrNull()

        assertTrue("Expected successful request replay to fail closed, got $failure", failure != null)
        assertEquals(1, database.requestLedgerDao().getAttempts(first.requestId.value).size)
    }

    @Test
    fun conversationPersistenceFailureStaysCommittingAndReleasesLeaseForLocalRepair() = runTest {
        val context = ledgerContext {
            throw IllegalStateException("conversation write failed")
        }
        val step = coordinator.openTextStep(
            context,
            listOf(userMessage("hello")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        step.dispatchObserver.onDispatch()
        step.markResponseStarted()
        durableResponse = assistantMessage("received")
        step.markResultReceived(durableResponse!!)

        val failure = runCatching {
            step.commitDurableOutput(assistantMessage("received"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val request = repository.getRequest(step.requestId)!!
        assertEquals("committing", request.requestState)
        assertEquals("result_received", request.billableBoundary)
        assertNull(request.leaseOwner)
        assertEquals(0, database.requestLedgerDao().getOutputs(step.requestId.value).size)

        val repair = coordinator.openTextStep(
            ledgerContext(),
            listOf(userMessage("hello")),
            params(),
            provider(),
            emptyList(),
        ) as ChatProviderStepOpenResult.RepairCommit
        repair.step.commitDurableOutput(repair.durableMessage)

        assertEquals("succeeded", repository.getRequest(step.requestId)!!.requestState)
        assertEquals(1, database.requestLedgerDao().getOutputs(step.requestId.value).size)
    }

    @Test
    fun changedInputCannotCreateChildWhileRootHasSentOutcome() = runTest {
        val context = ledgerContext()
        val root = coordinator.openTextStep(
            context,
            listOf(userMessage("first")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        root.dispatchObserver.onDispatch()

        val failure = runCatching {
            coordinator.openTextStep(
                context,
                listOf(userMessage("changed")),
                params(),
                provider(),
                emptyList(),
            )
        }.exceptionOrNull()

        assertTrue(failure is ChatProviderStepBlocked)
        assertEquals(
            1,
            repository.getChatRequestsForMessage(CONVERSATION_ID, RESPONSE_MESSAGE_ID).size,
        )
        root.finishTransportFailure(IllegalStateException("disconnect"))
    }

    @Test
    fun startupReconcileMapsOrphanedSentRequestToUnknownWithoutRedispatch() = runTest {
        val step = coordinator.openTextStep(
            ledgerContext(),
            listOf(userMessage("sent")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        step.prepareDispatch()
        step.dispatchObserver.onDispatch()
        step.releaseForLocalRepair(IllegalStateException("process stopped"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.unknown)
        assertEquals("unknown_outcome", repository.getRequest(step.requestId)!!.requestState)
        assertEquals(1, database.requestLedgerDao().getAttempts(step.requestId.value).size)
    }

    @Test
    fun startupReconcileMapsPartialResponseToInterruptedWithoutRedispatch() = runTest {
        val step = coordinator.openTextStep(
            ledgerContext(),
            listOf(userMessage("partial")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        step.prepareDispatch()
        step.dispatchObserver.onDispatch()
        step.markResponseStarted()
        step.releaseForLocalRepair(IllegalStateException("process stopped"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.interrupted)
        assertEquals("interrupted", repository.getRequest(step.requestId)!!.requestState)
        assertEquals(1, database.requestLedgerDao().getAttempts(step.requestId.value).size)
    }

    @Test
    fun startupReconcileCommitsOnlyExactDurableResultCheckpoint() = runTest {
        val step = coordinator.openTextStep(
            ledgerContext(),
            listOf(userMessage("complete")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        step.prepareDispatch()
        step.dispatchObserver.onDispatch()
        step.markResponseStarted()
        durableResponse = assistantMessage("paid result")
        step.markResultReceived(durableResponse!!)
        step.releaseForLocalRepair(IllegalStateException("process stopped"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.committed)
        assertEquals("succeeded", repository.getRequest(step.requestId)!!.requestState)
        assertEquals(1, database.requestLedgerDao().getOutputs(step.requestId.value).size)
    }

    @Test
    fun startupReconcileRejectsEmptyPlaceholderForReceivedResult() = runTest {
        val step = coordinator.openTextStep(
            ledgerContext(),
            listOf(userMessage("empty")),
            params(),
            provider(),
            emptyList(),
        ).requireDispatch()
        step.prepareDispatch()
        step.dispatchObserver.onDispatch()
        step.markResponseStarted()
        val received = assistantMessage("paid result")
        step.markResultReceived(received)
        durableResponse = UIMessage(
            id = Uuid.parse(RESPONSE_MESSAGE_ID),
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
        )
        step.releaseForLocalRepair(IllegalStateException("process stopped"))

        val report = reconciler().reconcilePending()

        assertEquals(1, report.failed)
        assertEquals("failed", repository.getRequest(step.requestId)!!.requestState)
        assertEquals(0, database.requestLedgerDao().getOutputs(step.requestId.value).size)
    }

    @Test
    fun eachDurableToolLoopStepPointsToItsDirectPredecessor() = runTest {
        val context = ledgerContext()
        val firstInput = listOf(userMessage("start"))
        val first = coordinator.openTextStep(context, firstInput, params(), provider(), emptyList()).requireDispatch()
        finish(first, "first")

        val secondInput = firstInput + assistantMessage("tool one")
        val second = coordinator.openTextStep(context, secondInput, params(), provider(), emptyList()).requireDispatch()
        finish(second, "second")

        val thirdInput = secondInput + UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("tool two")),
        )
        val third = coordinator.openTextStep(context, thirdInput, params(), provider(), emptyList()).requireDispatch()

        assertEquals(
            second.requestId.value,
            repository.getRequest(third.requestId)!!.parentRequestId,
        )
        third.finishTransportFailure(IllegalStateException("stop before handoff"))
    }

    private fun ledgerContext(
        persist: suspend () -> Unit = { persisted++ },
    ) = ChatGenerationLedgerContext(
        conversationId = CONVERSATION_ID,
        assistantId = ASSISTANT_ID,
        responseMessageId = RESPONSE_MESSAGE_ID,
        persistCurrentConversation = persist,
        loadResponseMessage = { durableResponse },
    )

    private fun params() = TextGenerationParams(model = MODEL)

    private fun provider() = ProviderSetting.OpenAI(
        id = Uuid.parse(PROVIDER_ID),
        models = listOf(MODEL),
    )

    private fun reconciler() = ChatRequestReconciler(
        requestRepository = repository,
        coordinator = coordinator,
        loadDurableMessage = { _, _ -> durableResponse },
        ownerId = "test-reconciler",
    )

    private fun userMessage(text: String) = UIMessage(
        id = Uuid.parse(USER_MESSAGE_ID),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMessage(text: String) = UIMessage(
        id = Uuid.parse(RESPONSE_MESSAGE_ID),
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private suspend fun finish(step: ChatProviderStepSession, text: String) {
        step.dispatchObserver.onDispatch()
        step.markResponseStarted()
        durableResponse = assistantMessage(text)
        step.commitDurableOutput(durableResponse!!)
    }

    private fun ChatProviderStepOpenResult.requireDispatch(): ChatProviderStepSession =
        (this as ChatProviderStepOpenResult.Dispatch).step

    companion object {
        private const val CONVERSATION_ID = "10000000-0000-0000-0000-000000000001"
        private const val ASSISTANT_ID = "10000000-0000-0000-0000-000000000002"
        private const val RESPONSE_MESSAGE_ID = "10000000-0000-0000-0000-000000000003"
        private const val USER_MESSAGE_ID = "10000000-0000-0000-0000-000000000004"
        private const val PROVIDER_ID = "10000000-0000-0000-0000-000000000005"
        private val MODEL = Model(
            id = Uuid.parse("10000000-0000-0000-0000-000000000006"),
            modelId = "test-chat-model",
        )
    }
}

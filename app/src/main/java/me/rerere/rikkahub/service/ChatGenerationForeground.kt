package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

data class ChatGenerationForegroundOwner(
    val ownerId: String,
    val conversationId: Uuid,
    val senderName: String,
    val startedAtEpochMillis: Long,
    val statusText: String? = null,
    val contentText: String? = null,
)

interface ChatGenerationForegroundLease : AutoCloseable {
    val ownerId: String

    suspend fun awaitReady()

    override fun close()
}

/**
 * Admission host for conversation replies, tool continuations, and automatic post-response metadata.
 * Direct utility requests such as manual translation/compression must opt in explicitly instead of
 * assuming that an ongoing notification provides foreground execution.
 */
interface ChatGenerationForegroundController {
    fun start(
        conversationId: Uuid,
        senderName: String,
        cancelExecution: () -> Unit,
    ): ChatGenerationForegroundLease
}

/**
 * In-process ownership registry for text/tool chat generations protected by the shared chat FGS.
 *
 * RequestLedger remains the durable authority across process death. This registry deliberately owns
 * only the live process lease and never attempts to replay a provider request after the process dies.
 */
class ChatGenerationForegroundRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ownerIdFactory: () -> String = { Uuid.random().toString() },
) {
    private data class Entry(
        val owner: ChatGenerationForegroundOwner,
        val cancelExecution: () -> Unit,
        val cancellationStarted: AtomicBoolean = AtomicBoolean(false),
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val readiness = linkedMapOf<String, CompletableDeferred<Unit>>()
    private val _owners = MutableStateFlow<Map<String, ChatGenerationForegroundOwner>>(emptyMap())
    val owners: StateFlow<Map<String, ChatGenerationForegroundOwner>> = _owners.asStateFlow()

    fun register(
        conversationId: Uuid,
        senderName: String,
        cancelExecution: () -> Unit,
    ): ChatGenerationForegroundOwner = synchronized(lock) {
        val ownerId = ownerIdFactory()
        check(ownerId.isNotBlank() && ownerId !in entries) {
            "Chat generation foreground owner id must be unique"
        }
        val owner = ChatGenerationForegroundOwner(
            ownerId = ownerId,
            conversationId = conversationId,
            senderName = senderName.take(MAX_SENDER_NAME_LENGTH),
            startedAtEpochMillis = clock(),
        )
        entries[ownerId] = Entry(owner, cancelExecution)
        readiness[ownerId] = CompletableDeferred()
        publishOwnersLocked()
        owner
    }

    fun signalReady(ownerId: String) {
        synchronized(lock) { readiness[ownerId] }?.complete(Unit)
    }

    fun signalFailure(ownerId: String, error: Throwable) {
        synchronized(lock) { readiness[ownerId] }?.completeExceptionally(error)
    }

    suspend fun awaitReady(ownerId: String) {
        val signal = synchronized(lock) { readiness[ownerId] }
            ?: error("Chat generation foreground owner was not registered")
        try {
            withTimeout(FOREGROUND_READY_TIMEOUT_MILLIS) {
                signal.await()
            }
        } finally {
            synchronized(lock) {
                if (readiness[ownerId] === signal) readiness.remove(ownerId)
            }
        }
    }

    fun updateConversationNotification(
        conversationId: Uuid,
        statusText: String?,
        contentText: String?,
    ) {
        synchronized(lock) {
            var changed = false
            entries.replaceAll { _, entry ->
                if (entry.owner.conversationId != conversationId) return@replaceAll entry
                val updated = entry.owner.copy(
                    statusText = statusText?.take(MAX_STATUS_TEXT_LENGTH),
                    contentText = contentText?.takeLast(MAX_CONTENT_TEXT_LENGTH),
                )
                changed = changed || updated != entry.owner
                entry.copy(owner = updated)
            }
            if (changed) publishOwnersLocked()
        }
    }

    fun cancel(ownerId: String): Boolean {
        val cancelExecution = synchronized(lock) {
            entries[ownerId]
                ?.takeIf { it.cancellationStarted.compareAndSet(false, true) }
                ?.cancelExecution
        } ?: return false
        cancelExecution()
        return true
    }

    fun interruptActive(ownerIds: Set<String>) {
        val callbacks = synchronized(lock) {
            ownerIds.mapNotNull { ownerId ->
                entries[ownerId]
                    ?.takeIf { it.cancellationStarted.compareAndSet(false, true) }
                    ?.cancelExecution
            }
        }
        callbacks.forEach { cancelExecution -> cancelExecution() }
    }

    fun release(ownerId: String) {
        val pending = synchronized(lock) {
            val removed = entries.remove(ownerId)
            val signal = readiness.remove(ownerId)
            if (removed != null) publishOwnersLocked()
            signal
        }
        pending?.cancel()
    }

    private fun publishOwnersLocked() {
        _owners.value = entries.mapValues { (_, entry) -> entry.owner }
    }

    private companion object {
        const val FOREGROUND_READY_TIMEOUT_MILLIS = 10_000L
        const val MAX_SENDER_NAME_LENGTH = 120
        const val MAX_STATUS_TEXT_LENGTH = 120
        const val MAX_CONTENT_TEXT_LENGTH = 240
    }
}

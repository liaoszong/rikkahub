package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (ConversationSession) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val lifecycleLock = Any()
    private var refCount = 0
    private var closed = false
    private var generationBlocked = false
    private var generationEpoch = 0L

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = synchronized(lifecycleLock) { refCount > 0 || isGenerating }
    val isClosed: Boolean get() = synchronized(lifecycleLock) { closed }

    // 同一会话的持久化必须串行，避免不同 coroutine 用相同 revision 并发覆盖。
    private val persistenceMutex = Mutex()

    @Volatile
    private var durableStateLoaded = false

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    init {
        synchronized(lifecycleLock) { scheduleIdleCheckLocked() }
    }

    fun tryAcquire(logChange: Boolean = true): Boolean {
        val refs = synchronized(lifecycleLock) {
            if (closed) return false
            refCount += 1
            cancelIdleCheckLocked()
            refCount
        }
        if (logChange) Log.d(TAG, "acquire $id (refs=$refs)")
        return true
    }

    fun tryAcquireGeneration(logChange: Boolean = true): ConversationGenerationLease? {
        val acquired = synchronized(lifecycleLock) {
            if (closed || generationBlocked) return null
            refCount += 1
            cancelIdleCheckLocked()
            generationEpoch to refCount
        }
        if (logChange) Log.d(TAG, "acquireGeneration $id (refs=${acquired.second})")
        return ConversationGenerationLease(this, acquired.first)
    }

    fun release(logChange: Boolean = true): Int {
        val refs = synchronized(lifecycleLock) {
            check(refCount > 0) { "Conversation session $id released without a matching lease" }
            refCount -= 1
            if (refCount == 0 && !closed) scheduleIdleCheckLocked()
            refCount
        }
        if (logChange) Log.d(TAG, "release $id (refs=$refs)")
        return refs
    }

    suspend fun <T> withPersistenceLock(block: suspend () -> T): T {
        if (!tryAcquire(logChange = false)) throw ConversationSessionClosedException(id)
        try {
            persistenceMutex.lock()
            try {
                if (isClosed) throw ConversationSessionClosedException(id)
                return block()
            } finally {
                persistenceMutex.unlock()
            }
        } finally {
            release(logChange = false)
        }
    }

    fun markDurableStateLoaded() {
        durableStateLoaded = true
    }

    fun hasDurableStateLoaded(): Boolean = durableStateLoaded

    fun markDeleted() {
        _deleted.value = true
    }

    fun markRestored() {
        _deleted.value = false
    }

    internal fun attachGenerationJob(job: Job, expectedEpoch: Long): Job? {
        val previousJob = synchronized(lifecycleLock) {
            if (closed || generationBlocked || generationEpoch != expectedEpoch) {
                throw ConversationGenerationRejectedException(id)
            }
            _generationJob.value.also { _generationJob.value = job }
        }
        previousJob?.cancel()
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                val releasedOwnership = _generationJob.compareAndSet(job, null)
                if (releasedOwnership && refCount == 0 && !closed) {
                    scheduleIdleCheckLocked()
                }
            }
        }
        return previousJob
    }

    fun blockGenerationJobs(): Job? {
        val generationJob = synchronized(lifecycleLock) {
            generationBlocked = true
            generationEpoch = Math.addExact(generationEpoch, 1L)
            _generationJob.value
        }
        generationJob?.cancel()
        return generationJob
    }

    fun resumeGenerationJobs() = synchronized(lifecycleLock) {
        check(!closed) { "Cannot resume generation jobs on closed session $id" }
        generationBlocked = false
    }

    fun getJob(): Job? = _generationJob.value

    fun tryCloseIfIdle(): Boolean = synchronized(lifecycleLock) {
        if (closed || refCount > 0 || isGenerating) return@synchronized false
        closed = true
        cancelIdleCheckLocked()
        true
    }

    private fun scheduleIdleCheckLocked() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            onIdle(this@ConversationSession)
        }
    }

    private fun cancelIdleCheckLocked() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        val generationJob = synchronized(lifecycleLock) {
            closed = true
            generationBlocked = true
            generationEpoch = Math.addExact(generationEpoch, 1L)
            cancelIdleCheckLocked()
            _generationJob.value.also { _generationJob.value = null }
        }
        generationJob?.cancel()
    }
}

class ConversationSessionClosedException(id: Uuid) :
    IllegalStateException("Conversation session $id is closed")

class ConversationGenerationRejectedException(id: Uuid) :
    IllegalStateException("Conversation session $id rejected a stale generation lease")

class ConversationSessionLease internal constructor(
    private val session: ConversationSession,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) session.release(logChange = false)
    }
}

class ConversationGenerationLease internal constructor(
    private val session: ConversationSession,
    private val epoch: Long,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    fun attach(job: Job): Job? {
        check(!released.get()) { "Generation lease for ${session.id} is already released" }
        return session.attachGenerationJob(job, epoch)
    }

    override fun close() {
        if (released.compareAndSet(false, true)) session.release(logChange = false)
    }
}

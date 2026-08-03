package me.rerere.rikkahub.data.imggen

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class ChatImageGenerationTaskPhase {
    QUEUED,
    RUNNING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    UNKNOWN_OUTCOME,
}

@Serializable
data class ChatImageGenerationTaskRecord(
    val taskId: String,
    val conversationId: String,
    val toolCallId: String,
    val requestId: String,
    val attempt: Int,
    val modelId: String = "",
    val modelName: String,
    val providerId: String? = null,
    val size: String = "auto",
    val referenceImageCount: Int = 0,
    /** Needed to reconcile a paid output whose file commit outlived its MediaAsset insert. */
    val prompt: String = "",
    val mediaOrigin: String = "ai_generated",
    val parentAssetId: String? = null,
    /** Durable lineage inputs needed when file commit outlives MediaAsset registration. */
    val referenceAssetIds: List<String> = emptyList(),
    val referenceSourcePaths: List<String> = emptyList(),
    val requestedImageCount: Int,
    val completedImageCount: Int = 0,
    val failedImageCount: Int = 0,
    /** Deterministic identities reserved before the paid request starts. */
    val reservedOutputAssetIds: List<String> = emptyList(),
    /** Subset of [reservedOutputAssetIds] whose durable file commit completed. */
    val outputAssetIds: List<String> = emptyList(),
    /** Authoritative per-slot projection; empty only for task records written before pale.6. */
    val slotStatuses: List<ChatImageSlotStatus> = emptyList(),
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val phase: ChatImageGenerationTaskPhase = ChatImageGenerationTaskPhase.QUEUED,
    val errorKind: ImageGenerationFailureKind? = null,
    val errorMessage: String? = null,
    /** A signal only; slot ledgers decide the authoritative terminal state. */
    val cancellationRequestedAtEpochMillis: Long? = null,
) {
    val isActive: Boolean
        get() = phase == ChatImageGenerationTaskPhase.QUEUED ||
            phase == ChatImageGenerationTaskPhase.RUNNING ||
            phase == ChatImageGenerationTaskPhase.RECOVERING
}

interface ChatImageGenerationTaskStore {
    fun load(): List<ChatImageGenerationTaskRecord>

    fun save(tasks: List<ChatImageGenerationTaskRecord>)
}

class SharedPreferencesChatImageGenerationTaskStore(
    context: Context,
    private val json: Json,
) : ChatImageGenerationTaskStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        // The removed standalone generator persisted the full prompt in a separate store.
        // It has no recovery owner after migration, so do not retain that private legacy state.
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    override fun load(): List<ChatImageGenerationTaskRecord> {
        val value = preferences.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ChatImageGenerationTaskRecord>>(value)
        } catch (error: Exception) {
            Log.e(TAG, "Discarding unreadable chat image task state", error)
            preferences.edit().remove(KEY_TASKS).apply()
            emptyList()
        }
    }

    override fun save(tasks: List<ChatImageGenerationTaskRecord>) {
        val editor = preferences.edit()
        if (tasks.isEmpty()) {
            editor.remove(KEY_TASKS)
        } else {
            editor.putString(KEY_TASKS, json.encodeToString(tasks))
        }
        if (!editor.commit()) {
            throw ImageGenerationException(
                kind = ImageGenerationFailureKind.CONFIGURATION,
                message = "Chat image generation state could not be persisted",
            )
        }
    }

    private companion object {
        const val TAG = "ChatImageTaskStore"
        const val PREFERENCES_NAME = "chat_image_generation_tasks"
        const val KEY_TASKS = "tasks_v1"
        const val LEGACY_PREFERENCES_NAME = "image_generation_tasks"
    }
}

interface ChatImageGenerationForegroundController {
    fun start(taskId: String)

    suspend fun awaitReady(taskId: String)
}

interface ChatImageGenerationTaskController {
    val tasks: StateFlow<Map<String, ChatImageGenerationTaskRecord>>

    suspend fun begin(
        task: ChatImageGenerationTaskRecord,
        cancelExecution: () -> Unit,
    )

    fun updateProgress(
        taskId: String,
        completedImageCount: Int,
        failedImageCount: Int,
        outputAssetIds: List<String> = emptyList(),
        slotStatuses: List<ChatImageSlotStatus> = emptyList(),
    )

    fun complete(taskId: String)

    fun fail(
        taskId: String,
        errorKind: ImageGenerationFailureKind,
        errorMessage: String,
    )

    fun cancelled(taskId: String)

    fun cancel(taskId: String): Boolean

    fun applyRecoveredState(
        taskId: String,
        phase: ChatImageGenerationTaskPhase,
        completedImageCount: Int,
        failedImageCount: Int,
        outputAssetIds: List<String>,
        slotStatuses: List<ChatImageSlotStatus> = emptyList(),
        errorKind: ImageGenerationFailureKind? = null,
        errorMessage: String? = null,
    )

    fun attachRecoveryTask(task: ChatImageGenerationTaskRecord)
}

class ChatImageGenerationTaskCoordinator(
    private val store: ChatImageGenerationTaskStore,
    private val foregroundController: ChatImageGenerationForegroundController,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatImageGenerationTaskController {
    private val cancellationHandlers = ConcurrentHashMap<String, () -> Unit>()
    private val initialTasks = restoreTasks()
    private val _tasks = MutableStateFlow(initialTasks.associateBy(ChatImageGenerationTaskRecord::taskId))
    override val tasks: StateFlow<Map<String, ChatImageGenerationTaskRecord>> = _tasks.asStateFlow()

    override suspend fun begin(
        task: ChatImageGenerationTaskRecord,
        cancelExecution: () -> Unit,
    ) {
        synchronized(this) {
            require(task.reservedOutputAssetIds.isEmpty() ||
                (task.reservedOutputAssetIds.size == task.requestedImageCount &&
                    task.reservedOutputAssetIds.distinct().size == task.reservedOutputAssetIds.size)) {
                "Reserved media identities must be unique and match the requested image count"
            }
            val existing = _tasks.value.values.firstOrNull {
                it.requestId == task.requestId && it.attempt == task.attempt
            }
            if (existing != null) {
                throw ImageGenerationException(
                    kind = ImageGenerationFailureKind.CONFIGURATION,
                    message = "Image generation request ${task.requestId} attempt ${task.attempt} " +
                        "already has durable state (${existing.phase}); it will not be replayed",
                )
            }
            cancellationHandlers[task.taskId] = cancelExecution
            try {
                replaceAndPersist(task.copy(phase = ChatImageGenerationTaskPhase.QUEUED))
            } catch (error: Throwable) {
                cancellationHandlers.remove(task.taskId)
                throw error
            }
        }

        try {
            foregroundController.start(task.taskId)
            foregroundController.awaitReady(task.taskId)
            synchronized(this) {
                val current = _tasks.value[task.taskId]
                    ?: throw CancellationException("Chat image generation task was removed")
                if (!current.isActive) {
                    throw CancellationException("Chat image generation task was cancelled")
                }
                replaceAndPersist(current.copy(phase = ChatImageGenerationTaskPhase.RUNNING))
            }
        } catch (cancelled: CancellationException) {
            cancelled(task.taskId)
            throw cancelled
        } catch (error: Throwable) {
            fail(
                taskId = task.taskId,
                errorKind = ImageGenerationFailureKind.CONFIGURATION,
                errorMessage = error.message ?: "Unable to start image generation in the foreground",
            )
            throw ImageGenerationException(
                kind = ImageGenerationFailureKind.CONFIGURATION,
                message = error.message ?: "Unable to start image generation in the foreground",
                cause = error,
            )
        }
    }

    override fun updateProgress(
        taskId: String,
        completedImageCount: Int,
        failedImageCount: Int,
        outputAssetIds: List<String>,
        slotStatuses: List<ChatImageSlotStatus>,
    ) {
        synchronized(this) {
            val current = _tasks.value[taskId]
                ?.takeIf(ChatImageGenerationTaskRecord::isActive)
                ?: return
            val mergedAssetIds = (current.outputAssetIds + outputAssetIds).distinct()
            require(current.reservedOutputAssetIds.isEmpty() ||
                mergedAssetIds.all(current.reservedOutputAssetIds::contains)) {
                "A task cannot commit an unreserved media identity"
            }
            require(slotStatuses.isEmpty() || slotStatuses.size == current.requestedImageCount) {
                "Slot status projection must match the requested image count"
            }
            replaceAndPersist(
                current.copy(
                    phase = ChatImageGenerationTaskPhase.RUNNING,
                    completedImageCount = completedImageCount.coerceAtLeast(0),
                    failedImageCount = failedImageCount.coerceAtLeast(0),
                    outputAssetIds = mergedAssetIds,
                    slotStatuses = slotStatuses.ifEmpty { current.slotStatuses },
                ),
            )
        }
    }

    override fun complete(taskId: String) {
        finish(taskId, ChatImageGenerationTaskPhase.COMPLETED)
    }

    override fun fail(
        taskId: String,
        errorKind: ImageGenerationFailureKind,
        errorMessage: String,
    ) {
        finish(
            taskId = taskId,
            phase = ChatImageGenerationTaskPhase.FAILED,
            errorKind = errorKind,
            errorMessage = errorMessage,
        )
    }

    override fun cancelled(taskId: String) {
        finish(
            taskId = taskId,
            phase = ChatImageGenerationTaskPhase.CANCELLED,
            errorKind = ImageGenerationFailureKind.USER_CANCELLED,
            errorMessage = "Image generation was cancelled",
        )
    }

    override fun cancel(taskId: String): Boolean {
        val callback = synchronized(this) {
            val current = _tasks.value[taskId]?.takeIf(ChatImageGenerationTaskRecord::isActive)
                ?: return false
            if (current.cancellationRequestedAtEpochMillis != null) return false
            replaceAndPersist(
                current.copy(
                    cancellationRequestedAtEpochMillis = clock(),
                    errorMessage = "Image generation cancellation was requested",
                ),
            )
            cancellationHandlers.remove(taskId)
        }
        callback?.invoke()
        return true
    }

    fun interruptAllActive(reason: String) {
        interruptActive(
            taskIds = _tasks.value.values
                .filter(ChatImageGenerationTaskRecord::isActive)
                .mapTo(mutableSetOf(), ChatImageGenerationTaskRecord::taskId),
            reason = reason,
        )
    }

    fun interruptActive(taskIds: Set<String>, reason: String) {
        val callbacks = synchronized(this) {
            val activeIds = _tasks.value.values
                .filter { task -> task.isActive && task.taskId in taskIds }
                .map(ChatImageGenerationTaskRecord::taskId)
            activeIds.forEach { taskId ->
                val current = _tasks.value.getValue(taskId)
                replaceAndPersist(
                    current.copy(
                        cancellationRequestedAtEpochMillis =
                            current.cancellationRequestedAtEpochMillis ?: clock(),
                        errorKind = ImageGenerationFailureKind.PROCESS_INTERRUPTED,
                        errorMessage = reason,
                    ),
                )
            }
            activeIds.mapNotNull(cancellationHandlers::remove)
        }
        callbacks.forEach { it.invoke() }
    }

    /** Applies the aggregate produced from authoritative per-slot RequestLedger recovery. */
    override fun applyRecoveredState(
        taskId: String,
        phase: ChatImageGenerationTaskPhase,
        completedImageCount: Int,
        failedImageCount: Int,
        outputAssetIds: List<String>,
        slotStatuses: List<ChatImageSlotStatus>,
        errorKind: ImageGenerationFailureKind?,
        errorMessage: String?,
    ) {
        require(phase !in ACTIVE_PHASES) { "Recovered task projection must be terminal" }
        synchronized(this) {
            val current = _tasks.value[taskId] ?: return
            require(current.reservedOutputAssetIds.isEmpty() ||
                outputAssetIds.all(current.reservedOutputAssetIds::contains)) {
                "Recovered task cannot commit an unreserved media identity"
            }
            require(slotStatuses.isEmpty() || slotStatuses.size == current.requestedImageCount) {
                "Recovered slot projection must match the requested image count"
            }
            cancellationHandlers.remove(taskId)
            replaceAndPersist(
                current.copy(
                    phase = phase,
                    completedImageCount = completedImageCount.coerceAtLeast(0),
                    failedImageCount = failedImageCount.coerceAtLeast(0),
                    outputAssetIds = (current.outputAssetIds + outputAssetIds).distinct(),
                    slotStatuses = slotStatuses.ifEmpty { current.slotStatuses },
                    finishedAtEpochMillis = current.finishedAtEpochMillis ?: clock(),
                    errorKind = errorKind,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    override fun attachRecoveryTask(task: ChatImageGenerationTaskRecord) {
        synchronized(this) {
            if (_tasks.value.containsKey(task.taskId)) return
            replaceAndPersist(
                task.copy(
                    phase = ChatImageGenerationTaskPhase.RECOVERING,
                    finishedAtEpochMillis = null,
                    errorKind = ImageGenerationFailureKind.PROCESS_INTERRUPTED,
                    errorMessage = "Recovering paid image request state. No provider request will be replayed.",
                ),
            )
        }
    }

    private fun finish(
        taskId: String,
        phase: ChatImageGenerationTaskPhase,
        errorKind: ImageGenerationFailureKind? = null,
        errorMessage: String? = null,
    ) {
        synchronized(this) {
            val current = _tasks.value[taskId] ?: return
            if (!current.isActive && current.phase != phase) return
            cancellationHandlers.remove(taskId)
            replaceAndPersist(
                current.copy(
                    phase = phase,
                    finishedAtEpochMillis = current.finishedAtEpochMillis ?: clock(),
                    errorKind = errorKind,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    private fun restoreTasks(): List<ChatImageGenerationTaskRecord> {
        val restored = store.load()
        val recovered = restored.map { task ->
            if (task.isActive) {
                task.copy(
                    phase = ChatImageGenerationTaskPhase.RECOVERING,
                    finishedAtEpochMillis = null,
                    errorKind = ImageGenerationFailureKind.PROCESS_INTERRUPTED,
                    errorMessage = "Recovering paid image request state. No provider request will be replayed.",
                )
            } else {
                task
            }
        }.sortedByDescending(ChatImageGenerationTaskRecord::startedAtEpochMillis)
            .take(MAX_PERSISTED_TASKS)
        if (recovered != restored) {
            runCatching { store.save(recovered) }
                .onFailure { Log.e(TAG, "Unable to persist interrupted chat image tasks", it) }
        }
        return recovered
    }

    private fun replaceAndPersist(task: ChatImageGenerationTaskRecord) {
        val updated = (_tasks.value + (task.taskId to task)).values
            .sortedWith(
                compareByDescending<ChatImageGenerationTaskRecord> { it.isActive }
                    .thenByDescending { it.startedAtEpochMillis },
            )
            .take(MAX_PERSISTED_TASKS)
        store.save(updated)
        _tasks.value = updated.associateBy(ChatImageGenerationTaskRecord::taskId)
    }

    private companion object {
        const val TAG = "ChatImageTaskCoordinator"
        const val MAX_PERSISTED_TASKS = 32
        val ACTIVE_PHASES = setOf(
            ChatImageGenerationTaskPhase.QUEUED,
            ChatImageGenerationTaskPhase.RUNNING,
            ChatImageGenerationTaskPhase.RECOVERING,
        )
    }
}

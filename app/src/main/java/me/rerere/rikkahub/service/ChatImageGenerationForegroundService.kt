package me.rerere.rikkahub.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.imggen.ChatImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskCoordinator
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

class ChatImageGenerationForegroundReadiness {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun prepare(taskId: String) {
        pending.put(taskId, CompletableDeferred())?.cancel()
    }

    fun signalReady(taskId: String) {
        pending[taskId]?.complete(Unit)
    }

    fun signalFailure(taskId: String, error: Throwable) {
        pending[taskId]?.completeExceptionally(error)
    }

    fun discard(taskId: String) {
        pending.remove(taskId)?.cancel()
    }

    suspend fun await(taskId: String) {
        val signal = pending[taskId]
            ?: error("Chat image foreground service was not registered")
        try {
            withTimeout(FOREGROUND_READY_TIMEOUT_MILLIS) {
                signal.await()
            }
        } finally {
            pending.remove(taskId, signal)
        }
    }

    private companion object {
        const val FOREGROUND_READY_TIMEOUT_MILLIS = 10_000L
    }
}

class AndroidChatImageGenerationForegroundController(
    private val context: Context,
    private val readiness: ChatImageGenerationForegroundReadiness,
) : ChatImageGenerationForegroundController {
    override fun start(taskId: String) {
        readiness.prepare(taskId)
        val intent = Intent(context, ChatImageGenerationForegroundService::class.java).apply {
            action = ChatImageGenerationForegroundService.ACTION_START
            putExtra(ChatImageGenerationForegroundService.EXTRA_TASK_ID, taskId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            readiness.discard(taskId)
            throw error
        }
    }

    override suspend fun awaitReady(taskId: String) {
        readiness.await(taskId)
    }
}

class ChatImageGenerationForegroundService : Service() {
    private val coordinator: ChatImageGenerationTaskCoordinator by inject()
    private val readiness: ChatImageGenerationForegroundReadiness by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private val ownedTaskIds = mutableSetOf<String>()
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = maxOf(latestStartId, startId)
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { taskId ->
                    serviceScope.launch(Dispatchers.IO) {
                        coordinator.cancel(taskId)
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                val task = taskId?.let { coordinator.tasks.value[it] }
                if (taskId.isNullOrBlank() || task?.isActive != true) {
                    taskId?.let {
                        readiness.signalFailure(
                            it,
                            IllegalStateException("Chat image generation task is no longer active"),
                        )
                    }
                    if (coordinator.tasks.value.values.none(ChatImageGenerationTaskRecord::isActive)) {
                        stopIfIdle(startId)
                    }
                    return START_NOT_STICKY
                }
                ownedTaskIds += taskId
                if (!startForegroundCompat(task)) {
                    ownedTaskIds -= taskId
                    val message = "Unable to start the chat image foreground service"
                    coordinator.fail(
                        taskId = taskId,
                        errorKind = ImageGenerationFailureKind.CONFIGURATION,
                        errorMessage = message,
                    )
                    readiness.signalFailure(
                        taskId,
                        IllegalStateException(message),
                    )
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }
                readiness.signalReady(taskId)
                observeTasks()
            }

            else -> {
                stopIfIdle(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ownedTaskIds.isNotEmpty()) {
            coordinator.interruptActive(
                taskIds = ownedTaskIds.toSet(),
                reason = "The foreground service stopped before image generation completed. " +
                    "The request was not retried.",
            )
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(task: ChatImageGenerationTaskRecord): Boolean = try {
        val notification = buildRunningNotification(listOf(task))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_RUNNING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID_RUNNING, notification)
        }
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to start chat image foreground service", error)
        false
    }

    @SuppressLint("MissingPermission")
    private fun observeTasks() {
        if (observerJob != null) return
        observerJob = serviceScope.launch {
            coordinator.tasks.collect { tasks ->
                val active = tasks.values.filter(ChatImageGenerationTaskRecord::isActive)
                ownedTaskIds.retainAll(active.mapTo(mutableSetOf(), ChatImageGenerationTaskRecord::taskId))
                if (active.isEmpty()) {
                    stopIfIdle(latestStartId)
                } else if (canPostNotifications()) {
                    runCatching {
                        NotificationManagerCompat.from(this@ChatImageGenerationForegroundService)
                            .notify(NOTIFICATION_ID_RUNNING, buildRunningNotification(active))
                    }.onFailure {
                        Log.e(TAG, "Unable to update chat image generation notification", it)
                    }
                }
            }
        }
    }

    private fun buildRunningNotification(tasks: List<ChatImageGenerationTaskRecord>): Notification {
        val primary = tasks.maxByOrNull(ChatImageGenerationTaskRecord::startedAtEpochMillis)
            ?: error("No active chat image generation task")
        val completed = tasks.sumOf(ChatImageGenerationTaskRecord::completedImageCount)
        val finished = completed + tasks.sumOf(ChatImageGenerationTaskRecord::failedImageCount)
        val requested = tasks.sumOf(ChatImageGenerationTaskRecord::requestedImageCount)
        val progressText = if (finished > 0) {
            "$finished / $requested"
        } else {
            getString(R.string.notification_image_generation_running)
        }
        return NotificationCompat.Builder(this, IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_image_generation_title))
            .setContentText(progressText)
            .setSubText(primary.modelName)
            .setContentIntent(buildOpenConversationIntent(primary.conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(requested.coerceAtLeast(1), finished.coerceAtMost(requested), finished == 0)
            .addAction(
                0,
                getString(R.string.notification_image_generation_cancel),
                buildCancelIntent(primary.taskId),
            )
            .build()
    }

    private fun buildOpenConversationIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            putExtra("conversationId", conversationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildCancelIntent(taskId: String): PendingIntent {
        val intent = Intent(this, ChatImageGenerationForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getService(
            this,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIfIdle(startId: Int) {
        if (startId != latestStartId ||
            coordinator.tasks.value.values.any(ChatImageGenerationTaskRecord::isActive)
        ) {
            return
        }
        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.CHAT_IMAGE_GENERATION_START"
        const val ACTION_CANCEL = "me.rerere.rikkahub.action.CHAT_IMAGE_GENERATION_CANCEL"
        const val EXTRA_TASK_ID = "chat_image_generation_task_id"
        const val NOTIFICATION_ID_RUNNING = 2111
        private const val TAG = "ChatImageGenerationFgs"
    }
}

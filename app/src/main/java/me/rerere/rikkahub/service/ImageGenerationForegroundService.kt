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
import me.rerere.rikkahub.data.imggen.ImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ImageGenerationPhase
import me.rerere.rikkahub.data.imggen.ImageGenerationTask
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskManager
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

class ImageGenerationForegroundReadiness {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun prepare(taskId: String) {
        pending[taskId] = CompletableDeferred()
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
            ?: error("Image generation foreground service was not registered")
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

class AndroidImageGenerationForegroundController(
    private val context: Context,
    private val readiness: ImageGenerationForegroundReadiness,
) : ImageGenerationForegroundController {
    override fun start(taskId: String) {
        readiness.prepare(taskId)
        val intent = Intent(context, ImageGenerationForegroundService::class.java).apply {
            action = ImageGenerationForegroundService.ACTION_START
            putExtra(ImageGenerationForegroundService.EXTRA_TASK_ID, taskId)
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

class ImageGenerationForegroundService : Service() {
    private val taskManager: ImageGenerationTaskManager by inject()
    private val readiness: ImageGenerationForegroundReadiness by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                taskManager.cancel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                val task = taskManager.task.value
                if (taskId.isNullOrBlank() || task.taskId != taskId) {
                    taskId?.let {
                        readiness.signalFailure(it, IllegalStateException("Image generation task is no longer active"))
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat(task)) {
                    readiness.signalFailure(
                        taskId,
                        IllegalStateException("Unable to start the image generation foreground service"),
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                readiness.signalReady(taskId)
                observeTask()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(task: ImageGenerationTask): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_RUNNING,
                    buildRunningNotification(task),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(task))
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start image generation foreground service", error)
            false
        }
    }

    @SuppressLint("MissingPermission") // Calls are guarded by canPostNotifications().
    private fun observeTask() {
        if (observerJob != null) return
        observerJob = serviceScope.launch {
            taskManager.task.collect { task ->
                when {
                    task.isActive -> {
                        if (canPostNotifications()) runCatching {
                            NotificationManagerCompat.from(this@ImageGenerationForegroundService)
                                .notify(NOTIFICATION_ID_RUNNING, buildRunningNotification(task))
                        }.onFailure {
                            Log.e(TAG, "Unable to update image generation notification", it)
                        }
                    }
                    task.phase != ImageGenerationPhase.IDLE -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (canPostNotifications()) runCatching {
                            NotificationManagerCompat.from(this@ImageGenerationForegroundService)
                                .notify(NOTIFICATION_ID_FINISHED, buildFinishedNotification(task))
                        }.onFailure {
                            Log.e(TAG, "Unable to post image generation result notification", it)
                        }
                        stopSelf()
                    }
                    else -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildRunningNotification(task: ImageGenerationTask): Notification {
        val cancelIntent = Intent(this, ImageGenerationForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progressText = when (task.phase) {
            ImageGenerationPhase.PREVIEW_AVAILABLE ->
                getString(R.string.notification_image_generation_preview)
            else -> getString(R.string.notification_image_generation_running)
        }
        return NotificationCompat.Builder(this, IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_image_generation_title))
            .setContentText(progressText)
            .setSubText(task.modelName)
            .setContentIntent(buildOpenImageGenerationIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.notification_image_generation_cancel), cancelPendingIntent)
            .build()
    }

    private fun buildFinishedNotification(task: ImageGenerationTask): Notification {
        val successful = task.phase == ImageGenerationPhase.COMPLETED
        val text = when (task.phase) {
            ImageGenerationPhase.COMPLETED ->
                resources.getQuantityString(
                    R.plurals.notification_image_generation_completed,
                    task.images.size,
                    task.images.size,
                )
            ImageGenerationPhase.CANCELLED ->
                getString(R.string.notification_image_generation_cancelled)
            ImageGenerationPhase.INTERRUPTED ->
                getString(R.string.notification_image_generation_interrupted)
            else -> task.errorMessage ?: getString(R.string.notification_image_generation_failed)
        }
        return NotificationCompat.Builder(this, IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(
                if (successful) {
                    getString(R.string.notification_image_generation_complete_title)
                } else {
                    getString(R.string.notification_image_generation_failed_title)
                },
            )
            .setContentText(text)
            .setContentIntent(buildOpenImageGenerationIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(
                if (successful) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            )
            .build()
    }

    private fun buildOpenImageGenerationIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            putExtra(RouteActivity.EXTRA_OPEN_IMAGE_GENERATION, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.IMAGE_GENERATION_START"
        const val ACTION_CANCEL = "me.rerere.rikkahub.action.IMAGE_GENERATION_CANCEL"
        const val EXTRA_TASK_ID = "image_generation_task_id"
        const val NOTIFICATION_ID_RUNNING = 2101
        const val NOTIFICATION_ID_FINISHED = 2102
        private const val REQUEST_OPEN = 2101
        private const val REQUEST_CANCEL = 2102
        private const val TAG = "ImageGenerationFgs"
    }
}

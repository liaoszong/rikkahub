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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.imggen.ImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ImageGenerationPhase
import me.rerere.rikkahub.data.imggen.ImageGenerationTask
import me.rerere.rikkahub.data.imggen.ImageGenerationTaskManager
import org.koin.android.ext.android.inject

class AndroidImageGenerationForegroundController(
    private val context: Context,
) : ImageGenerationForegroundController {
    override fun start(taskId: String) {
        val intent = Intent(context, ImageGenerationForegroundService::class.java).apply {
            action = ImageGenerationForegroundService.ACTION_START
            putExtra(ImageGenerationForegroundService.EXTRA_TASK_ID, taskId)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

class ImageGenerationForegroundService : Service() {
    private val taskManager: ImageGenerationTaskManager by inject()
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
                if (!startForegroundCompat(taskManager.task.value)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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

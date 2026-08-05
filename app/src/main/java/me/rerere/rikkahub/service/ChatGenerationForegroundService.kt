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
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.imggen.ChatImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskCoordinator
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

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
        val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
            action = ChatGenerationForegroundService.ACTION_START_IMAGE
            putExtra(ChatGenerationForegroundService.EXTRA_IMAGE_TASK_ID, taskId)
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

class AndroidChatGenerationForegroundController(
    private val context: Context,
    private val registry: ChatGenerationForegroundRegistry,
) : ChatGenerationForegroundController {
    override fun start(
        conversationId: Uuid,
        senderName: String,
        cancelExecution: () -> Unit,
    ): ChatGenerationForegroundLease {
        val owner = registry.register(
            conversationId = conversationId,
            senderName = senderName,
            cancelExecution = cancelExecution,
        )
        val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
            action = ChatGenerationForegroundService.ACTION_START_TEXT
            putExtra(ChatGenerationForegroundService.EXTRA_TEXT_OWNER_ID, owner.ownerId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            registry.release(owner.ownerId)
            throw error
        }

        return object : ChatGenerationForegroundLease {
            private val closed = AtomicBoolean(false)
            override val ownerId: String = owner.ownerId

            override suspend fun awaitReady() {
                registry.awaitReady(owner.ownerId)
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    registry.release(owner.ownerId)
                }
            }
        }
    }
}

/**
 * One foreground-service owner for the complete user-visible chat generation lifecycle.
 *
 * Text streaming, tool loops and conversation image generation share this service so moving the
 * Activity to the background cannot demote the provider socket to a page-owned task.
 */
class ChatGenerationForegroundService : Service() {
    private val imageCoordinator: ChatImageGenerationTaskCoordinator by inject()
    private val imageReadiness: ChatImageGenerationForegroundReadiness by inject()
    private val textRegistry: ChatGenerationForegroundRegistry by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var imageObserverJob: Job? = null
    private var textObserverJob: Job? = null
    private val ownedImageTaskIds = mutableSetOf<String>()
    private val ownedTextOwnerIds = mutableSetOf<String>()
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = maxOf(latestStartId, startId)
        when (intent?.action) {
            ACTION_CANCEL_IMAGE -> {
                intent.getStringExtra(EXTRA_IMAGE_TASK_ID)?.let { taskId ->
                    serviceScope.launch(Dispatchers.IO) { imageCoordinator.cancel(taskId) }
                }
            }

            ACTION_CANCEL_TEXT -> {
                intent.getStringExtra(EXTRA_TEXT_OWNER_ID)?.let(textRegistry::cancel)
            }

            ACTION_START_IMAGE -> startImageOwner(intent, startId)
            ACTION_START_TEXT -> startTextOwner(intent, startId)
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ownedImageTaskIds.isNotEmpty()) {
            imageCoordinator.interruptActive(
                taskIds = ownedImageTaskIds.toSet(),
                reason = "The foreground service stopped before image generation completed. " +
                    "The request was not retried.",
            )
        }
        if (ownedTextOwnerIds.isNotEmpty()) {
            textRegistry.interruptActive(ownedTextOwnerIds.toSet())
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startImageOwner(intent: Intent, startId: Int) {
        val taskId = intent.getStringExtra(EXTRA_IMAGE_TASK_ID)
        val task = taskId?.let { imageCoordinator.tasks.value[it] }
        val promoted = startForegroundCompat {
            task?.let { buildImageNotification(listOf(it)) } ?: buildFallbackNotification()
        }
        if (!promoted) {
            taskId?.let {
                val message = "Unable to start the chat generation foreground service"
                if (task?.isActive == true) {
                    imageCoordinator.fail(
                        taskId = taskId,
                        errorKind = ImageGenerationFailureKind.CONFIGURATION,
                        errorMessage = message,
                    )
                }
                imageReadiness.signalFailure(taskId, IllegalStateException(message))
            }
            stopIfIdle(startId)
            return
        }
        if (taskId.isNullOrBlank() || task?.isActive != true) {
            taskId?.let {
                imageReadiness.signalFailure(
                    it,
                    IllegalStateException("Chat image generation task is no longer active"),
                )
            }
            stopIfIdle(startId)
            return
        }

        ownedImageTaskIds += taskId
        imageReadiness.signalReady(taskId)
        observeWork()
    }

    private fun startTextOwner(intent: Intent, startId: Int) {
        val ownerId = intent.getStringExtra(EXTRA_TEXT_OWNER_ID)
        val owner = ownerId?.let { textRegistry.owners.value[it] }
        val promoted = startForegroundCompat {
            owner?.let { buildTextNotification(listOf(it)) } ?: buildFallbackNotification()
        }
        if (!promoted) {
            ownerId?.let {
                textRegistry.signalFailure(
                    it,
                    IllegalStateException("Unable to start the chat generation foreground service"),
                )
            }
            stopIfIdle(startId)
            return
        }
        if (ownerId.isNullOrBlank() || owner == null) {
            ownerId?.let {
                textRegistry.signalFailure(
                    it,
                    IllegalStateException("Chat generation foreground owner is no longer active"),
                )
            }
            stopIfIdle(startId)
            return
        }

        ownedTextOwnerIds += ownerId
        textRegistry.signalReady(ownerId)
        observeWork()
    }

    private fun startForegroundCompat(buildNotification: () -> Notification): Boolean = try {
        val notification = buildNotification()
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
        Log.e(TAG, "Unable to start chat generation foreground service", error)
        false
    }

    private fun observeWork() {
        if (imageObserverJob == null) {
            imageObserverJob = serviceScope.launch {
                imageCoordinator.tasks.collect { refreshForegroundState() }
            }
        }
        if (textObserverJob == null) {
            textObserverJob = serviceScope.launch {
                textRegistry.owners.collect { refreshForegroundState() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshForegroundState() {
        val activeImages = imageCoordinator.tasks.value.values
            .filter(ChatImageGenerationTaskRecord::isActive)
        val textOwners = textRegistry.owners.value
        ownedImageTaskIds.retainAll(activeImages.mapTo(mutableSetOf(), ChatImageGenerationTaskRecord::taskId))
        ownedTextOwnerIds.retainAll(textOwners.keys)
        if (ownedImageTaskIds.isEmpty() && ownedTextOwnerIds.isEmpty()) {
            stopIfIdle(latestStartId)
            return
        }
        if (!canPostNotifications()) return

        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID_RUNNING, buildRunningNotification())
        }.onFailure {
            Log.e(TAG, "Unable to update chat generation notification", it)
        }
    }

    private fun buildRunningNotification(): Notification {
        val activeImages = imageCoordinator.tasks.value.values
            .filter { it.taskId in ownedImageTaskIds && it.isActive }
        return if (activeImages.isNotEmpty()) {
            buildImageNotification(activeImages)
        } else {
            val activeTextOwners = textRegistry.owners.value.values
                .filter { it.ownerId in ownedTextOwnerIds }
            buildTextNotification(activeTextOwners)
        }
    }

    private fun buildImageNotification(tasks: List<ChatImageGenerationTaskRecord>): Notification {
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
        return NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_image_generation_title))
            .setContentText(progressText)
            .setSubText(primary.modelName)
            .setContentIntent(buildOpenConversationIntent(primary.conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(requested.coerceAtLeast(1), finished.coerceAtMost(requested), finished == 0)
            .addAction(
                0,
                getString(R.string.notification_image_generation_cancel),
                buildCancelImageIntent(primary.taskId),
            )
            .build()
    }

    private fun buildTextNotification(owners: List<ChatGenerationForegroundOwner>): Notification {
        val primary = owners.maxByOrNull(ChatGenerationForegroundOwner::startedAtEpochMillis)
            ?: error("No active chat text generation owner")
        return NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(primary.senderName.ifBlank { getString(R.string.notification_live_update_title) })
            .setContentText(primary.contentText ?: getString(R.string.notification_live_update_title))
            .setSubText(primary.statusText)
            .setContentIntent(buildOpenConversationIntent(primary.conversationId.toString()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(
                0,
                getString(R.string.notification_image_generation_cancel),
                buildCancelTextIntent(primary.ownerId),
            )
            .build()
    }

    private fun buildFallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_live_update_title))
            .setContentText(getString(R.string.notification_live_update_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()

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

    private fun buildCancelImageIntent(taskId: String): PendingIntent {
        val intent = Intent(this, ChatGenerationForegroundService::class.java).apply {
            action = ACTION_CANCEL_IMAGE
            putExtra(EXTRA_IMAGE_TASK_ID, taskId)
        }
        return PendingIntent.getService(
            this,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildCancelTextIntent(ownerId: String): PendingIntent {
        val intent = Intent(this, ChatGenerationForegroundService::class.java).apply {
            action = ACTION_CANCEL_TEXT
            putExtra(EXTRA_TEXT_OWNER_ID, ownerId)
        }
        return PendingIntent.getService(
            this,
            ownerId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIfIdle(startId: Int) {
        if (startId != latestStartId || ownedImageTaskIds.isNotEmpty() || ownedTextOwnerIds.isNotEmpty()) {
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
        const val ACTION_START_IMAGE = "me.rerere.rikkahub.action.CHAT_GENERATION_START_IMAGE"
        const val ACTION_CANCEL_IMAGE = "me.rerere.rikkahub.action.CHAT_GENERATION_CANCEL_IMAGE"
        const val ACTION_START_TEXT = "me.rerere.rikkahub.action.CHAT_GENERATION_START_TEXT"
        const val ACTION_CANCEL_TEXT = "me.rerere.rikkahub.action.CHAT_GENERATION_CANCEL_TEXT"
        const val EXTRA_IMAGE_TASK_ID = "chat_generation_image_task_id"
        const val EXTRA_TEXT_OWNER_ID = "chat_generation_text_owner_id"
        const val NOTIFICATION_ID_RUNNING = 2111
        private const val TAG = "ChatGenerationFgs"
    }
}

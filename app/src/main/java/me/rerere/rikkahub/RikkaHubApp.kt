package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.imggen.MediaAssetRecovery
import me.rerere.rikkahub.data.imggen.ImageMediaReconciliationResult
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.credential.CredentialReadiness
import me.rerere.rikkahub.data.db.conversation.ConversationV2BackfillCoordinator
import me.rerere.rikkahub.data.db.conversation.ConversationV2BackfillSummary
import me.rerere.rikkahub.data.db.fts.MessageFtsOutboxProcessor
import me.rerere.rikkahub.data.db.media.ConversationMediaReferenceBackfillProcessor
import me.rerere.rikkahub.fork.pale.request.ChatRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ChatRequestReconcileReport
import me.rerere.rikkahub.fork.pale.request.ToolRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ToolRequestReconcileReport
import me.rerere.rikkahub.fork.pale.request.ImageRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ImageRequestReconcileReport
import me.rerere.rikkahub.fork.pale.request.ImageTaskRecoveryCoordinator
import me.rerere.rikkahub.fork.pale.request.ImageTaskRecoveryReport
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.PendingRestoreManager
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID = "image_generation"

private data class DurableStartupRecoveryResult(
    val conversation: ConversationV2BackfillSummary,
    val chat: ChatRequestReconcileReport,
    val media: ImageMediaReconciliationResult,
    val imageRequests: ImageRequestReconcileReport,
    val imageTasks: ImageTaskRecoveryReport,
    val imagePasses: Int,
    val tools: ToolRequestReconcileReport,
)

private operator fun ToolRequestReconcileReport.plus(other: ToolRequestReconcileReport) =
    ToolRequestReconcileReport(
        inspected = inspected + other.inspected,
        committed = committed + other.committed,
        unknown = unknown + other.unknown,
        cancelled = cancelled + other.cancelled,
        failed = failed + other.failed,
        failures = failures + other.failures,
        deferred = other.deferred,
    )

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install this before restore, dependency injection, or Keystore access. Credential
        // bootstrap is fail-closed, but unrelated startup failures must still be observable.
        CrashHandler.install(this)
        runCatching {
            PendingRestoreManager.applyFilesBeforeDatabase(this)
        }.onFailure {
            Log.e(TAG, "Pending restore file switch failed; continuing with the previous data", it)
        }
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        // Credential projection can touch DataStore, fsync-backed journal files and Android
        // Keystore. Never block Application.onCreate with that work. Network entry points share
        // SettingsStore's readiness gate and cannot dispatch until this coroutine reaches Ready.
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                PendingRestoreManager.completeSettingsAfterKoin(
                    context = this@RikkaHubApp,
                    settingsStore = get(),
                    json = get<Json>(),
                )
            }.onFailure {
                Log.e(TAG, "Pending restore settings commit failed; previous data was restored", it)
            }
            when (val readiness = get<SettingsStore>().migrateCredentialVault()) {
                CredentialReadiness.Ready -> {
                    startWebServerIfEnabled()
                    incrementLaunchCount()
                }
                else -> Log.e(TAG, "Credential boundary unavailable; external requests remain disabled: $readiness")
            }
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // Resume durable search projection work before creating any new outbox events.
        get<MessageFtsOutboxProcessor>().start()

        // Verify exact ConversationStore -> MediaAsset ownership after restore/startup.
        get<ConversationMediaReferenceBackfillProcessor>().start()

        // Finish schema migrations and recover durable request evidence in strict dependency order.
        backfillConversationStoreV2()

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun backfillConversationStoreV2() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val result = get<ConversationV2BackfillCoordinator>().runPending()
                val requestRecovery = get<ChatRequestReconciler>().reconcilePending()
                var imageRequestRecovery = get<ImageRequestReconciler>().reconcilePending()
                var imageTaskRecovery = get<ImageTaskRecoveryCoordinator>().reconcilePending()
                // Rebuild the durable task descriptor before orphan file registration so
                // MediaAsset never freezes a generated/edited image with legacy placeholder
                // model, provider, prompt, or lineage metadata.
                val mediaRecovery = get<MediaAssetRecovery>().reconcilePending()
                var imageRecoveryPasses = 1
                var toolRecovery = get<ToolRequestReconciler>().reconcilePending()
                // A dead process can leave at most one 90-second fenced lease. Wait in this
                // background coroutine and retry locally; never reclaim or resend it early.
                while (imageTaskRecovery.pending > 0 && imageRecoveryPasses < 20) {
                    delay(5_000)
                    imageRequestRecovery = get<ImageRequestReconciler>().reconcilePending()
                    imageTaskRecovery = get<ImageTaskRecoveryCoordinator>().reconcilePending()
                    imageRecoveryPasses++
                }
                if (imageRecoveryPasses > 1) {
                    toolRecovery += get<ToolRequestReconciler>().reconcilePending()
                }
                DurableStartupRecoveryResult(
                    conversation = result,
                    chat = requestRecovery,
                    media = mediaRecovery,
                    imageRequests = imageRequestRecovery,
                    imageTasks = imageTaskRecovery,
                    imagePasses = imageRecoveryPasses,
                    tools = toolRecovery,
                )
            }.onSuccess { recovery ->
                get<MessageFtsOutboxProcessor>().requestDrain()
                get<ConversationMediaReferenceBackfillProcessor>().requestBackfill()
                val result = recovery.conversation
                val requestRecovery = recovery.chat
                val toolRecovery = recovery.tools
                if (requestRecovery.inspected > 0 || requestRecovery.failures.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "reconcileChatRequests: inspected=${requestRecovery.inspected} " +
                            "committed=${requestRecovery.committed} unknown=${requestRecovery.unknown} " +
                            "interrupted=${requestRecovery.interrupted} failed=${requestRecovery.failed} " +
                            "errors=${requestRecovery.failures.size}",
                    )
                }
                if (recovery.media.inspected > 0 || recovery.media.failures.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "reconcileGeneratedImages: inspected=${recovery.media.inspected} " +
                            "registered=${recovery.media.registered} failures=${recovery.media.failures.size}",
                    )
                }
                if (recovery.imageRequests.inspected > 0 || recovery.imageTasks.failures.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "reconcileImageRequests: inspected=${recovery.imageRequests.inspected} " +
                            "committed=${recovery.imageRequests.committed} " +
                            "unknown=${recovery.imageRequests.unknown} " +
                            "interrupted=${recovery.imageRequests.interrupted} " +
                            "taskProjected=${recovery.imageTasks.projected} " +
                            "pending=${recovery.imageTasks.pending} passes=${recovery.imagePasses}",
                    )
                }
                if (toolRecovery.inspected > 0 || toolRecovery.failures.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "reconcileToolRequests: inspected=${toolRecovery.inspected} " +
                            "committed=${toolRecovery.committed} unknown=${toolRecovery.unknown} " +
                            "cancelled=${toolRecovery.cancelled} failed=${toolRecovery.failed} " +
                            "deferred=${toolRecovery.deferred} " +
                            "errors=${toolRecovery.failures.size}",
                    )
                }
                if (result.inspected > 0) {
                    Log.i(
                        TAG,
                        "backfillConversationStoreV2: inspected=${result.inspected} " +
                            "ready=${result.ready} quarantined=${result.quarantined} " +
                            "inProgress=${result.inProgress} failed=${result.failed}",
                    )
                }
            }.onFailure {
                Log.e(TAG, "backfillConversationStoreV2 failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val imageGenerationChannel = NotificationChannelCompat
            .Builder(
                IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
            .setName(getString(R.string.notification_channel_image_generation))
            .setVibrationEnabled(false)
            .setShowBadge(true)
            .build()
        notificationManager.createNotificationChannel(imageGenerationChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)

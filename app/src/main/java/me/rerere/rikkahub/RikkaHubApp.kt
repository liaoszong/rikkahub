package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CancellationException
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
import me.rerere.rikkahub.data.imggen.ImageMediaReconciliationResult
import me.rerere.rikkahub.data.imggen.MediaAssetRecovery
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.credential.CredentialReadiness
import me.rerere.rikkahub.data.db.conversation.ConversationV2BackfillCoordinator
import me.rerere.rikkahub.data.db.conversation.CitationBackfillCoordinator
import me.rerere.rikkahub.data.db.conversation.runCitationBackfillSchedule
import me.rerere.rikkahub.data.db.fts.MessageFtsOutboxProcessor
import me.rerere.rikkahub.data.db.media.ConversationMediaReferenceBackfillProcessor
import me.rerere.rikkahub.fork.pale.request.ChatRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ToolRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ToolRequestReconcileReport
import me.rerere.rikkahub.fork.pale.request.ImageRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ImageTaskRecoveryCoordinator
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.startup.StartupBootstrapCoordinator
import me.rerere.rikkahub.startup.StartupBootstrapGate
import me.rerere.rikkahub.startup.StartupRuntimeMode
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeFailure
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.PendingRestoreManager
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID = "image_generation"

internal data class StartupRecoveryDomain(
    val name: String,
    val recover: suspend () -> Unit,
)

internal suspend fun runIndependentStartupRecoveryDomains(
    domains: List<StartupRecoveryDomain>,
    onFailure: (domain: String, error: Throwable) -> Unit,
) {
    domains.forEach { domain ->
        try {
            domain.recover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(domain.name, error)
        }
    }
}

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

private operator fun ImageMediaReconciliationResult.plus(other: ImageMediaReconciliationResult) =
    ImageMediaReconciliationResult(
        inspected = inspected + other.inspected,
        registered = registered + other.registered,
        metadataRepaired = metadataRepaired + other.metadataRepaired,
        missingFiles = missingFiles + other.missingFiles,
        failures = failures + other.failures,
    )

class RikkaHubApp : Application() {
    private lateinit var startupBootstrapCoordinator: StartupBootstrapCoordinator
    private val runtimeStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Install this before restore, dependency injection, or Keystore access. Credential
        // bootstrap is fail-closed, but unrelated startup failures must still be observable.
        CrashHandler.install(this)
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }

        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        startupBootstrapCoordinator = StartupBootstrapCoordinator(
            scope = get<AppScope>(),
            restoreDispatcher = Dispatchers.IO,
            activationDispatcher = Dispatchers.Main.immediate,
            stateStore = StartupBootstrapGate.stateStore(),
            restore = {
                // The verified session is an in-memory capability: settings commit reuses the
                // validation performed before file application instead of hashing payloads twice.
                val session = PendingRestoreManager.applyFilesBeforeDatabase(this@RikkaHubApp)
                PendingRestoreManager.completeSettingsAfterKoin(
                    context = this@RikkaHubApp,
                    settingsStore = get(),
                    json = get<Json>(),
                    session = session,
                )
            },
            activateRuntime = {
                // A previous activation or UI crash must be able to reach SafeModeActivity on the
                // next clean process. Restore is already committed at this boundary, so skip the
                // normal consumers and let RouteActivity publish the existing crash recovery UI.
                if (CrashHandler.hasCrashed(this@RikkaHubApp)) {
                    StartupRuntimeMode.SAFE_MODE
                } else {
                    startRuntimeAfterBootstrap()
                    StartupRuntimeMode.NORMAL
                }
            },
            onRestoreFailure = { error ->
                logSafeError(TAG, "startup", "bootstrap_restore", error)
            },
            onActivationFailure = { error ->
                // Activation may have started durable consumers and is unsafe to retry in-process.
                // Re-throw from a fresh main-loop task so CrashHandler records it before Android
                // terminates the partial process. The public gate remains Failed, never Ready.
                check(Handler(Looper.getMainLooper()).post { throw error }) {
                    "Unable to schedule fail-fast runtime termination"
                }
            },
        )
        startupBootstrapCoordinator.start()
    }

    internal fun retryStartupBootstrap(): Boolean =
        ::startupBootstrapCoordinator.isInitialized && startupBootstrapCoordinator.start()

    private fun startRuntimeAfterBootstrap() {
        StartupBootstrapGate.requireDatabaseAccess()
        if (!runtimeStarted.compareAndSet(false, true)) return

        // These used to be eager Koin singletons. Resolve them only after settings are committed.
        get<UpdateChecker>()
        get<ChatNotificationManager>()

        // Credential projection can touch DataStore, fsync-backed journal files and Android
        // Keystore. It starts only after restore, and its own readiness gate protects networking.
        get<AppScope>().launch(Dispatchers.IO) {
            when (get<SettingsStore>().migrateCredentialVault()) {
                CredentialReadiness.Ready -> {
                    startWebServerIfEnabled()
                    incrementLaunchCount()
                }
                else -> logSafeFailure(TAG, "credential", "initialize_boundary")
            }
        }

        // Resume durable search projection work before creating any new outbox events.
        get<MessageFtsOutboxProcessor>().start()

        // Verify exact ConversationStore -> MediaAsset ownership after restore/startup.
        get<ConversationMediaReferenceBackfillProcessor>().start()

        // Finish schema migrations and recover durable request evidence in strict dependency order.
        backfillConversationStoreV2()

        // Init QuickJS native library
        QuickJSLoader.init()

        deleteTempFiles()
        cleanupToolOutputs()
        cleanupWorkspaceTempDirs()
        checkWorkspaceIntegrity()
        syncManagedFiles()

        // A committed restore is already invisible to future startup. Its former payload and
        // rollback journal can therefore be reclaimed independently without delaying the gate.
        get<AppScope>().launch(Dispatchers.IO) {
            PendingRestoreManager.cleanupCommittedGarbage(this@RikkaHubApp)
        }

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
                logSafeError(TAG, "startup", "increment_launch_count", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                logSafeError(TAG, "workspace", "cleanup_temp_directories", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                logSafeError(TAG, "workspace", "check_integrity", it)
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
                logSafeError(TAG, "files", "sync_managed_files", it)
            }
        }
    }

    private fun backfillConversationStoreV2() {
        get<AppScope>().launch(Dispatchers.IO) {
            var imageRecoveryPasses = 1
            runIndependentStartupRecoveryDomains(
                domains = listOf(
                    // Preserve the original dependency order: every recovery domain below may
                    // read or write conversations through the v2 authority.
                    StartupRecoveryDomain("conversation_store") {
                        val result = get<ConversationV2BackfillCoordinator>().runPending()
                        get<MessageFtsOutboxProcessor>().requestDrain()
                        get<ConversationMediaReferenceBackfillProcessor>().requestBackfill()
                        if (result.inspected > 0) {
                            Log.i(
                                TAG,
                                "backfillConversationStoreV2: inspected=${result.inspected} " +
                                    "ready=${result.ready} quarantined=${result.quarantined} " +
                                    "inProgress=${result.inProgress} failed=${result.failed}",
                            )
                        }
                    },
                    StartupRecoveryDomain("chat_request_ledger") {
                        val recovery = get<ChatRequestReconciler>().reconcilePending()
                        if (recovery.inspected > 0 || recovery.failures.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "reconcileChatRequests: inspected=${recovery.inspected} " +
                                    "committed=${recovery.committed} unknown=${recovery.unknown} " +
                                    "interrupted=${recovery.interrupted} failed=${recovery.failed} " +
                                    "errors=${recovery.failures.size}",
                            )
                        }
                    },
                    StartupRecoveryDomain("image_request_and_media") {
                        var requestRecovery = get<ImageRequestReconciler>().reconcilePending()
                        var taskRecovery = get<ImageTaskRecoveryCoordinator>().reconcilePending()
                        // Rebuild the durable task descriptor before orphan file registration so
                        // MediaAsset never freezes a paid result with placeholder lineage.
                        var mediaRecovery = get<MediaAssetRecovery>().reconcilePending()
                        var taskDescriptorAdvancedAfterMedia = false
                        // A dead process can leave at most one 90-second fenced lease. Wait here;
                        // never reclaim or resend a charged request early.
                        while (taskRecovery.pending > 0 && imageRecoveryPasses < 20) {
                            delay(5_000)
                            requestRecovery = get<ImageRequestReconciler>().reconcilePending()
                            taskRecovery = get<ImageTaskRecoveryCoordinator>().reconcilePending()
                            taskDescriptorAdvancedAfterMedia =
                                taskDescriptorAdvancedAfterMedia || taskRecovery.projected > 0
                            imageRecoveryPasses++
                        }
                        if (taskDescriptorAdvancedAfterMedia) {
                            // A newly projected task descriptor can supply lineage that was absent
                            // during the first media pass. Reconcile again before downstream tool
                            // recovery and report both passes as one startup domain result.
                            mediaRecovery += get<MediaAssetRecovery>().reconcilePending()
                        }
                        if (mediaRecovery.inspected > 0 || mediaRecovery.failures.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "reconcileGeneratedImages: inspected=${mediaRecovery.inspected} " +
                                    "registered=${mediaRecovery.registered} " +
                                    "failures=${mediaRecovery.failures.size}",
                            )
                        }
                        if (requestRecovery.inspected > 0 || taskRecovery.failures.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "reconcileImageRequests: inspected=${requestRecovery.inspected} " +
                                    "committed=${requestRecovery.committed} unknown=${requestRecovery.unknown} " +
                                    "interrupted=${requestRecovery.interrupted} " +
                                    "taskProjected=${taskRecovery.projected} pending=${taskRecovery.pending} " +
                                    "passes=$imageRecoveryPasses",
                            )
                        }
                    },
                    StartupRecoveryDomain("tool_request_ledger") {
                        var recovery = get<ToolRequestReconciler>().reconcilePending()
                        if (imageRecoveryPasses > 1) {
                            recovery += get<ToolRequestReconciler>().reconcilePending()
                        }
                        if (recovery.inspected > 0 || recovery.failures.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "reconcileToolRequests: inspected=${recovery.inspected} " +
                                    "committed=${recovery.committed} unknown=${recovery.unknown} " +
                                    "cancelled=${recovery.cancelled} failed=${recovery.failed} " +
                                    "deferred=${recovery.deferred} errors=${recovery.failures.size}",
                            )
                        }
                    },
                    StartupRecoveryDomain("citation_store") {
                        backfillCitationStore()
                    },
                ),
                onFailure = { domain, error ->
                    // Never serialize recovery payloads or exception messages into logs: a
                    // malformed legacy row may itself contain credentials.
                    logSafeError(TAG, "startup_recovery", domain, error)
                },
            )
        }
    }

    private suspend fun backfillCitationStore() {
        var attempted = 0
        var migrated = 0
        var quarantined = 0
        var deferred = 0
        val coordinator = get<CitationBackfillCoordinator>()
        runCitationBackfillSchedule(
            runBatch = { coordinator.backfillBatch() },
            onBatch = { batch ->
                attempted += batch.attempted
                migrated += batch.migrated
                quarantined += batch.quarantined
                deferred += batch.deferred
            },
        )
        if (attempted > 0) {
            Log.i(
                TAG,
                "backfillCitationStore: attempted=$attempted ready=$migrated " +
                    "quarantined=$quarantined deferred=$deferred",
            )
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
                logSafeError(TAG, "web", "start_server_if_enabled", it)
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
        logSafeError(TAG, "coroutine", "app_scope", e)
    }
)

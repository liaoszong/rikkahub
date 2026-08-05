package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupAppFile
import me.rerere.rikkahub.data.sync.BackupRestoreCoordinator
import me.rerere.rikkahub.data.sync.BackupSettingsSanitizer
import me.rerere.rikkahub.data.sync.collectBackupAppFiles
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeStarted
import me.rerere.rikkahub.utils.logSafeSuccess
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val backupRestoreCoordinator: BackupRestoreCoordinator,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        logSafeSuccess(TAG, "backup", "test_webdav_connection")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val file = prepareBackupFile(config)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        logSafeSuccess(TAG, "backup", "upload_webdav_archive")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            logSafeStarted(TAG, "backup", "download_webdav_archive")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            logSafeSuccess(TAG, "backup", "download_webdav_archive")

            // Restore from backup file
            backupRestoreCoordinator.stageRestore(
                backupFile = backupFile,
                restoreDatabase = config.items.contains(WebDavConfig.BackupItem.DATABASE),
                restoreFiles = config.items.contains(WebDavConfig.BackupItem.FILES),
            )
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                logSafeSuccess(TAG, "backup", "cleanup_restore_archive")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        logSafeSuccess(TAG, "backup", "delete_webdav_archive")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        logSafeStarted(TAG, "backup", "restore_local_file")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            backupRestoreCoordinator.stageRestore(
                backupFile = file,
                restoreDatabase = config.items.contains(WebDavConfig.BackupItem.DATABASE),
                restoreFiles = config.items.contains(WebDavConfig.BackupItem.FILES),
            )
            logSafeSuccess(TAG, "backup", "restore_local_file")
        } catch (e: Exception) {
            logSafeError(TAG, "backup", "restore_local_file", e)
            throw Exception("Restore failed (${e.javaClass.simpleName})", e)
        }
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = BackupSettingsSanitizer.encode(settingsStore.settingsFlow.value, json)
            )

            // Backup database files
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val snapshot = backupRestoreCoordinator.createDatabaseSnapshot()
                try {
                    addFileToZip(zipOut, snapshot, "rikka_hub.db")
                } finally {
                    snapshot.delete()
                }
            }

            // Backup app files
            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val appFiles = webDavBackupAppFiles(context.filesDir)
                logSafeStarted(TAG, "backup", "archive_app_files")
                appFiles.forEach { appFile ->
                    addFileToZip(zipOut, appFile.source, appFile.archivePath)
                }
            }
        }

        logSafeSuccess(TAG, "backup", "create_archive")
        backupFile
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
    }
}

internal fun webDavBackupAppFiles(filesDir: File): List<BackupAppFile> =
    collectBackupAppFiles(filesDir)

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

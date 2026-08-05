package me.rerere.rikkahub.data.sync

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
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

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val backupRestoreCoordinator: BackupRestoreCoordinator,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        logSafeSuccess(TAG, "backup", "test_s3_connection")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val file = prepareBackupFile(config)
        val client = getS3Client(config)
        val key = "rikkahub_backups/${file.name}"

        client.putObject(
            key = key,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        logSafeSuccess(TAG, "backup", "upload_s3_archive")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            logSafeStarted(TAG, "backup", "download_s3_archive")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            logSafeSuccess(TAG, "backup", "download_s3_archive")

            // Restore from backup file
            backupRestoreCoordinator.stageRestore(
                backupFile = backupFile,
                restoreDatabase = config.items.contains(S3Config.BackupItem.DATABASE),
                restoreFiles = config.items.contains(S3Config.BackupItem.FILES),
            )
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                logSafeSuccess(TAG, "backup", "cleanup_restore_archive")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        settingsStore.awaitCredentialReady()
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        logSafeSuccess(TAG, "backup", "delete_s3_archive")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
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
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                val snapshot = backupRestoreCoordinator.createDatabaseSnapshot()
                try {
                    addFileToZip(zipOut, snapshot, "rikka_hub.db")
                } finally {
                    snapshot.delete()
                }
            }

            // Backup app files
            if (config.items.contains(S3Config.BackupItem.FILES)) {
                val appFiles = s3BackupAppFiles(context.filesDir)
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

internal fun s3BackupAppFiles(filesDir: File): List<BackupAppFile> =
    collectBackupAppFiles(filesDir)

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

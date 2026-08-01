package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry

private const val RESTORE_TAG = "BackupRestore"

class BackupRestoreCoordinator(
    private val context: Context,
    private val json: Json,
    private val database: AppDatabase,
) {
    fun createDatabaseSnapshot(): File {
        val snapshot = File(context.cacheDir, "rikka_hub-snapshot-${UUID.randomUUID()}.db")
        snapshot.delete()
        val escapedPath = snapshot.absolutePath.replace("'", "''")
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            check(cursor.moveToFirst()) { "Unable to checkpoint the database before backup" }
            check(cursor.getInt(0) == 0) { "Database checkpoint is busy" }
        }
        sqlite.execSQL("VACUUM INTO '$escapedPath'")
        validateSqlite(snapshot)
        return snapshot
    }

    fun stageRestore(
        backupFile: File,
        restoreDatabase: Boolean,
        restoreFiles: Boolean,
    ) {
        val restoreRoot = PendingRestoreManager.restoreRoot(context)
        check(!PendingRestoreManager.pendingDirectory(context).exists()) {
            "A validated restore is already waiting for application restart"
        }
        restoreRoot.mkdirs()
        val staging = File(restoreRoot, ".staging-${UUID.randomUUID()}")
        val payload = File(staging, "payload").apply { mkdirs() }
        val entries = mutableListOf<StagedRestoreEntry>()
        val seenEntries = mutableSetOf<String>()
        var hasSettings = false

        try {
            SafeBackupArchive(backupFile).use { archive ->
                var entry: ZipEntry?
                while (archive.nextEntry().also { entry = it } != null) {
                    val zipEntry = requireNotNull(entry)
                    if (zipEntry.isDirectory) continue
                    val name = zipEntry.name
                    val selected = when {
                        name == SETTINGS_ENTRY -> true
                        name in DATABASE_ENTRIES -> restoreDatabase
                        restoreFiles && isRestorableAppFile(name) -> true
                        else -> false
                    }
                    if (!selected) continue
                    check(seenEntries.add(name)) { "Backup archive contains a duplicate entry: $name" }

                    val target = SafeBackupArchive.resolveWithin(payload, name)
                    archive.copyCurrentEntryToFile(target)
                    if (name == SETTINGS_ENTRY) {
                        validateSettings(target)
                        hasSettings = true
                    } else if (name == DATABASE_ENTRY) {
                        validateSqlite(target)
                    }
                    entries += StagedRestoreEntry(name, target.length(), target.sha256())
                }
            }
            check(hasSettings) { "Backup archive does not contain settings.json" }
            check(!restoreDatabase || entries.any { it.name == DATABASE_ENTRY }) {
                "Backup archive does not contain the main database"
            }

            val manifest = PendingRestoreManifest(
                restoreDatabase = restoreDatabase,
                restoreFiles = restoreFiles,
                entries = entries,
            )
            File(staging, MANIFEST_FILE).writeText(json.encodeToString(manifest))
            File(staging, PHASE_FILE).writeText(RestorePhase.READY.name)
            atomicMove(staging, PendingRestoreManager.pendingDirectory(context))
            Log.i(RESTORE_TAG, "Validated restore staged with ${entries.size} files")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun validateSettings(file: File) {
        check(file.length() <= SafeBackupArchive.MAX_SETTINGS_BYTES) { "settings.json is too large" }
        val migrated = SettingsJsonMigrator.migrate(file.readText())
        json.decodeFromString<Settings>(migrated)
    }
}

object PendingRestoreManager {
    fun restoreRoot(context: Context): File = File(context.noBackupFilesDir, "backup-restore")

    fun pendingDirectory(context: Context): File = File(restoreRoot(context), "pending")

    fun applyFilesBeforeDatabase(context: Context) {
        val pending = pendingDirectory(context)
        if (!pending.exists()) return
        val json = restoreJson()
        val manifest = readManifest(pending, json)
        verifyPayload(pending, manifest)
        val transaction = AtomicRestoreTransaction(File(pending, "transaction"))

        when (readPhase(pending)) {
            RestorePhase.FILES_APPLIED -> return
            RestorePhase.APPLYING -> transaction.rollback()
            RestorePhase.READY -> Unit
        }

        writePhase(pending, RestorePhase.APPLYING)
        try {
            transaction.apply(buildOperations(context, pending, manifest))
            writePhase(pending, RestorePhase.FILES_APPLIED)
            Log.i(RESTORE_TAG, "Pending restore files applied before Room initialization")
        } catch (error: Throwable) {
            runCatching { transaction.rollback() }
                .onFailure { error.addSuppressed(it) }
            writePhase(pending, RestorePhase.READY)
            throw error
        }
    }

    suspend fun completeSettingsAfterKoin(
        context: Context,
        settingsStore: SettingsStore,
        json: Json,
    ) {
        val pending = pendingDirectory(context)
        if (!pending.exists()) return
        check(readPhase(pending) == RestorePhase.FILES_APPLIED) {
            "Pending restore files were not applied"
        }
        val manifest = readManifest(pending, json)
        verifyPayload(pending, manifest)
        val settingsFile = SafeBackupArchive.resolveWithin(File(pending, "payload"), SETTINGS_ENTRY)
        val previousSettings = settingsStore.settingsFlowRaw.first()
        try {
            val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
            settingsStore.update(json.decodeFromString<Settings>(migrated))
            pending.deleteRecursively()
            Log.i(RESTORE_TAG, "Pending restore committed")
        } catch (error: Throwable) {
            runCatching { settingsStore.update(previousSettings) }
                .onFailure { error.addSuppressed(it) }
            runCatching { AtomicRestoreTransaction(File(pending, "transaction")).rollback() }
                .onFailure { error.addSuppressed(it) }
            writePhase(pending, RestorePhase.READY)
            throw error
        }
    }

    private fun buildOperations(
        context: Context,
        pending: File,
        manifest: PendingRestoreManifest,
    ): List<RestoreFileOperation> {
        val payload = File(pending, "payload")
        val operations = mutableListOf<RestoreFileOperation>()
        manifest.entries
            .asSequence()
            .filter { it.name != SETTINGS_ENTRY }
            .mapTo(operations) { entry ->
                val source = SafeBackupArchive.resolveWithin(payload, entry.name)
                val target = when (entry.name) {
                    DATABASE_ENTRY -> context.getDatabasePath("rikka_hub")
                    DATABASE_WAL_ENTRY -> File(context.getDatabasePath("rikka_hub").parentFile, "rikka_hub-wal")
                    DATABASE_SHM_ENTRY -> File(context.getDatabasePath("rikka_hub").parentFile, "rikka_hub-shm")
                    else -> SafeBackupArchive.resolveWithin(context.filesDir, entry.name)
                }
                RestoreFileOperation.Replace(source, target)
            }

        if (manifest.restoreDatabase && manifest.entries.none { it.name == DATABASE_WAL_ENTRY }) {
            operations += RestoreFileOperation.Delete(
                File(context.getDatabasePath("rikka_hub").parentFile, "rikka_hub-wal")
            )
        }
        if (manifest.restoreDatabase && manifest.entries.none { it.name == DATABASE_SHM_ENTRY }) {
            operations += RestoreFileOperation.Delete(
                File(context.getDatabasePath("rikka_hub").parentFile, "rikka_hub-shm")
            )
        }
        return operations
    }

    private fun verifyPayload(pending: File, manifest: PendingRestoreManifest) {
        val payload = File(pending, "payload")
        manifest.entries.forEach { entry ->
            val file = SafeBackupArchive.resolveWithin(payload, entry.name)
            check(file.isFile && file.length() == entry.size && file.sha256() == entry.sha256) {
                "Staged restore payload failed integrity verification: ${entry.name}"
            }
        }
        check(manifest.entries.any { it.name == SETTINGS_ENTRY }) { "Restore manifest has no settings" }
    }

    private fun readManifest(pending: File, json: Json): PendingRestoreManifest =
        json.decodeFromString(File(pending, MANIFEST_FILE).readText())

    private fun readPhase(pending: File): RestorePhase =
        RestorePhase.valueOf(File(pending, PHASE_FILE).readText().trim())

    private fun writePhase(pending: File, phase: RestorePhase) {
        writeTextAtomically(File(pending, PHASE_FILE), phase.name)
    }

    private fun restoreJson() = Json { ignoreUnknownKeys = true }
}

internal sealed interface RestoreFileOperation {
    val target: File

    data class Replace(val source: File, override val target: File) : RestoreFileOperation
    data class Delete(override val target: File) : RestoreFileOperation
}

internal class AtomicRestoreTransaction(private val transactionRoot: File) {
    private val rollbackRoot = File(transactionRoot, "rollback")
    private val journalFile = File(transactionRoot, "journal.tsv")

    fun apply(
        operations: List<RestoreFileOperation>,
        afterOperation: (Int) -> Unit = {},
    ) {
        transactionRoot.deleteRecursively()
        rollbackRoot.mkdirs()
        journalFile.parentFile?.mkdirs()
        journalFile.createNewFile()

        operations.forEachIndexed { index, operation ->
            val target = operation.target.canonicalFile
            val backup = File(rollbackRoot, "$index.bin")
            val hadOriginal = target.isFile
            if (hadOriginal) copyFileAtomically(target, backup)
            appendJournal(RestoreJournalEntry(target.absolutePath, backup.absolutePath, hadOriginal))

            when (operation) {
                is RestoreFileOperation.Replace -> copyFileAtomically(operation.source, target)
                is RestoreFileOperation.Delete -> target.delete()
            }
            afterOperation(index)
        }
    }

    fun rollback() {
        if (!journalFile.exists()) return
        readJournal().asReversed().forEach { entry ->
            val target = File(entry.target)
            if (entry.hadOriginal) {
                val backup = File(entry.backup)
                check(backup.isFile) { "Restore rollback file is missing: ${backup.name}" }
                copyFileAtomically(backup, target)
            } else {
                target.delete()
            }
        }
        transactionRoot.deleteRecursively()
    }

    private fun appendJournal(entry: RestoreJournalEntry) {
        val line = listOf(
            entry.target.encodeJournalField(),
            entry.backup.encodeJournalField(),
            entry.hadOriginal.toString(),
        ).joinToString("\t") + "\n"
        FileOutputStream(journalFile, true).use { output ->
            output.write(line.toByteArray())
            output.fd.sync()
        }
    }

    private fun readJournal(): List<RestoreJournalEntry> = journalFile.readLines()
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split('\t')
            check(fields.size == 3) { "Restore journal is invalid" }
            RestoreJournalEntry(
                target = fields[0].decodeJournalField(),
                backup = fields[1].decodeJournalField(),
                hadOriginal = fields[2].toBooleanStrict(),
            )
        }
}

@Serializable
internal data class PendingRestoreManifest(
    val version: Int = 1,
    val restoreDatabase: Boolean,
    val restoreFiles: Boolean,
    val entries: List<StagedRestoreEntry>,
)

@Serializable
internal data class StagedRestoreEntry(
    val name: String,
    val size: Long,
    val sha256: String,
)

private data class RestoreJournalEntry(
    val target: String,
    val backup: String,
    val hadOriginal: Boolean,
)

private enum class RestorePhase { READY, APPLYING, FILES_APPLIED }

private const val SETTINGS_ENTRY = "settings.json"
private const val DATABASE_ENTRY = "rikka_hub.db"
private const val DATABASE_WAL_ENTRY = "rikka_hub-wal"
private const val DATABASE_SHM_ENTRY = "rikka_hub-shm"
private const val MANIFEST_FILE = "manifest.json"
private const val PHASE_FILE = "phase"
private val DATABASE_ENTRIES = setOf(DATABASE_ENTRY, DATABASE_WAL_ENTRY, DATABASE_SHM_ENTRY)

private fun isRestorableAppFile(name: String): Boolean =
    name.startsWith("${FileFolders.UPLOAD}/") ||
        name.startsWith("${FileFolders.SKILLS}/") ||
        name.startsWith("${FileFolders.FONTS}/")

private fun validateSqlite(file: File) {
    check(file.isFile && file.length() >= 100) { "Database snapshot is empty or truncated" }
    val header = ByteArray(16)
    FileInputStream(file).use { check(it.read(header) == header.size) }
    check(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Database snapshot is not SQLite" }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun copyFileAtomically(source: File, target: File) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.restore-${UUID.randomUUID()}.tmp")
    try {
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        atomicMove(temp, target)
    } finally {
        temp.delete()
    }
}

private fun atomicMove(source: File, target: File) {
    target.parentFile?.mkdirs()
    try {
        java.nio.file.Files.move(
            source.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: Exception) {
        java.nio.file.Files.move(
            source.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

private fun writeTextAtomically(target: File, value: String) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
        atomicMove(temp, target)
    } finally {
        temp.delete()
    }
}

private fun String.encodeJournalField(): String = java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString(toByteArray())

private fun String.decodeJournalField(): String = String(java.util.Base64.getUrlDecoder().decode(this))

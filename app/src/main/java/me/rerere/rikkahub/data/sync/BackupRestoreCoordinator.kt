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
            PendingRestoreManager.invalidateVerifiedSession()
            atomicMove(staging, PendingRestoreManager.pendingDirectory(context))
            PendingRestoreManager.invalidateVerifiedSession()
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
    private val verifier = PendingRestoreVerifier()

    fun restoreRoot(context: Context): File = File(context.noBackupFilesDir, "backup-restore")

    fun pendingDirectory(context: Context): File = File(restoreRoot(context), "pending")

    internal fun applyFilesBeforeDatabase(context: Context): PendingRestoreSession? {
        val pending = pendingDirectory(context).canonicalFile
        if (!pending.exists()) {
            verifier.invalidatePath(pending)
            return null
        }
        val transaction = AtomicRestoreTransaction(File(pending, "transaction"))

        // Rollback evidence is authoritative and must be handled before reading or hashing staged
        // payloads. This also repairs READY written by older builds after a failed rollback.
        when (readRestorePhase(pending)) {
            RestorePhase.APPLYING,
            RestorePhase.ROLLING_BACK,
            RestorePhase.ROLLBACK_FAILED,
            -> rollbackPendingTransaction(pending, transaction)

            RestorePhase.READY -> {
                if (transaction.hasTransactionState()) {
                    rollbackPendingTransaction(pending, transaction)
                }
            }

            RestorePhase.FILES_APPLIED -> Unit
        }

        val session = verifier.verify(pending)
        when (readRestorePhase(pending)) {
            RestorePhase.FILES_APPLIED -> {
                check(transaction.hasRecoveryJournal()) {
                    "Applied restore has no durable rollback journal"
                }
                return session
            }

            RestorePhase.READY -> Unit
            else -> error("Pending restore did not reach a stable phase after rollback recovery")
        }

        writeRestorePhase(pending, RestorePhase.APPLYING)
        try {
            transaction.apply(buildOperations(context, pending, session.manifest))
            writeRestorePhase(pending, RestorePhase.FILES_APPLIED)
            Log.i(RESTORE_TAG, "Pending restore files applied before Room initialization")
            return session
        } catch (error: Throwable) {
            runCatching { rollbackPendingTransaction(pending, transaction) }
                .onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    internal suspend fun completeSettingsAfterKoin(
        context: Context,
        settingsStore: SettingsStore,
        json: Json,
        session: PendingRestoreSession?,
    ) {
        if (session == null) return
        val pending = session.pendingDirectory
        check(pending.canonicalFile == pendingDirectory(context).canonicalFile) {
            "Pending restore session does not belong to this application"
        }
        check(pending.exists()) { "Pending restore session disappeared before settings commit" }
        check(session.identity == PendingRestoreIdentity.readFrom(pending)) {
            "Pending restore identity changed after payload verification"
        }
        check(readRestorePhase(pending) == RestorePhase.FILES_APPLIED) {
            "Pending restore files were not applied"
        }
        val previousSettings = settingsStore.settingsFlowRaw.first()
        try {
            val migrated = SettingsJsonMigrator.migrate(session.settingsFile.readText())
            settingsStore.update(
                decodeRestoredSettingsPreservingLocalSecrets(
                    restoredSettingsJson = migrated,
                    localSettings = previousSettings,
                    json = json,
                )
            )
            movePendingToCommittedGarbage(
                restoreRoot = restoreRoot(context),
                pending = pending,
            )
        } catch (error: Throwable) {
            runCatching { settingsStore.update(previousSettings) }
                .onFailure { error.addSuppressed(it) }
            if (pending.exists()) {
                runCatching {
                    rollbackPendingTransaction(
                        pending = pending,
                        transaction = AtomicRestoreTransaction(File(pending, "transaction")),
                    )
                }.onFailure { error.addSuppressed(it) }
            }
            throw error
        }
        // Nothing after the atomic rename belongs to the rollback boundary: pending is now absent
        // and this commit must remain authoritative even if bookkeeping or logging later fails.
        verifier.invalidate(session.identity)
        Log.i(RESTORE_TAG, "Pending restore committed")
    }

    /** Old committed directories are never interpreted as pending restore work. */
    internal fun cleanupCommittedGarbage(context: Context) {
        cleanupCommittedRestoreGarbage(restoreRoot(context))
    }

    internal fun invalidateVerifiedSession() {
        verifier.invalidateAll()
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
                    else -> resolveRestorableAppFile(context.filesDir, entry.name)
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

}

/** Capability produced only after this process has verified the complete staged payload. */
internal data class PendingRestoreSession(
    val pendingDirectory: File,
    val settingsFile: File,
    val manifest: PendingRestoreManifest,
    val identity: PendingRestoreIdentity,
)

internal data class PendingRestoreIdentity(
    val canonicalPath: String,
    val manifestFingerprint: String,
) {
    companion object {
        fun readFrom(pending: File): PendingRestoreIdentity {
            val canonicalPending = pending.canonicalFile
            val manifestBytes = File(canonicalPending, MANIFEST_FILE).readBytes()
            return PendingRestoreIdentity(
                canonicalPath = canonicalPending.path,
                manifestFingerprint = manifestBytes.sha256(),
            )
        }
    }
}

internal class PendingRestoreVerifier(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val payloadVerifier: (File, PendingRestoreManifest) -> Unit = ::verifyPendingRestorePayload,
) {
    private val monitor = Any()
    private var cachedSession: PendingRestoreSession? = null

    fun verify(pending: File): PendingRestoreSession {
        val canonicalPending = pending.canonicalFile
        val manifestBytes = File(canonicalPending, MANIFEST_FILE).readBytes()
        val identity = PendingRestoreIdentity(
            canonicalPath = canonicalPending.path,
            manifestFingerprint = manifestBytes.sha256(),
        )
        synchronized(monitor) {
            cachedSession?.takeIf { it.identity == identity }?.let { return it }

            val manifest = json.decodeFromString<PendingRestoreManifest>(manifestBytes.decodeToString())
            payloadVerifier(canonicalPending, manifest)
            return PendingRestoreSession(
                pendingDirectory = canonicalPending,
                settingsFile = SafeBackupArchive.resolveWithin(
                    File(canonicalPending, "payload"),
                    SETTINGS_ENTRY,
                ),
                manifest = manifest,
                identity = identity,
            ).also { cachedSession = it }
        }
    }

    fun invalidate(identity: PendingRestoreIdentity) {
        synchronized(monitor) {
            if (cachedSession?.identity == identity) cachedSession = null
        }
    }

    fun invalidatePath(pending: File) {
        val canonicalPath = pending.canonicalFile.path
        synchronized(monitor) {
            if (cachedSession?.identity?.canonicalPath == canonicalPath) cachedSession = null
        }
    }

    fun invalidateAll() {
        synchronized(monitor) { cachedSession = null }
    }
}

private fun verifyPendingRestorePayload(pending: File, manifest: PendingRestoreManifest) {
    val payload = File(pending, "payload")
    manifest.entries.forEach { entry ->
        val file = SafeBackupArchive.resolveWithin(payload, entry.name)
        check(file.isFile && file.length() == entry.size && file.sha256() == entry.sha256) {
            "Staged restore payload failed integrity verification: ${entry.name}"
        }
    }
    check(manifest.entries.any { it.name == SETTINGS_ENTRY }) { "Restore manifest has no settings" }
}

/**
 * Durably records rollback intent before touching live files. READY is written only after every
 * journal entry was restored and the transaction directory was removed successfully.
 */
internal fun rollbackPendingTransaction(
    pending: File,
    transaction: AtomicRestoreTransaction,
    rollback: () -> Unit = { transaction.rollback() },
) {
    writeRestorePhase(pending, RestorePhase.ROLLING_BACK)
    try {
        rollback()
        writeRestorePhase(pending, RestorePhase.READY)
    } catch (error: Throwable) {
        runCatching { writeRestorePhase(pending, RestorePhase.ROLLBACK_FAILED) }
            .onFailure { error.addSuppressed(it) }
        throw error
    }
}

internal fun movePendingToCommittedGarbage(
    restoreRoot: File,
    pending: File,
    uniqueSuffix: String = UUID.randomUUID().toString(),
    atomicMover: (source: File, target: File) -> Unit = ::atomicMoveRequired,
): File {
    val canonicalRoot = restoreRoot.canonicalFile
    val canonicalPending = pending.canonicalFile
    check(canonicalPending.parentFile == canonicalRoot && canonicalPending.name == PENDING_DIRECTORY) {
        "Only the active pending restore may be committed"
    }
    val committed = File(canonicalRoot, "$COMMITTED_GC_PREFIX$uniqueSuffix")
    check(!committed.exists()) { "Committed restore garbage destination already exists" }

    try {
        atomicMover(canonicalPending, committed)
    } catch (error: Throwable) {
        // An injected interruption can be observed after the atomic rename reached disk. Treat
        // that exact source-missing/target-present state as committed; every other state fails.
        if (!canonicalPending.exists() && committed.isDirectory) return committed
        throw error
    }
    check(!canonicalPending.exists() && committed.isDirectory) {
        "Pending restore commit rename did not complete"
    }
    return committed
}

/** Best-effort GC. Failures never recreate or reactivate a committed restore. */
internal fun cleanupCommittedRestoreGarbage(
    restoreRoot: File,
    delete: (File) -> Boolean = { it.deleteRecursively() },
) {
    restoreRoot.listFiles()
        .orEmpty()
        .filter { it.name.startsWith(COMMITTED_GC_PREFIX) }
        .forEach { garbage -> runCatching { delete(garbage) } }
}

private fun readRestorePhase(pending: File): RestorePhase =
    RestorePhase.valueOf(File(pending, PHASE_FILE).readText().trim())

private fun writeRestorePhase(pending: File, phase: RestorePhase) {
    writeTextAtomically(File(pending, PHASE_FILE), phase.name)
}

internal fun decodeRestoredSettingsPreservingLocalSecrets(
    restoredSettingsJson: String,
    localSettings: Settings,
    json: Json,
): Settings {
    // Legacy and hand-authored backups may predate portable-backup redaction. Treat every restored
    // settings document as untrusted and apply the current policy before considering local secrets.
    val restored = BackupSettingsSanitizer.sanitize(json.parseToJsonElement(restoredSettingsJson))
    val local = json.encodeToJsonElement(Settings.serializer(), localSettings)
    val merged = BackupSettingsSanitizer.mergeLocalSecrets(restored, local)
    return json.decodeFromJsonElement(Settings.serializer(), merged)
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
        check(!transactionRoot.exists()) { "Unresolved restore transaction must be rolled back first" }
        check(rollbackRoot.mkdirs()) { "Unable to create restore rollback directory" }
        check(journalFile.createNewFile()) { "Unable to create restore rollback journal" }

        operations.forEachIndexed { index, operation ->
            val target = operation.target.canonicalFile
            val backup = File(rollbackRoot, "$index.bin")
            val hadOriginal = target.isFile
            if (hadOriginal) copyFileAtomically(target, backup)
            appendJournal(RestoreJournalEntry(target.absolutePath, backup.absolutePath, hadOriginal))

            when (operation) {
                is RestoreFileOperation.Replace -> copyFileAtomically(operation.source, target)
                is RestoreFileOperation.Delete -> if (target.exists()) {
                    check(target.delete()) { "Unable to delete restored target: ${target.name}" }
                }
            }
            afterOperation(index)
        }
    }

    fun hasTransactionState(): Boolean = transactionRoot.exists()

    fun hasRecoveryJournal(): Boolean = journalFile.isFile

    fun rollback(afterRestoredEntry: (Int) -> Unit = {}) {
        if (!transactionRoot.exists()) return
        if (journalFile.exists()) {
            readJournal().asReversed().forEachIndexed { index, entry ->
                val target = File(entry.target)
                if (entry.hadOriginal) {
                    val backup = File(entry.backup)
                    check(backup.isFile) { "Restore rollback file is missing: ${backup.name}" }
                    copyFileAtomically(backup, target)
                } else if (target.exists()) {
                    check(target.delete()) { "Unable to delete rollback target: ${target.name}" }
                }
                afterRestoredEntry(index)
            }
        }

        // Never recursively delete active rollback evidence. Once every live target is restored,
        // atomically detach the directory from the active transaction name; only that detached
        // garbage may be deleted best-effort.
        val completedGarbage = File(
            requireNotNull(transactionRoot.parentFile),
            "$ROLLBACK_GC_PREFIX${UUID.randomUUID()}",
        )
        try {
            atomicMoveRequired(transactionRoot, completedGarbage)
        } catch (error: Throwable) {
            if (transactionRoot.exists() || !completedGarbage.isDirectory) throw error
        }
        runCatching { completedGarbage.deleteRecursively() }
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

private enum class RestorePhase {
    READY,
    APPLYING,
    ROLLING_BACK,
    ROLLBACK_FAILED,
    FILES_APPLIED,
}

private const val SETTINGS_ENTRY = "settings.json"
private const val DATABASE_ENTRY = "rikka_hub.db"
private const val DATABASE_WAL_ENTRY = "rikka_hub-wal"
private const val DATABASE_SHM_ENTRY = "rikka_hub-shm"
private const val MANIFEST_FILE = "manifest.json"
private const val PHASE_FILE = "phase"
private const val PENDING_DIRECTORY = "pending"
private const val COMMITTED_GC_PREFIX = "committed-gc-"
private const val ROLLBACK_GC_PREFIX = "rollback-gc-"
private val DATABASE_ENTRIES = setOf(DATABASE_ENTRY, DATABASE_WAL_ENTRY, DATABASE_SHM_ENTRY)

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

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

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

private fun atomicMoveRequired(source: File, target: File) {
    target.parentFile?.mkdirs()
    java.nio.file.Files.move(
        source.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
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

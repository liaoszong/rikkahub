package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class BackupArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Long = 200,
    val maxEntryNameLength: Int = 1_024,
)

internal class SafeBackupArchive(
    backupFile: File,
    private val limits: BackupArchiveLimits = BackupArchiveLimits(),
) : Closeable {
    private val input = ZipInputStream(FileInputStream(backupFile))
    private var entryCount = 0
    private var totalBytes = 0L
    private var currentEntry: ZipEntry? = null
    private var currentEntryBytes = 0L

    fun nextEntry(): ZipEntry? {
        finishCurrentEntry()
        val entry = input.nextEntry ?: return null
        validateEntryName(entry.name)
        entryCount++
        require(entryCount <= limits.maxEntries) { "Backup archive contains too many entries" }
        require(entry.size < 0 || entry.size <= limits.maxEntryBytes) { "Backup entry is too large" }
        currentEntry = entry
        currentEntryBytes = 0
        return entry
    }

    fun readCurrentEntryBytes(maxBytes: Long = limits.maxEntryBytes): ByteArray {
        val output = ByteArrayOutputStream()
        copyCurrentEntryTo(output, maxBytes)
        return output.toByteArray()
    }

    fun copyCurrentEntryToFile(target: File, maxBytes: Long = limits.maxEntryBytes) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.restore-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { copyCurrentEntryTo(it, maxBytes) }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    fun copyCurrentEntryTo(output: OutputStream, maxBytes: Long = limits.maxEntryBytes) {
        val entry = currentEntry ?: error("No current backup entry")
        val effectiveLimit = minOf(maxBytes, limits.maxEntryBytes)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            currentEntryBytes += read
            totalBytes += read
            require(currentEntryBytes <= effectiveLimit) { "Backup entry is too large: ${entry.name}" }
            require(totalBytes <= limits.maxTotalBytes) { "Backup archive expands beyond the total size limit" }
            output.write(buffer, 0, read)
        }
        validateCompressionRatio(entry, currentEntryBytes)
    }

    override fun close() {
        try {
            finishCurrentEntry()
        } finally {
            input.close()
        }
    }

    private fun finishCurrentEntry() {
        val entry = currentEntry ?: return
        validateCompressionRatio(entry, currentEntryBytes)
        input.closeEntry()
        currentEntry = null
        currentEntryBytes = 0
    }

    private fun validateCompressionRatio(entry: ZipEntry, extractedBytes: Long) {
        val compressedBytes = entry.compressedSize
        if (compressedBytes > 0) {
            require(extractedBytes <= compressedBytes * limits.maxCompressionRatio) {
                "Backup entry compression ratio is unsafe: ${entry.name}"
            }
        }
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && name.length <= limits.maxEntryNameLength) {
            "Backup entry name is invalid"
        }
        require('\\' !in name && '\u0000' !in name && !name.startsWith('/') && ':' !in name) {
            "Backup entry path is unsafe"
        }
        require(name.split('/').none { it == "." || it == ".." }) {
            "Backup entry path escapes the restore root"
        }
    }

    companion object {
        const val MAX_SETTINGS_BYTES = 16L * 1024 * 1024

        fun resolveWithin(root: File, relativePath: String): File {
            val canonicalRoot = root.canonicalFile
            val target = File(canonicalRoot, relativePath).canonicalFile
            require(
                target.path == canonicalRoot.path ||
                    target.path.startsWith(canonicalRoot.path + File.separator)
            ) { "Backup entry path escapes the restore root" }
            return target
        }
    }
}

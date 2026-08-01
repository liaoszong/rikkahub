package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeBackupArchiveTest {
    @Test
    fun `valid archive is read within configured limits`() {
        val zip = createZip(mapOf("upload/image.png" to byteArrayOf(1, 2, 3)))

        SafeBackupArchive(zip).use { archive ->
            assertTrue(archive.nextEntry()?.name == "upload/image.png")
            assertArrayEquals(byteArrayOf(1, 2, 3), archive.readCurrentEntryBytes())
        }
    }

    @Test
    fun `zip slip paths are rejected before extraction`() {
        val zip = createZip(mapOf("upload/../../settings.json" to byteArrayOf(1)))
        expectFailure { SafeBackupArchive(zip).use { it.nextEntry() } }
    }

    @Test
    fun `entry expansion limit aborts extraction`() {
        val zip = createZip(mapOf("upload/large.bin" to ByteArray(32)))
        expectFailure {
            SafeBackupArchive(zip, BackupArchiveLimits(maxEntryBytes = 16)).use { archive ->
                archive.nextEntry()
                archive.readCurrentEntryBytes()
            }
        }
    }

    @Test
    fun `canonical resolver cannot leave target root`() {
        val root = Files.createTempDirectory("safe-restore-root-").toFile()
        expectFailure { SafeBackupArchive.resolveWithin(root, "../outside") }
        assertTrue(SafeBackupArchive.resolveWithin(root, "nested/file").path.startsWith(root.canonicalPath))
    }

    private fun createZip(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("safe-backup-", ".zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected validation failure", failed)
    }
}

package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.webdav.webDavBackupAppFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupAppFilesTest {
    @Test
    fun `webdav and s3 share deterministic recursive archive paths`() {
        val filesDir = Files.createTempDirectory("backup-app-files-").toFile()
        write(filesDir, "upload/request.txt")
        write(filesDir, "skills/tool/nested/SKILL.md")
        write(filesDir, "fonts/custom.ttf")
        write(filesDir, "images/conversation-a/legacy.png")
        write(filesDir, "chat_generated_images/conversation-b/task-c/result.png")
        write(filesDir, "tool_outputs/must-not-leave-device.txt")

        val webDavPaths = webDavBackupAppFiles(filesDir).map { it.archivePath }
        val s3Paths = s3BackupAppFiles(filesDir).map { it.archivePath }

        assertEquals(webDavPaths, s3Paths)
        assertEquals(
            listOf(
                "upload/request.txt",
                "skills/tool/nested/SKILL.md",
                "fonts/custom.ttf",
                "images/conversation-a/legacy.png",
                "chat_generated_images/conversation-b/task-c/result.png",
            ),
            webDavPaths,
        )
        assertFalse(webDavPaths.any { it.startsWith("tool_outputs/") })
    }

    @Test
    fun `nested durable media files resolve and restore below approved roots`() {
        val root = Files.createTempDirectory("nested-media-restore-").toFile()
        val filesDir = File(root, "files").apply { mkdirs() }
        val stagedLegacy = write(root, "staged/legacy.png", byteArrayOf(1, 2, 3))
        val stagedChat = write(root, "staged/chat.png", byteArrayOf(4, 5, 6))
        val legacyTarget = resolveRestorableAppFile(
            filesDir,
            "images/conversation-a/versions/v1.png",
        )
        val chatTarget = resolveRestorableAppFile(
            filesDir,
            "chat_generated_images/conversation-b/tasks/task-c/v2.png",
        )

        AtomicRestoreTransaction(File(root, "transaction")).apply(
            listOf(
                RestoreFileOperation.Replace(stagedLegacy, legacyTarget),
                RestoreFileOperation.Replace(stagedChat, chatTarget),
            )
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), legacyTarget.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), chatTarget.readBytes())
        assertTrue(legacyTarget.canonicalPath.startsWith(filesDir.canonicalPath + File.separator))
        assertTrue(chatTarget.canonicalPath.startsWith(filesDir.canonicalPath + File.separator))
    }

    @Test
    fun `restore rejects unauthorized roots and zip slip variants`() {
        val filesDir = Files.createTempDirectory("restore-allowlist-").toFile()
        val rejected = listOf(
            "tool_outputs/result.png",
            "cache/result.png",
            "images",
            "../images/result.png",
            "images/../upload/result.png",
            "images//result.png",
            "images\\result.png",
            "/images/result.png",
            "C:/images/result.png",
        )

        rejected.forEach { archivePath ->
            assertFalse("Expected path to be rejected: $archivePath", isRestorableAppFile(archivePath))
            expectIllegalArgument { resolveRestorableAppFile(filesDir, archivePath) }
        }

        BACKUP_APP_FILE_ROOTS.forEach { root ->
            assertTrue(isRestorableAppFile("$root/nested/file.bin"))
        }
    }

    private fun write(root: File, relativePath: String, bytes: ByteArray = relativePath.toByteArray()): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private fun expectIllegalArgument(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}

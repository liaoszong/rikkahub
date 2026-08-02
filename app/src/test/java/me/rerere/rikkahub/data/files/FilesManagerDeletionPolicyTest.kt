package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilesManagerDeletionPolicyTest {
    @Test
    fun `conversation cleanup preserves both gallery folders even when files are managed`() {
        val root = Files.createTempDirectory("chat-file-gallery-policy").toFile()
        try {
            listOf(
                FileFolders.LEGACY_GENERATED_IMAGES,
                FileFolders.CHAT_GENERATED_IMAGES,
            ).forEachIndexed { index, folder ->
                val file = root.resolve("$folder/gallery-$index.png").apply {
                    parentFile?.mkdirs()
                    writeText("gallery")
                }
                val candidate = resolveChatFileDeletionCandidate(
                    filesDir = root,
                    uriScheme = "file",
                    uriPath = file.path,
                )

                assertNull(candidate)
                assertFalse(
                    deleteManagedChatFileIfAuthorized(
                        filesDir = root,
                        candidate = ChatFileDeletionCandidate(
                            file = file.canonicalFile,
                            relativePath = "$folder/${file.name}",
                            folder = folder,
                        ),
                        managedFile = ManagedFileEntity(
                            id = index + 1L,
                            folder = folder,
                            relativePath = "$folder/${file.name}",
                            displayName = file.name,
                            mimeType = "image/png",
                            sizeBytes = file.length(),
                            createdAt = 1L,
                            updatedAt = 1L,
                        ),
                    ),
                )
                assertTrue(file.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `external file URI is rejected without deleting its target`() {
        val root = Files.createTempDirectory("chat-file-external-policy").toFile()
        val external = Files.createTempFile("external-chat-file", ".txt").toFile().apply {
            writeText("keep")
        }
        try {
            val candidate = resolveChatFileDeletionCandidate(
                filesDir = root,
                uriScheme = "file",
                uriPath = external.path,
            )

            assertNull(candidate)
            assertTrue(external.exists())
        } finally {
            root.deleteRecursively()
            external.delete()
        }
    }

    @Test
    fun `registered upload attachment is physically deleted`() {
        val root = Files.createTempDirectory("chat-file-upload-policy").toFile()
        try {
            val file = root.resolve("${FileFolders.UPLOAD}/attachment.txt").apply {
                parentFile?.mkdirs()
                writeText("remove")
            }
            val candidate = requireNotNull(
                resolveChatFileDeletionCandidate(
                    filesDir = root,
                    uriScheme = "file",
                    uriPath = file.path,
                ),
            )

            assertTrue(
                deleteManagedChatFileIfAuthorized(
                    filesDir = root,
                    candidate = candidate,
                    managedFile = managedFile(candidate, id = 41L),
                ),
            )
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unregistered upload attachment is preserved`() {
        val root = Files.createTempDirectory("chat-file-unregistered-policy").toFile()
        try {
            val file = root.resolve("${FileFolders.UPLOAD}/attachment.txt").apply {
                parentFile?.mkdirs()
                writeText("keep")
            }
            val candidate = requireNotNull(
                resolveChatFileDeletionCandidate(
                    filesDir = root,
                    uriScheme = "file",
                    uriPath = file.path,
                ),
            )

            assertFalse(
                deleteManagedChatFileIfAuthorized(
                    filesDir = root,
                    candidate = candidate,
                    managedFile = null,
                ),
            )
            assertTrue(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun managedFile(candidate: ChatFileDeletionCandidate, id: Long) = ManagedFileEntity(
        id = id,
        folder = candidate.folder,
        relativePath = candidate.relativePath,
        displayName = candidate.file.name,
        mimeType = "text/plain",
        sizeBytes = candidate.file.length(),
        createdAt = 1L,
        updatedAt = 1L,
    )
}

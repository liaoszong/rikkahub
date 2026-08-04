package me.rerere.rikkahub.data.credential

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class AtomicCredentialFiles(private val root: File) {
    init {
        require(root.exists() || root.mkdirs()) { "Unable to create credential vault directory" }
        require(root.isDirectory) { "Credential vault root is not a directory" }
    }

    fun read(fileName: String): ByteArray? = target(fileName).takeIf(File::isFile)?.readBytes()

    fun exists(fileName: String): Boolean = target(fileName).isFile

    /** Writes in the destination directory, fsyncs, verifies, atomically replaces, then fsyncs the directory. */
    fun writeVerified(fileName: String, bytes: ByteArray, verify: (ByteArray) -> Unit) {
        val target = target(fileName)
        val temp = File(root, ".${target.name}.${System.nanoTime()}.tmp")
        check(temp.parentFile.canonicalFile == root.canonicalFile)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val persisted = temp.readBytes()
            verify(persisted)
            require(persisted.contentEquals(bytes)) { "Credential temp file differs from encoded bytes" }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Credential vault filesystem does not support atomic replace", unsupported)
            }
            // Android/Linux permits syncing a directory descriptor. Windows does not expose one through NIO.
            if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
                Files.newByteChannel(root.toPath(), StandardOpenOption.READ).use { channel ->
                    if (channel is java.nio.channels.FileChannel) channel.force(true)
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun target(fileName: String): File {
        require(fileName.matches(Regex("[A-Za-z0-9_.-]{1,160}"))) { "Unsafe vault file name" }
        return File(root, fileName).also { require(it.parentFile.canonicalFile == root.canonicalFile) }
    }
}

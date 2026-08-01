package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class AtomicRestoreTransactionTest {
    @Test
    fun `applies all replacement and deletion operations`() {
        val root = Files.createTempDirectory("restore-success").toFile()
        try {
            val source = root.resolve("stage/new.txt").apply {
                parentFile?.mkdirs()
                writeText("new")
            }
            val replaced = root.resolve("live/replaced.txt").apply {
                parentFile?.mkdirs()
                writeText("old")
            }
            val deleted = root.resolve("live/deleted.txt").apply { writeText("remove") }

            AtomicRestoreTransaction(root.resolve("transaction")).apply(
                listOf(
                    RestoreFileOperation.Replace(source, replaced),
                    RestoreFileOperation.Delete(deleted),
                )
            )

            assertEquals("new", replaced.readText())
            assertFalse(deleted.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rollback restores every original after injected partial failure`() {
        val root = Files.createTempDirectory("restore-failure").toFile()
        try {
            val firstSource = root.resolve("stage/first.txt").apply {
                parentFile?.mkdirs()
                writeText("new-first")
            }
            val secondSource = root.resolve("stage/second.txt").apply { writeText("new-second") }
            val firstTarget = root.resolve("live/first.txt").apply {
                parentFile?.mkdirs()
                writeText("old-first")
            }
            val secondTarget = root.resolve("live/second.txt").apply { writeText("old-second") }
            val transaction = AtomicRestoreTransaction(root.resolve("transaction"))

            runCatching {
                transaction.apply(
                    listOf(
                        RestoreFileOperation.Replace(firstSource, firstTarget),
                        RestoreFileOperation.Replace(secondSource, secondTarget),
                    )
                ) { index ->
                    if (index == 0) error("injected failure")
                }
            }
            transaction.rollback()

            assertEquals("old-first", firstTarget.readText())
            assertEquals("old-second", secondTarget.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `persisted journal recovers after simulated process death`() {
        val root = Files.createTempDirectory("restore-crash").toFile()
        try {
            val source = root.resolve("stage/new.txt").apply {
                parentFile?.mkdirs()
                writeText("new")
            }
            val target = root.resolve("live/value.txt").apply {
                parentFile?.mkdirs()
                writeText("old")
            }
            val transactionRoot = root.resolve("transaction")

            AtomicRestoreTransaction(transactionRoot).apply(
                listOf(RestoreFileOperation.Replace(source, target))
            )
            assertEquals("new", target.readText())

            AtomicRestoreTransaction(transactionRoot).rollback()

            assertEquals("old", target.readText())
            assertFalse(transactionRoot.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

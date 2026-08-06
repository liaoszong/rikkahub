package me.rerere.rikkahub.data.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRestoreDurabilityTest {
    @Test
    fun `rollback failure never advances to Ready or removes recovery evidence`() {
        val root = Files.createTempDirectory("pending-rollback-failure-").toFile()
        try {
            val pending = File(root, "pending").apply { mkdirs() }
            File(pending, "phase").writeText("APPLYING")
            val source = write(root, "payload/new.txt", "new")
            val target = write(root, "live/value.txt", "old")
            val transactionRoot = File(pending, "transaction")
            val transaction = AtomicRestoreTransaction(transactionRoot)
            transaction.apply(listOf(RestoreFileOperation.Replace(source, target)))
            check(File(transactionRoot, "rollback/0.bin").delete())

            assertThrows(IllegalStateException::class.java) {
                rollbackPendingTransaction(pending, transaction)
            }

            assertEquals("ROLLBACK_FAILED", File(pending, "phase").readText())
            assertTrue(File(transactionRoot, "journal.tsv").isFile)
            assertTrue(transactionRoot.exists())
            assertNotEquals("READY", File(pending, "phase").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rollback interruption keeps failed phase and journal until an idempotent retry completes`() {
        val root = Files.createTempDirectory("pending-rollback-").toFile()
        try {
            val pending = File(root, "pending").apply { mkdirs() }
            File(pending, "phase").writeText("APPLYING")
            val source = write(root, "payload/new.txt", "new")
            val target = write(root, "live/value.txt", "old")
            val transactionRoot = File(pending, "transaction")
            val transaction = AtomicRestoreTransaction(transactionRoot)
            transaction.apply(listOf(RestoreFileOperation.Replace(source, target)))
            assertEquals("new", target.readText())

            assertThrows(IllegalStateException::class.java) {
                rollbackPendingTransaction(pending, transaction) {
                    transaction.rollback { error("injected rollback interruption") }
                }
            }

            assertEquals("ROLLBACK_FAILED", File(pending, "phase").readText())
            assertTrue(File(transactionRoot, "journal.tsv").isFile)
            assertTrue(transactionRoot.exists())

            rollbackPendingTransaction(pending, transaction)

            assertEquals("READY", File(pending, "phase").readText())
            assertEquals("old", target.readText())
            assertFalse(transactionRoot.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same pending identity verifies once and manifest identity changes invalidate capability`() {
        val root = Files.createTempDirectory("pending-verify-").toFile()
        try {
            val pending = File(root, "pending").apply { mkdirs() }
            val manifestFile = File(pending, "manifest.json")
            val verifications = AtomicInteger()
            val verifier = PendingRestoreVerifier(
                payloadVerifier = { _, _ -> verifications.incrementAndGet() },
            )
            manifestFile.writeText(Json.encodeToString(manifest(restoreFiles = false)))

            val first = verifier.verify(pending)
            val retry = verifier.verify(pending)

            assertSame(first, retry)
            assertEquals(1, verifications.get())

            manifestFile.writeText(Json.encodeToString(manifest(restoreFiles = true)))
            val replacedIdentity = verifier.verify(pending)

            assertNotEquals(first.identity, replacedIdentity.identity)
            assertEquals(2, verifications.get())

            verifier.invalidate(replacedIdentity.identity)
            verifier.verify(pending)
            assertEquals(3, verifications.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `committed rename is authoritative even when garbage cleanup remains partial`() {
        val root = Files.createTempDirectory("pending-commit-").toFile()
        try {
            val pending = File(root, "pending").apply { mkdirs() }
            write(pending, "payload/settings.json", "settings")

            val committed = movePendingToCommittedGarbage(
                restoreRoot = root,
                pending = pending,
                uniqueSuffix = "test",
            )

            assertFalse(pending.exists())
            assertTrue(File(committed, "payload/settings.json").isFile)

            cleanupCommittedRestoreGarbage(root) { false }
            assertTrue(committed.exists())

            val nextPending = File(root, "pending").apply { mkdirs() }
            cleanupCommittedRestoreGarbage(root)
            assertTrue(nextPending.exists())
            assertFalse(committed.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `post-rename interruption is recognized as committed while pre-rename failure stays pending`() {
        val root = Files.createTempDirectory("pending-rename-").toFile()
        try {
            val pending = File(root, "pending").apply { mkdirs() }
            val committed = movePendingToCommittedGarbage(
                restoreRoot = root,
                pending = pending,
                uniqueSuffix = "interrupted",
                atomicMover = { source, target ->
                    check(source.renameTo(target))
                    error("injected interruption after rename")
                },
            )

            assertFalse(pending.exists())
            assertTrue(committed.isDirectory)

            val nextPending = File(root, "pending").apply { mkdirs() }
            assertThrows(IllegalStateException::class.java) {
                movePendingToCommittedGarbage(
                    restoreRoot = root,
                    pending = nextPending,
                    uniqueSuffix = "not-moved",
                    atomicMover = { _, _ -> error("injected failure before rename") },
                )
            }
            assertTrue(nextPending.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest(restoreFiles: Boolean) = PendingRestoreManifest(
        restoreDatabase = false,
        restoreFiles = restoreFiles,
        entries = emptyList(),
    )

    private fun write(root: File, relativePath: String, value: String): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
}

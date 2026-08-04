package me.rerere.rikkahub.data.credential

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMigrationJournalTest {
    @Test
    fun `journal enforces durable ordered idempotent migration`() {
        val root = Files.createTempDirectory("credential-journal-test").toFile()
        try {
            val slot = CredentialSlotId.of("provider", "owner", "api-key")
            val ref = CredentialRefId.new()
            CredentialMigrationJournal(root).prepare("provider.owner.api-key", slot, ref)

            val reopened = CredentialMigrationJournal(root)
            assertEquals(CredentialMigrationStage.PREPARE, reopened.get("provider.owner.api-key")!!.stage)
            assertEquals(
                CredentialMigrationStage.ENVELOPE_VERIFIED,
                reopened.advance("provider.owner.api-key", CredentialMigrationStage.ENVELOPE_VERIFIED).stage,
            )
            // Repeating an already persisted step is safe after a crash/restart.
            reopened.advance("provider.owner.api-key", CredentialMigrationStage.ENVELOPE_VERIFIED)
            assertTrue(
                runCatching {
                    reopened.advance("provider.owner.api-key", CredentialMigrationStage.LEGACY_CLEARED)
                }.isFailure,
            )
            reopened.advance("provider.owner.api-key", CredentialMigrationStage.REFERENCES_WRITTEN)
            reopened.advance("provider.owner.api-key", CredentialMigrationStage.LEGACY_CLEARED)
            assertTrue(reopened.incomplete().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}

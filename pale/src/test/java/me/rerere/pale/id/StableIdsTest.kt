package me.rerere.pale.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StableIdsTest {
    @Test
    fun `new ids are unique lowercase UUIDs`() {
        val first = MediaAssetId.random()
        val second = MediaAssetId.random()

        assertNotEquals(first, second)
        assertEquals(first.value.lowercase(), first.value)
        assertEquals(36, first.value.length)
    }

    @Test
    fun `conversation message and part identities are distinct types`() {
        val raw = "legacy-message-42"

        assertEquals(raw, MessageId(raw).value)
        assertNotEquals(MessageId(raw), MessagePartId(raw))
        assertNotEquals(MessageNodeId(raw), MessageBranchGroupId(raw))
        assertEquals(36, ConversationId.random().value.length)
    }

    @Test
    fun `legacy ids remain valid opaque identities`() {
        assertEquals(
            "legacy-genmedia-42",
            MediaAssetId("legacy-genmedia-42").value,
        )
    }

    @Test
    fun `paths and blank values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MediaAssetId("") }
        assertThrows(IllegalArgumentException::class.java) { MediaAssetId("images/a.png") }
    }

    @Test
    fun `request ledger identities never reuse provider identifiers`() {
        val request = RequestId.random()
        val attempt = RequestAttemptId.random()
        val output = RequestOutputId.random()
        val invocation = ToolInvocationId.random()
        val permission = ToolPermissionId.random()
        val migration = RequestMigrationJournalId.random()

        assertEquals(
            6,
            setOf(
                request.value,
                attempt.value,
                output.value,
                invocation.value,
                permission.value,
                migration.value,
            ).size,
        )
    }
}

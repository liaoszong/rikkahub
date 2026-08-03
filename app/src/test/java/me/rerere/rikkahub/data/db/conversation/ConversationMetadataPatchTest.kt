package me.rerere.rikkahub.data.db.conversation

import java.time.Instant
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMetadataPatchTest {
    @Test
    fun `patch changes only explicitly selected metadata`() {
        val original = conversation().copy(
            title = "before",
            folderId = Uuid.parse("50000000-0000-0000-0000-000000000001"),
        )

        val patched = ConversationMetadataPatch(
            title = ConversationMetadataField.Set("after"),
            isPinned = ConversationMetadataField.Set(true),
            folderId = ConversationMetadataField.Set(null),
        ).applyTo(original)

        assertEquals("after", patched.title)
        assertTrue(patched.isPinned)
        assertNull(patched.folderId)
        assertEquals(original.messageNodes, patched.messageNodes)
        assertEquals(original.storageRevision, patched.storageRevision)
    }

    private fun conversation() = Conversation(
        id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
        assistantId = DEFAULT_ASSISTANT_ID,
        messageNodes = listOf(
            MessageNode(
                id = Uuid.parse("20000000-0000-0000-0000-000000000001"),
                messages = emptyList(),
            ),
        ),
        createAt = Instant.ofEpochMilli(1),
        updateAt = Instant.ofEpochMilli(2),
    )
}

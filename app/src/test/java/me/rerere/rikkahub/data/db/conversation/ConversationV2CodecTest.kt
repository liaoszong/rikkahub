package me.rerere.rikkahub.data.db.conversation

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationV2CodecTest {
    private val codec = ConversationV2Codec(JsonInstant)

    @Test
    fun selectedPathDefinesParentsAndLivePartIdentityIgnoresPayload() {
        val rootA = message("20000000-0000-0000-0000-000000000001", "root-a")
        val rootB = message("20000000-0000-0000-0000-000000000002", "root-b")
        val child = message("20000000-0000-0000-0000-000000000003", "child")
        val conversation = conversation(
            listOf(
                MessageNode(
                    id = Uuid.parse("30000000-0000-0000-0000-000000000001"),
                    messages = listOf(rootA, rootB),
                    selectIndex = 1,
                ),
                MessageNode(
                    id = Uuid.parse("30000000-0000-0000-0000-000000000002"),
                    messages = listOf(child),
                ),
            ),
        )

        val before = codec.encode(conversation)
        val changed = codec.encode(
            conversation.copy(
                messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                    if (index == 1) {
                        node.copy(messages = listOf(child.copy(parts = listOf(UIMessagePart.Text("changed")))))
                    } else {
                        node
                    }
                },
            ),
        )

        assertEquals(
            rootB.id.toString(),
            before.graph.messages.single { it.messageId == child.id.toString() }.parentMessageId,
        )
        assertEquals(before.graph.parts.last().partId, changed.graph.parts.last().partId)
        assertNotEquals(before.graph.parts.last().payloadDigest, changed.graph.parts.last().payloadDigest)
        assertEquals(child.id.toString(), before.graph.activeLeafMessageId)
    }

    @Test
    fun sourceDigestUsesUnicodeCodePointsAndStorageRevisionIsNotSerialized() {
        val conversation = conversation(
            listOf(
                MessageNode(
                    id = Uuid.parse("30000000-0000-0000-0000-000000000003"),
                    messages = listOf(message("20000000-0000-0000-0000-000000000004", "a😀b")),
                ),
            ),
        ).copy(storageRevision = 41)
        val encoded = codec.encode(conversation)

        assertEquals(
            encoded.legacySourceDigest,
            digestLegacyConversationSource(conversation.id.toString(), encoded.legacyNodes),
        )
        assertFalse(JsonInstant.encodeToString(conversation).contains("storageRevision"))
    }

    @Test
    fun emptyBranchGroupsAreDroppedFromBothStoresAndReturnedModel() {
        val first = MessageNode(
            id = Uuid.parse("30000000-0000-0000-0000-000000000011"),
            messages = listOf(message("20000000-0000-0000-0000-000000000011", "first")),
        )
        val empty = MessageNode(
            id = Uuid.parse("30000000-0000-0000-0000-000000000012"),
            messages = emptyList(),
        )
        val last = MessageNode(
            id = Uuid.parse("30000000-0000-0000-0000-000000000013"),
            messages = listOf(message("20000000-0000-0000-0000-000000000013", "last")),
        )

        val encoded = codec.encode(conversation(listOf(first, empty, last)))

        assertEquals(listOf(first, last), encoded.normalizedMessageNodes)
        assertEquals(listOf(0, 1), encoded.legacyNodes.map { it.nodeIndex })
        assertEquals(2, encoded.graph.groups.size)
        assertEquals(setOf(INFERENCE_EMPTY_BRANCH_GROUP_DROPPED), encoded.inferenceFlags)
    }

    @Test
    fun allAttachmentKindsPersistTheirStableAssetIdentity() {
        val message = UIMessage(
            id = Uuid.parse("20000000-0000-0000-0000-000000000021"),
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image("file:///a", assetId = "image-asset"),
                UIMessagePart.Video("file:///b", assetId = "video-asset"),
                UIMessagePart.Audio("file:///c", assetId = "audio-asset"),
                UIMessagePart.Document("file:///d", "d.pdf", "application/pdf", assetId = "document-asset"),
            ),
        )

        val encoded = codec.encode(
            conversation(
                listOf(
                    MessageNode(
                        id = Uuid.parse("30000000-0000-0000-0000-000000000021"),
                        messages = listOf(message),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("image-asset", "video-asset", "audio-asset", "document-asset"),
            encoded.graph.parts.map { it.assetId },
        )
    }

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
        assistantId = Uuid.parse("40000000-0000-0000-0000-000000000001"),
        title = "codec",
        messageNodes = nodes,
        createAt = Instant.ofEpochMilli(1),
        updateAt = Instant.ofEpochMilli(2),
    )

    private fun message(id: String, text: String) = UIMessage(
        id = Uuid.parse(id),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse("2026-08-02T12:00:00"),
    )
}

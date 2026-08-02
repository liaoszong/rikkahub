package me.rerere.rikkahub.data.db.conversation

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val CONVERSATION_V2_INTERNAL_WRITER_MARKER = "__rikkahub_conversation_v2_internal__"
internal const val INFERENCE_EMPTY_BRANCH_GROUP_DROPPED = "EMPTY_BRANCH_GROUP_DROPPED"

internal data class EncodedConversationV2(
    val legacyNodes: List<MessageNodeEntity>,
    val graph: ConversationV2Graph,
    val legacySourceDigest: String,
    val normalizedMessageNodes: List<MessageNode>,
    val inferenceFlags: Set<String>,
)

/** Pure, deterministic projection from the in-memory compatibility model to both durable stores. */
internal class ConversationV2Codec(
    private val json: Json,
) {
    fun encode(conversation: Conversation): EncodedConversationV2 {
        val conversationId = conversation.id.toString()
        val seenGroupIds = mutableSetOf<String>()
        val seenMessageIds = mutableSetOf<String>()
        val groups = mutableListOf<MessageBranchGroupEntity>()
        val messages = mutableListOf<ConversationMessageEntity>()
        val parts = mutableListOf<MessagePartEntity>()
        val legacyNodes = mutableListOf<MessageNodeEntity>()
        val normalizedNodes = conversation.messageNodes.filter { it.messages.isNotEmpty() }
        val inferenceFlags = buildSet {
            if (normalizedNodes.size != conversation.messageNodes.size) {
                add(INFERENCE_EMPTY_BRANCH_GROUP_DROPPED)
            }
        }
        var parentMessageId: String? = null

        normalizedNodes.forEachIndexed { nodeIndex, node ->
            val groupId = node.id.toString()
            if (!seenGroupIds.add(groupId)) {
                throw ConversationV2WriteValidationException(
                    conversationId,
                    "Duplicate branch group ID $groupId",
                )
            }
            if (node.selectIndex !in node.messages.indices) {
                throw ConversationV2WriteValidationException(
                    conversationId,
                    "Branch group $groupId has invalid selectIndex=${node.selectIndex}",
                )
            }

            val rawMessages = json.encodeToString(ListSerializer(UIMessage.serializer()), node.messages)
            legacyNodes += MessageNodeEntity(
                id = groupId,
                conversationId = conversationId,
                nodeIndex = nodeIndex,
                messages = rawMessages,
                selectIndex = node.selectIndex,
            )

            var groupCreatedAt: String? = null
            node.messages.forEachIndexed { siblingOrdinal, message ->
                val messageId = message.id.toString()
                if (!seenMessageIds.add(messageId)) {
                    throw ConversationV2WriteValidationException(
                        conversationId,
                        "Duplicate message ID $messageId",
                    )
                }
                val rawMessage = json
                    .encodeToJsonElement(UIMessage.serializer(), message)
                    .jsonObject
                val rawParts = rawMessage["parts"]?.jsonArray
                    ?: throw ConversationV2WriteValidationException(
                        conversationId,
                        "Message $messageId has no serialized parts array",
                    )
                if (rawParts.size != message.parts.size) {
                    throw ConversationV2WriteValidationException(
                        conversationId,
                        "Message $messageId changed part count while encoding",
                    )
                }
                val createdAt = rawMessage.requireString(conversationId, messageId, "createdAt")
                if (siblingOrdinal == 0) groupCreatedAt = createdAt
                val requestIds = message.parts
                    .filterIsInstance<UIMessagePart.Tool>()
                    .map { it.requestId }
                    .filter(String::isNotBlank)
                    .distinct()
                val state = if (message.parts.any { it.isInFlight() }) {
                    ConversationV2Values.MESSAGE_INTERRUPTED
                } else {
                    ConversationV2Values.MESSAGE_COMPLETED
                }
                messages += ConversationMessageEntity(
                    conversationId = conversationId,
                    messageId = messageId,
                    parentMessageId = parentMessageId,
                    branchGroupId = groupId,
                    siblingOrdinal = siblingOrdinal,
                    requestId = requestIds.singleOrNull(),
                    role = rawMessage.requireString(conversationId, messageId, "role"),
                    state = state,
                    modelId = rawMessage.optionalString("modelId"),
                    createdAt = createdAt,
                    finishedAt = rawMessage.optionalString("finishedAt"),
                    usageJson = rawMessage.optionalCanonical("usage"),
                    annotationsJson = rawMessage["annotations"]?.toCanonicalJson() ?: "[]",
                    translation = rawMessage.optionalString("translation"),
                    contentDigest = sha256Hex(rawMessage.toCanonicalJson()),
                    legacyInferred = false,
                )

                rawParts.forEachIndexed { ordinal, rawPart ->
                    val partObject = rawPart as? JsonObject
                        ?: throw ConversationV2WriteValidationException(
                            conversationId,
                            "Message $messageId part $ordinal is not an object",
                        )
                    val kind = partObject["type"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: throw ConversationV2WriteValidationException(
                            conversationId,
                            "Message $messageId part $ordinal has no type",
                        )
                    val payloadJson = partObject.toCanonicalJson()
                    parts += MessagePartEntity(
                        conversationId = conversationId,
                        partId = stableConversationPartId(conversationId, messageId, ordinal),
                        messageId = messageId,
                        ordinal = ordinal,
                        kind = kind,
                        payloadJson = payloadJson,
                        payloadDigest = sha256Hex(payloadJson),
                        assetId = (message.parts[ordinal] as? UIMessagePart.Image)?.assetId,
                        toolInvocationId = message.parts[ordinal].toolInvocationIdOrNull(),
                    )
                }
            }
            groups += MessageBranchGroupEntity(
                conversationId = conversationId,
                branchGroupId = groupId,
                legacyNodeIndex = nodeIndex,
                legacyOrder = nodeIndex,
                createdAt = checkNotNull(groupCreatedAt),
                legacyInferred = false,
            )
            node.messages.getOrNull(node.selectIndex)?.let { selected ->
                parentMessageId = selected.id.toString()
            }
        }

        val graph = ConversationV2Graph(
            activeLeafMessageId = parentMessageId,
            groups = groups,
            messages = messages,
            parts = parts,
        )
        return EncodedConversationV2(
            legacyNodes = legacyNodes,
            graph = graph,
            legacySourceDigest = digestLegacyConversationSource(conversationId, legacyNodes),
            normalizedMessageNodes = normalizedNodes,
            inferenceFlags = inferenceFlags,
        )
    }
}

internal class ConversationV2LegacySourceDigest(
    conversationId: String,
    nodeCount: Int,
) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add("domain", "rikkahub-conversation-v2-legacy-source-v1")
        add("conversation_id", conversationId)
        add("node_count", nodeCount.toString())
    }

    fun addNode(
        nodeId: String,
        nodeIndex: Int,
        selectIndex: Int,
        messageLength: Long,
        rawMessages: String,
    ) {
        add("node.id", nodeId)
        add("node.index", nodeIndex.toString())
        add("node.select_index", selectIndex.toString())
        add("node.message_length", messageLength.toString())
        add("node.messages", rawMessages)
    }

    fun finish(): String = digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun add(name: String, value: String) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun update(bytes: ByteArray) {
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            ),
        )
        digest.update(bytes)
    }
}

internal fun digestLegacyConversationSource(
    conversationId: String,
    nodes: List<MessageNodeEntity>,
): String {
    val digest = ConversationV2LegacySourceDigest(conversationId, nodes.size)
    nodes.sortedWith(compareBy(MessageNodeEntity::nodeIndex, MessageNodeEntity::id)).forEach { node ->
        digest.addNode(
            nodeId = node.id,
            nodeIndex = node.nodeIndex,
            selectIndex = node.selectIndex,
            messageLength = node.messages.codePointCount(0, node.messages.length).toLong(),
            rawMessages = node.messages,
        )
    }
    return digest.finish()
}

internal class ConversationV2WriteValidationException(
    val conversationId: String,
    detail: String,
) : IllegalArgumentException("Conversation $conversationId cannot be encoded for v2: $detail")

private fun JsonObject.requireString(
    conversationId: String,
    messageId: String,
    field: String,
): String = optionalString(field) ?: throw ConversationV2WriteValidationException(
    conversationId,
    "Message $messageId has no $field",
)

private fun JsonObject.optionalString(field: String): String? =
    (this[field] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optionalCanonical(field: String): String? =
    this[field]?.takeUnless { it is JsonNull }?.toCanonicalJson()

private fun UIMessagePart.isInFlight(): Boolean =
    (this is UIMessagePart.Reasoning && finishedAt == null) ||
        (this is UIMessagePart.Tool && isRunning)

private fun UIMessagePart.toolInvocationIdOrNull(): String? =
    (this as? UIMessagePart.Tool)?.toolCallId?.takeIf(String::isNotBlank)

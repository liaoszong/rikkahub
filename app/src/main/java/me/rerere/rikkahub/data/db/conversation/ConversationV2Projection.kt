package me.rerere.rikkahub.data.db.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.ConversationGraphDAO
import me.rerere.rikkahub.data.db.dao.ConversationMigrationDAO
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity
import me.rerere.rikkahub.data.model.MessageNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid

data class ConversationV2ShadowProjection(
    val conversationId: String,
    val activeLeafMessageId: String?,
    val nodes: List<ConversationV2ShadowNode>,
    val graphDigest: String,
) {
    /** Compatibility view only. It never writes either the legacy or v2 store. */
    fun asLegacyMessageNodes(): List<MessageNode> = nodes.map { node ->
        MessageNode(
            id = Uuid.parse(node.branchGroupId),
            messages = node.messages,
            selectIndex = node.selectedIndex,
        )
    }
}

data class ConversationV2ShadowNode(
    val branchGroupId: String,
    val messages: List<UIMessage>,
    val selectedIndex: Int,
)

/** Builds a read-only compatibility projection without changing the production reader. */
class ConversationV2ShadowProjector(
    private val graphDAO: ConversationGraphDAO,
    private val migrationDAO: ConversationMigrationDAO,
    private val json: Json,
) {
    suspend fun loadReady(conversationId: String): ConversationV2ShadowProjection? {
        val state = migrationDAO.getConversationState(conversationId) ?: return null
        val journal = migrationDAO.getJournal(conversationId) ?: return null
        if (
            state.storageVersion != ConversationV2Values.STORAGE_VERSION_V2 ||
            journal.phase != ConversationV2Values.MIGRATION_READY
        ) {
            return null
        }
        return load(conversationId, state.activeLeafMessageId)
    }

    internal suspend fun load(
        conversationId: String,
        activeLeafMessageId: String?,
    ): ConversationV2ShadowProjection {
        val graph = loadConversationV2Graph(graphDAO, conversationId, activeLeafMessageId)
        val messagesById = graph.messages.associateBy { it.messageId }
        val selectedPath = mutableListOf<ConversationMessageEntity>()
        val visited = mutableSetOf<String>()
        var cursor = activeLeafMessageId
        while (cursor != null) {
            check(visited.add(cursor)) { "Conversation $conversationId contains a parent cycle at $cursor" }
            val message = messagesById[cursor]
                ?: error("Conversation $conversationId active path references missing message $cursor")
            check(message.deletedAt == null) {
                "Conversation $conversationId active path references deleted message $cursor"
            }
            selectedPath += message
            cursor = message.parentMessageId
        }
        selectedPath.reverse()

        val partsByMessage = graph.parts.groupBy { it.messageId }
        val messagesByGroup = graph.messages.groupBy { it.branchGroupId }
        val orderedGroups = graph.groups.sortedWith(
            compareBy<MessageBranchGroupEntity> { it.legacyOrder ?: Int.MAX_VALUE }
                .thenBy { it.branchGroupId },
        )
        val selectedByGroup = selectedPath.associateBy { it.branchGroupId }
        check(selectedByGroup.size == selectedPath.size) {
            "Conversation $conversationId active path repeats a branch group"
        }
        val nonEmptyGroupIds = orderedGroups.mapNotNull { group ->
            group.branchGroupId.takeIf {
                messagesByGroup[group.branchGroupId].orEmpty().any { message -> message.deletedAt == null }
            }
        }
        check(nonEmptyGroupIds == selectedPath.map(ConversationMessageEntity::branchGroupId)) {
            "Conversation $conversationId active path does not cover every non-empty branch group in order"
        }

        var previousSelectedMessageId: String? = null
        val nodes = orderedGroups.map { group ->
            val siblings = messagesByGroup[group.branchGroupId]
                .orEmpty()
                .filter { it.deletedAt == null }
                .sortedWith(compareBy(ConversationMessageEntity::siblingOrdinal, ConversationMessageEntity::messageId))
            check(siblings.all { it.parentMessageId == previousSelectedMessageId }) {
                "Conversation $conversationId branch group ${group.branchGroupId} has an invalid inferred parent"
            }
            val selected = selectedByGroup[group.branchGroupId]
            val selectedIndex = if (siblings.isEmpty()) {
                check(selected == null) {
                    "Conversation $conversationId empty branch group ${group.branchGroupId} is selected"
                }
                0
            } else {
                checkNotNull(selected) {
                    "Conversation $conversationId has no selected message for branch group ${group.branchGroupId}"
                }
                siblings.indexOfFirst { it.messageId == selected.messageId }.also { index ->
                    check(index >= 0) {
                        "Conversation $conversationId cannot select missing sibling ${selected.messageId}"
                    }
                }
            }
            selected?.let { previousSelectedMessageId = it.messageId }
            ConversationV2ShadowNode(
                branchGroupId = group.branchGroupId,
                messages = siblings.map { message ->
                    decodeMessage(message, partsByMessage[message.messageId].orEmpty())
                },
                selectedIndex = selectedIndex,
            )
        }

        return ConversationV2ShadowProjection(
            conversationId = conversationId,
            activeLeafMessageId = activeLeafMessageId,
            nodes = nodes,
            graphDigest = digestConversationV2Graph(graph),
        )
    }

    private fun decodeMessage(
        message: ConversationMessageEntity,
        parts: List<MessagePartEntity>,
    ): UIMessage {
        val envelope = message.envelopeExtrasJson
            ?.let(json::parseToJsonElement)
            ?.jsonObject
            ?.toMutableMap()
            ?: mutableMapOf()
        envelope["id"] = JsonPrimitive(message.messageId)
        envelope["role"] = JsonPrimitive(message.role)
        envelope["parts"] = JsonArray(
            parts
                .filter { it.deletedAt == null }
                .sortedWith(compareBy(MessagePartEntity::ordinal, MessagePartEntity::partId))
                .map { part ->
                    val payload = json.parseToJsonElement(part.payloadJson)
                    check(sha256Hex(payload.toCanonicalJson()) == part.payloadDigest) {
                        "Part ${part.partId} payload digest does not match"
                    }
                    payload
                },
        )
        envelope["annotations"] = json.parseToJsonElement(message.annotationsJson)
        envelope["createdAt"] = JsonPrimitive(message.createdAt)
        message.finishedAt?.let { envelope["finishedAt"] = JsonPrimitive(it) }
        message.modelId?.let { envelope["modelId"] = JsonPrimitive(it) }
        message.usageJson?.let { envelope["usage"] = json.parseToJsonElement(it) }
        message.translation?.let { envelope["translation"] = JsonPrimitive(it) }
        return json.decodeFromJsonElement(UIMessage.serializer(), JsonObject(envelope))
    }
}

internal data class ConversationV2Graph(
    val activeLeafMessageId: String?,
    val groups: List<MessageBranchGroupEntity>,
    val messages: List<ConversationMessageEntity>,
    val parts: List<MessagePartEntity>,
)

internal suspend fun loadConversationV2Graph(
    graphDAO: ConversationGraphDAO,
    conversationId: String,
    activeLeafMessageId: String?,
) = ConversationV2Graph(
    activeLeafMessageId = activeLeafMessageId,
    groups = graphDAO.getBranchGroups(conversationId),
    messages = graphDAO.getMessages(conversationId),
    parts = graphDAO.getAllParts(conversationId),
)

internal fun digestConversationV2Graph(graph: ConversationV2Graph): String {
    val digest = StableDigest("rikkahub-conversation-v2-graph-v1")
    digest.addNullable("active_leaf_message_id", graph.activeLeafMessageId)

    val groups = graph.groups.sortedWith(
        compareBy<MessageBranchGroupEntity> { it.legacyOrder ?: Int.MAX_VALUE }
            .thenBy { it.branchGroupId },
    )
    val groupOrder = groups.mapIndexed { index, group -> group.branchGroupId to index }.toMap()
    val messages = graph.messages.sortedWith(
        compareBy<ConversationMessageEntity> { groupOrder[it.branchGroupId] ?: Int.MAX_VALUE }
            .thenBy { it.siblingOrdinal }
            .thenBy { it.messageId },
    )
    val messageOrder = messages.mapIndexed { index, message -> message.messageId to index }.toMap()
    val parts = graph.parts.sortedWith(
        compareBy<MessagePartEntity> { messageOrder[it.messageId] ?: Int.MAX_VALUE }
            .thenBy { it.ordinal }
            .thenBy { it.partId },
    )

    digest.add("group_count", groups.size.toString())
    groups.forEach { group ->
        digest.add("group.conversation_id", group.conversationId)
        digest.add("group.branch_group_id", group.branchGroupId)
        digest.addNullable("group.legacy_node_index", group.legacyNodeIndex?.toString())
        digest.addNullable("group.legacy_order", group.legacyOrder?.toString())
        digest.add("group.created_at", group.createdAt)
        digest.add("group.revision", group.revision.toString())
        digest.add("group.legacy_inferred", group.legacyInferred.toString())
    }

    digest.add("message_count", messages.size.toString())
    messages.forEach { message ->
        digest.add("message.conversation_id", message.conversationId)
        digest.add("message.message_id", message.messageId)
        digest.addNullable("message.parent_message_id", message.parentMessageId)
        digest.add("message.branch_group_id", message.branchGroupId)
        digest.add("message.sibling_ordinal", message.siblingOrdinal.toString())
        digest.addNullable("message.origin_conversation_id", message.originConversationId)
        digest.addNullable("message.origin_message_id", message.originMessageId)
        digest.addNullable("message.legacy_message_id", message.legacyMessageId)
        digest.addNullable("message.request_id", message.requestId)
        digest.add("message.role", message.role)
        digest.add("message.state", message.state)
        digest.addNullable("message.model_id", message.modelId)
        digest.addNullable("message.provider_id", message.providerId)
        digest.addNullable("message.provider_response_id", message.providerResponseId)
        digest.add("message.created_at", message.createdAt)
        digest.addNullable("message.finished_at", message.finishedAt)
        digest.addNullable("message.usage_json", message.usageJson)
        digest.add("message.annotations_json", message.annotationsJson)
        digest.addNullable("message.translation", message.translation)
        digest.addNullable("message.envelope_extras_json", message.envelopeExtrasJson)
        digest.add("message.revision", message.revision.toString())
        digest.add("message.content_digest", message.contentDigest)
        digest.add("message.legacy_inferred", message.legacyInferred.toString())
        digest.addNullable("message.deleted_at", message.deletedAt?.toString())
    }

    digest.add("part_count", parts.size.toString())
    parts.forEach { part ->
        digest.add("part.conversation_id", part.conversationId)
        digest.add("part.part_id", part.partId)
        digest.add("part.message_id", part.messageId)
        digest.add("part.ordinal", part.ordinal.toString())
        digest.add("part.kind", part.kind)
        digest.add("part.schema_version", part.schemaVersion.toString())
        digest.add("part.payload_json", part.payloadJson)
        digest.add("part.payload_digest", part.payloadDigest)
        digest.addNullable("part.asset_id", part.assetId)
        digest.addNullable("part.tool_invocation_id", part.toolInvocationId)
        digest.add("part.revision", part.revision.toString())
        digest.addNullable("part.deleted_at", part.deletedAt?.toString())
    }
    return digest.finish()
}

internal fun JsonElement.toCanonicalJson(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${JsonPrimitive(key)}:${value.toCanonicalJson()}"
        }

    is JsonArray -> joinToString(prefix = "[", postfix = "]", separator = ",") {
        it.toCanonicalJson()
    }

    JsonNull -> "null"
    is JsonPrimitive -> toString()
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun stableLegacyPartId(
    conversationId: String,
    messageId: String,
    ordinal: Int,
    kind: String,
    canonicalPayloadDigest: String,
): String = deterministicConversationV2Id(
    kind = "legacy-part",
    conversationId,
    messageId,
    ordinal.toString(),
    kind,
    canonicalPayloadDigest,
)

internal fun deterministicConversationV2Id(kind: String, vararg components: String): String {
    val bytes = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            buildList {
                add("rikkahub-conversation-v2")
                add(kind)
                addAll(components)
            }.forEach { component ->
                val encoded = component.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        buffer.toByteArray()
    }
    return UUID.nameUUIDFromBytes(bytes).toString()
}

private class StableDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add("domain", domain)
    }

    fun add(name: String, value: String) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(byteArrayOf(1))
        update(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun addNullable(name: String, value: String?) {
        if (value == null) {
            update(name.toByteArray(StandardCharsets.UTF_8))
            update(byteArrayOf(0))
        } else {
            add(name, value)
        }
    }

    fun finish(): String = digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
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

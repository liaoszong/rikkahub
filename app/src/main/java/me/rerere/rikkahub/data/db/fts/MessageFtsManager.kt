package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        database.withTransaction {
            replaceConversationInTransaction(
                conversationId = conversation.id.toString(),
                title = conversation.title,
                updateAtMillis = conversation.updateAt.toEpochMilli(),
                nodes = conversation.messageNodes,
            )
        }
    }

    internal fun replaceConversationInTransaction(
        conversationId: String,
        title: String,
        updateAtMillis: Long,
        nodes: List<MessageNode>,
    ) {
        check(db.inTransaction()) { "FTS projection updates must be transactional" }
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        val statement = db.compileStatement(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        )
        try {
            nodes.forEach { node ->
                node.messages.forEach { message ->
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        statement.clearBindings()
                        statement.bindString(1, text)
                        statement.bindString(2, node.id.toString())
                        statement.bindString(3, message.id.toString())
                        statement.bindString(4, conversationId)
                        statement.bindString(5, title)
                        statement.bindString(6, updateAtMillis.toString())
                        statement.executeInsert()
                    }
                }
            }
        } finally {
            statement.close()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            deleteConversationInTransaction(conversationId)
        }
    }

    internal fun deleteConversationInTransaction(conversationId: String) {
        check(db.inTransaction()) { "FTS projection deletes must be transactional" }
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.withTransaction {
            deleteAllInTransaction()
        }
    }

    internal fun deleteAllInTransaction() {
        check(db.inTransaction()) { "FTS projection deletes must be transactional" }
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)

package me.rerere.rikkahub.data.db.migrations

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_27_28_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun emptyDatabase_validatesAndDoesNotInventMigrationWork() {
        val databaseName = "migration-27-28-conversation-v2-empty"
        helper.createDatabase(databaseName, 27).close()

        val db = helper.runMigrationsAndValidate(databaseName, 28, true, Migration_27_28)

        assertCount(db, "conversation_migration_journal", 0)
        assertCount(db, "message_branch_group", 0)
        assertCount(db, "conversation_message", 0)
        assertCount(db, "message_part", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun existingConversations_keepLegacyProjectionAndSeedPendingJournal() {
        val databaseName = "migration-27-28-conversation-v2-existing"
        helper.createDatabase(databaseName, 27).apply {
            insertLegacyConversation("conversation-a", "legacy-root-a")
            insertLegacyConversation("conversation-b", "legacy-root-b")
            execSQL(
                """
                INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
                VALUES ('node-a', 'conversation-a', 0, '[{"legacy":true}]', 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 28, true, Migration_27_28)

        db.query(
            "SELECT revision, active_leaf_message_id, storage_version, deleted_at, " +
                "last_writer_replica_id, nodes FROM ConversationEntity ORDER BY id",
        ).use { cursor ->
            repeat(2) { index ->
                assertTrue(cursor.moveToNext())
                assertEquals(0L, cursor.getLong(0))
                assertTrue(cursor.isNull(1))
                assertEquals(1, cursor.getInt(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
                assertEquals("legacy-root-${if (index == 0) "a" else "b"}", cursor.getString(5))
            }
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT conversation_id, phase, source_revision, next_node_index, " +
                "inference_flags_json FROM conversation_migration_journal ORDER BY conversation_id",
        ).use { cursor ->
            repeat(2) { index ->
                assertTrue(cursor.moveToNext())
                assertEquals("conversation-${if (index == 0) "a" else "b"}", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("[]", cursor.getString(4))
            }
            assertFalse(cursor.moveToNext())
        }
        db.query("SELECT messages, select_index FROM message_node WHERE id = 'node-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[{\"legacy\":true}]", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        assertCount(db, "message_branch_group", 0)
        assertCount(db, "conversation_message", 0)
        assertCount(db, "message_part", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun scopedIds_parentForeignKeysAndDeleteOutboxRemainConsistent() {
        val databaseName = "migration-27-28-conversation-v2-constraints"
        helper.createDatabase(databaseName, 27).apply {
            insertLegacyConversation("conversation-a", "[]")
            insertLegacyConversation("conversation-b", "[]")
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 28, true, Migration_27_28)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.insertBranchGroup("conversation-a", "group-a-root", 0)
        db.insertBranchGroup("conversation-a", "group-a-child", 1)
        db.insertBranchGroup("conversation-a", "group-a-invalid", 2)
        db.insertBranchGroup("conversation-b", "group-b-root", 0)

        db.insertMessage("conversation-a", "shared-message", null, "group-a-root", 0)
        db.insertMessage("conversation-b", "shared-message", null, "group-b-root", 0)
        db.insertMessage("conversation-b", "only-in-b", null, "group-b-root", 1)
        db.insertMessage("conversation-a", "child-a", "shared-message", "group-a-child", 0)

        assertCount(db, "conversation_message WHERE message_id = 'shared-message'", 2)
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertMessage(
                conversationId = "conversation-a",
                messageId = "invalid-cross-conversation-parent",
                parentMessageId = "only-in-b",
                branchGroupId = "group-a-invalid",
                siblingOrdinal = 0,
            )
        }

        db.execSQL(
            """
            INSERT INTO message_part (
                conversation_id, part_id, message_id, ordinal, kind, payload_json, payload_digest
            ) VALUES (
                'conversation-a', 'shared-part', 'child-a', 0, 'text', '{"text":"hello"}', 'digest-part'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO message_fts_outbox (
                event_id, conversation_id, target_revision, operation, created_at, updated_at
            ) VALUES (
                'delete-conversation-a', 'conversation-a', 1, 'DELETE', 100, 100
            )
            """.trimIndent(),
        )

        db.execSQL("DELETE FROM ConversationEntity WHERE id = 'conversation-a'")

        assertCount(db, "conversation_message WHERE conversation_id = 'conversation-a'", 0)
        assertCount(db, "message_part WHERE conversation_id = 'conversation-a'", 0)
        assertCount(db, "message_fts_outbox WHERE event_id = 'delete-conversation-a'", 1)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    private fun SupportSQLiteDatabase.insertLegacyConversation(id: String, nodes: String) {
        execSQL(
            """
            INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at)
            VALUES (?, ?, ?, 10, 20)
            """.trimIndent(),
            arrayOf(id, id, nodes),
        )
    }

    private fun SupportSQLiteDatabase.insertBranchGroup(
        conversationId: String,
        branchGroupId: String,
        legacyOrder: Int,
    ) {
        execSQL(
            """
            INSERT INTO message_branch_group (
                conversation_id, branch_group_id, legacy_order, created_at
            ) VALUES (?, ?, ?, '2026-01-01T00:00:00')
            """.trimIndent(),
            arrayOf<Any?>(conversationId, branchGroupId, legacyOrder),
        )
    }

    private fun SupportSQLiteDatabase.insertMessage(
        conversationId: String,
        messageId: String,
        parentMessageId: String?,
        branchGroupId: String,
        siblingOrdinal: Int,
    ) {
        execSQL(
            """
            INSERT INTO conversation_message (
                conversation_id, message_id, parent_message_id, branch_group_id,
                sibling_ordinal, role, state, created_at, content_digest
            ) VALUES (?, ?, ?, ?, ?, 'assistant', 'COMPLETED', '2026-01-01T00:00:00', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                conversationId,
                messageId,
                parentMessageId,
                branchGroupId,
                siblingOrdinal,
                "digest-$conversationId-$messageId",
            ),
        )
    }

    private fun assertCount(db: SupportSQLiteDatabase, fromExpression: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $fromExpression").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }
}

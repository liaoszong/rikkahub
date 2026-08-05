package me.rerere.rikkahub.data.db.migrations

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CitationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun room30CreatesValidatedEmptyCitationAuthority() {
        val name = "migration-30-31-citation-empty"
        helper.createDatabase(name, 30).close()

        val db = helper.runMigrationsAndValidate(name, 31, true, Migration_30_31)

        assertEquals(0, db.count("citation_source"))
        assertEquals(0, db.count("message_citation"))
        assertEquals(0, db.count("citation_migration_journal"))
        db.close()
    }

    @Test
    fun messageDeleteCascadesOccurrenceButRetainsReusableSource() {
        val name = "migration-30-31-citation-fk"
        helper.createDatabase(name, 30).close()
        val db = helper.runMigrationsAndValidate(name, 31, true, Migration_30_31)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.insertConversationGraph()
        db.execSQL(
            "INSERT INTO citation_source(source_id, canonical_url, title, record_digest) " +
                "VALUES ('source-1', 'https://example.com/', 'Example', 'source-digest')",
        )
        db.execSQL(
            "INSERT INTO message_citation(" +
                "citation_id, conversation_id, message_id, source_id, ordinal, provenance, record_digest" +
                ") VALUES ('citation-1', 'conversation-1', 'message-1', 'source-1', 0, 'provider', 'citation-digest')",
        )

        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO citation_source(source_id, canonical_url, title, record_digest) " +
                    "VALUES ('source-2', 'https://example.com/', 'Duplicate', 'duplicate')",
            )
        }

        db.execSQL("DELETE FROM conversation_message WHERE conversation_id = 'conversation-1'")
        assertEquals(0, db.count("message_citation"))
        assertEquals(1, db.count("citation_source"))
        db.close()
    }

    private fun SupportSQLiteDatabase.insertConversationGraph() {
        execSQL(
            "INSERT INTO ConversationEntity(id, title, nodes, create_at, update_at, storage_version) " +
                "VALUES ('conversation-1', 'Title', '[]', 1, 1, 2)",
        )
        execSQL(
            "INSERT INTO message_branch_group(conversation_id, branch_group_id, legacy_order, created_at) " +
                "VALUES ('conversation-1', 'group-1', 0, '2026-01-01T00:00:00')",
        )
        execSQL(
            "INSERT INTO conversation_message(" +
                "conversation_id, message_id, branch_group_id, sibling_ordinal, role, state, created_at, content_digest" +
                ") VALUES ('conversation-1', 'message-1', 'group-1', 0, 'ASSISTANT', 'COMPLETED', " +
                "'2026-01-01T00:00:00', 'message-digest')",
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}

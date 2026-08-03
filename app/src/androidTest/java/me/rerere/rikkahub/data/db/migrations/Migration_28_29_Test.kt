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
class Migration_28_29_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun emptyDatabase_validatesWithoutInventingRequests() {
        val databaseName = "migration-28-29-request-ledger-empty"
        helper.createDatabase(databaseName, 28).close()

        val db = helper.runMigrationsAndValidate(databaseName, 29, true, Migration_28_29)

        listOf(
            "request_ledger",
            "request_attempt",
            "request_output",
            "tool_invocation",
            "tool_permission",
            "request_audit_event",
            "tool_audit_event",
            "request_migration_journal",
        ).forEach { table -> assertCount(db, table, 0) }
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun stableIdentityAndEvidenceConstraintsRejectDuplicateDispatches() {
        val databaseName = "migration-28-29-request-ledger-constraints"
        helper.createDatabase(databaseName, 28).close()
        val db = helper.runMigrationsAndValidate(databaseName, 29, true, Migration_28_29)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.insertRequest("request-parent", "intent-parent")
        db.insertRequest("request-child", "intent-child", parentRequestId = "request-parent")
        db.insertAttempt("attempt-1", "request-parent", 1, "idem-1")

        assertThrows(SQLiteConstraintException::class.java) {
            db.insertRequest("request-duplicate-intent", "intent-parent")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertAttempt("attempt-duplicate-ordinal", "request-parent", 1, "idem-2")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertAttempt("attempt-duplicate-idempotency", "request-child", 1, "idem-1")
        }
        db.insertMigrationJournal("journal-1", "image_task_store", "global")
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertMigrationJournal("journal-2", "image_task_store", "global")
        }

        db.insertPermission("permission-1", "permission-key-1", "request-parent")
        db.insertInvocation("invocation-1", "request-parent", "attempt-1", "permission-1")
        db.insertOutput("output-1", "request-parent", "attempt-1")
        db.insertRequestAudit("request-event-1", "request-parent", "attempt-1", "invocation-1", "permission-1")
        db.insertToolAudit("tool-event-1", "request-parent", "invocation-1", "permission-1")

        assertThrows(SQLiteConstraintException::class.java) {
            db.insertOutput("output-cross-request", "request-child", "attempt-1")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertInvocation("invocation-cross-request", "request-child", "attempt-1", "permission-1")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertRequestAudit(
                "request-event-cross-request",
                "request-child",
                "attempt-1",
                null,
                null,
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertToolAudit("tool-event-cross-request", "request-child", "invocation-1", null)
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertToolAudit("tool-event-missing-invocation", null, "missing-invocation", null)
        }

        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("DELETE FROM request_attempt WHERE attempt_id = 'attempt-1'")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("DELETE FROM tool_permission WHERE permission_id = 'permission-1'")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("DELETE FROM request_ledger WHERE request_id = 'request-parent'")
        }

        assertCount(db, "request_ledger WHERE request_id = 'request-parent'", 1)
        assertCount(db, "request_attempt WHERE attempt_id = 'attempt-1'", 1)
        assertCount(db, "request_output WHERE output_id = 'output-1'", 1)
        assertCount(db, "tool_invocation WHERE invocation_id = 'invocation-1'", 1)
        assertCount(db, "tool_permission WHERE permission_id = 'permission-1'", 1)
        assertCount(db, "request_audit_event WHERE event_id = 'request-event-1'", 1)
        assertCount(db, "tool_audit_event WHERE event_id = 'tool-event-1'", 1)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun deletingParentWithoutEvidenceOnlyDetachesChildRelationship() {
        val databaseName = "migration-28-29-request-ledger-parent-delete"
        helper.createDatabase(databaseName, 28).close()
        val db = helper.runMigrationsAndValidate(databaseName, 29, true, Migration_28_29)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.insertRequest("request-parent", "intent-parent")
        db.insertRequest("request-child", "intent-child", parentRequestId = "request-parent")

        db.execSQL("DELETE FROM request_ledger WHERE request_id = 'request-parent'")

        assertNullColumn(db, "request_ledger", "parent_request_id", "request_id", "request-child")
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun deletingConversationPreservesBillingLedgerEvidence() {
        val databaseName = "migration-28-29-request-ledger-conversation-delete"
        helper.createDatabase(databaseName, 28).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES ('conversation-1', 'title', '[]', 1, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(databaseName, 29, true, Migration_28_29)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.insertRequest("request-1", "intent-1", conversationId = "conversation-1")

        db.execSQL("DELETE FROM ConversationEntity WHERE id = 'conversation-1'")

        db.query("SELECT conversation_id FROM request_ledger WHERE request_id = 'request-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conversation-1", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun pale4Room26_upgradesContinuouslyTo29WithoutLosingConversation() {
        val databaseName = "migration-26-29-request-ledger-chain"
        helper.createDatabase(databaseName, 26).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES ('legacy-conversation', 'legacy-title', '[\"node-marker\"]', 1, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            29,
            true,
            Migration_26_27,
            Migration_27_28,
            Migration_28_29,
        )

        db.query(
            "SELECT title, nodes, storage_version FROM ConversationEntity " +
                "WHERE id = 'legacy-conversation'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-title", cursor.getString(0))
            assertEquals("[\"node-marker\"]", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT phase FROM conversation_migration_journal " +
                "WHERE conversation_id = 'legacy-conversation'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
        }
        assertCount(db, "request_ledger", 0)
        assertCount(db, "request_migration_journal", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    private fun SupportSQLiteDatabase.insertRequest(
        requestId: String,
        intentKey: String,
        parentRequestId: String? = null,
        conversationId: String? = null,
    ) {
        execSQL(
            """
            INSERT INTO request_ledger (
                request_id, intent_key, parent_request_id, request_kind, conversation_id,
                input_digest, capability_snapshot_json, resolver_version,
                approval_state, request_state, billable_boundary, created_at, updated_at
            ) VALUES (?, ?, ?, 'chat_generation', ?, 'input-digest', '{}', 1,
                'not_required', 'created', 'not_sent', 10, 10)
            """.trimIndent(),
            arrayOf<Any?>(requestId, intentKey, parentRequestId, conversationId),
        )
    }

    private fun SupportSQLiteDatabase.insertAttempt(
        attemptId: String,
        requestId: String,
        ordinal: Int,
        idempotencyKey: String,
    ) {
        execSQL(
            """
            INSERT INTO request_attempt (
                attempt_id, request_id, attempt_ordinal, idempotency_key,
                attempt_state, billable_boundary, request_fingerprint,
                prepared_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'prepared', 'not_sent', 'fingerprint', 10, 10, 10)
            """.trimIndent(),
            arrayOf<Any>(attemptId, requestId, ordinal, idempotencyKey),
        )
    }

    private fun SupportSQLiteDatabase.insertPermission(
        permissionId: String,
        permissionKey: String,
        sourceRequestId: String,
    ) {
        execSQL(
            """
            INSERT INTO tool_permission (
                permission_id, permission_key, source_request_id, principal_kind,
                principal_id, tool_name, action, schema_digest, decision, scope_kind,
                constraints_json, capability_snapshot_json, policy_version,
                decided_at, created_at, updated_at
            ) VALUES (?, ?, ?, 'assistant', 'assistant-1', 'tool-1', 'execute',
                'schema-digest', 'allow', 'once', '{}', '{}', 1, 10, 10, 10)
            """.trimIndent(),
            arrayOf(permissionId, permissionKey, sourceRequestId),
        )
    }

    private fun SupportSQLiteDatabase.insertInvocation(
        invocationId: String,
        requestId: String,
        attemptId: String,
        permissionId: String,
    ) {
        execSQL(
            """
            INSERT INTO tool_invocation (
                invocation_id, request_id, attempt_id, provider_tool_call_id, tool_name,
                schema_digest, input_digest, side_effect_class, approval_state,
                execution_state, permission_id, created_at, updated_at
            ) VALUES (?, ?, ?, 'provider-call-1', 'tool-1', 'schema-digest',
                'input-digest', 'unknown', 'approved', 'running', ?, 10, 10)
            """.trimIndent(),
            arrayOf(invocationId, requestId, attemptId, permissionId),
        )
    }

    private fun SupportSQLiteDatabase.insertOutput(outputId: String, requestId: String, attemptId: String) {
        execSQL(
            """
            INSERT INTO request_output (
                output_id, request_id, attempt_id, output_kind, ordinal,
                content_digest, committed_at
            ) VALUES (?, ?, ?, 'message', 0, 'output-digest', 20)
            """.trimIndent(),
            arrayOf(outputId, requestId, attemptId),
        )
    }

    private fun SupportSQLiteDatabase.insertRequestAudit(
        eventId: String,
        requestId: String,
        attemptId: String?,
        invocationId: String?,
        permissionId: String?,
    ) {
        execSQL(
            """
            INSERT INTO request_audit_event (
                event_id, request_id, event_seq, attempt_id, invocation_id, permission_id,
                event_kind, actor_kind, payload_digest, payload_json, created_at
            ) VALUES (?, ?, 1, ?, ?, ?, 'dispatch_started', 'system', 'event-digest', '{}', 20)
            """.trimIndent(),
            arrayOf(eventId, requestId, attemptId, invocationId, permissionId),
        )
    }

    private fun SupportSQLiteDatabase.insertToolAudit(
        eventId: String,
        requestId: String?,
        invocationId: String?,
        permissionId: String?,
    ) {
        execSQL(
            """
            INSERT INTO tool_audit_event (
                event_id, request_id, invocation_id, permission_id,
                event_kind, actor_kind, summary, payload_digest, created_at
            ) VALUES (?, ?, ?, ?, 'execution_started', 'system', 'started', 'event-digest', 20)
            """.trimIndent(),
            arrayOf(eventId, requestId, invocationId, permissionId),
        )
    }

    private fun SupportSQLiteDatabase.insertMigrationJournal(
        journalId: String,
        sourceKind: String,
        sourceId: String,
    ) {
        execSQL(
            """
            INSERT INTO request_migration_journal (
                journal_id, source_kind, source_id, phase, created_at, updated_at
            ) VALUES (?, ?, ?, 'pending', 10, 10)
            """.trimIndent(),
            arrayOf(journalId, sourceKind, sourceId),
        )
    }

    private fun assertNullColumn(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        idColumn: String,
        id: String,
    ) {
        db.query("SELECT `$column` FROM `$table` WHERE `$idColumn` = ?", arrayOf(id)).use { cursor ->
            assertTrue("Expected $table row for $id", cursor.moveToFirst())
            assertTrue("Expected $table.$column to be NULL", cursor.isNull(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertCount(db: SupportSQLiteDatabase, expression: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $expression").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key violation in ${cursor.columnNames.joinToString()}", cursor.moveToFirst())
        }
    }
}

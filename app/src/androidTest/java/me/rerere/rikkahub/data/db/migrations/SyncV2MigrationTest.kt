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
class SyncV2MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun emptyRoom29CreatesValidatedEmptySyncFoundation() {
        val databaseName = "migration-29-30-sync-empty"
        helper.createDatabase(databaseName, 29).close()

        val db = helper.runMigrationsAndValidate(databaseName, 30, true, Migration_29_30)

        listOf("sync_replica", "sync_record_head", "sync_outbox", "sync_conflict")
            .forEach { table -> assertCount(db, table, 0) }
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun immutableOperationIdentityAndCausalRelationshipsAreEnforced() {
        val databaseName = "migration-29-30-sync-constraints"
        helper.createDatabase(databaseName, 29).close()
        val db = helper.runMigrationsAndValidate(databaseName, 30, true, Migration_29_30)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.insertReplica("replica-a", "space-a")
        db.insertHead("conversation", "conversation-a", "replica-a:1", "replica-a", 1)
        db.insertOutbox("replica-a:1", "replica-a", 1, "conversation", "conversation-a")
        db.insertConflict(
            conflictId = "conflict-a",
            entityType = "conversation",
            entityId = "conversation-a",
            localOperationId = "replica-a:1",
            remoteOperationId = "replica-b:1",
        )

        assertThrows(SQLiteConstraintException::class.java) {
            db.insertOutbox("replica-a:2", "replica-a", 1, "conversation", "conversation-a")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertOutbox("replica-missing:1", "replica-missing", 1, "conversation", "conversation-a")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertOutbox("replica-a:3", "replica-a", 3, "conversation", "missing-record")
        }
        db.insertReplica("replica-b", "space-b")
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertOutbox(
                operationId = "replica-b:1",
                replicaId = "replica-b",
                sequence = 1,
                entityType = "conversation",
                entityId = "conversation-a",
                spaceId = "space-b",
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("DELETE FROM sync_record_head WHERE entity_id = 'conversation-a'")
        }

        db.execSQL("DELETE FROM sync_outbox WHERE operation_id = 'replica-a:1'")
        db.execSQL("DELETE FROM sync_record_head WHERE entity_id = 'conversation-a'")
        assertCount(db, "sync_conflict", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun room29DataSurvivesAdditiveSyncMigration() {
        val databaseName = "migration-29-30-sync-preserves-ledger"
        helper.createDatabase(databaseName, 29).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES ('conversation-a', 'kept-title', '[]', 1, 2)",
            )
            execSQL(
                """
                INSERT INTO request_ledger (
                    request_id, intent_key, request_kind, input_digest, capability_snapshot_json,
                    resolver_version, approval_state, request_state, billable_boundary, created_at, updated_at
                ) VALUES ('request-a', 'intent-a', 'chat_generation', 'digest-a', '{}', 1,
                    'not_required', 'created', 'not_sent', 1, 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 30, true, Migration_29_30)

        assertTextValue(db, "SELECT title FROM ConversationEntity WHERE id = 'conversation-a'", "kept-title")
        assertTextValue(db, "SELECT intent_key FROM request_ledger WHERE request_id = 'request-a'", "intent-a")
        assertCount(db, "sync_replica", 0)
        assertCount(db, "sync_outbox", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    @Test
    fun room26UpgradesContinuouslyTo30WithoutLosingLegacyConversation() {
        val databaseName = "migration-26-30-sync-chain"
        helper.createDatabase(databaseName, 26).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES ('legacy-conversation', 'legacy-title', '[\"node-marker\"]', 1, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            30,
            true,
            Migration_26_27,
            Migration_27_28,
            Migration_28_29,
            Migration_29_30,
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
        assertCount(db, "sync_replica", 0)
        assertCount(db, "sync_record_head", 0)
        assertNoForeignKeyViolations(db)
        db.close()
    }

    private fun SupportSQLiteDatabase.insertReplica(replicaId: String, spaceId: String) {
        execSQL(
            """
            INSERT INTO sync_replica (
                replica_id, space_id, sync_epoch, created_at, updated_at
            ) VALUES (?, ?, 'epoch-a', 1, 1)
            """.trimIndent(),
            arrayOf(replicaId, spaceId),
        )
    }

    private fun SupportSQLiteDatabase.insertHead(
        entityType: String,
        entityId: String,
        operationId: String,
        replicaId: String,
        counter: Long,
        spaceId: String = "space-a",
        syncEpoch: String = "epoch-a",
    ) {
        execSQL(
            """
            INSERT INTO sync_record_head (
                space_id, sync_epoch, entity_type, entity_id, operation_id, dot_replica_id, dot_counter,
                writer_replica_id, causal_vector_json, hlc_physical_ms, hlc_logical, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', 1, 0, 1)
            """.trimIndent(),
            arrayOf<Any?>(
                spaceId,
                syncEpoch,
                entityType,
                entityId,
                operationId,
                replicaId,
                counter,
                replicaId,
            ),
        )
    }

    private fun SupportSQLiteDatabase.insertOutbox(
        operationId: String,
        replicaId: String,
        sequence: Long,
        entityType: String,
        entityId: String,
        spaceId: String = "space-a",
        syncEpoch: String = "epoch-a",
    ) {
        execSQL(
            """
            INSERT INTO sync_outbox (
                operation_id, space_id, sync_epoch, replica_id, sequence, entity_type, entity_id, base_vector_json,
                dot_counter, hlc_physical_ms, hlc_logical, envelope_bytes, state,
                next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, '{}', ?, 1, 0, X'01', 'pending', 1, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(
                operationId,
                spaceId,
                syncEpoch,
                replicaId,
                sequence,
                entityType,
                entityId,
                sequence,
            ),
        )
    }

    private fun SupportSQLiteDatabase.insertConflict(
        conflictId: String,
        entityType: String,
        entityId: String,
        localOperationId: String,
        remoteOperationId: String,
        spaceId: String = "space-a",
        syncEpoch: String = "epoch-a",
    ) {
        execSQL(
            """
            INSERT INTO sync_conflict (
                conflict_id, space_id, sync_epoch, entity_type, entity_id,
                local_operation_id, remote_operation_id,
                base_vector_json, local_head_json, remote_head_json, classification,
                resolution_state, detected_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, '{}', '{}', '{}', 'concurrent_edit', 'open', 1, 1)
            """.trimIndent(),
            arrayOf(
                conflictId,
                spaceId,
                syncEpoch,
                entityType,
                entityId,
                localOperationId,
                remoteOperationId,
            ),
        )
    }

    private fun assertCount(db: SupportSQLiteDatabase, fromClause: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $fromClause").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }

    private fun assertTextValue(db: SupportSQLiteDatabase, query: String, expected: String) {
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key violation in ${cursor.columnNames.joinToString()}", cursor.moveToFirst())
        }
    }
}

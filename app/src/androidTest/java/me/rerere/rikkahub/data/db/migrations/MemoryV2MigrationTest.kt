package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryV2MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun legacyMemoryIsBackfilledWithoutScopeExpansionOrDeletion() {
        val name = "migration-31-32-memory-v2"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (7, 'assistant-1', 'prefers Chinese')")
            execSQL("INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (8, '__global__', 'global preference')")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 32, true, Migration_31_32)

        assertEquals(2, db.query("SELECT COUNT(*) FROM MemoryEntity").use { it.moveToFirst(); it.getInt(0) })
        db.query(
            "SELECT memory_id, scope_kind, scope_id, source_trust, status FROM memory_record_v2 ORDER BY legacy_id"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("legacy:7", cursor.getString(0))
            assertEquals("assistant", cursor.getString(1))
            assertEquals("assistant-1", cursor.getString(2))
            assertEquals("legacy_manual", cursor.getString(3))
            assertEquals("active", cursor.getString(4))
            cursor.moveToNext()
            assertEquals("user", cursor.getString(1))
            assertEquals("__global__", cursor.getString(2))
        }
        db.close()
    }

    @Test
    fun currentMemoryRevisionIsBackfilledIntoImmutableTimeline() {
        val name = "migration-32-33-memory-revisions"
        helper.createDatabase(name, 32).apply {
            execSQL(
                """
                INSERT INTO memory_record_v2(
                    memory_id, legacy_id, type, scope_kind, scope_id, canonical_statement,
                    source_refs_json, source_trust, created_at, confidence, sensitivity, status,
                    revision, supersedes_json, conflicts_with_json, extraction_policy_version, updated_at
                ) VALUES (
                    'legacy:7', 7, 'fact', 'assistant', 'assistant-1', 'updated preference',
                    '["memory:7"]', 'legacy_manual', 10, 1.0, 'normal', 'active',
                    3, '[]', '[]', 1, 30
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 33, true, Migration_32_33)
        db.query(
            "SELECT revision, canonical_statement, supersedes_revision, event_kind " +
                "FROM memory_revision_v2 WHERE memory_id = 'legacy:7'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
            assertEquals("updated preference", cursor.getString(1))
            assertEquals(2, cursor.getInt(2))
            assertEquals("migration_snapshot", cursor.getString(3))
        }
        db.close()
    }
}

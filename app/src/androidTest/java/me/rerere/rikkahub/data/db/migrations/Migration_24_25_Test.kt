package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration_deduplicatesPathsAndAddsIdentityColumnsAndUniqueIndex() {
        val databaseName = "migration-24-25"
        helper.createDatabase(databaseName, 24).apply {
            execSQL(
                """
                INSERT INTO GenMediaEntity
                    (path, model_id, prompt, create_at, type, source_paths)
                VALUES ('images/same.png', 'model-id', 'first', 1, 'image_generation', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO GenMediaEntity
                    (path, model_id, prompt, create_at, type, source_paths)
                VALUES ('images/same.png', 'model-id', 'duplicate', 2, 'image_generation', NULL)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 25, true, Migration_24_25)
        db.query(
            "SELECT id, prompt, model_display_name, provider_id FROM GenMediaEntity WHERE path = 'images/same.png'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("first", cursor.getString(1))
            assertEquals(null, cursor.getString(2))
            assertEquals(null, cursor.getString(3))
            assertTrue(!cursor.moveToNext())
        }
        db.query("PRAGMA index_list(`GenMediaEntity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundUniquePathIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_GenMediaEntity_path") {
                    foundUniquePathIndex = cursor.getInt(uniqueIndex) == 1
                }
            }
            assertTrue(foundUniquePathIndex)
        }
        db.close()
    }
}

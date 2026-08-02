package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration_preservesGalleryRowsAndAddsDeterministicAssetAndFileIdentity() {
        val databaseName = "migration-25-26-media-assets"
        helper.createDatabase(databaseName, 25).apply {
            execSQL(
                """
                INSERT INTO managed_files
                    (id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at)
                VALUES
                    (55, 'chat_generated_images', 'chat_generated_images/chat-result.png',
                     'chat-result.png', 'image/png', 321, 90, 91)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO GenMediaEntity
                    (id, path, model_id, model_display_name, provider_id, prompt,
                     create_at, type, source_paths)
                VALUES
                    (7, 'images/generated.png', 'model-g', 'Generated Model', 'provider-g',
                     'a generated image', 100, 'image_generation', NULL),
                    (9, 'images/edited.webp', 'model-e', 'Edit Model', 'provider-e',
                     'an edited image', 200, 'image_edit', 'images/generated.png')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 26, true, Migration_25_26)
        db.query(
            """
            SELECT id, path, asset_id, managed_file_id, origin, mime_type, size_bytes,
                   storage_state, visibility, prompt, source_paths
            FROM GenMediaEntity ORDER BY id
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getInt(0))
            assertEquals("images/generated.png", cursor.getString(1))
            assertEquals("legacy-genmedia-7", cursor.getString(2))
            assertFalse(cursor.isNull(3))
            assertEquals("ai_generated", cursor.getString(4))
            assertEquals("image/png", cursor.getString(5))
            assertEquals(0, cursor.getLong(6))
            assertEquals("needs_metadata", cursor.getString(7))
            assertEquals("visible", cursor.getString(8))
            assertEquals("a generated image", cursor.getString(9))

            assertTrue(cursor.moveToNext())
            assertEquals(9, cursor.getInt(0))
            assertEquals("legacy-genmedia-9", cursor.getString(2))
            assertEquals("ai_edited", cursor.getString(4))
            assertEquals("image/webp", cursor.getString(5))
            assertEquals("images/generated.png", cursor.getString(10))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT parent_asset_id FROM GenMediaEntity WHERE id = 9",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-genmedia-7", cursor.getString(0))
        }
        db.query(
            """
            SELECT asset_id, managed_file_id, path, model_id, prompt, mime_type, size_bytes,
                   storage_state, visibility
            FROM GenMediaEntity WHERE path = 'chat_generated_images/chat-result.png'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-chat-file-55", cursor.getString(0))
            assertEquals(55, cursor.getLong(1))
            assertEquals("chat_generated_images/chat-result.png", cursor.getString(2))
            assertEquals("legacy-chat-image", cursor.getString(3))
            assertEquals("", cursor.getString(4))
            assertEquals("image/png", cursor.getString(5))
            assertEquals(321, cursor.getLong(6))
            assertEquals("needs_metadata", cursor.getString(7))
            assertEquals("visible", cursor.getString(8))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            """
            SELECT relative_path, mime_type, size_bytes
            FROM managed_files WHERE relative_path LIKE 'images/%' ORDER BY relative_path
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("images/edited.webp", cursor.getString(0))
            assertEquals("image/webp", cursor.getString(1))
            assertEquals(0, cursor.getLong(2))
            assertTrue(cursor.moveToNext())
            assertEquals("images/generated.png", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        db.close()
    }

    @Test
    fun migration_reusesExistingManagedFileIdentityWithoutChangingIt() {
        val databaseName = "migration-25-26-existing-managed-file"
        helper.createDatabase(databaseName, 25).apply {
            execSQL(
                """
                INSERT INTO managed_files
                    (id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at)
                VALUES (41, 'images', 'images/existing.jpg', 'existing.jpg', 'image/jpeg', 1234, 10, 11)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO GenMediaEntity
                    (id, path, model_id, model_display_name, provider_id, prompt,
                     create_at, type, source_paths)
                VALUES (5, 'images/existing.jpg', 'model', NULL, NULL, 'prompt',
                        12, 'image_generation', NULL)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 26, true, Migration_25_26)
        db.query(
            "SELECT managed_file_id, size_bytes, mime_type FROM GenMediaEntity WHERE id = 5",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(41, cursor.getLong(0))
            assertEquals(1234, cursor.getLong(1))
            assertEquals("image/jpeg", cursor.getString(2))
        }
        db.query("SELECT COUNT(*) FROM managed_files WHERE relative_path = 'images/existing.jpg'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }
}

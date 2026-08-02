package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaAssetFileMetadata
import me.rerere.rikkahub.data.repository.MediaAssetMetadataProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_26_27_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration_buildsConservativeV2GraphWithoutInventingContentOrReferences() {
        val databaseName = "migration-26-27-media-v2"
        helper.createDatabase(databaseName, 26).apply {
            execSQL(
                """
                INSERT INTO managed_files
                    (id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at)
                VALUES
                    (10, 'images', 'images/a.png', 'a.png', 'image/png', 100, 10, 11),
                    (11, 'images', 'images/b.png', 'b.png', 'image/png', 100, 12, 13),
                    (12, 'images', 'images/c.png', 'c.png', 'image/png', 0, 14, 15),
                    (13, 'images', 'images/d.png', 'd.png', 'image/png', 200, 16, 17)
                """.trimIndent(),
            )
            insertAsset(
                id = 1,
                path = "images/a.png",
                assetId = "asset-a",
                managedFileId = 10,
                sha256 = SHA_A,
                storageState = "available",
                conversationId = "conversation-1",
                messageNodeId = "node-1",
                toolCallId = "tool-1",
            )
            insertAsset(
                id = 2,
                path = "images/b.png",
                assetId = "asset-b",
                managedFileId = 11,
                sha256 = SHA_A,
                storageState = "available",
                conversationId = "conversation-1",
                messageNodeId = "node-1",
                toolCallId = "tool-1",
                parentAssetId = "asset-a",
                type = "image_edit",
            )
            insertAsset(
                id = 3,
                path = "images/c.png",
                assetId = "asset-c",
                managedFileId = 12,
                sha256 = null,
                storageState = "needs_metadata",
            )
            insertAsset(
                id = 4,
                path = "images/d.png",
                assetId = "asset-d",
                managedFileId = 13,
                sha256 = SHA_B,
                storageState = "missing",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 27, true, Migration_26_27)

        db.query("SELECT id, file_id FROM managed_files ORDER BY id").use { cursor ->
            repeat(4) { index ->
                assertTrue(cursor.moveToNext())
                val id = 10 + index
                assertEquals(id, cursor.getInt(0))
                assertEquals("legacy-managed-file-$id", cursor.getString(1))
            }
            assertFalse(cursor.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM media_blob").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db.query("SELECT blob_id, sha256 FROM media_blob WHERE sha256 = '$SHA_A'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sha256:$SHA_A", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT blob_id FROM media_blob WHERE blob_id = 'legacy-media-blob-asset-c' AND sha256 IS NULL",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        db.query("SELECT COUNT(*) FROM media_asset_blob").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
        db.query(
            "SELECT managed_file_id, state FROM media_replica " +
                "WHERE managed_file_id = 'legacy-managed-file-13'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-managed-file-13", cursor.getString(0))
            assertEquals("missing", cursor.getString(1))
        }
        db.query(
            "SELECT related_asset_id, relation_kind, ordinal FROM media_relation WHERE asset_id = 'asset-b'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("asset-a", cursor.getString(0))
            assertEquals("edit_of", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        db.query(
            "SELECT COUNT(*), COUNT(DISTINCT owner_key) FROM message_media_ref " +
                "WHERE conversation_id = 'conversation-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
        }
        db.query(
            "SELECT message_id, part_id FROM message_media_ref WHERE asset_id = 'asset-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.query(
            "SELECT state, detail FROM media_migration_journal " +
                "WHERE scope_kind = 'asset' AND scope_key = 'asset-a' AND stage = 'reference_backfill'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("pending", cursor.getString(0))
            assertEquals("message_scan_required", cursor.getString(1))
        }
        db.query(
            "SELECT state, detail FROM media_migration_journal " +
                "WHERE scope_kind = 'asset' AND scope_key = 'asset-c' AND stage = 'blob_backfill'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("pending", cursor.getString(0))
            assertEquals("sha256_verification_required", cursor.getString(1))
        }
        db.query(
            "SELECT state FROM media_migration_journal " +
                "WHERE scope_kind = 'asset' AND scope_key = 'asset-d' AND stage = 'reference_backfill'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("pending", cursor.getString(0))
        }
        db.query(
            "SELECT media_kind, display_name, lifecycle, privacy_scope, retention_policy, deleted_at " +
                "FROM GenMediaEntity WHERE asset_id = 'asset-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("image", cursor.getString(0))
            assertEquals("a.png", cursor.getString(1))
            assertEquals("active", cursor.getString(2))
            assertEquals("private", cursor.getString(3))
            assertEquals("library", cursor.getString(4))
            assertTrue(cursor.isNull(5))
        }
        db.close()
    }

    @Test
    fun migratedNullableBlobReconcilesMissingFileIdempotentlyAfterOpen() = runBlocking {
        val databaseName = "migration-26-27-nullable-reconcile"
        helper.createDatabase(databaseName, 26).apply {
            execSQL(
                """
                INSERT INTO managed_files
                    (id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at)
                VALUES (20, 'images', 'images/missing.png', 'missing.png', 'image/png', 0, 10, 11)
                """.trimIndent(),
            )
            insertAsset(
                id = 20,
                path = "images/missing.png",
                assetId = "asset-missing",
                managedFileId = 20,
                sha256 = null,
                storageState = "needs_metadata",
            )
            close()
        }
        helper.runMigrationsAndValidate(databaseName, 27, true, Migration_26_27).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            databaseName,
        ).addMigrations(Migration_27_28).build()
        try {
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = FilesRepository(database.managedFileDao()),
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 0,
                        width = null,
                        height = null,
                        sha256 = null,
                        storageState = MediaAssetEntity.STORAGE_MISSING,
                    )
                },
            )

            val first = repository.reconcileLocalMetadata(resolveFile = { java.io.File("missing") })
            val replay = repository.reconcileLocalMetadata(resolveFile = { java.io.File("missing") })

            assertTrue(first.failures.isEmpty())
            assertTrue(replay.failures.isEmpty())
            assertEquals(1, first.missing)
            assertEquals(1, replay.missing)
            assertEquals(
                "legacy-media-blob-asset-missing",
                database.genMediaDao().getAssetBlob("asset-missing", "original")?.blobId,
            )
            assertEquals(1, database.genMediaDao().countBlobs())
        } finally {
            database.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(databaseName)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertAsset(
        id: Int,
        path: String,
        assetId: String,
        managedFileId: Long,
        sha256: String?,
        storageState: String,
        conversationId: String? = null,
        messageNodeId: String? = null,
        toolCallId: String? = null,
        parentAssetId: String? = null,
        type: String = "image_generation",
    ) {
        execSQL(
            """
            INSERT INTO GenMediaEntity (
                id, path, model_id, model_display_name, provider_id, prompt, create_at,
                type, source_paths, asset_id, managed_file_id, origin, mime_type,
                size_bytes, width, height, sha256, storage_state, visibility,
                conversation_id, message_node_id, tool_call_id, parent_asset_id,
                updated_at, hidden_at, metadata_version
            ) VALUES (?, ?, 'model', NULL, NULL, 'prompt', ?, ?, NULL, ?, ?, ?, 'image/png',
                      ?, 10, 10, ?, ?, 'visible', ?, ?, ?, ?, ?, NULL, 1)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                path,
                id * 10L,
                type,
                assetId,
                managedFileId,
                if (type == "image_edit") "ai_edited" else "ai_generated",
                if (storageState == "missing") 200L else 100L,
                sha256,
                storageState,
                conversationId,
                messageNodeId,
                toolCallId,
                parentAssetId,
                id * 10L + 1,
            ),
        )
    }

    private companion object {
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
    }
}

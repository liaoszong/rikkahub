package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaAssetRepositoryTest {
    @Test
    fun orphanManagedChatFileRecoversReservedAssetIdentityWithoutProviderReplay() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val assetId = "83f995a4-5018-38e6-8e3c-3d68b95d86c2"
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-recovery-${System.nanoTime()}").apply { mkdirs() }
        val file = directory.resolve("$assetId.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val files = FilesRepository(database.managedFileDao())
            files.insert(
                ManagedFileEntity(
                    folder = "chat_generated_images",
                    relativePath = "chat_generated_images/${file.name}",
                    displayName = "generated.png",
                    mimeType = "image/png",
                    sizeBytes = file.length(),
                    createdAt = 10,
                    updatedAt = 10,
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 3,
                        width = 1,
                        height = 1,
                        sha256 = "digest",
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )

            val first = repository.reconcileUnregisteredGeneratedFiles(
                folder = "chat_generated_images",
                resolveFile = { file },
            )
            val replay = repository.reconcileUnregisteredGeneratedFiles(
                folder = "chat_generated_images",
                resolveFile = { file },
            )

            assertEquals(1, first.inspected)
            assertEquals(1, first.registered)
            assertEquals(assetId, database.genMediaDao().getByAssetId(assetId)?.assetId)
            assertEquals(0, replay.inspected)
            assertEquals(0, replay.registered)
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun generatedAssetRegistrationPersistsStableIdentityAndConversationLineage() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-registration-${System.nanoTime()}").apply { mkdirs() }
        val file = directory.resolve("result.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val files = FilesRepository(database.managedFileDao())
            val managed = files.insert(
                ManagedFileEntity(
                    folder = "chat_generated_images",
                    relativePath = "chat_generated_images/result.png",
                    displayName = "result.png",
                    mimeType = "image/png",
                    sizeBytes = file.length(),
                    createdAt = 10,
                    updatedAt = 10,
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 3,
                        width = 64,
                        height = 32,
                        sha256 = "digest",
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )
            val registration = GeneratedMediaAssetRegistration(
                assetId = "asset-stable",
                modelId = "model-id",
                modelDisplayName = "Model",
                providerId = "provider-id",
                prompt = "prompt",
                createdAt = 12,
                conversationId = "conversation-id",
                messageNodeId = "node-id",
                toolCallId = "tool-call-id",
            )

            val first = repository.registerGeneratedAsset(managed, file, registration)
            val replay = repository.registerGeneratedAsset(managed, file, registration)

            assertEquals(first.id, replay.id)
            assertEquals("asset-stable", first.assetId)
            assertEquals(managed.id, first.managedFileId)
            assertEquals("conversation-id", first.conversationId)
            assertEquals("tool-call-id", first.toolCallId)
            assertEquals(64, first.width)
            assertEquals(32, first.height)
            assertEquals("digest", first.sha256)
            assertEquals(1, database.genMediaDao().getAllMediaIncludingHidden().size)
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun reconciliationHydratesLegacyMetadataWithoutReplacingManagedFileIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val file = File.createTempFile("legacy-media-asset", ".webp")
        try {
            val files = FilesRepository(database.managedFileDao())
            val managed = files.insert(
                ManagedFileEntity(
                    folder = "images",
                    relativePath = "images/legacy.webp",
                    displayName = "legacy.webp",
                    mimeType = "image/webp",
                    sizeBytes = 0,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            database.genMediaDao().insertOrGet(
                MediaAssetEntity(
                    path = managed.relativePath,
                    modelId = "legacy-model",
                    prompt = "legacy prompt",
                    createAt = 1,
                    assetId = "legacy-asset",
                    managedFileId = managed.id,
                    mimeType = "image/webp",
                    storageState = MediaAssetEntity.STORAGE_NEEDS_METADATA,
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/webp",
                        sizeBytes = 400,
                        width = 20,
                        height = 10,
                        sha256 = "legacy-digest",
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )

            val result = repository.reconcileLocalMetadata(
                resolveFile = { relativePath ->
                    assertEquals("images/legacy.webp", relativePath)
                    file
                },
            )

            assertEquals(1, result.inspected)
            assertEquals(1, result.repaired)
            assertTrue(result.failures.isEmpty())
            val repaired = database.genMediaDao().getByAssetId("legacy-asset")
            assertNotNull(repaired)
            assertEquals(managed.id, repaired?.managedFileId)
            assertEquals(400, repaired?.sizeBytes)
            assertEquals("legacy-digest", repaired?.sha256)
            assertEquals(managed.id, files.getByPath(managed.relativePath)?.id)
            assertEquals(400, files.getByPath(managed.relativePath)?.sizeBytes)
        } finally {
            file.delete()
            database.close()
        }
    }
}

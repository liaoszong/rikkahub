package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MediaAssetDeleteResult
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.entity.MediaV2Values
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaAssetRepositoryTest {
    @Test
    fun ordinaryAttachmentRelocatesToCanonicalLibraryWithoutChangingAssetIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = context.cacheDir.resolve("attachment-relocation-${System.nanoTime()}").apply { mkdirs() }
        val source = directory.resolve("source.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val canonical = directory.resolve("canonical.pdf").apply { writeBytes(source.readBytes()) }
        try {
            val files = FilesRepository(database.managedFileDao())
            val upload = files.insert(
                managed(
                    relativePath = "upload/source.pdf",
                    displayName = "report.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = source.length(),
                ),
            )
            val library = files.insert(
                managed(
                    relativePath = "library_attachments/canonical.pdf",
                    displayName = "report.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = canonical.length(),
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { file, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "application/pdf",
                        sizeBytes = file.length(),
                        width = null,
                        height = null,
                        sha256 = SHA_A,
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )
            val registration = AttachmentMediaAssetRegistration(
                assetId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                origin = MediaAssetEntity.ORIGIN_USER_ATTACHMENT,
                conversationId = "conversation",
                messageNodeId = "node",
            )

            repository.registerAttachmentAsset(upload, source, registration)
            val relocated = repository.registerAttachmentAsset(library, canonical, registration)

            assertEquals(registration.assetId, relocated.assetId)
            assertEquals("library_attachments/canonical.pdf", relocated.path)
            assertEquals(library.id, relocated.managedFileId)
            assertEquals(MediaAssetEntity.TYPE_ATTACHMENT, relocated.type)
            assertEquals(MediaAssetEntity.MEDIA_KIND_DOCUMENT, relocated.mediaKind)
            assertEquals(
                MediaV2Values.JOURNAL_COMPLETE,
                database.genMediaDao().getJournal(
                    "file",
                    library.fileId,
                    MediaV2Values.STAGE_FILE_RELOCATION,
                )?.state,
            )
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun recoveredLegacyPlaceholderIsUpgradedByDurableTaskMetadata() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val assetId = "4aa8aa38-805d-4e37-b253-6f113243d85a"
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-placeholder-upgrade-${System.nanoTime()}").apply { mkdirs() }
        val file = directory.resolve("$assetId.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val files = FilesRepository(database.managedFileDao())
            files.insert(
                ManagedFileEntity(
                    folder = "chat_generated_images",
                    relativePath = "chat_generated_images/${file.name}",
                    displayName = file.name,
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
                        sha256 = SHA_A,
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )
            repository.reconcileUnregisteredGeneratedFiles(
                folder = "chat_generated_images",
                resolveFile = { file },
            )

            val registration = GeneratedMediaAssetRegistration(
                assetId = assetId,
                origin = MediaAssetEntity.ORIGIN_AI_EDITED,
                modelId = "real-model",
                modelDisplayName = "Real Model",
                providerId = "real-provider",
                prompt = "edit this image",
                createdAt = 11,
                conversationId = "conversation-id",
                toolCallId = "tool-call-id",
            )
            val recovery = repository.reconcileUnregisteredGeneratedFiles(
                folder = "chat_generated_images",
                resolveFile = { file },
                registrationsByAssetId = mapOf(assetId to registration),
            )
            val upgraded = requireNotNull(database.genMediaDao().getByAssetId(assetId))

            assertEquals(1, recovery.inspected)
            assertEquals(1, recovery.registered)
            assertEquals("real-model", upgraded.modelId)
            assertEquals("Real Model", upgraded.modelDisplayName)
            assertEquals("real-provider", upgraded.providerId)
            assertEquals("edit this image", upgraded.prompt)
            assertEquals(MediaAssetEntity.ORIGIN_AI_EDITED, upgraded.origin)
            assertEquals(MediaAssetEntity.TYPE_IMAGE_EDIT, upgraded.type)
            assertEquals("conversation-id", upgraded.conversationId)
            assertEquals("tool-call-id", upgraded.toolCallId)
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun failedEarlyCandidateDoesNotStarveLaterPaidOutput() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val failedAssetId = "11111111-1111-4111-8111-111111111111"
        val healthyAssetId = "22222222-2222-4222-8222-222222222222"
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-recovery-keyset-${System.nanoTime()}").apply { mkdirs() }
        val failedFile = directory.resolve("$failedAssetId.png").apply { writeBytes(byteArrayOf(1)) }
        val healthyFile = directory.resolve("$healthyAssetId.png").apply { writeBytes(byteArrayOf(2)) }
        try {
            val files = FilesRepository(database.managedFileDao())
            val failedManaged = files.insert(
                managed(
                    relativePath = "chat_generated_images/${failedFile.name}",
                    displayName = failedFile.name,
                ),
            )
            val healthyManaged = files.insert(
                managed(
                    relativePath = "chat_generated_images/${healthyFile.name}",
                    displayName = healthyFile.name,
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = availableProbe(SHA_A),
            )

            val result = repository.reconcileUnregisteredGeneratedFiles(
                folder = "chat_generated_images",
                resolveFile = { relativePath ->
                    if (relativePath == failedManaged.relativePath) error("injected file failure")
                    healthyFile
                },
                registrationsByAssetId = mapOf(
                    failedAssetId to registration(failedAssetId),
                    healthyAssetId to registration(healthyAssetId),
                ),
                limit = 1,
            )

            assertEquals(2, result.inspected)
            assertEquals(1, result.registered)
            assertEquals(1, result.failures.size)
            assertNull(database.genMediaDao().getByAssetId(failedAssetId))
            assertEquals(
                healthyManaged.id,
                database.genMediaDao().getByAssetId(healthyAssetId)?.managedFileId,
            )
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

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
                        sha256 = SHA_A,
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
                        sha256 = SHA_A,
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
            assertEquals(SHA_A, first.sha256)
            assertEquals(1, database.genMediaDao().getAllMediaIncludingHidden().size)
            assertEquals(1, database.genMediaDao().countBlobs())
            assertEquals(1, database.genMediaDao().countAssetBlobs())
            assertEquals(1, database.genMediaDao().countReplicas())
            assertEquals(1, database.genMediaDao().countMessageRefs())
            assertEquals(
                MediaV2Values.JOURNAL_PENDING,
                database.genMediaDao().getJournal(
                    scopeKind = "asset",
                    scopeKey = "asset-stable",
                    stage = MediaV2Values.STAGE_REFERENCE_BACKFILL,
                )?.state,
            )
            assertEquals(
                managed.fileId,
                database.genMediaDao().getReplicaByManagedFileId(managed.fileId)?.managedFileId,
            )
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
                        sha256 = SHA_B,
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
            assertEquals(400L, repaired?.sizeBytes)
            assertEquals(SHA_B, repaired?.sha256)
            assertEquals(managed.id, files.getByPath(managed.relativePath)?.id)
            assertEquals(400L, files.getByPath(managed.relativePath)?.sizeBytes)
        } finally {
            file.delete()
            database.close()
        }
    }

    @Test
    fun sharedDigestUsesOneBlobWhileKeepingDistinctReplicasReferencesAndLineage() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-shared-blob-${System.nanoTime()}").apply { mkdirs() }
        val firstFile = directory.resolve("first.png").apply { writeBytes(byteArrayOf(1)) }
        val secondFile = directory.resolve("second.png").apply { writeBytes(byteArrayOf(1)) }
        try {
            val files = FilesRepository(database.managedFileDao())
            val firstManaged = files.insert(managed("chat_generated_images/first.png", "first.png"))
            val secondManaged = files.insert(managed("chat_generated_images/second.png", "second.png"))
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 1,
                        width = 1,
                        height = 1,
                        sha256 = SHA_A,
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )
            repository.registerGeneratedAsset(
                firstManaged,
                firstFile,
                registration("asset-first"),
            )
            repository.registerGeneratedAsset(
                secondManaged,
                secondFile,
                registration("asset-second").copy(
                    origin = MediaAssetEntity.ORIGIN_AI_EDITED,
                    parentAssetId = "asset-first",
                    sourcePaths = listOf(firstManaged.relativePath),
                ),
            )

            assertEquals(1, database.genMediaDao().countBlobs())
            assertEquals(2, database.genMediaDao().countAssetBlobs())
            assertEquals(2, database.genMediaDao().countReplicas())
            assertEquals(2, database.genMediaDao().countMessageRefs())
            assertEquals(2, database.genMediaDao().countRelations())
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun reconciliationRebindsSyntheticBlobToVerifiedDigestWithoutChangingReplicaIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-blob-resolution-${System.nanoTime()}").apply { mkdirs() }
        val file = directory.resolve("result.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var verifiedSha: String? = null
        try {
            val files = FilesRepository(database.managedFileDao())
            val managed = files.insert(managed("chat_generated_images/result.png", "result.png"))
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 3,
                        width = verifiedSha?.let { 1 },
                        height = verifiedSha?.let { 1 },
                        sha256 = verifiedSha,
                        storageState = if (verifiedSha == null) {
                            MediaAssetEntity.STORAGE_NEEDS_METADATA
                        } else {
                            MediaAssetEntity.STORAGE_AVAILABLE
                        },
                    )
                },
            )
            repository.registerGeneratedAsset(managed, file, registration("asset-resolution"))
            val synthetic = database.genMediaDao().getAssetBlob("asset-resolution", "original")
            assertNull(synthetic?.let { database.genMediaDao().getBlob(it.blobId)?.sha256 })
            val replicaId = database.genMediaDao().getReplicaByManagedFileId(managed.fileId)?.replicaId

            verifiedSha = SHA_B
            val reconciliation = repository.reconcileLocalMetadata(resolveFile = { file })

            assertEquals(1, reconciliation.repaired)
            assertEquals(1, database.genMediaDao().countBlobs())
            assertEquals("sha256:$SHA_B", database.genMediaDao().getAssetBlob("asset-resolution", "original")?.blobId)
            val replica = database.genMediaDao().getReplicaByManagedFileId(managed.fileId)
            assertEquals(replicaId, replica?.replicaId)
            assertEquals("sha256:$SHA_B", replica?.blobId)
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun orderedReferenceInputsPersistEveryResolvableAssetOnce() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-reference-lineage-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = FilesRepository(database.managedFileDao())
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = availableProbe(SHA_A),
            )
            val firstFile = directory.resolve("first.png").apply { writeBytes(byteArrayOf(1)) }
            val secondFile = directory.resolve("second.png").apply { writeBytes(byteArrayOf(1)) }
            val resultFile = directory.resolve("result.png").apply { writeBytes(byteArrayOf(1)) }
            val firstManaged = files.insert(managed("chat_generated_images/first.png", "first.png"))
            val secondManaged = files.insert(managed("chat_generated_images/second.png", "second.png"))
            val resultManaged = files.insert(managed("chat_generated_images/result.png", "result.png"))
            repository.registerGeneratedAsset(firstManaged, firstFile, registration("asset-first"))
            repository.registerGeneratedAsset(secondManaged, secondFile, registration("asset-second"))

            repository.registerGeneratedAsset(
                resultManaged,
                resultFile,
                registration("asset-result").copy(
                    origin = MediaAssetEntity.ORIGIN_AI_EDITED,
                    referenceInputs = listOf(
                        MediaAssetReferenceInput(
                            assetId = "asset-second",
                            sourcePath = secondManaged.relativePath,
                        ),
                        MediaAssetReferenceInput(
                            assetId = "asset-first",
                            sourcePath = firstManaged.relativePath,
                        ),
                        MediaAssetReferenceInput(
                            assetId = "asset-second",
                            sourcePath = secondManaged.relativePath,
                        ),
                    ),
                ),
            )

            val relations = database.genMediaDao().getRelations(
                assetId = "asset-result",
                relationKind = MediaV2Values.RELATION_REFERENCE_INPUT,
            )
            assertEquals(listOf("asset-second", "asset-first"), relations.map { it.relatedAssetId })
            assertEquals(listOf(0, 1), relations.map { it.ordinal })
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    @Test
    fun concurrentVisibilityUpdateMakesMetadataReconciliationRetryWithoutOverwritingUserState() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val file = File.createTempFile("media-cas", ".png")
        try {
            val files = FilesRepository(database.managedFileDao())
            val managed = files.insert(managed("images/cas.png", "cas.png"))
            database.genMediaDao().insertOrGet(
                MediaAssetEntity(
                    path = managed.relativePath,
                    modelId = "legacy-model",
                    prompt = "legacy",
                    createAt = 1,
                    assetId = "asset-cas",
                    managedFileId = managed.id,
                    storageState = MediaAssetEntity.STORAGE_NEEDS_METADATA,
                    updatedAt = 1,
                ),
            )
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = MediaAssetMetadataProbe { _, _ ->
                    runBlocking { database.genMediaDao().hide("asset-cas", 20) }
                    MediaAssetFileMetadata(
                        mimeType = "image/png",
                        sizeBytes = 3,
                        width = 1,
                        height = 1,
                        sha256 = SHA_A,
                        storageState = MediaAssetEntity.STORAGE_AVAILABLE,
                    )
                },
            )

            val result = repository.reconcileLocalMetadata(resolveFile = { file })

            assertEquals(1, result.failures.size)
            val committed = requireNotNull(database.genMediaDao().getByAssetId("asset-cas"))
            assertEquals(MediaAssetEntity.VISIBILITY_HIDDEN, committed.visibility)
            assertEquals(20L, committed.updatedAt)
            assertEquals(MediaAssetEntity.STORAGE_NEEDS_METADATA, committed.storageState)
        } finally {
            file.delete()
            database.close()
        }
    }

    @Test
    fun deletingRelationTargetDefersSafelyInsteadOfThrowingForeignKeyFailure() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val directory = ApplicationProvider.getApplicationContext<android.content.Context>()
            .cacheDir.resolve("media-delete-relation-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = FilesRepository(database.managedFileDao())
            val repository = GenMediaRepository(
                dao = database.genMediaDao(),
                filesRepository = files,
                metadataProbe = availableProbe(SHA_A),
            )
            val parentFile = directory.resolve("parent.png").apply { writeBytes(byteArrayOf(1)) }
            val childFile = directory.resolve("child.png").apply { writeBytes(byteArrayOf(1)) }
            val parent = repository.registerGeneratedAsset(
                files.insert(managed("images/parent.png", "parent.png")),
                parentFile,
                registration("asset-parent").withoutMessageOwner(),
            )
            val parentJournal = requireNotNull(
                database.genMediaDao().getJournal(
                    "asset",
                    parent.assetId,
                    MediaV2Values.STAGE_REFERENCE_BACKFILL,
                ),
            )
            database.genMediaDao().updateJournalState(
                journalId = parentJournal.journalId,
                state = MediaV2Values.JOURNAL_COMPLETE,
                detail = null,
                updatedAt = 20,
            )
            repository.registerGeneratedAsset(
                files.insert(managed("images/child.png", "child.png")),
                childFile,
                registration("asset-child").withoutMessageOwner().copy(
                    origin = MediaAssetEntity.ORIGIN_AI_EDITED,
                    parentAssetId = parent.assetId,
                ),
            )

            val result = repository.deleteMedia(parent.id)

            assertEquals(MediaAssetDeleteResult.DEFERRED_REFERENCED, result)
            val deferred = requireNotNull(database.genMediaDao().getByAssetId(parent.assetId))
            assertEquals(MediaAssetEntity.LIFECYCLE_DELETE_PENDING, deferred.lifecycle)
            assertEquals(MediaAssetEntity.VISIBILITY_HIDDEN, deferred.visibility)
            assertNotNull(database.genMediaDao().getByAssetId("asset-child"))
        } finally {
            directory.deleteRecursively()
            database.close()
        }
    }

    private fun managed(
        relativePath: String,
        displayName: String,
        mimeType: String = "image/png",
        sizeBytes: Long = 1,
    ) = ManagedFileEntity(
        folder = relativePath.substringBefore('/'),
        relativePath = relativePath,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAt = 10,
        updatedAt = 10,
    )

    private fun registration(assetId: String) = GeneratedMediaAssetRegistration(
        assetId = assetId,
        modelId = "model-id",
        prompt = "prompt",
        createdAt = 12,
        conversationId = "conversation-id",
        messageNodeId = "node-id",
        toolCallId = "tool-call-id",
    )

    private fun GeneratedMediaAssetRegistration.withoutMessageOwner() = copy(
        conversationId = null,
        messageNodeId = null,
        toolCallId = null,
    )

    private fun availableProbe(sha256: String) = MediaAssetMetadataProbe { _, _ ->
        MediaAssetFileMetadata(
            mimeType = "image/png",
            sizeBytes = 1,
            width = 1,
            height = 1,
            sha256 = sha256,
            storageState = MediaAssetEntity.STORAGE_AVAILABLE,
        )
    }

    private companion object {
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
    }
}

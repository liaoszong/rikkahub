package me.rerere.rikkahub.data.repository

import android.graphics.BitmapFactory
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.MediaAssetGraphWrite
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetBlobEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.entity.MediaBlobEntity
import me.rerere.rikkahub.data.db.entity.MediaMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.MediaRelationEntity
import me.rerere.rikkahub.data.db.entity.MediaReplicaEntity
import me.rerere.rikkahub.data.db.entity.MediaV2Values
import me.rerere.rikkahub.data.db.entity.MessageMediaRefEntity
import me.rerere.rikkahub.data.db.media.MediaReferenceBackfillScheduler
import me.rerere.rikkahub.data.files.DurableAssetOwnership
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.pale.media.MediaStableIds
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Compatibility facade and MediaAsset v1 repository.
 *
 * There is intentionally one persisted gallery table. Existing callers can keep using
 * the GenMedia names while new call sites register stable assets through
 * [registerGeneratedAsset].
 */
class GenMediaRepository(
    private val dao: GenMediaDAO,
    private val filesRepository: FilesRepository? = null,
    private val metadataProbe: MediaAssetMetadataProbe = AndroidMediaAssetMetadataProbe,
    private val mediaReferenceBackfillScheduler: MediaReferenceBackfillScheduler =
        MediaReferenceBackfillScheduler {},
) : DurableAssetOwnership {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    fun getLibraryImages(): PagingSource<Int, MediaAssetEntity> = dao.getLibraryImages()

    fun getLibraryAttachments(): PagingSource<Int, MediaAssetEntity> = dao.getLibraryAttachments()

    fun getAllMediaIncludingHidden(): PagingSource<Int, GenMediaEntity> = dao.getAllIncludingHidden()

    suspend fun insertMedia(media: GenMediaEntity): Long = dao.insertOrGet(media).id.toLong()

    suspend fun getAsset(assetId: String): MediaAssetEntity? = dao.getByAssetId(assetId)

    suspend fun getAssetByPath(relativePath: String): MediaAssetEntity? = dao.getByPath(relativePath)

    override suspend fun isOwned(relativePath: String, managedFileId: Long?): Boolean {
        val asset = if (managedFileId == null) null else dao.getByManagedFileId(managedFileId)
        val resolved = asset ?: dao.getByPath(relativePath)
        return resolved != null && resolved.lifecycle != MediaAssetEntity.LIFECYCLE_DELETED
    }

    suspend fun getAssetsForToolCall(toolCallId: String): List<MediaAssetEntity> =
        dao.getByToolCallId(toolCallId)

    suspend fun getDirectVersions(parentAssetId: String): List<MediaAssetEntity> =
        dao.getDirectVersions(parentAssetId)

    suspend fun getReadyConversationIds(afterConversationId: String?, limit: Int): List<String> =
        dao.getReadyConversationIdsForMediaPage(afterConversationId, limit)

    suspend fun getAssetsRequiringRelocation(afterId: Int, limit: Int): List<MediaAssetEntity> =
        dao.getAssetsRequiringRelocation(afterId, limit)

    suspend fun registerAttachmentAsset(
        managedFile: ManagedFileEntity,
        file: File,
        registration: AttachmentMediaAssetRegistration,
    ): MediaAssetEntity {
        val committed = withContext(Dispatchers.IO) {
            require(managedFile.id > 0) { "A committed managed file is required" }
            MediaStableIds.requireValid(registration.assetId, "Media asset id")
            MediaStableIds.requireValid(managedFile.fileId, "Managed file id")
            require(registration.origin in MediaAssetOrigins.ATTACHMENTS) {
                "Unsupported attachment origin: ${registration.origin}"
            }
            val inspected = metadataProbe.inspect(file, managedFile.mimeType)
            val now = System.currentTimeMillis()
            val updatedManagedFile = managedFile.copy(
                mimeType = inspected.mimeType,
                sizeBytes = inspected.sizeBytes,
                updatedAt = now,
            )
            val draft = MediaAssetEntity(
                path = updatedManagedFile.relativePath,
                modelId = ATTACHMENT_MODEL_ID,
                prompt = "",
                createAt = registration.createdAt,
                type = MediaAssetEntity.TYPE_ATTACHMENT,
                assetId = registration.assetId,
                mediaKind = inspected.mimeType.toMediaKind(),
                displayName = updatedManagedFile.displayName,
                retentionPolicy = MediaAssetEntity.RETENTION_LIBRARY,
                managedFileId = updatedManagedFile.id,
                origin = registration.origin,
                mimeType = inspected.mimeType,
                sizeBytes = inspected.sizeBytes,
                width = inspected.width,
                height = inspected.height,
                sha256 = inspected.sha256,
                storageState = inspected.storageState,
                conversationId = registration.conversationId,
                messageNodeId = registration.messageNodeId,
                toolCallId = registration.toolCallId,
                updatedAt = now,
            )
            val existing = dao.getByAssetId(registration.assetId)
            if (existing != null && existing.path != draft.path) {
                dao.relocateAssetGraph(
                    buildGraphWrite(
                        managedFile = updatedManagedFile,
                        asset = draft.copy(id = existing.id, createAt = existing.createAt),
                        includeMigrationJournal = true,
                        expectedAssetUpdatedAt = existing.updatedAt,
                    ),
                )
            } else {
                dao.registerAssetGraph(
                    buildGraphWrite(
                        managedFile = updatedManagedFile,
                        asset = draft,
                        includeMigrationJournal = true,
                    ),
                )
            }
        }
        mediaReferenceBackfillScheduler.requestBackfill()
        return committed
    }

    suspend fun relocateAsset(
        asset: MediaAssetEntity,
        managedFile: ManagedFileEntity,
        file: File,
    ): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(managedFile.id > 0) { "A committed managed file is required" }
        require(asset.lifecycle != MediaAssetEntity.LIFECYCLE_DELETED) { "Deleted asset cannot be relocated" }
        val inspected = metadataProbe.inspect(file, managedFile.mimeType)
        val now = maxOf(System.currentTimeMillis(), asset.updatedAt + 1)
        val updatedManagedFile = managedFile.copy(
            mimeType = inspected.mimeType,
            sizeBytes = inspected.sizeBytes,
            updatedAt = now,
        )
        dao.relocateAssetGraph(
            buildGraphWrite(
                managedFile = updatedManagedFile,
                asset = asset.copy(
                    path = updatedManagedFile.relativePath,
                    displayName = updatedManagedFile.displayName,
                    managedFileId = updatedManagedFile.id,
                    mimeType = inspected.mimeType,
                    sizeBytes = inspected.sizeBytes,
                    width = inspected.width,
                    height = inspected.height,
                    sha256 = inspected.sha256,
                    storageState = inspected.storageState,
                    updatedAt = now,
                ),
                includeMigrationJournal = true,
                expectedAssetUpdatedAt = asset.updatedAt,
            ),
        )
    }

    /**
     * Registers a generated or edited image exactly once. [managedFile] is the file
     * identity and [registration.assetId] is the logical identity used by messages,
     * version chains and future sync.
     */
    suspend fun registerGeneratedAsset(
        managedFile: ManagedFileEntity,
        file: File,
        registration: GeneratedMediaAssetRegistration,
    ): MediaAssetEntity {
        val committed = withContext(Dispatchers.IO) {
            require(managedFile.id > 0) { "A committed managed file is required" }
            require(managedFile.relativePath.isNotBlank()) { "Managed file path cannot be blank" }
            require(file.name == managedFile.relativePath.substringAfterLast('/')) {
                "Managed file identity does not match the inspected file"
            }
            MediaStableIds.requireValid(registration.assetId, "Media asset id")
            MediaStableIds.requireValid(managedFile.fileId, "Managed file id")
            require(registration.modelId.isNotBlank()) { "Generated media model id cannot be blank" }
            require(registration.origin in MediaAssetOrigins.ALL) {
                "Unsupported media origin: ${registration.origin}"
            }
            registration.parentAssetId?.let { parentId ->
                require(dao.getByAssetId(parentId) != null) {
                    "Parent media asset does not exist: $parentId"
                }
            }

            val inspected = metadataProbe.inspect(file, managedFile.mimeType)
            val storageState = inspected.storageState
            val now = System.currentTimeMillis()
            val updatedManagedFile = managedFile.copy(
                mimeType = inspected.mimeType,
                sizeBytes = inspected.sizeBytes,
                updatedAt = now,
            )
            val compatibilitySourcePaths = (
                registration.referenceInputs.mapNotNull(MediaAssetReferenceInput::sourcePath) +
                    registration.sourcePaths
                ).filter(String::isNotBlank).distinct()
            val asset = MediaAssetEntity(
                path = managedFile.relativePath,
                modelId = registration.modelId,
                modelDisplayName = registration.modelDisplayName,
                providerId = registration.providerId,
                prompt = registration.prompt,
                createAt = registration.createdAt,
                type = if (registration.origin == MediaAssetEntity.ORIGIN_AI_EDITED) {
                    MediaAssetEntity.TYPE_IMAGE_EDIT
                } else {
                    MediaAssetEntity.TYPE_IMAGE_GENERATION
                },
                sourcePaths = compatibilitySourcePaths.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                assetId = registration.assetId,
                displayName = updatedManagedFile.displayName,
                managedFileId = updatedManagedFile.id,
                origin = registration.origin,
                mimeType = inspected.mimeType,
                sizeBytes = inspected.sizeBytes,
                width = inspected.width,
                height = inspected.height,
                sha256 = inspected.sha256,
                storageState = storageState,
                conversationId = registration.conversationId,
                messageNodeId = registration.messageNodeId,
                toolCallId = registration.toolCallId,
                parentAssetId = registration.parentAssetId,
                updatedAt = now,
            )
            val registered = dao.registerAssetGraph(
                buildGraphWrite(
                    managedFile = updatedManagedFile,
                    asset = asset,
                    includeMigrationJournal = inspected.sha256 == null,
                    referenceInputs = registration.referenceInputs,
                ),
            )
            if (registered.modelId == LEGACY_CHAT_MODEL_ID && registered.prompt.isBlank() &&
                registration.modelId != LEGACY_CHAT_MODEL_ID
            ) {
                val upgradedAt = maxOf(now, registered.updatedAt + 1)
                val upgraded = dao.upgradeLegacyChatPlaceholder(
                    id = registered.id,
                    assetId = registered.assetId,
                    modelId = asset.modelId,
                    modelDisplayName = asset.modelDisplayName,
                    providerId = asset.providerId,
                    prompt = asset.prompt,
                    type = asset.type,
                    sourcePaths = asset.sourcePaths,
                    origin = asset.origin,
                    conversationId = asset.conversationId,
                    messageNodeId = asset.messageNodeId,
                    toolCallId = asset.toolCallId,
                    parentAssetId = asset.parentAssetId,
                    updatedAt = upgradedAt,
                    expectedUpdatedAt = registered.updatedAt,
                )
                val current = requireNotNull(dao.getByAssetId(registered.assetId))
                check(upgraded == 1 || current.modelId != LEGACY_CHAT_MODEL_ID || current.prompt.isNotBlank()) {
                    "Legacy chat media placeholder upgrade lost a concurrent write: ${registered.assetId}"
                }
                current
            } else {
                registered
            }
        }
        mediaReferenceBackfillScheduler.requestBackfill()
        return committed
    }

    suspend fun hideAsset(assetId: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.hide(assetId, now) > 0

    suspend fun restoreAsset(assetId: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.restore(assetId, now) > 0

    /**
     * Hydrates legacy v25 rows after Room migration. The migration itself never touches
     * image bytes; this pass records file size, dimensions and digest and explicitly
     * marks missing/corrupt files instead of silently dropping gallery history.
     */
    suspend fun reconcileLocalMetadata(
        resolveFile: (relativePath: String) -> File,
        limit: Int = 256,
    ): MediaAssetReconciliationResult {
        val result = withContext(Dispatchers.IO) {
            require(limit in 1..2_048) { "Reconciliation limit must be between 1 and 2048" }
            val failures = mutableListOf<String>()
            var repaired = 0
            var missing = 0
            val candidates = dao.getAssetsNeedingMetadata(limit)
            candidates.forEach { asset ->
                runCatching {
                    val file = resolveFile(asset.path)
                    val inspected = metadataProbe.inspect(file, asset.mimeType)
                    val now = maxOf(System.currentTimeMillis(), asset.updatedAt + 1)
                    val managedFile = ensureManagedFile(asset, file, inspected, now)
                    val reconciled = asset.copy(
                        displayName = managedFile?.displayName ?: asset.displayName,
                        managedFileId = managedFile?.id ?: asset.managedFileId,
                        mimeType = inspected.mimeType,
                        sizeBytes = inspected.sizeBytes,
                        width = inspected.width,
                        height = inspected.height,
                        sha256 = inspected.sha256,
                        storageState = inspected.storageState,
                        metadataVersion = MediaAssetEntity.METADATA_VERSION,
                        updatedAt = now,
                    )
                    dao.reconcileAssetGraph(
                        buildGraphWrite(
                            managedFile = managedFile,
                            asset = reconciled,
                            includeMigrationJournal = true,
                            expectedAssetUpdatedAt = asset.updatedAt,
                        ),
                    )
                    if (inspected.storageState == MediaAssetEntity.STORAGE_MISSING) {
                        missing++
                    } else if (inspected.storageState == MediaAssetEntity.STORAGE_AVAILABLE) {
                        repaired++
                    }
                }.onFailure { error ->
                    failures += "${asset.assetId}: ${error.message ?: error::class.java.simpleName}"
                }
            }
            MediaAssetReconciliationResult(
                inspected = candidates.size,
                repaired = repaired,
                missing = missing,
                failures = failures,
            )
        }
        if (result.inspected > 0) mediaReferenceBackfillScheduler.requestBackfill()
        return result
    }

    /**
     * Recovers managed chat-generated files whose file commit outlived a failed Room
     * registration. UUID file names retain the pre-reserved paid-request identity;
     * older random managed files receive a deterministic legacy identity.
     *
     * Keyset pagination is deliberate: a permanently damaged early file must not keep
     * later paid outputs outside a fixed recovery window. Existing legacy placeholders
     * are only upgraded when the durable task store supplies matching authoritative
     * metadata; recovery never guesses lineage or replays a provider request.
     */
    suspend fun reconcileUnregisteredGeneratedFiles(
        folder: String,
        resolveFile: (relativePath: String) -> File,
        registrationsByAssetId: Map<String, GeneratedMediaAssetRegistration> = emptyMap(),
        limit: Int = 256,
    ): MediaAssetReconciliationResult = withContext(Dispatchers.IO) {
        require(limit in 1..2_048) { "Reconciliation limit must be between 1 and 2048" }
        val failures = mutableListOf<String>()
        var inspected = 0
        var registered = 0
        var missing = 0
        suspend fun reconcileCandidates(loadPage: suspend (afterId: Long) -> List<ManagedFileEntity>) {
            var afterId = 0L
            while (true) {
                val candidates = loadPage(afterId)
                if (candidates.isEmpty()) break
                candidates.forEach { managedFile ->
                    inspected++
                    runCatching {
                        val recoveredAssetId = managedFile.recoverableAssetId()
                        val durableRegistration = registrationsByAssetId[recoveredAssetId]?.also { registration ->
                            require(registration.assetId == recoveredAssetId) {
                                "Durable media registration identity does not match its recovery key"
                            }
                        }
                        val existing = dao.getByPath(managedFile.relativePath)
                        if (existing != null) {
                            val upgradeable = existing.modelId == LEGACY_CHAT_MODEL_ID &&
                                existing.prompt.isBlank() && existing.assetId == recoveredAssetId
                            if (!upgradeable || durableRegistration == null) {
                                if (existing.storageState == MediaAssetEntity.STORAGE_MISSING) missing++
                                return@runCatching
                            }
                        }
                        val file = resolveFile(managedFile.relativePath)
                        val asset = registerGeneratedAsset(
                            managedFile = managedFile,
                            file = file,
                            registration = durableRegistration
                                ?: GeneratedMediaAssetRegistration(
                                    assetId = recoveredAssetId,
                                    modelId = LEGACY_CHAT_MODEL_ID,
                                    prompt = "",
                                    createdAt = managedFile.createdAt,
                                ),
                        )
                        registered++
                        if (asset.storageState == MediaAssetEntity.STORAGE_MISSING) missing++
                    }.onFailure { error ->
                        failures += "${managedFile.relativePath}: ${error.message ?: error::class.java.simpleName}"
                    }
                }
                afterId = candidates.last().id
                if (candidates.size < limit) break
            }
        }

        reconcileCandidates { afterId ->
            dao.getUnregisteredGeneratedRecoveryCandidates(
                folder = folder,
                afterId = afterId,
                limit = limit,
            )
        }
        registrationsByAssetId.keys.chunked(RECOVERY_ASSET_ID_QUERY_CHUNK).forEach { assetIds ->
            reconcileCandidates { afterId ->
                dao.getUpgradeableGeneratedRecoveryCandidates(
                    folder = folder,
                    afterId = afterId,
                    legacyModelId = LEGACY_CHAT_MODEL_ID,
                    assetIds = assetIds,
                    limit = limit,
                )
            }
        }
        MediaAssetReconciliationResult(
            inspected = inspected,
            registered = registered,
            missing = missing,
            failures = failures,
        )
    }

    suspend fun deleteMedia(id: Int) = dao.delete(id)

    private suspend fun ensureManagedFile(
        asset: MediaAssetEntity,
        file: File,
        metadata: MediaAssetFileMetadata,
        now: Long,
    ): ManagedFileEntity? {
        val repository = filesRepository ?: return null
        val existing = asset.managedFileId?.let { managedFileId -> repository.getById(managedFileId) }
            ?: repository.getByPath(asset.path)
        if (existing != null) {
            return existing.copy(
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes,
                updatedAt = now,
            )
        }
        if (!file.isFile) return null
        val folder = asset.path.substringBefore('/', missingDelimiterValue = "images")
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = asset.path,
                displayName = asset.path.substringAfterLast('/'),
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes,
                createdAt = asset.createAt,
                updatedAt = now,
            ),
        )
    }

    private suspend fun buildGraphWrite(
        managedFile: ManagedFileEntity?,
        asset: MediaAssetEntity,
        includeMigrationJournal: Boolean,
        referenceInputs: List<MediaAssetReferenceInput> = emptyList(),
        expectedAssetUpdatedAt: Long? = null,
    ): MediaAssetGraphWrite {
        val verifiedBlobId = MediaStableIds.blobIdForSha256(asset.sha256)
        val verifiedSha256 = verifiedBlobId?.substringAfter("sha256:")
        val existingUnknownBlobId = if (verifiedBlobId == null) {
            dao.getAssetBlob(asset.assetId, MediaV2Values.BLOB_ROLE_ORIGINAL)?.blobId
        } else {
            null
        }
        val blobId = verifiedBlobId
            ?: existingUnknownBlobId
            ?: MediaStableIds.derived("media-blob", asset.assetId)
        val blobState = asset.storageState.toBlobState()
        val blob = MediaBlobEntity(
            blobId = blobId,
            sha256 = verifiedSha256,
            mimeType = asset.mimeType,
            sizeBytes = asset.sizeBytes,
            width = asset.width,
            height = asset.height,
            storageState = blobState,
            createdAt = asset.createAt,
            verifiedAt = asset.updatedAt.takeIf {
                verifiedSha256 != null && blobState == MediaV2Values.BLOB_AVAILABLE
            },
        )
        val assetBlob = MediaAssetBlobEntity(
            assetId = asset.assetId,
            blobId = blobId,
            role = MediaV2Values.BLOB_ROLE_ORIGINAL,
            createdAt = asset.updatedAt,
        )
        val replica = managedFile?.let { file ->
            MediaReplicaEntity(
                replicaId = MediaStableIds.derived("media-replica", file.fileId),
                blobId = blobId,
                kind = MediaV2Values.REPLICA_LOCAL_MANAGED,
                managedFileId = file.fileId,
                state = blobState,
                verifiedAt = asset.updatedAt.takeIf {
                    verifiedSha256 != null && blobState == MediaV2Values.BLOB_AVAILABLE
                },
                createdAt = file.createdAt,
                updatedAt = file.updatedAt,
            )
        }

        val relations = mutableListOf<MediaRelationEntity>()
        asset.parentAssetId
            ?.takeIf { parentId -> parentId != asset.assetId && dao.getByAssetId(parentId) != null }
            ?.let { parentId ->
                relations += relation(
                    asset = asset,
                    relatedAssetId = parentId,
                    kind = MediaV2Values.RELATION_EDIT_OF,
                    ordinal = 0,
                )
            }
        val lineageInputs = referenceInputs.ifEmpty {
            asset.sourcePaths
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map { sourcePath -> MediaAssetReferenceInput(sourcePath = sourcePath) }
                ?.toList()
                .orEmpty()
        }
        val relatedAssets = mutableSetOf<String>()
        lineageInputs.forEachIndexed { ordinal, input ->
            val sourceAssetId = input.assetId
                ?.let { candidate -> dao.getByAssetId(candidate)?.assetId }
                ?: input.sourcePath?.let { sourcePath -> dao.getByPath(sourcePath)?.assetId }
            if (
                sourceAssetId != null &&
                sourceAssetId != asset.assetId &&
                relatedAssets.add(sourceAssetId)
            ) {
                relations += relation(
                    asset = asset,
                    relatedAssetId = sourceAssetId,
                    kind = MediaV2Values.RELATION_REFERENCE_INPUT,
                    ordinal = ordinal,
                )
            }
        }
        val reference = asset.toLegacyMessageReference()
        val journals = buildList {
            if (includeMigrationJournal) {
                add(
                    migrationJournal(
                        scopeKind = "asset",
                        scopeKey = asset.assetId,
                        stage = MediaV2Values.STAGE_BLOB_BACKFILL,
                        state = if (verifiedSha256 == null) {
                            MediaV2Values.JOURNAL_PENDING
                        } else {
                            MediaV2Values.JOURNAL_COMPLETE
                        },
                        detail = if (verifiedSha256 == null) "sha256_verification_required" else null,
                        updatedAt = asset.updatedAt,
                    ),
                )
                managedFile?.let { file ->
                    val relocationComplete = file.folder in setOf(
                        FileFolders.CHAT_GENERATED_IMAGES,
                        FileFolders.LEGACY_GENERATED_IMAGES,
                        FileFolders.LIBRARY_ATTACHMENTS,
                    )
                    add(
                        migrationJournal(
                            scopeKind = "file",
                            scopeKey = file.fileId,
                            stage = MediaV2Values.STAGE_FILE_RELOCATION,
                            state = if (relocationComplete) {
                                MediaV2Values.JOURNAL_COMPLETE
                            } else {
                                MediaV2Values.JOURNAL_PENDING
                            },
                            detail = if (relocationComplete) null else "relocation_required",
                            updatedAt = asset.updatedAt,
                        ),
                    )
                }
            }
            add(
                migrationJournal(
                    scopeKind = "asset",
                    scopeKey = asset.assetId,
                    stage = MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    state = if (reference?.hasConcreteMessageOwner() == true) {
                        MediaV2Values.JOURNAL_COMPLETE
                    } else {
                        MediaV2Values.JOURNAL_PENDING
                    },
                    detail = if (reference?.hasConcreteMessageOwner() == true) {
                        null
                    } else {
                        "message_scan_required"
                    },
                    updatedAt = asset.updatedAt,
                ),
            )
        }
        return MediaAssetGraphWrite(
            managedFile = managedFile,
            asset = asset,
            blob = blob,
            assetBlob = assetBlob,
            replica = replica,
            relations = relations,
            reference = reference,
            journals = journals,
            expectedAssetUpdatedAt = expectedAssetUpdatedAt,
        )
    }

    private fun relation(
        asset: MediaAssetEntity,
        relatedAssetId: String,
        kind: String,
        ordinal: Int,
    ) = MediaRelationEntity(
        relationId = MediaStableIds.derived(
            "media-relation",
            asset.assetId,
            relatedAssetId,
            kind,
            ordinal.toString(),
        ),
        assetId = asset.assetId,
        relatedAssetId = relatedAssetId,
        relationKind = kind,
        ordinal = ordinal,
        createdAt = asset.updatedAt,
    )

    private fun MediaAssetEntity.toLegacyMessageReference(): MessageMediaRefEntity? {
        if (conversationId == null && messageNodeId == null && toolCallId == null) return null
        return MessageMediaRefEntity(
            refId = MediaStableIds.derived("media-ref", assetId, "legacy-context"),
            ownerKey = buildString {
                append("legacy-v1|")
                append(conversationId.orEmpty())
                append('|')
                append(messageNodeId.orEmpty())
                append('|')
                append(toolCallId.orEmpty())
            },
            assetId = assetId,
            conversationId = conversationId,
            messageNodeId = messageNodeId,
            toolCallId = toolCallId,
            createdAt = updatedAt,
        )
    }

    private fun MessageMediaRefEntity.hasConcreteMessageOwner(): Boolean =
        !messageId.isNullOrBlank() && !partId.isNullOrBlank()

    private fun migrationJournal(
        scopeKind: String,
        scopeKey: String,
        stage: String,
        state: String,
        detail: String?,
        updatedAt: Long,
    ) = MediaMigrationJournalEntity(
        journalId = MediaStableIds.derived("media-journal", scopeKind, scopeKey, stage),
        scopeKind = scopeKind,
        scopeKey = scopeKey,
        stage = stage,
        state = state,
        detail = detail,
        updatedAt = updatedAt,
    )
}

data class GeneratedMediaAssetRegistration(
    /** Must be created before provider/file work and persisted by the owning task. */
    val assetId: String,
    val origin: String = MediaAssetEntity.ORIGIN_AI_GENERATED,
    val modelId: String,
    val modelDisplayName: String? = null,
    val providerId: String? = null,
    val prompt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
    val messageNodeId: String? = null,
    val toolCallId: String? = null,
    val parentAssetId: String? = null,
    val sourcePaths: List<String> = emptyList(),
    val referenceInputs: List<MediaAssetReferenceInput> = emptyList(),
)

data class AttachmentMediaAssetRegistration(
    val assetId: String,
    val origin: String,
    val createdAt: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
    val messageNodeId: String? = null,
    val toolCallId: String? = null,
)

data class MediaAssetReferenceInput(
    val assetId: String? = null,
    val sourcePath: String? = null,
) {
    init {
        require(!assetId.isNullOrBlank() || !sourcePath.isNullOrBlank()) {
            "A media reference input requires an asset id or managed source path"
        }
    }
}

object MediaAssetIds {
    /** Deterministic across process recovery without coupling identity to a mutable path. */
    fun forChatToolOutput(toolCallId: String, outputIndex: Int): String {
        require(toolCallId.isNotBlank()) { "Tool call id cannot be blank" }
        require(outputIndex >= 0) { "Output index cannot be negative" }
        return UUID.nameUUIDFromBytes(
            "chat-image:$toolCallId:$outputIndex".encodeToByteArray(),
        ).toString()
    }

    fun forMessagePart(messageId: String, nestedLocation: String): String {
        require(messageId.isNotBlank()) { "Message id cannot be blank" }
        require(nestedLocation.isNotBlank()) { "Message part location cannot be blank" }
        return UUID.nameUUIDFromBytes(
            "message-asset:$messageId:$nestedLocation".encodeToByteArray(),
        ).toString()
    }
}

object MediaAssetOrigins {
    val ALL = setOf(
        MediaAssetEntity.ORIGIN_AI_GENERATED,
        MediaAssetEntity.ORIGIN_AI_EDITED,
    )

    val ATTACHMENTS = setOf(
        MediaAssetEntity.ORIGIN_USER_ATTACHMENT,
        MediaAssetEntity.ORIGIN_ASSISTANT_ATTACHMENT,
        MediaAssetEntity.ORIGIN_TOOL_OUTPUT,
    )
}

data class MediaAssetFileMetadata(
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val sha256: String?,
    val storageState: String,
)

data class MediaAssetReconciliationResult(
    val inspected: Int = 0,
    val registered: Int = 0,
    val repaired: Int = 0,
    val missing: Int = 0,
    val failures: List<String> = emptyList(),
)

fun interface MediaAssetMetadataProbe {
    fun inspect(file: File, declaredMimeType: String): MediaAssetFileMetadata
}

private object AndroidMediaAssetMetadataProbe : MediaAssetMetadataProbe {
    override fun inspect(file: File, declaredMimeType: String): MediaAssetFileMetadata {
        val mimeType = declaredMimeType.takeUnless { it.isBlank() || it == "application/octet-stream" }
            ?: inferImageMimeType(file.name)
        if (!file.isFile) {
            return MediaAssetFileMetadata(
                mimeType = mimeType,
                sizeBytes = 0,
                width = null,
                height = null,
                sha256 = null,
                storageState = MediaAssetEntity.STORAGE_MISSING,
            )
        }

        val isImage = mimeType.startsWith("image/")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (isImage) BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth.takeIf { it > 0 }
        val height = bounds.outHeight.takeIf { it > 0 }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return MediaAssetFileMetadata(
            mimeType = mimeType,
            sizeBytes = file.length(),
            width = width,
            height = height,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            storageState = if (!isImage || width != null && height != null) {
                MediaAssetEntity.STORAGE_AVAILABLE
            } else {
                MediaAssetEntity.STORAGE_CORRUPT
            },
        )
    }
}

private fun inferImageMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

private fun String.toBlobState(): String = when (this) {
    MediaAssetEntity.STORAGE_AVAILABLE -> MediaV2Values.BLOB_AVAILABLE
    MediaAssetEntity.STORAGE_MISSING -> MediaV2Values.BLOB_MISSING
    MediaAssetEntity.STORAGE_CORRUPT -> MediaV2Values.BLOB_CORRUPT
    else -> MediaV2Values.BLOB_STAGING
}

private fun ManagedFileEntity.recoverableAssetId(): String {
    val stem = relativePath.substringAfterLast('/').substringBeforeLast('.')
    val canonicalUuid = runCatching { UUID.fromString(stem).toString() }.getOrNull()
    return canonicalUuid?.takeIf { it == stem } ?: "legacy-chat-file-$id"
}

private const val LEGACY_CHAT_MODEL_ID = "legacy-chat-image"
private const val ATTACHMENT_MODEL_ID = "local-attachment"
private const val RECOVERY_ASSET_ID_QUERY_CHUNK = 400

private fun String.toMediaKind(): String = when {
    startsWith("image/") -> MediaAssetEntity.MEDIA_KIND_IMAGE
    startsWith("video/") -> MediaAssetEntity.MEDIA_KIND_VIDEO
    startsWith("audio/") -> MediaAssetEntity.MEDIA_KIND_AUDIO
    startsWith("text/") || this == "application/pdf" -> MediaAssetEntity.MEDIA_KIND_DOCUMENT
    else -> MediaAssetEntity.MEDIA_KIND_FILE
}

/** Preferred name for new call sites; retained as an alias to avoid a second repository binding. */
typealias MediaAssetRepository = GenMediaRepository

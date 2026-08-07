package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetBlobEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.entity.MediaBlobEntity
import me.rerere.rikkahub.data.db.entity.MediaMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.MediaRelationEntity
import me.rerere.rikkahub.data.db.entity.MediaReplicaEntity
import me.rerere.rikkahub.data.db.entity.MessageMediaRefEntity
import me.rerere.pale.media.MediaStableIds

@Dao
interface GenMediaDAO : ConversationMediaReferenceDAO {
    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE visibility = 'visible' ORDER BY create_at DESC",
    )
    fun getAll(): PagingSource<Int, MediaAssetEntity>

    @Query(
        "SELECT * FROM GenMediaEntity WHERE visibility = 'visible' " +
            "AND type IN ('image_generation', 'image_edit') ORDER BY create_at DESC",
    )
    fun getLibraryImages(): PagingSource<Int, MediaAssetEntity>

    @Query(
        "SELECT * FROM GenMediaEntity WHERE visibility = 'visible' " +
            "AND type = 'attachment' ORDER BY create_at DESC",
    )
    fun getLibraryAttachments(): PagingSource<Int, MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity ORDER BY create_at DESC")
    fun getAllIncludingHidden(): PagingSource<Int, MediaAssetEntity>

    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE visibility = 'visible' ORDER BY create_at DESC",
    )
    suspend fun getAllMedia(): List<MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity ORDER BY create_at DESC")
    suspend fun getAllMediaIncludingHidden(): List<MediaAssetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(media: MediaAssetEntity): Long

    @Update
    suspend fun updateRow(media: MediaAssetEntity): Int

    @Query("SELECT * FROM GenMediaEntity WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): MediaAssetEntity?

    @Transaction
    suspend fun update(media: MediaAssetEntity): Int {
        val committed = getById(media.id) ?: return 0
        require(committed.assetId == media.assetId) {
            "Media asset row ${media.id} cannot change identity from ${committed.assetId} to ${media.assetId}"
        }
        return updateRow(media)
    }

    @Query(
        "UPDATE GenMediaEntity SET display_name = :displayName, managed_file_id = :managedFileId, " +
            "mime_type = :mimeType, size_bytes = :sizeBytes, width = :width, height = :height, " +
            "sha256 = :sha256, storage_state = :storageState, metadata_version = :metadataVersion, " +
            "updated_at = :updatedAt WHERE id = :id AND asset_id = :assetId " +
            "AND updated_at = :expectedUpdatedAt",
    )
    suspend fun updateReconciledMetadata(
        id: Int,
        assetId: String,
        displayName: String,
        managedFileId: Long?,
        mimeType: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
        sha256: String?,
        storageState: String,
        metadataVersion: Int,
        updatedAt: Long,
        expectedUpdatedAt: Long,
    ): Int

    @Query(
        "UPDATE GenMediaEntity SET path = :path, display_name = :displayName, " +
            "managed_file_id = :managedFileId, mime_type = :mimeType, size_bytes = :sizeBytes, " +
            "width = :width, height = :height, sha256 = :sha256, storage_state = :storageState, " +
            "metadata_version = :metadataVersion, updated_at = :updatedAt " +
            "WHERE id = :id AND asset_id = :assetId AND updated_at = :expectedUpdatedAt",
    )
    suspend fun updateRelocatedAsset(
        id: Int,
        assetId: String,
        path: String,
        displayName: String,
        managedFileId: Long,
        mimeType: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
        sha256: String?,
        storageState: String,
        metadataVersion: Int,
        updatedAt: Long,
        expectedUpdatedAt: Long,
    ): Int

    @Query("SELECT * FROM GenMediaEntity WHERE asset_id = :assetId LIMIT 1")
    suspend fun getByAssetId(assetId: String): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE managed_file_id = :managedFileId LIMIT 1")
    suspend fun getByManagedFileId(managedFileId: Long): MediaAssetEntity?

    @Query("SELECT * FROM GenMediaEntity WHERE tool_call_id = :toolCallId ORDER BY create_at ASC, id ASC")
    suspend fun getByToolCallId(toolCallId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM GenMediaEntity WHERE parent_asset_id = :parentAssetId ORDER BY create_at ASC, id ASC")
    suspend fun getDirectVersions(parentAssetId: String): List<MediaAssetEntity>

    @Query(
        "SELECT * FROM GenMediaEntity WHERE id > :afterId AND lifecycle != 'deleted' " +
            "AND (path LIKE 'upload/%' OR path LIKE 'tool_outputs/%') " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun getAssetsRequiringRelocation(afterId: Int, limit: Int): List<MediaAssetEntity>

    @Query(
        "SELECT managed.* FROM managed_files AS managed " +
            "LEFT JOIN GenMediaEntity AS media ON media.path = managed.relative_path " +
            "WHERE managed.folder = :folder AND managed.id > :afterId AND media.id IS NULL " +
            "ORDER BY managed.id ASC LIMIT :limit",
    )
    suspend fun getUnregisteredGeneratedRecoveryCandidates(
        folder: String,
        afterId: Long,
        limit: Int,
    ): List<ManagedFileEntity>

    @Query(
        "SELECT managed.* FROM managed_files AS managed " +
            "INNER JOIN GenMediaEntity AS media ON media.path = managed.relative_path " +
            "WHERE managed.folder = :folder AND managed.id > :afterId " +
            "AND media.model_id = :legacyModelId AND media.prompt = '' " +
            "AND media.asset_id IN (:assetIds) ORDER BY managed.id ASC LIMIT :limit",
    )
    suspend fun getUpgradeableGeneratedRecoveryCandidates(
        folder: String,
        afterId: Long,
        legacyModelId: String,
        assetIds: List<String>,
        limit: Int,
    ): List<ManagedFileEntity>

    @Query(
        "SELECT * FROM GenMediaEntity " +
            "WHERE storage_state IN ('needs_metadata', 'missing', 'corrupt') " +
            "OR sha256 IS NULL OR (media_kind = 'image' AND (width IS NULL OR height IS NULL)) " +
            "ORDER BY CASE WHEN storage_state = 'needs_metadata' THEN 0 ELSE 1 END, " +
            "updated_at ASC LIMIT :limit",
    )
    suspend fun getAssetsNeedingMetadata(limit: Int): List<MediaAssetEntity>

    @Query(
        "UPDATE GenMediaEntity SET visibility = 'hidden', hidden_at = :hiddenAt, " +
            "updated_at = :hiddenAt WHERE asset_id = :assetId",
    )
    suspend fun hide(assetId: String, hiddenAt: Long): Int

    @Query(
        "UPDATE GenMediaEntity SET visibility = 'visible', hidden_at = NULL, " +
            "updated_at = :restoredAt WHERE asset_id = :assetId",
    )
    suspend fun restore(assetId: String, restoredAt: Long): Int

    @Query(
        "UPDATE GenMediaEntity SET model_id = :modelId, model_display_name = :modelDisplayName, " +
            "provider_id = :providerId, prompt = :prompt, type = :type, source_paths = :sourcePaths, " +
            "origin = :origin, conversation_id = :conversationId, message_node_id = :messageNodeId, " +
            "tool_call_id = :toolCallId, parent_asset_id = :parentAssetId, updated_at = :updatedAt " +
            "WHERE id = :id AND asset_id = :assetId AND model_id = 'legacy-chat-image' " +
            "AND prompt = '' AND updated_at = :expectedUpdatedAt",
    )
    suspend fun upgradeLegacyChatPlaceholder(
        id: Int,
        assetId: String,
        modelId: String,
        modelDisplayName: String?,
        providerId: String?,
        prompt: String,
        type: String,
        sourcePaths: String?,
        origin: String,
        conversationId: String?,
        messageNodeId: String?,
        toolCallId: String?,
        parentAssetId: String?,
        updatedAt: Long,
        expectedUpdatedAt: Long,
    ): Int

    /**
     * Registers a file exactly once. The unique path index is the cross-process
     * authority; the transaction turns a concurrent/replayed registration into
     * the already committed row instead of a second gallery item.
     */
    @Transaction
    suspend fun insertOrGet(media: MediaAssetEntity): MediaAssetEntity {
        MediaStableIds.requireValid(media.assetId, "Media asset id")
        val insertedId = insertIgnore(media)
        if (insertedId != -1L) return media.copy(id = insertedId.toInt())

        getByAssetId(media.assetId)?.let { committed ->
            require(committed.path == media.path) {
                "Media asset identity ${media.assetId} is already bound to ${committed.path}"
            }
            return committed
        }
        media.managedFileId?.let { managedFileId ->
            getByManagedFileId(managedFileId)?.let { committed ->
                require(committed.path == media.path) {
                    "Managed file $managedFileId is already bound to ${committed.path}"
                }
                return committed
            }
        }
        return requireNotNull(getByPath(media.path)) {
            "Media asset conflict was reported but no committed row exists: ${media.path}"
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlobIgnore(blob: MediaBlobEntity): Long

    @Query("SELECT * FROM media_blob WHERE blob_id = :blobId LIMIT 1")
    suspend fun getBlob(blobId: String): MediaBlobEntity?

    @Query("SELECT * FROM media_blob WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBlobBySha256(sha256: String): MediaBlobEntity?

    @Query(
        "UPDATE media_blob SET " +
            "mime_type = CASE WHEN mime_type = 'application/octet-stream' THEN :mimeType ELSE mime_type END, " +
            "size_bytes = CASE WHEN size_bytes = 0 THEN :sizeBytes ELSE size_bytes END, " +
            "width = COALESCE(width, :width), height = COALESCE(height, :height), " +
            "duration_ms = COALESCE(duration_ms, :durationMs), " +
            "storage_state = CASE " +
            "WHEN :storageState = 'available' THEN 'available' " +
            "WHEN storage_state = 'available' THEN storage_state ELSE :storageState END, " +
            "verified_at = COALESCE(:verifiedAt, verified_at) " +
            "WHERE blob_id = :blobId AND " +
            "((sha256 IS NULL AND :sha256 IS NULL) OR sha256 = :sha256)",
    )
    suspend fun updateBlobObservation(
        blobId: String,
        sha256: String?,
        mimeType: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        storageState: String,
        verifiedAt: Long?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssetBlobIgnore(link: MediaAssetBlobEntity): Long

    @Query("SELECT * FROM media_asset_blob WHERE asset_id = :assetId AND role = :role LIMIT 1")
    suspend fun getAssetBlob(assetId: String, role: String): MediaAssetBlobEntity?

    @Query(
        "UPDATE media_replica SET blob_id = :newBlobId, updated_at = :updatedAt " +
            "WHERE blob_id = :oldBlobId",
    )
    suspend fun moveReplicasToVerifiedBlob(
        oldBlobId: String,
        newBlobId: String,
        updatedAt: Long,
    ): Int

    @Query(
        "DELETE FROM media_asset_blob WHERE asset_id = :assetId " +
            "AND role = :role AND blob_id = :blobId",
    )
    suspend fun deleteAssetBlob(assetId: String, role: String, blobId: String): Int

    @Query(
        "DELETE FROM media_blob WHERE blob_id = :blobId " +
            "AND NOT EXISTS (SELECT 1 FROM media_asset_blob WHERE blob_id = :blobId) " +
            "AND NOT EXISTS (SELECT 1 FROM media_replica WHERE blob_id = :blobId)",
    )
    suspend fun deleteUnreferencedBlob(blobId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReplicaIgnore(replica: MediaReplicaEntity): Long

    @Query("SELECT * FROM media_replica WHERE replica_id = :replicaId LIMIT 1")
    suspend fun getReplica(replicaId: String): MediaReplicaEntity?

    @Query("SELECT * FROM media_replica WHERE managed_file_id = :managedFileId LIMIT 1")
    suspend fun getReplicaByManagedFileId(managedFileId: String): MediaReplicaEntity?

    @Query(
        "UPDATE media_replica SET etag = :etag, state = :state, encrypted = :encrypted, " +
            "verified_at = :verifiedAt, updated_at = :updatedAt WHERE replica_id = :replicaId",
    )
    suspend fun updateReplicaObservation(
        replicaId: String,
        etag: String?,
        state: String,
        encrypted: Boolean,
        verifiedAt: Long?,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRelationIgnore(relation: MediaRelationEntity): Long

    @Query(
        "SELECT * FROM media_relation WHERE asset_id = :assetId " +
            "AND relation_kind = :relationKind AND ordinal = :ordinal LIMIT 1",
    )
    suspend fun getRelationAt(
        assetId: String,
        relationKind: String,
        ordinal: Int,
    ): MediaRelationEntity?

    @Query(
        "SELECT * FROM media_relation WHERE asset_id = :assetId " +
            "AND relation_kind = :relationKind ORDER BY ordinal ASC",
    )
    suspend fun getRelations(assetId: String, relationKind: String): List<MediaRelationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageRefIgnore(reference: MessageMediaRefEntity): Long

    @Query(
        "SELECT * FROM message_media_ref WHERE owner_key = :ownerKey " +
            "AND asset_id = :assetId LIMIT 1",
    )
    suspend fun getMessageRef(ownerKey: String, assetId: String): MessageMediaRefEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJournalIgnore(journal: MediaMigrationJournalEntity): Long

    @Query(
        "SELECT * FROM media_migration_journal WHERE scope_kind = :scopeKind " +
            "AND scope_key = :scopeKey AND stage = :stage LIMIT 1",
    )
    suspend fun getJournal(
        scopeKind: String,
        scopeKey: String,
        stage: String,
    ): MediaMigrationJournalEntity?

    @Query(
        "UPDATE media_migration_journal SET state = :state, detail = :detail, " +
            "updated_at = :updatedAt WHERE journal_id = :journalId",
    )
    suspend fun updateJournalState(
        journalId: String,
        state: String,
        detail: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE managed_files SET folder = :folder, relative_path = :relativePath, " +
            "display_name = :displayName, mime_type = :mimeType, size_bytes = :sizeBytes, " +
            "created_at = :createdAt, updated_at = :updatedAt " +
            "WHERE id = :id AND file_id = :fileId",
    )
    suspend fun updateManagedFileForGraph(
        id: Long,
        fileId: String,
        folder: String,
        relativePath: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun registerAssetGraph(write: MediaAssetGraphWrite): MediaAssetEntity {
        requireManagedFileUpdate(write.managedFile)
        val committed = insertOrGet(write.asset)
        require(committed.assetId == write.asset.assetId) {
            "Media path ${write.asset.path} is already owned by ${committed.assetId}"
        }
        persistV2Graph(write)
        return committed
    }

    @Transaction
    suspend fun reconcileAssetGraph(write: MediaAssetGraphWrite): MediaAssetEntity {
        requireManagedFileUpdate(write.managedFile)
        val expectedUpdatedAt = requireNotNull(write.expectedAssetUpdatedAt) {
            "Media asset reconciliation requires an expected update timestamp"
        }
        val asset = write.asset
        require(
            updateReconciledMetadata(
                id = asset.id,
                assetId = asset.assetId,
                displayName = asset.displayName,
                managedFileId = asset.managedFileId,
                mimeType = asset.mimeType,
                sizeBytes = asset.sizeBytes,
                width = asset.width,
                height = asset.height,
                sha256 = asset.sha256,
                storageState = asset.storageState,
                metadataVersion = asset.metadataVersion,
                updatedAt = asset.updatedAt,
                expectedUpdatedAt = expectedUpdatedAt,
            ) == 1,
        ) {
            "Media asset reconciliation lost a concurrent update: ${asset.assetId}"
        }
        persistV2Graph(write)
        return requireNotNull(getByAssetId(asset.assetId))
    }

    @Transaction
    suspend fun relocateAssetGraph(write: MediaAssetGraphWrite): MediaAssetEntity {
        val file = requireNotNull(write.managedFile) { "Media relocation requires a managed file" }
        requireManagedFileUpdate(file)
        val expectedUpdatedAt = requireNotNull(write.expectedAssetUpdatedAt) {
            "Media relocation requires an expected update timestamp"
        }
        val asset = write.asset
        require(
            updateRelocatedAsset(
                id = asset.id,
                assetId = asset.assetId,
                path = asset.path,
                displayName = asset.displayName,
                managedFileId = file.id,
                mimeType = asset.mimeType,
                sizeBytes = asset.sizeBytes,
                width = asset.width,
                height = asset.height,
                sha256 = asset.sha256,
                storageState = asset.storageState,
                metadataVersion = asset.metadataVersion,
                updatedAt = asset.updatedAt,
                expectedUpdatedAt = expectedUpdatedAt,
            ) == 1,
        ) { "Media relocation lost a concurrent update: ${asset.assetId}" }
        persistV2Graph(write)
        return requireNotNull(getByAssetId(asset.assetId))
    }

    suspend fun persistV2Graph(write: MediaAssetGraphWrite) {
        val assetId = write.asset.assetId
        MediaStableIds.requireValid(assetId, "Media asset id")
        val blob = write.blob
        if (blob == null) {
            require(write.assetBlob == null && write.replica == null) {
                "A replica or asset/blob link cannot exist without a proven blob"
            }
        } else {
            MediaStableIds.requireValid(blob.blobId, "Media blob id")
            val contentBlobId = MediaStableIds.blobIdForSha256(blob.sha256)
            require(contentBlobId == null || contentBlobId == blob.blobId) {
                "Verified media blob id must be derived from its SHA-256"
            }
            insertBlobIgnore(blob)
            val committedBlob = getBlob(blob.blobId)
                ?: blob.sha256?.let { getBlobBySha256(it) }
            requireNotNull(committedBlob) { "Media blob conflict has no committed row: ${blob.blobId}" }
            require(committedBlob.blobId == blob.blobId && committedBlob.sha256 == blob.sha256) {
                "Media blob identity conflicts with an existing digest"
            }
            updateBlobObservation(
                blobId = committedBlob.blobId,
                sha256 = committedBlob.sha256,
                mimeType = blob.mimeType,
                sizeBytes = blob.sizeBytes,
                width = blob.width,
                height = blob.height,
                durationMs = blob.durationMs,
                storageState = blob.storageState,
                verifiedAt = blob.verifiedAt,
            )

            write.assetBlob?.copy(assetId = assetId, blobId = committedBlob.blobId)?.let { link ->
                getAssetBlob(assetId, link.role)?.let { previousLink ->
                    if (previousLink.blobId != committedBlob.blobId) {
                        val previousBlob = requireNotNull(getBlob(previousLink.blobId))
                        require(previousBlob.sha256 == null && committedBlob.sha256 != null) {
                            "Media asset $assetId already has a different verified ${link.role} blob"
                        }
                        moveReplicasToVerifiedBlob(
                            oldBlobId = previousBlob.blobId,
                            newBlobId = committedBlob.blobId,
                            updatedAt = link.createdAt,
                        )
                        require(deleteAssetBlob(assetId, link.role, previousBlob.blobId) == 1)
                        deleteUnreferencedBlob(previousBlob.blobId)
                    }
                }
                insertAssetBlobIgnore(link)
                val committedLink = requireNotNull(getAssetBlob(assetId, link.role))
                require(committedLink.blobId == committedBlob.blobId) {
                    "Media asset $assetId already has a different ${link.role} blob"
                }
            }

            write.replica?.copy(blobId = committedBlob.blobId)?.let { replica ->
                insertReplicaIgnore(replica)
                val committedReplica = getReplica(replica.replicaId)
                    ?: replica.managedFileId?.let { getReplicaByManagedFileId(it) }
                requireNotNull(committedReplica) {
                    "Media replica conflict has no committed row: ${replica.replicaId}"
                }
                require(
                    committedReplica.blobId == committedBlob.blobId &&
                        committedReplica.managedFileId == replica.managedFileId
                ) {
                    "Managed replica identity is already bound to another blob or file"
                }
                updateReplicaObservation(
                    replicaId = committedReplica.replicaId,
                    etag = replica.etag,
                    state = replica.state,
                    encrypted = replica.encrypted,
                    verifiedAt = replica.verifiedAt,
                    updatedAt = replica.updatedAt,
                )
            }
        }

        write.relations.forEach { input ->
            val relation = input.copy(assetId = assetId)
            require(relation.relatedAssetId != assetId) { "A media asset cannot relate to itself" }
            insertRelationIgnore(relation)
            val committedRelation = requireNotNull(
                getRelationAt(assetId, relation.relationKind, relation.ordinal),
            )
            require(committedRelation.relatedAssetId == relation.relatedAssetId) {
                "Media relation slot ${relation.relationKind}[${relation.ordinal}] is already occupied"
            }
        }

        write.reference?.copy(assetId = assetId)?.let { reference ->
            insertMessageRefIgnore(reference)
            val committedReference = requireNotNull(getMessageRef(reference.ownerKey, assetId))
            require(committedReference.assetId == assetId) {
                "Media reference owner ${reference.ownerKey} is already bound to another asset"
            }
        }

        write.journals.forEach { journal ->
            insertJournalIgnore(journal)
            val committedJournal = requireNotNull(
                getJournal(journal.scopeKind, journal.scopeKey, journal.stage),
            )
            updateJournalState(
                journalId = committedJournal.journalId,
                state = journal.state,
                detail = journal.detail,
                updatedAt = journal.updatedAt,
            )
        }
    }

    suspend fun requireManagedFileUpdate(file: ManagedFileEntity?) {
        if (file == null) return
        MediaStableIds.requireValid(file.fileId, "Managed file id")
        require(file.id > 0) { "A committed managed file is required" }
        require(
            updateManagedFileForGraph(
                id = file.id,
                fileId = file.fileId,
                folder = file.folder,
                relativePath = file.relativePath,
                displayName = file.displayName,
                mimeType = file.mimeType,
                sizeBytes = file.sizeBytes,
                createdAt = file.createdAt,
                updatedAt = file.updatedAt,
            ) == 1,
        ) {
            "Managed file update rejected because its row or stable identity changed: ${file.id}"
        }
    }

    @Query("SELECT COUNT(*) FROM media_blob")
    suspend fun countBlobs(): Int

    @Query("SELECT COUNT(*) FROM media_asset_blob")
    suspend fun countAssetBlobs(): Int

    @Query("SELECT COUNT(*) FROM media_replica")
    suspend fun countReplicas(): Int

    @Query("SELECT COUNT(*) FROM media_relation")
    suspend fun countRelations(): Int

    @Query("SELECT COUNT(*) FROM message_media_ref")
    suspend fun countMessageRefs(): Int

    @Query("SELECT COUNT(*) FROM media_relation WHERE related_asset_id = :assetId")
    suspend fun countIncomingRelations(assetId: String): Int

    @Query("SELECT COUNT(*) FROM message_media_ref WHERE asset_id = :assetId")
    suspend fun countMessageReferences(assetId: String): Int

    @Query(
        "SELECT COUNT(*) FROM media_migration_journal WHERE scope_kind = 'asset' " +
            "AND scope_key = :assetId AND stage = 'reference_backfill' AND state = 'complete'",
    )
    suspend fun countCompletedReferenceBackfills(assetId: String): Int

    @Query(
        "UPDATE GenMediaEntity SET lifecycle = 'delete_pending', visibility = 'hidden', " +
            "hidden_at = COALESCE(hidden_at, :now), deleted_at = COALESCE(deleted_at, :now), " +
            "updated_at = :now WHERE id = :id AND asset_id = :assetId",
    )
    suspend fun markDeletePending(id: Int, assetId: String, now: Long): Int

    @Query("DELETE FROM GenMediaEntity WHERE id = :id AND asset_id = :assetId")
    suspend fun deleteRow(id: Int, assetId: String): Int

    @Transaction
    suspend fun delete(
        id: Int,
        now: Long = System.currentTimeMillis(),
    ): MediaAssetDeleteResult {
        val asset = getById(id) ?: return MediaAssetDeleteResult.NOT_FOUND
        val mustFailClosed = countIncomingRelations(asset.assetId) > 0 ||
            countMessageReferences(asset.assetId) > 0 ||
            countCompletedReferenceBackfills(asset.assetId) != 1
        if (mustFailClosed) {
            require(markDeletePending(id, asset.assetId, now) == 1)
            return MediaAssetDeleteResult.DEFERRED_REFERENCED
        }
        return if (deleteRow(id, asset.assetId) == 1) {
            MediaAssetDeleteResult.DELETED
        } else {
            MediaAssetDeleteResult.NOT_FOUND
        }
    }
}

enum class MediaAssetDeleteResult {
    DELETED,
    DEFERRED_REFERENCED,
    NOT_FOUND,
}

data class MediaAssetGraphWrite(
    val managedFile: ManagedFileEntity?,
    val asset: MediaAssetEntity,
    val blob: MediaBlobEntity? = null,
    val assetBlob: MediaAssetBlobEntity? = null,
    val replica: MediaReplicaEntity? = null,
    val relations: List<MediaRelationEntity> = emptyList(),
    val reference: MessageMediaRefEntity? = null,
    val journals: List<MediaMigrationJournalEntity> = emptyList(),
    val expectedAssetUpdatedAt: Long? = null,
)

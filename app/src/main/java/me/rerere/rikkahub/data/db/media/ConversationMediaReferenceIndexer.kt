package me.rerere.rikkahub.data.db.media

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.conversation.ConversationV2ShadowProjector
import me.rerere.rikkahub.data.db.conversation.sha256Hex
import me.rerere.rikkahub.data.db.conversation.stableLegacyPartId
import me.rerere.rikkahub.data.db.conversation.toCanonicalJson
import me.rerere.rikkahub.data.db.dao.ConversationMediaJournalEpoch
import me.rerere.rikkahub.data.db.dao.ConversationMediaReferenceDAO
import me.rerere.rikkahub.data.db.dao.ConversationMediaReferenceReplaceResult
import me.rerere.rikkahub.data.db.dao.ConversationMediaSourceRow
import me.rerere.rikkahub.data.db.dao.ConversationMigrationDAO
import me.rerere.rikkahub.data.db.entity.MessageMediaRefEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.MessageNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal const val EXACT_V2_OWNER_PREFIX = "rikkahub-media-ref-v2|"

/** Classification is fail-closed: only an explicit HTTP(S) URL is non-local and ignorable. */
sealed interface ManagedMediaLocation {
    data class Managed(val relativePath: String) : ManagedMediaLocation

    data object ExplicitRemote : ManagedMediaLocation

    data class InvalidLocal(val reason: String) : ManagedMediaLocation
}

fun interface ManagedMediaPathResolver {
    fun resolve(url: String): ManagedMediaLocation
}

class FilesDirManagedMediaPathResolver(filesDir: File) : ManagedMediaPathResolver {
    private val root = filesDir.canonicalFile

    override fun resolve(url: String): ManagedMediaLocation {
        val value = url.trim()
        if (value.isEmpty()) return ManagedMediaLocation.InvalidLocal("blank media path")

        val parsed = try {
            URI(value)
        } catch (_: Exception) {
            null
        }
        when (parsed?.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> return ManagedMediaLocation.ExplicitRemote
            "file" -> return resolveFileUri(parsed)
            null -> Unit
            else -> return ManagedMediaLocation.InvalidLocal("unsupported media URI scheme")
        }

        if (parsed == null && value.startsWith("file:", ignoreCase = true)) {
            return ManagedMediaLocation.InvalidLocal("malformed file URI")
        }
        return resolveLocalPath(value)
    }

    private fun resolveFileUri(uri: URI): ManagedMediaLocation {
        if (uri.rawAuthority?.isNotEmpty() == true) {
            return ManagedMediaLocation.InvalidLocal("file URI authority is not app-local")
        }
        val decodedPath = try {
            uri.path
        } catch (_: Exception) {
            null
        } ?: return ManagedMediaLocation.InvalidLocal("malformed file URI")
        if (containsTraversal(decodedPath)) {
            return ManagedMediaLocation.InvalidLocal("file URI contains traversal")
        }
        val file = try {
            File(uri)
        } catch (_: Exception) {
            return ManagedMediaLocation.InvalidLocal("malformed file URI")
        }
        return classifyCanonical(file)
    }

    private fun resolveLocalPath(value: String): ManagedMediaLocation {
        if (containsTraversal(value)) {
            return ManagedMediaLocation.InvalidLocal("local path contains traversal")
        }
        val file = File(value)
        val target = if (file.isAbsolute) file else File(root, value.replace('\\', '/').trimStart('/'))
        return classifyCanonical(target)
    }

    private fun classifyCanonical(file: File): ManagedMediaLocation {
        val target = try {
            file.canonicalFile
        } catch (_: Exception) {
            return ManagedMediaLocation.InvalidLocal("local path cannot be canonicalized")
        }
        if (target == root || !target.toPath().startsWith(root.toPath())) {
            return ManagedMediaLocation.InvalidLocal("local path is outside app files")
        }
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return if (relative.isBlank()) {
            ManagedMediaLocation.InvalidLocal("local path does not name a managed file")
        } else {
            ManagedMediaLocation.Managed(relative)
        }
    }

    private fun containsTraversal(value: String): Boolean = value
        .replace('\\', '/')
        .split('/')
        .any { segment -> segment == ".." }
}

/**
 * Exact ConversationStore-v2 -> MediaAsset-v2 reference maintainer.
 *
 * Public wrappers own a Room transaction. Methods suffixed `InTransaction` are intentionally
 * synchronous with a caller's outer ConversationStore transaction and never launch background
 * work. Valid live writes replace one conversation's exact set atomically and preserve an already
 * proven global journal. Integrity or resolution failures downgrade the global reference journals
 * to pending and return INCOMPLETE, so GC remains fail-closed until a complete rescan succeeds.
 */
class ConversationMediaReferenceIndexer(
    private val database: AppDatabase,
    private val dao: ConversationMediaReferenceDAO,
    private val migrationDAO: ConversationMigrationDAO,
    private val shadowProjector: ConversationV2ShadowProjector,
    private val json: Json,
    private val managedPathResolver: ManagedMediaPathResolver,
) {
    suspend fun replaceReadyConversationReferences(
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ): ConversationMediaIndexResult = database.withTransaction {
        replaceReadyConversationReferencesInTransaction(conversationId, now)
    }

    /** Call from ConversationV2Writer's existing outer AppDatabase transaction. */
    suspend fun replaceReadyConversationReferencesInTransaction(
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ): ConversationMediaIndexResult {
        val prepared = try {
            prepareValidatedReadyConversationInTransaction(conversationId, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.rethrowCancellationCause()
            invalidateReferenceCompleteness(
                now = now,
                detail = "conversation_reference_validation_failed:$conversationId",
            )
            return ConversationMediaIndexResult(
                status = ConversationMediaIndexStatus.INCOMPLETE,
                desiredReferences = 0,
                failure = error::class.java.simpleName.ifBlank { "UnknownError" },
            )
        }
        if (prepared.resolved.unresolved > 0) {
            invalidateReferenceCompleteness(
                now = now,
                detail = "conversation_reference_unresolved:$conversationId",
            )
            return prepared.toIncompleteIndexResult()
        }
        // From this point on, failures must escape the outer ConversationStore transaction.
        // Swallowing a DAO error after partial inserts would commit a mixed ownership set.
        return commitPreparedConversationInTransaction(prepared).toIndexResult()
    }

    suspend fun deleteConversationExactRefs(
        conversationId: String,
    ): Int = database.withTransaction {
        deleteConversationExactRefsInTransaction(conversationId)
    }

    /** Call from the same transaction that deletes or logically deletes the conversation. */
    suspend fun deleteConversationExactRefsInTransaction(
        conversationId: String,
    ): Int = dao.deleteConversationOwnedReferences(conversationId)

    suspend fun requiresGlobalBackfill(): Boolean =
        dao.countAssetsRequiringReferenceBackfill() > 0

    suspend fun backfillReadyConversations(
        now: Long = System.currentTimeMillis(),
        pageSize: Int = DEFAULT_CONVERSATION_PAGE_SIZE,
    ): ConversationMediaBackfillResult {
        require(pageSize in 1..MAX_CONVERSATION_PAGE_SIZE) { "Invalid conversation media page size" }
        val epoch = database.withTransaction {
            val captured = dao.beginConversationMediaReferenceEpoch(
                requestedNow = now,
                detail = "conversation_reference_scan_in_progress",
            )
            dao.clearOrphanExactV2References()
            captured
        }

        val scanned = mutableListOf<ConversationMediaConversationEpoch>()
        val failures = mutableListOf<String>()
        var readyConversations = 0
        var unresolvedImages = 0
        var afterConversationId: String? = null
        while (true) {
            val page = database.withTransaction {
                dao.getReadyConversationIdsForMediaPage(afterConversationId, pageSize)
            }
            if (page.isEmpty()) break
            for (conversationId in page) {
                readyConversations++
                try {
                    val result = database.withTransaction {
                        indexValidatedReadyConversationInTransaction(conversationId, now)
                    }
                    unresolvedImages += result.unresolvedImages
                    if (result.unresolvedImages == 0) {
                        scanned += result.epoch
                    } else {
                        failures += "$conversationId: ${result.unresolvedImages} media image(s) unresolved"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error.rethrowCancellationCause()
                    failures += "$conversationId: ${error::class.java.simpleName.ifBlank { "UnknownError" }}"
                }
            }
            afterConversationId = page.last()
        }

        val referenceCount = scanned.sumOf(ConversationMediaConversationEpoch::referenceCount)
        if (failures.isNotEmpty() || unresolvedImages > 0) {
            return ConversationMediaBackfillResult(
                status = ConversationMediaBackfillStatus.INCOMPLETE,
                readyConversations = readyConversations,
                indexedConversations = scanned.size,
                referenceCount = referenceCount,
                unresolvedImages = unresolvedImages,
                failures = failures,
            )
        }

        val finalization = try {
            database.withTransaction {
                finalizeBackfillInTransaction(epoch, scanned, pageSize, now)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.rethrowCancellationCause()
            failures += "finalization: ${error::class.java.simpleName.ifBlank { "UnknownError" }}"
            ConversationMediaBackfillStatus.BLOCKED
        }
        return ConversationMediaBackfillResult(
            status = finalization,
            readyConversations = readyConversations,
            indexedConversations = scanned.size,
            referenceCount = referenceCount,
            unresolvedImages = unresolvedImages,
            failures = failures,
        )
    }

    private suspend fun indexValidatedReadyConversationInTransaction(
        conversationId: String,
        now: Long,
    ): IndexedReadyConversation {
        val prepared = prepareValidatedReadyConversationInTransaction(conversationId, now)
        if (prepared.resolved.unresolved > 0) {
            return IndexedReadyConversation(
                epoch = prepared.source.epoch.copy(referenceCount = 0, referenceDigest = EMPTY_REFERENCE_DIGEST),
                replacement = null,
                unresolvedImages = prepared.resolved.unresolved,
                ignoredRemoteImages = prepared.resolved.ignoredRemote,
                ignoredNonAssetImages = prepared.resolved.ignoredNonAsset,
            )
        }
        return commitPreparedConversationInTransaction(prepared)
    }

    private suspend fun prepareValidatedReadyConversationInTransaction(
        conversationId: String,
        now: Long,
    ): PreparedReadyConversation {
        val source = loadValidatedReadySourceInTransaction(conversationId)
        return PreparedReadyConversation(
            source = source,
            resolved = resolveProjection(conversationId, source.projection, now),
        )
    }

    private suspend fun commitPreparedConversationInTransaction(
        prepared: PreparedReadyConversation,
    ): IndexedReadyConversation {
        val conversationId = prepared.source.epoch.conversationId
        val replacement = dao.replaceConversationReferences(conversationId, prepared.resolved.references)
        val committed = dao.getExactV2References(conversationId)
        return IndexedReadyConversation(
            epoch = prepared.source.epoch.copy(
                referenceCount = committed.size,
                referenceDigest = conversationMediaReferenceDigest(committed),
            ),
            replacement = replacement,
            unresolvedImages = 0,
            ignoredRemoteImages = prepared.resolved.ignoredRemote,
            ignoredNonAssetImages = prepared.resolved.ignoredNonAsset,
        )
    }

    private suspend fun invalidateReferenceCompleteness(now: Long, detail: String) {
        dao.beginConversationMediaReferenceEpoch(requestedNow = now, detail = detail)
    }

    private suspend fun loadValidatedReadySourceInTransaction(
        conversationId: String,
    ): ValidatedReadySource {
        val beforeState = requireNotNull(migrationDAO.getConversationState(conversationId)) {
            "Conversation $conversationId does not exist"
        }
        val beforeJournal = requireNotNull(migrationDAO.getJournal(conversationId)) {
            "Conversation $conversationId has no migration journal"
        }
        val shadow = requireNotNull(shadowProjector.loadReady(conversationId)) {
            "Conversation $conversationId is not a validated READY v2 source"
        }
        val afterState = migrationDAO.getConversationState(conversationId)
        val afterJournal = migrationDAO.getJournal(conversationId)
        require(beforeState == afterState && beforeJournal == afterJournal) {
            "Conversation $conversationId changed during READY validation"
        }
        require(shadow.activeLeafMessageId == beforeState.activeLeafMessageId) {
            "Conversation $conversationId active leaf changed during READY validation"
        }

        val rows = dao.getConversationMediaSourceRows(conversationId)
        require(
            beforeState == migrationDAO.getConversationState(conversationId) &&
                beforeJournal == migrationDAO.getJournal(conversationId),
        ) { "Conversation $conversationId changed while loading exact media sources" }
        val projection = projectV2Conversation(conversationId, rows, json, managedPathResolver)
        return ValidatedReadySource(
            projection = projection,
            epoch = ConversationMediaConversationEpoch(
                conversationId = conversationId,
                conversationRevision = beforeState.revision,
                migrationJournalUpdatedAt = beforeJournal.updatedAt,
                activeLeafMessageId = shadow.activeLeafMessageId,
                readyGraphDigest = shadow.graphDigest,
                sourceDigest = projection.sourceDigest,
                referenceCount = 0,
                referenceDigest = EMPTY_REFERENCE_DIGEST,
            ),
        )
    }

    private suspend fun finalizeBackfillInTransaction(
        journalEpoch: ConversationMediaJournalEpoch,
        scanned: List<ConversationMediaConversationEpoch>,
        pageSize: Int,
        now: Long,
    ): ConversationMediaBackfillStatus {
        if (
            dao.countConversationsBlockingMediaReferenceCompletion() > 0 ||
            dao.countQuarantinedConversationMediaSources() > 0 ||
            dao.countAssetsMissingReferenceBackfillJournal() > 0 ||
            dao.countOrphanExactV2References() > 0
        ) {
            return ConversationMediaBackfillStatus.BLOCKED
        }
        if (dao.getAssetReferenceJournalEpoch() != journalEpoch.rows) {
            return ConversationMediaBackfillStatus.SOURCE_CHANGED
        }

        val currentConversationIds = loadReadyConversationIdsInTransaction(pageSize)
        val expectedConversationIds = scanned.map(ConversationMediaConversationEpoch::conversationId)
        if (currentConversationIds != expectedConversationIds) {
            return ConversationMediaBackfillStatus.SOURCE_CHANGED
        }
        scanned.forEach { expected ->
            val currentSource = loadValidatedReadySourceInTransaction(expected.conversationId)
            val current = currentSource.epoch
            if (
                current.conversationRevision != expected.conversationRevision ||
                current.migrationJournalUpdatedAt != expected.migrationJournalUpdatedAt ||
                current.activeLeafMessageId != expected.activeLeafMessageId ||
                current.readyGraphDigest != expected.readyGraphDigest ||
                current.sourceDigest != expected.sourceDigest
            ) {
                return ConversationMediaBackfillStatus.SOURCE_CHANGED
            }
            val reResolved = resolveProjection(expected.conversationId, currentSource.projection, now)
            if (
                reResolved.unresolved > 0 ||
                conversationMediaReferenceDigest(reResolved.references) != expected.referenceDigest
            ) {
                return ConversationMediaBackfillStatus.SOURCE_CHANGED
            }
            val references = dao.getExactV2References(expected.conversationId)
            if (
                references.size != expected.referenceCount ||
                conversationMediaReferenceDigest(references) != expected.referenceDigest
            ) {
                return ConversationMediaBackfillStatus.REFERENCE_MISMATCH
            }
        }

        val completedAt = if (now > journalEpoch.updatedAt) now else Math.addExact(journalEpoch.updatedAt, 1L)
        return if (dao.completeConversationMediaReferenceEpoch(journalEpoch, completedAt)) {
            // The global READY scan is now authoritative. Coarse v1 ownership would otherwise
            // keep assets permanently referenced after the matching exact part disappears.
            dao.deleteAllLegacyV1References()
            ConversationMediaBackfillStatus.COMPLETE
        } else {
            ConversationMediaBackfillStatus.SOURCE_CHANGED
        }
    }

    private suspend fun loadReadyConversationIdsInTransaction(pageSize: Int): List<String> {
        val result = mutableListOf<String>()
        var after: String? = null
        while (true) {
            val page = dao.getReadyConversationIdsForMediaPage(after, pageSize)
            if (page.isEmpty()) return result
            result += page
            after = page.last()
        }
    }

    private suspend fun resolveProjection(
        conversationId: String,
        projection: ConversationMediaProjection,
        now: Long,
    ): ResolvedConversationMediaProjection {
        val references = mutableListOf<MessageMediaRefEntity>()
        var unresolved = 0
        var ignoredRemote = 0
        var ignoredNonAsset = 0
        projection.images.forEach { image ->
            val assertedAssetId = image.assetId?.takeIf(String::isNotBlank)
            val assetId = when (val location = image.location) {
                is ManagedMediaLocation.InvalidLocal -> {
                    if (assertedAssetId == null) {
                        ignoredNonAsset++
                        return@forEach
                    }
                    null
                }
                ManagedMediaLocation.ExplicitRemote -> {
                    if (assertedAssetId == null) {
                        ignoredRemote++
                        return@forEach
                    }
                    dao.findMediaAssetId(assertedAssetId)
                }
                is ManagedMediaLocation.Managed -> {
                    val pathAssetId = dao.findMediaAssetIdByPath(location.relativePath)
                    if (assertedAssetId == null) {
                        if (pathAssetId != null) {
                            pathAssetId
                        } else if (location.relativePath.isGeneratedMediaPath()) {
                            null
                        } else {
                            ignoredNonAsset++
                            return@forEach
                        }
                    } else {
                        val asserted = dao.findMediaAssetId(assertedAssetId)
                        asserted?.takeIf { it == pathAssetId }
                    }
                }
            }
            if (assetId == null) {
                unresolved++
                return@forEach
            }

            val ownerKey = exactV2OwnerKey(
                conversationId = conversationId,
                branchGroupId = image.branchGroupId,
                messageId = image.messageId,
                partId = image.partId,
                nestedLocation = image.nestedLocation,
                toolCallId = image.toolCallId,
            )
            references += MessageMediaRefEntity(
                refId = deterministicMediaReferenceId(ownerKey, assetId),
                ownerKey = ownerKey,
                assetId = assetId,
                conversationId = conversationId,
                // Compatibility column; this value is the v2 branch_group_id.
                messageNodeId = image.branchGroupId,
                messageId = image.messageId,
                partId = image.partId,
                toolCallId = image.toolCallId,
                createdAt = now,
            )
        }
        return ResolvedConversationMediaProjection(
            references = references.distinctBy(MessageMediaRefEntity::refId),
            unresolved = unresolved,
            ignoredRemote = ignoredRemote,
            ignoredNonAsset = ignoredNonAsset,
        )
    }
}

private fun String.isGeneratedMediaPath(): Boolean {
    val folder = substringBefore('/')
    return folder == FileFolders.LEGACY_GENERATED_IMAGES ||
        folder == FileFolders.CHAT_GENERATED_IMAGES ||
        folder == FileFolders.LIBRARY_ATTACHMENTS
}

internal fun projectTypedConversation(
    conversationId: String,
    nodes: List<MessageNode>,
    json: Json,
    pathResolver: ManagedMediaPathResolver,
): ConversationMediaProjection {
    val images = buildList {
        nodes.forEach { node ->
            val branchGroupId = node.id.toString()
            node.messages.forEach { message ->
                val messageId = message.id.toString()
                message.parts.forEachIndexed { ordinal, part ->
                    val payload = json.encodeToJsonElement(UIMessagePart.serializer(), part)
                    val canonical = payload.toCanonicalJson()
                    val partId = stableLegacyPartId(
                        conversationId = conversationId,
                        messageId = messageId,
                        ordinal = ordinal,
                        kind = (payload.jsonObject["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        canonicalPayloadDigest = sha256Hex(canonical),
                    )
                    collectImageCandidates(
                        part = part,
                        owner = ConversationMediaOwner(branchGroupId, messageId, partId),
                        pathResolver = pathResolver,
                        nestedLocation = "top",
                        inheritedToolCallId = null,
                        output = this,
                    )
                }
            }
        }
    }
    return ConversationMediaProjection(
        images = images,
        sourceDigest = stableDigest("typed-conversation", listOf(conversationId) + images.map { it.digestValue() }),
    )
}

internal fun projectV2Conversation(
    conversationId: String,
    rows: List<ConversationMediaSourceRow>,
    json: Json,
    pathResolver: ManagedMediaPathResolver,
): ConversationMediaProjection {
    require(rows.all { it.conversationId == conversationId }) { "Mixed conversation media source rows" }
    val images = buildList {
        rows.forEach { row ->
            if (row.messageDeletedAt != null || row.partDeletedAt != null) return@forEach
            val payload = json.parseToJsonElement(row.payloadJson)
            val canonical = payload.toCanonicalJson()
            require(sha256Hex(canonical) == row.payloadDigest) {
                "Part ${row.partId} payload digest does not match"
            }
            val payloadKind = (payload.jsonObject["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(payloadKind == row.kind) { "Part ${row.partId} kind does not match its payload" }
            val part = json.decodeFromJsonElement(UIMessagePart.serializer(), payload)
            if (part.isMediaAttachmentPart()) {
                val decodedAssetId = part.mediaAssetIdOrNull()
                require(
                    row.partAssetId.isNullOrBlank() || decodedAssetId.isNullOrBlank() ||
                        row.partAssetId == decodedAssetId,
                ) { "Part ${row.partId} has conflicting media asset identities" }
            }
            if (part is UIMessagePart.Tool) {
                require(
                    row.toolInvocationId.isNullOrBlank() || part.toolCallId.isBlank() ||
                        row.toolInvocationId == part.toolCallId,
                ) { "Part ${row.partId} has conflicting tool invocation identities" }
            }
            collectImageCandidates(
                part = part,
                owner = ConversationMediaOwner(row.branchGroupId, row.messageId, row.partId),
                pathResolver = pathResolver,
                nestedLocation = "top",
                inheritedToolCallId = row.toolInvocationId,
                topLevelAssetId = row.partAssetId,
                output = this,
            )
        }
    }
    return ConversationMediaProjection(
        images = images,
        sourceDigest = conversationMediaConversationSourceDigest(conversationId, rows),
    )
}

private fun collectImageCandidates(
    part: UIMessagePart,
    owner: ConversationMediaOwner,
    pathResolver: ManagedMediaPathResolver,
    nestedLocation: String,
    inheritedToolCallId: String?,
    output: MutableList<ConversationMediaImageCandidate>,
    topLevelAssetId: String? = null,
) {
    when (part) {
        is UIMessagePart.Image -> output += ConversationMediaImageCandidate(
            branchGroupId = owner.branchGroupId,
            messageId = owner.messageId,
            partId = owner.partId,
            nestedLocation = nestedLocation,
            toolCallId = inheritedToolCallId?.takeIf(String::isNotBlank),
            assetId = topLevelAssetId?.takeIf(String::isNotBlank) ?: part.assetId,
            location = pathResolver.resolve(part.url),
        )
        is UIMessagePart.Video -> output += part.toMediaCandidate(
            owner, pathResolver, nestedLocation, inheritedToolCallId, topLevelAssetId,
        )
        is UIMessagePart.Audio -> output += part.toMediaCandidate(
            owner, pathResolver, nestedLocation, inheritedToolCallId, topLevelAssetId,
        )
        is UIMessagePart.Document -> output += part.toMediaCandidate(
            owner, pathResolver, nestedLocation, inheritedToolCallId, topLevelAssetId,
        )
        is UIMessagePart.Tool -> {
            val toolCallId = part.toolCallId.takeIf(String::isNotBlank) ?: inheritedToolCallId
            part.output.forEachIndexed { index, child ->
                collectImageCandidates(
                    child,
                    owner,
                    pathResolver,
                    "$nestedLocation/output/$index",
                    toolCallId,
                    output,
                )
            }
            part.progress.forEachIndexed { index, child ->
                collectImageCandidates(
                    child,
                    owner,
                    pathResolver,
                    "$nestedLocation/progress/$index",
                    toolCallId,
                    output,
                )
            }
        }
        else -> Unit
    }
}

private fun UIMessagePart.toMediaCandidate(
    owner: ConversationMediaOwner,
    pathResolver: ManagedMediaPathResolver,
    nestedLocation: String,
    inheritedToolCallId: String?,
    topLevelAssetId: String?,
): ConversationMediaImageCandidate {
    val url = when (this) {
        is UIMessagePart.Video -> url
        is UIMessagePart.Audio -> url
        is UIMessagePart.Document -> url
        else -> error("Not an attachment part")
    }
    return ConversationMediaImageCandidate(
        branchGroupId = owner.branchGroupId,
        messageId = owner.messageId,
        partId = owner.partId,
        nestedLocation = nestedLocation,
        toolCallId = inheritedToolCallId?.takeIf(String::isNotBlank),
        assetId = topLevelAssetId?.takeIf(String::isNotBlank) ?: mediaAssetIdOrNull(),
        location = pathResolver.resolve(url),
    )
}

private fun UIMessagePart.isMediaAttachmentPart(): Boolean = when (this) {
    is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio, is UIMessagePart.Document -> true
    else -> false
}

private fun UIMessagePart.mediaAssetIdOrNull(): String? = when (this) {
    is UIMessagePart.Image -> assetId
    is UIMessagePart.Video -> assetId
    is UIMessagePart.Audio -> assetId
    is UIMessagePart.Document -> assetId
    else -> null
}

internal fun conversationMediaConversationSourceDigest(
    conversationId: String,
    rows: List<ConversationMediaSourceRow>,
): String = stableDigest(
    "conversation-media-source",
    buildList {
        add(conversationId)
        rows.sortedWith(
            compareBy(ConversationMediaSourceRow::branchGroupId)
                .thenBy(ConversationMediaSourceRow::messageId)
                .thenBy(ConversationMediaSourceRow::ordinal)
                .thenBy(ConversationMediaSourceRow::partId),
        ).forEach { row ->
            add(row.conversationId)
            add(row.branchGroupId)
            add(row.messageId)
            add(row.messageRevision.toString())
            add(row.messageDeletedAt?.toString().orEmpty())
            add(row.partId)
            add(row.ordinal.toString())
            add(row.kind)
            add(row.payloadJson)
            add(row.payloadDigest)
            add(row.partAssetId.orEmpty())
            add(row.toolInvocationId.orEmpty())
            add(row.partRevision.toString())
            add(row.partDeletedAt?.toString().orEmpty())
        }
    },
)

internal fun conversationMediaReferenceDigest(references: List<MessageMediaRefEntity>): String = stableDigest(
    "conversation-media-reference-set",
    buildList {
        references.sortedBy(MessageMediaRefEntity::refId).forEach { reference ->
            add(reference.refId)
            add(reference.ownerKey)
            add(reference.assetId)
            add(reference.conversationId.orEmpty())
            add(reference.messageNodeId.orEmpty())
            add(reference.messageId.orEmpty())
            add(reference.partId.orEmpty())
            add(reference.toolCallId.orEmpty())
        }
    },
)

internal fun MessageMediaRefEntity.hasSameReferenceAuthority(other: MessageMediaRefEntity): Boolean =
    refId == other.refId && ownerKey == other.ownerKey && assetId == other.assetId &&
        conversationId == other.conversationId && messageNodeId == other.messageNodeId &&
        messageId == other.messageId && partId == other.partId && toolCallId == other.toolCallId

private fun exactV2OwnerKey(
    conversationId: String,
    branchGroupId: String,
    messageId: String,
    partId: String,
    nestedLocation: String,
    toolCallId: String?,
): String = EXACT_V2_OWNER_PREFIX + listOf(
    conversationId,
    branchGroupId,
    messageId,
    partId,
    nestedLocation,
    toolCallId.orEmpty(),
).joinToString(separator = "") { value -> "${value.length}:$value" }

private fun deterministicMediaReferenceId(ownerKey: String, assetId: String): String =
    UUID.nameUUIDFromBytes(
        lengthPrefixedBytes(listOf("rikkahub-media-reference-v2", ownerKey, assetId)),
    ).toString()

private fun stableDigest(domain: String, values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(lengthPrefixedBytes(listOf(domain) + values))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun lengthPrefixedBytes(values: List<String>): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use { output ->
        values.forEach { value ->
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        }
    }
    buffer.toByteArray()
}

/** A lower layer must not be able to accidentally wrap and neutralize structured cancellation. */
private fun Exception.rethrowCancellationCause() {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) throw current
        current = current.cause
    }
}

private fun ConversationMediaImageCandidate.digestValue(): String = listOf(
    branchGroupId,
    messageId,
    partId,
    nestedLocation,
    toolCallId.orEmpty(),
    assetId.orEmpty(),
    when (val value = location) {
        is ManagedMediaLocation.Managed -> "managed:${value.relativePath}"
        ManagedMediaLocation.ExplicitRemote -> "remote"
        is ManagedMediaLocation.InvalidLocal -> "invalid:${value.reason}"
    },
).joinToString("\u0000")

private fun IndexedReadyConversation.toIndexResult(): ConversationMediaIndexResult {
    val committed = replacement
    return if (committed == null) {
        ConversationMediaIndexResult(
            status = ConversationMediaIndexStatus.INCOMPLETE,
            desiredReferences = 0,
            unresolvedImages = unresolvedImages,
            ignoredRemoteImages = ignoredRemoteImages,
            ignoredNonAssetImages = ignoredNonAssetImages,
        )
    } else {
        ConversationMediaIndexResult(
            status = ConversationMediaIndexStatus.COMPLETE,
            desiredReferences = committed.committed,
            insertedReferences = committed.inserted,
            deletedReferences = committed.deleted,
            ignoredRemoteImages = ignoredRemoteImages,
            ignoredNonAssetImages = ignoredNonAssetImages,
        )
    }
}

private fun PreparedReadyConversation.toIncompleteIndexResult() = ConversationMediaIndexResult(
    status = ConversationMediaIndexStatus.INCOMPLETE,
    desiredReferences = 0,
    unresolvedImages = resolved.unresolved,
    ignoredRemoteImages = resolved.ignoredRemote,
    ignoredNonAssetImages = resolved.ignoredNonAsset,
)

private data class ConversationMediaOwner(
    val branchGroupId: String,
    val messageId: String,
    val partId: String,
)

internal data class ConversationMediaProjection(
    val images: List<ConversationMediaImageCandidate>,
    val sourceDigest: String,
)

internal data class ConversationMediaImageCandidate(
    val branchGroupId: String,
    val messageId: String,
    val partId: String,
    val nestedLocation: String,
    val toolCallId: String?,
    val assetId: String?,
    val location: ManagedMediaLocation,
) {
    val managedRelativePath: String?
        get() = (location as? ManagedMediaLocation.Managed)?.relativePath
}

private data class ResolvedConversationMediaProjection(
    val references: List<MessageMediaRefEntity>,
    val unresolved: Int,
    val ignoredRemote: Int,
    val ignoredNonAsset: Int,
)

private data class ValidatedReadySource(
    val projection: ConversationMediaProjection,
    val epoch: ConversationMediaConversationEpoch,
)

private data class PreparedReadyConversation(
    val source: ValidatedReadySource,
    val resolved: ResolvedConversationMediaProjection,
)

private data class IndexedReadyConversation(
    val epoch: ConversationMediaConversationEpoch,
    val replacement: ConversationMediaReferenceReplaceResult?,
    val unresolvedImages: Int,
    val ignoredRemoteImages: Int,
    val ignoredNonAssetImages: Int,
)

private data class ConversationMediaConversationEpoch(
    val conversationId: String,
    val conversationRevision: Long,
    val migrationJournalUpdatedAt: Long,
    val activeLeafMessageId: String?,
    val readyGraphDigest: String,
    val sourceDigest: String,
    val referenceCount: Int,
    val referenceDigest: String,
)

data class ConversationMediaIndexResult(
    val status: ConversationMediaIndexStatus,
    val desiredReferences: Int,
    val insertedReferences: Int = 0,
    val deletedReferences: Int = 0,
    val unresolvedImages: Int = 0,
    val ignoredRemoteImages: Int = 0,
    val ignoredNonAssetImages: Int = 0,
    val failure: String? = null,
)

enum class ConversationMediaIndexStatus { COMPLETE, INCOMPLETE }

data class ConversationMediaBackfillResult(
    val status: ConversationMediaBackfillStatus,
    val readyConversations: Int,
    val indexedConversations: Int,
    val referenceCount: Int,
    val unresolvedImages: Int,
    val failures: List<String>,
)

enum class ConversationMediaBackfillStatus {
    COMPLETE,
    BLOCKED,
    INCOMPLETE,
    SOURCE_CHANGED,
    REFERENCE_MISMATCH,
}

private val EMPTY_REFERENCE_DIGEST = conversationMediaReferenceDigest(emptyList())
private const val DEFAULT_CONVERSATION_PAGE_SIZE = 64
private const val MAX_CONVERSATION_PAGE_SIZE = 512

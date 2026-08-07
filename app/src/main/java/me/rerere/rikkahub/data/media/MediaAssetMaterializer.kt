package me.rerere.rikkahub.data.media

import android.content.Context
import androidx.core.net.toUri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.db.media.FilesDirManagedMediaPathResolver
import me.rerere.rikkahub.data.db.media.ManagedMediaLocation
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AttachmentMediaAssetRegistration
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.GeneratedMediaAssetRegistration
import me.rerere.rikkahub.data.repository.MediaAssetIds
import java.net.URLConnection
import kotlin.uuid.Uuid

/**
 * Moves conversation-owned files out of temporary folders and binds every attachable
 * message part to a stable MediaAsset identity. Copy/registration completes before the
 * caller persists the rewritten conversation, so a crash can leave only a recoverable
 * extra replica, never a message pointing at a deleted temporary file.
 */
class MediaAssetMaterializer(
    context: Context,
    private val filesManager: FilesManager,
    private val mediaRepository: GenMediaRepository,
    private val conversationRepository: ConversationRepository,
) {
    private val pathResolver = FilesDirManagedMediaPathResolver(context.filesDir)

    suspend fun materializeConversation(
        conversation: Conversation,
        strict: Boolean = false,
    ): MediaAssetMaterializationResult {
        var changed = false
        var materialized = 0
        val failures = mutableListOf<String>()
        val supersededFiles = mutableListOf<String>()
        val nodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map { message ->
                runCatching {
                    materializeMessage(
                        conversationId = conversation.id.toString(),
                        messageNodeId = node.id.toString(),
                        message = message,
                    )
                }.fold(
                    onSuccess = { result ->
                        changed = changed || result.message != message
                        materialized += result.materialized
                        supersededFiles += result.supersededFiles
                        result.message
                    },
                    onFailure = { error ->
                        if (strict) throw error
                        failures += "${message.id}: ${error.message ?: error::class.java.simpleName}"
                        message
                    },
                )
            }
            if (messages == node.messages) node else node.copy(messages = messages)
        }
        return MediaAssetMaterializationResult(
            conversation = if (changed) conversation.copy(messageNodes = nodes) else conversation,
            materialized = materialized,
            failures = failures,
            supersededFiles = supersededFiles.distinct(),
        )
    }

    suspend fun backfillReadyConversations(pageSize: Int = 64): MediaAssetBackfillResult {
        require(pageSize in 1..256)
        var after: String? = null
        var inspected = 0
        var updated = 0
        var materialized = 0
        val failures = mutableListOf<String>()
        val relocation = relocateRegisteredTemporaryAssets(pageSize)
        materialized += relocation.first
        failures += relocation.second
        while (true) {
            val ids = mediaRepository.getReadyConversationIds(after, pageSize)
            if (ids.isEmpty()) break
            ids.forEach { id ->
                inspected++
                runCatching {
                    val conversation = conversationRepository.getConversationById(Uuid.parse(id))
                        ?: return@runCatching
                    val result = materializeConversation(conversation)
                    materialized += result.materialized
                    failures += result.failures
                    if (result.conversation != conversation) {
                        conversationRepository.updateConversation(result.conversation)
                        filesManager.deleteChatFiles(result.supersededFiles.map(String::toUri))
                        updated++
                    }
                }.onFailure { error ->
                    failures += "$id: ${error.message ?: error::class.java.simpleName}"
                }
            }
            after = ids.last()
            if (ids.size < pageSize) break
        }
        return MediaAssetBackfillResult(inspected, updated, materialized, failures)
    }

    private suspend fun relocateRegisteredTemporaryAssets(
        pageSize: Int,
    ): Pair<Int, List<String>> {
        var afterId = 0
        var relocated = 0
        val failures = mutableListOf<String>()
        while (true) {
            val assets = mediaRepository.getAssetsRequiringRelocation(afterId, pageSize)
            if (assets.isEmpty()) break
            assets.forEach { asset ->
                runCatching {
                    val source = filesManager.resolveManagedFile(asset.path)
                    require(source.isFile) { "Asset file is missing: ${asset.path}" }
                    val targetFolder = if (asset.type == MediaAssetEntity.TYPE_ATTACHMENT) {
                        FileFolders.LIBRARY_ATTACHMENTS
                    } else {
                        FileFolders.CHAT_GENERATED_IMAGES
                    }
                    val target = filesManager.commitManagedFileWithIdentity(
                        folder = targetFolder,
                        source = source,
                        assetId = asset.assetId,
                        displayName = asset.displayName.ifBlank { source.name },
                        mimeType = asset.mimeType,
                    )
                    val managed = filesManager.registerExistingManagedFile(
                        folder = targetFolder,
                        file = target,
                        displayName = asset.displayName.ifBlank { source.name },
                        mimeType = asset.mimeType,
                        createdAt = asset.createAt,
                    )
                    mediaRepository.relocateAsset(asset, managed, target)
                    relocated++
                }.onFailure { error ->
                    failures += "${asset.assetId}: ${error.message ?: error::class.java.simpleName}"
                }
            }
            afterId = assets.last().id
            if (assets.size < pageSize) break
        }
        return relocated to failures
    }

    suspend fun materializeMessage(
        conversationId: String,
        messageNodeId: String,
        message: UIMessage,
    ): MaterializedMessage {
        var count = 0
        val supersededFiles = mutableListOf<String>()
        val parts = message.parts.mapIndexed { index, part ->
            val result = materializePart(
                conversationId = conversationId,
                messageNodeId = messageNodeId,
                message = message,
                part = part,
                nestedLocation = "part/$index",
                toolCallId = null,
            )
            count += result.materialized
            supersededFiles += result.supersededFiles
            result.part
        }
        return MaterializedMessage(
            message = if (parts == message.parts) message else message.copy(parts = parts),
            materialized = count,
            supersededFiles = supersededFiles.distinct(),
        )
    }

    private suspend fun materializePart(
        conversationId: String,
        messageNodeId: String,
        message: UIMessage,
        part: UIMessagePart,
        nestedLocation: String,
        toolCallId: String?,
    ): MaterializedPart = when (part) {
        is UIMessagePart.Tool -> {
            val stableToolCallId = part.toolCallId.takeIf(String::isNotBlank) ?: toolCallId
            var count = 0
            val supersededFiles = mutableListOf<String>()
            val output = part.output.mapIndexed { index, child ->
                materializePart(
                    conversationId,
                    messageNodeId,
                    message,
                    child,
                    "$nestedLocation/output/$index",
                    stableToolCallId,
                ).also {
                    count += it.materialized
                    supersededFiles += it.supersededFiles
                }.part
            }
            val progress = part.progress.mapIndexed { index, child ->
                materializePart(
                    conversationId,
                    messageNodeId,
                    message,
                    child,
                    "$nestedLocation/progress/$index",
                    stableToolCallId,
                ).also {
                    count += it.materialized
                    supersededFiles += it.supersededFiles
                }.part
            }
            MaterializedPart(
                part = if (output == part.output && progress == part.progress) part else {
                    part.copy(output = output, progress = progress)
                },
                materialized = count,
                supersededFiles = supersededFiles,
            )
        }

        is UIMessagePart.Image -> materializeFilePart(
            original = part,
            url = part.url,
            assertedAssetId = part.assetId,
            displayName = null,
            declaredMime = "image/*",
            conversationId = conversationId,
            messageNodeId = messageNodeId,
            message = message,
            nestedLocation = nestedLocation,
            toolCallId = toolCallId,
        ) { url, assetId -> part.copy(url = url, assetId = assetId) }

        is UIMessagePart.Video -> materializeFilePart(
            part, part.url, part.assetId, null, "video/*", conversationId, messageNodeId,
            message, nestedLocation, toolCallId,
        ) { url, assetId -> part.copy(url = url, assetId = assetId) }

        is UIMessagePart.Audio -> materializeFilePart(
            part, part.url, part.assetId, null, "audio/*", conversationId, messageNodeId,
            message, nestedLocation, toolCallId,
        ) { url, assetId -> part.copy(url = url, assetId = assetId) }

        is UIMessagePart.Document -> materializeFilePart(
            part, part.url, part.assetId, part.fileName, part.mime, conversationId, messageNodeId,
            message, nestedLocation, toolCallId,
        ) { url, assetId -> part.copy(url = url, assetId = assetId) }

        else -> MaterializedPart(part, 0)
    }

    private suspend fun materializeFilePart(
        original: UIMessagePart,
        url: String,
        assertedAssetId: String?,
        displayName: String?,
        declaredMime: String,
        conversationId: String,
        messageNodeId: String,
        message: UIMessage,
        nestedLocation: String,
        toolCallId: String?,
        rewrite: (String, String) -> UIMessagePart,
    ): MaterializedPart {
        val location = pathResolver.resolve(url)
        if (location !is ManagedMediaLocation.Managed) {
            val known = if (assertedAssetId == null) null else mediaRepository.getAsset(assertedAssetId)
            return MaterializedPart(if (known == null) original else rewrite(url, known.assetId), 0)
        }
        val sourcePath = location.relativePath
        val existingById = if (assertedAssetId == null) null else mediaRepository.getAsset(assertedAssetId)
        val existingByPath = mediaRepository.getAssetByPath(sourcePath)
        val existing = existingById ?: existingByPath
        if (existing != null && existing.type != MediaAssetEntity.TYPE_ATTACHMENT) {
            val sourceFolder = sourcePath.substringBefore('/')
            if (
                original is UIMessagePart.Image &&
                sourceFolder in setOf(FileFolders.UPLOAD, FileFolders.TOOL_OUTPUTS)
            ) {
                val source = filesManager.resolveManagedFile(sourcePath)
                require(source.isFile) { "Gallery asset file is missing: $sourcePath" }
                val sourceManaged = filesManager.getByRelativePath(sourcePath)
                val resolvedName = existing.displayName.ifBlank { sourceManaged?.displayName ?: source.name }
                val target = filesManager.commitManagedFileWithIdentity(
                    folder = FileFolders.CHAT_GENERATED_IMAGES,
                    source = source,
                    assetId = existing.assetId,
                    displayName = resolvedName,
                    mimeType = existing.mimeType,
                )
                val managed = filesManager.registerExistingManagedFile(
                    folder = FileFolders.CHAT_GENERATED_IMAGES,
                    file = target,
                    displayName = resolvedName,
                    mimeType = existing.mimeType,
                    createdAt = existing.createAt,
                )
                mediaRepository.relocateAsset(existing, managed, target)
                return MaterializedPart(
                    part = rewrite(target.toUri().toString(), existing.assetId),
                    materialized = 1,
                    supersededFiles = temporarySourceReplacedBy(sourcePath, target.toUri().toString()),
                )
            }
            return MaterializedPart(rewrite(url, existing.assetId), 0)
        }

        val source = filesManager.resolveManagedFile(sourcePath)
        require(source.isFile) { "Attachment file is missing: $sourcePath" }
        val sourceManaged = filesManager.getByRelativePath(sourcePath)
        val resolvedName = displayName?.takeIf(String::isNotBlank)
            ?: sourceManaged?.displayName?.takeIf(String::isNotBlank)
            ?: source.name
        val resolvedMime = declaredMime.takeUnless { it.isBlank() || it.endsWith("/*") }
            ?: sourceManaged?.mimeType?.takeUnless { it.isBlank() || it.endsWith("/*") }
            ?: URLConnection.guessContentTypeFromName(resolvedName)
            ?: "application/octet-stream"
        val assetId = existing?.assetId
            ?: assertedAssetId?.takeIf(String::isNotBlank)
            ?: MediaAssetIds.forMessagePart(message.id.toString(), nestedLocation)
        if (
            existing == null && original is UIMessagePart.Image &&
            message.role == MessageRole.ASSISTANT && toolCallId.isNullOrBlank()
        ) {
            val target = filesManager.commitManagedFileWithIdentity(
                folder = FileFolders.CHAT_GENERATED_IMAGES,
                source = source,
                assetId = assetId,
                displayName = resolvedName,
                mimeType = resolvedMime,
            )
            val managed = filesManager.registerExistingManagedFile(
                folder = FileFolders.CHAT_GENERATED_IMAGES,
                file = target,
                displayName = resolvedName,
                mimeType = resolvedMime,
                createdAt = sourceManaged?.createdAt ?: System.currentTimeMillis(),
            )
            mediaRepository.registerGeneratedAsset(
                managedFile = managed,
                file = target,
                registration = GeneratedMediaAssetRegistration(
                    assetId = assetId,
                    modelId = message.modelId?.toString() ?: "assistant-image-output",
                    prompt = "",
                    createdAt = sourceManaged?.createdAt ?: System.currentTimeMillis(),
                    conversationId = conversationId,
                    messageNodeId = messageNodeId,
                ),
            )
            return MaterializedPart(
                part = rewrite(target.toUri().toString(), assetId),
                materialized = 1,
                supersededFiles = temporarySourceReplacedBy(sourcePath, target.toUri().toString()),
            )
        }
        val target = filesManager.commitManagedFileWithIdentity(
            folder = FileFolders.LIBRARY_ATTACHMENTS,
            source = source,
            assetId = assetId,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
        val managed = filesManager.registerExistingManagedFile(
            folder = FileFolders.LIBRARY_ATTACHMENTS,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
            createdAt = sourceManaged?.createdAt ?: System.currentTimeMillis(),
        )
        val origin = when {
            !toolCallId.isNullOrBlank() -> MediaAssetEntity.ORIGIN_TOOL_OUTPUT
            message.role == MessageRole.USER -> MediaAssetEntity.ORIGIN_USER_ATTACHMENT
            else -> MediaAssetEntity.ORIGIN_ASSISTANT_ATTACHMENT
        }
        mediaRepository.registerAttachmentAsset(
            managedFile = managed,
            file = target,
            registration = AttachmentMediaAssetRegistration(
                assetId = assetId,
                origin = origin,
                createdAt = sourceManaged?.createdAt ?: System.currentTimeMillis(),
                conversationId = conversationId,
                messageNodeId = messageNodeId,
                toolCallId = toolCallId,
            ),
        )
        return MaterializedPart(
            part = rewrite(target.toUri().toString(), assetId),
            materialized = 1,
            supersededFiles = temporarySourceReplacedBy(sourcePath, target.toUri().toString()),
        )
    }

    private fun temporarySourceReplacedBy(sourcePath: String, targetUrl: String): List<String> {
        val sourceFolder = sourcePath.substringBefore('/')
        if (sourceFolder !in setOf(FileFolders.UPLOAD, FileFolders.TOOL_OUTPUTS)) return emptyList()
        val sourceUrl = filesManager.resolveManagedFile(sourcePath).toUri().toString()
        return if (sourceUrl == targetUrl) emptyList() else listOf(sourceUrl)
    }

}

data class MediaAssetMaterializationResult(
    val conversation: Conversation,
    val materialized: Int,
    val failures: List<String>,
    val supersededFiles: List<String>,
)

data class MediaAssetBackfillResult(
    val inspected: Int,
    val updated: Int,
    val materialized: Int,
    val failures: List<String>,
)

data class MaterializedMessage(
    val message: UIMessage,
    val materialized: Int,
    val supersededFiles: List<String>,
)

private data class MaterializedPart(
    val part: UIMessagePart,
    val materialized: Int,
    val supersededFiles: List<String> = emptyList(),
)

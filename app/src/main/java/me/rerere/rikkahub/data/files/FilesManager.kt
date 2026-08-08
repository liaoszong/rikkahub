package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.common.android.SafeLogLevel
import me.rerere.common.android.SafeLogOutcome
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val durableAssetOwnership: DurableAssetOwnership = DurableAssetOwnership.NONE,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    private fun logFileError(operation: String, error: Throwable) {
        Logging.logErrorToLogcat(
            tag = TAG,
            domain = "files",
            operation = operation,
            error = error,
        )
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("Failed to open managed input stream")
            inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            createManagedFileEntity(
                folder = folder,
                file = target,
                displayName = resolvedName,
                mimeType = resolvedMime,
            )
        } catch (error: Throwable) {
            runCatching {
                check(!target.exists() || target.delete() && !target.exists()) {
                    "Failed to roll back managed file ${target.name}"
                }
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File = resolveManagedFile(entity.relativePath)

    fun resolveManagedFile(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "Managed file path must be relative"
        }
        val root = context.filesDir.canonicalFile
        val resolved = File(root, relativePath).canonicalFile
        require(resolved != root && resolved.toPath().startsWith(root.toPath())) {
            "Managed file path escapes app storage"
        }
        return resolved
    }

    fun toManagedRelativePath(file: File): String? = managedRelativePath(
        filesDir = context.filesDir,
        file = file,
    )

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open managed input stream")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackManagedFile(
                    folder = FileFolders.UPLOAD,
                    file = file,
                    displayName = sourceName,
                    mimeType = guessedMime
                )
                newUris.add(file.toUri())
            }.onFailure {
                logFileError(operation = "copy_chat_attachment", error = it)
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "event=operation domain=files operation=materialize_base64_image " +
                                        "outcome=succeeded itemCount=${urls.size}",
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun deleteChatFiles(uris: List<Uri>) {
        val candidates = uris.mapNotNull { uri ->
            resolveChatFileDeletionCandidate(
                filesDir = context.filesDir,
                uriScheme = uri.scheme,
                uriPath = uri.path,
            )
        }.distinctBy(ChatFileDeletionCandidate::relativePath)
        if (candidates.isEmpty()) return

        appScope.launch(Dispatchers.IO) {
            candidates.forEach { candidate ->
                val managedFile = repository.getByPath(candidate.relativePath)
                    ?: return@forEach
                if (durableAssetOwnership.isOwned(candidate.relativePath, managedFile.id)) {
                    Log.i(
                        TAG,
                        "event=operation domain=files operation=delete_managed_chat_file " +
                            "outcome=skipped reason=durable_asset",
                    )
                    return@forEach
                }
                if (deleteManagedChatFileIfAuthorized(
                        filesDir = context.filesDir,
                        candidate = candidate,
                        managedFile = managedFile,
                    )
                ) {
                    repository.deleteById(managedFile.id)
                } else {
                    Log.w(
                        TAG,
                        "event=operation domain=files operation=delete_managed_chat_file outcome=rejected",
                    )
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve(FileFolders.LEGACY_GENERATED_IMAGES)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Persists paid/generated output under its pre-reserved logical identity. If the
     * file commit succeeds but MediaAsset registration is interrupted, startup repair
     * can recover the same asset id from the file name without replaying the provider.
     */
    suspend fun saveManagedFromBytesWithIdentity(
        folder: String,
        bytes: ByteArray,
        assetId: String,
        displayName: String,
        mimeType: String,
        createdAt: Long = System.currentTimeMillis(),
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = commitManagedBytesWithIdentity(
            folder = folder,
            bytes = bytes,
            assetId = assetId,
            mimeType = mimeType,
        )
        registerExistingManagedFile(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
            createdAt = createdAt,
        )
    }

    /**
     * Commits bytes under a stable generated-asset identity without touching Room.
     *
     * Paid-output callers must be able to record the rename/fsync boundary before any
     * repairable ManagedFile or MediaAsset metadata write. [registerExistingManagedFile]
     * remains the separate idempotent metadata step.
     */
    suspend fun commitManagedBytesWithIdentity(
        folder: String,
        bytes: ByteArray,
        assetId: String,
        mimeType: String,
    ): File = withContext(Dispatchers.IO) {
        val canonicalAssetId = runCatching { UUID.fromString(assetId).toString() }
            .getOrElse { throw IllegalArgumentException("Media asset id must be a UUID", it) }
        require(canonicalAssetId == assetId) { "Media asset id must use canonical UUID form" }
        val extension = managedExtension(mimeType = mimeType, displayName = null, imageOnly = true)
        val directory = File(context.filesDir, folder).apply { mkdirs() }.canonicalFile
        val target = File(directory, "$canonicalAssetId.$extension").canonicalFile
        require(target.parentFile == directory) { "Generated media path escapes its managed folder" }

        if (target.isFile) {
            require(target.hasSameContent(bytes)) {
                "Media asset $canonicalAssetId is already bound to different file content"
            }
        } else {
            writeManagedAtomically(target, bytes)
        }
        target
    }

    /** Streams an arbitrary local attachment into its stable library identity. */
    suspend fun commitManagedFileWithIdentity(
        folder: String,
        source: File,
        assetId: String,
        displayName: String,
        mimeType: String,
    ): File = withContext(Dispatchers.IO) {
        require(source.isFile) { "Attachment source does not exist: ${source.name}" }
        val canonicalAssetId = runCatching { UUID.fromString(assetId).toString() }
            .getOrElse { throw IllegalArgumentException("Media asset id must be a UUID", it) }
        require(canonicalAssetId == assetId) { "Media asset id must use canonical UUID form" }
        val extension = managedExtension(mimeType, displayName, imageOnly = false)
        val directory = File(context.filesDir, folder).apply { mkdirs() }.canonicalFile
        val target = File(directory, "$canonicalAssetId.$extension").canonicalFile
        require(target.parentFile == directory) { "Attachment path escapes its managed folder" }

        if (target.isFile) {
            require(target.hasSameContent(source)) {
                "Media asset $canonicalAssetId is already bound to different file content"
            }
        } else {
            writeManagedAtomically(target, source)
        }
        target
    }

    /**
     * Registers a file that was committed atomically by another data component.
     * Existing path identity is updated in-place rather than REPLACE-inserted, which
     * keeps MediaAsset foreign keys stable across reconciliation replays.
     */
    suspend fun registerExistingManagedFile(
        folder: String,
        file: File,
        displayName: String = file.name,
        mimeType: String = guessMimeType(file, displayName),
        createdAt: Long = System.currentTimeMillis(),
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val folderRoot = File(context.filesDir, folder).canonicalFile
        val target = file.canonicalFile
        require(target.toPath().startsWith(folderRoot.toPath()) && target != folderRoot) {
            "Managed file must remain inside $folder"
        }
        require(target.isFile) { "Managed file does not exist: ${target.name}" }

        val relativePath = buildRelativePath(folder, target)
        val now = System.currentTimeMillis()
        val existing = repository.getByPath(relativePath)
        if (existing != null) {
            val updated = existing.copy(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                updatedAt = now,
            )
            repository.update(updated)
            return@withContext updated
        }
        repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = relativePath,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                createdAt = createdAt,
                updatedAt = now,
            ),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Logging.logOperationToLogcat(
                            tag = TAG,
                            domain = "files",
                            operation = "download_message_image",
                            outcome = SafeLogOutcome.FAILED,
                            level = SafeLogLevel.ERROR,
                            httpStatus = connection.responseCode,
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                removed += repository.deleteByPath(entity.relativePath)
            }
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (durableAssetOwnership.isOwned(entity.relativePath, entity.id)) {
            return@withContext false
        }
        deleteManagedFileWithIdentity(
            entity = entity,
            deleteFromDisk = deleteFromDisk,
            resolveFile = ::getFile,
            deletePhysicalFile = File::delete,
            deleteIdentity = repository::deleteById,
        )
    }

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }

        var allDeletedFromDisk = true
        entries.orEmpty().forEach { entry ->
            allDeletedFromDisk = deleteUnownedTree(entry, dir) && allDeletedFromDisk
        }

        repository.listByFolder(folder).first().forEach { entity ->
            if (!getFile(entity).exists() && !durableAssetOwnership.isOwned(entity.relativePath, entity.id)) {
                repository.deleteById(entity.id)
            }
        }
        allDeletedFromDisk
    }

    private suspend fun deleteUnownedTree(entry: File, root: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return false
        if (canonicalEntry == canonicalRoot || canonicalEntry.parentFile == null) return false
        if (!canonicalEntry.path.startsWith(canonicalRoot.path + File.separator)) return false

        if (entry.isDirectory) {
            val children = entry.listFiles() ?: return false
            var allDeleted = true
            children.forEach { child ->
                allDeleted = deleteUnownedTree(child, canonicalRoot) && allDeleted
            }
            if (entry.listFiles()?.isNotEmpty() != false) return false
            return runCatching { entry.delete() }.getOrDefault(false) && allDeleted
        }

        val relativePath = managedRelativePath(context.filesDir, entry) ?: return false
        val managedFile = repository.getByPath(relativePath)
        if (durableAssetOwnership.isOwned(relativePath, managedFile?.id)) return false
        if (!runCatching { entry.delete() }.getOrDefault(false)) return false
        if (managedFile != null) repository.deleteById(managedFile.id)
        return true
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                logFileError(operation = "track_managed_file", error = it)
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

private fun writeManagedAtomically(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            NioFiles.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            NioFiles.move(temporary.toPath(), target.toPath())
        }
    } finally {
        temporary.delete()
    }
}

private fun writeManagedAtomically(target: File, source: File) {
    val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
    try {
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        try {
            NioFiles.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            NioFiles.move(temporary.toPath(), target.toPath())
        }
    } finally {
        temporary.delete()
    }
}

private fun File.hasSameContent(bytes: ByteArray): Boolean {
    if (length() != bytes.size.toLong()) return false
    val fileDigest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            fileDigest.update(buffer, 0, count)
        }
    }
    val bytesDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return fileDigest.digest().contentEquals(bytesDigest)
}

private fun File.hasSameContent(other: File): Boolean {
    if (length() != other.length()) return false
    fun File.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
    return sha256().contentEquals(other.sha256())
}

private fun managedExtension(mimeType: String, displayName: String?, imageOnly: Boolean): String {
    val normalizedMime = mimeType.lowercase().substringBefore(';').trim()
    val known = when (normalizedMime) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "application/json" -> "json"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> null
    }
    if (known != null) return known
    if (imageOnly) throw IllegalArgumentException("Unsupported generated image MIME: $mimeType")
    return displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        ?: "bin"
}

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

object FileFolders {
    const val UPLOAD = "upload"
    const val LIBRARY_ATTACHMENTS = "library_attachments"
    const val CHAT_GENERATED_IMAGES = "chat_generated_images"
    const val LEGACY_GENERATED_IMAGES = "images"
    const val SKILLS = "skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
    const val CONTENT_BLOBS = "content_blobs"
}

/**
 * Fail-closed gate between temporary chat-file cleanup and the durable asset graph.
 * A file remains protected even when a conversation reference disappears; lifecycle
 * and retention are decided by MediaAsset, never by a UI message deletion.
 */
fun interface DurableAssetOwnership {
    suspend fun isOwned(relativePath: String, managedFileId: Long?): Boolean

    companion object {
        val NONE = DurableAssetOwnership { _, _ -> false }
    }
}

internal data class ChatFileDeletionCandidate(
    val file: File,
    val relativePath: String,
    val folder: String,
)

private val CHAT_FILE_DELETABLE_FOLDERS = setOf(
    FileFolders.UPLOAD,
    FileFolders.TOOL_OUTPUTS,
)

private val CHAT_FILE_PROTECTED_GALLERY_FOLDERS = setOf(
    FileFolders.CHAT_GENERATED_IMAGES,
    FileFolders.LEGACY_GENERATED_IMAGES,
)

/**
 * Resolves a message-owned file reference without trusting the serialized URI path.
 * Gallery folders are deliberately excluded: removing a conversation reference must
 * not destroy a durable library asset.
 */
internal fun resolveChatFileDeletionCandidate(
    filesDir: File,
    uriScheme: String?,
    uriPath: String?,
): ChatFileDeletionCandidate? {
    if (!uriScheme.equals("file", ignoreCase = true) || uriPath.isNullOrBlank()) return null

    val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
    val candidate = runCatching { File(uriPath).canonicalFile }.getOrNull() ?: return null
    if (candidate == root || !candidate.toPath().startsWith(root.toPath())) return null

    val relativePath = runCatching {
        candidate.relativeTo(root).path.replace(File.separatorChar, '/')
    }.getOrNull()?.takeIf { it.contains('/') } ?: return null
    val folder = relativePath.substringBefore('/')
    if (folder in CHAT_FILE_PROTECTED_GALLERY_FOLDERS || folder !in CHAT_FILE_DELETABLE_FOLDERS) {
        return null
    }

    val allowedRoot = runCatching { File(root, folder).canonicalFile }.getOrNull() ?: return null
    if (candidate == allowedRoot || !candidate.toPath().startsWith(allowedRoot.toPath())) return null
    return ChatFileDeletionCandidate(
        file = candidate,
        relativePath = relativePath,
        folder = folder,
    )
}

/**
 * Deletes only a file whose canonical path and persisted ManagedFile identity agree.
 * Returning true also covers an already-missing file so its stale DB row can be removed.
 */
internal fun deleteManagedChatFileIfAuthorized(
    filesDir: File,
    candidate: ChatFileDeletionCandidate,
    managedFile: ManagedFileEntity?,
    durableAssetOwned: Boolean = false,
): Boolean {
    if (durableAssetOwned) return false
    if (managedFile == null || managedFile.id <= 0L) return false
    if (managedFile.folder != candidate.folder || managedFile.relativePath != candidate.relativePath) return false

    val revalidated = resolveChatFileDeletionCandidate(
        filesDir = filesDir,
        uriScheme = "file",
        uriPath = candidate.file.path,
    ) ?: return false
    if (revalidated.relativePath != candidate.relativePath || revalidated.file != candidate.file) return false

    val managedTarget = runCatching {
        File(filesDir.canonicalFile, managedFile.relativePath).canonicalFile
    }.getOrNull() ?: return false
    if (managedTarget != revalidated.file) return false
    if (!revalidated.file.exists()) return true
    if (!revalidated.file.isFile) return false
    return revalidated.file.delete() && !revalidated.file.exists()
}

internal fun managedRelativePath(filesDir: File, file: File): String? = runCatching {
    val root = filesDir.canonicalFile
    val target = file.canonicalFile
    if (target == root || !target.toPath().startsWith(root.toPath())) return@runCatching null
    root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
}.getOrNull()?.takeIf(String::isNotBlank)

internal suspend fun deleteManagedFileWithIdentity(
    entity: ManagedFileEntity,
    deleteFromDisk: Boolean,
    resolveFile: (ManagedFileEntity) -> File,
    deletePhysicalFile: (File) -> Boolean,
    deleteIdentity: suspend (Long) -> Int,
): Boolean {
    if (deleteFromDisk) {
        val target = runCatching { resolveFile(entity) }.getOrNull() ?: return false
        val removed = if (!target.exists()) {
            true
        } else {
            target.isFile &&
                runCatching { deletePhysicalFile(target) }.getOrDefault(false) &&
                !target.exists()
        }
        if (!removed) return false
    }
    return deleteIdentity(entity.id) > 0
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)

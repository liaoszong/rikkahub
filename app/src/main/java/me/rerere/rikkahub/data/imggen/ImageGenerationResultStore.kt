package me.rerere.rikkahub.data.imggen

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GeneratedMediaAssetRegistration
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ImageMediaReconciliationResult(
    val inspected: Int = 0,
    val registered: Int = 0,
    val metadataRepaired: Int = 0,
    val missingFiles: Int = 0,
    val failures: List<String> = emptyList(),
)

/**
 * One-time and crash-recovery bridge for files created by older releases. It deliberately
 * contains no provider execution API; chat is the sole owner of new image requests.
 */
class MediaAssetRecovery(
    private val context: Context,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
    private val chatTaskStore: ChatImageGenerationTaskStore,
) {
    suspend fun reconcilePending(): ImageMediaReconciliationResult {
        val pending = pendingStore().reconcile()
        val chatFolderSyncFailure = runCatching {
            filesManager.syncFolder(FileFolders.CHAT_GENERATED_IMAGES)
        }.exceptionOrNull()
        val registrationsByAssetId = chatTaskStore.load().toPendingMediaRegistrations()
        val orphanedChatFiles = genMediaRepository.reconcileUnregisteredGeneratedFiles(
            folder = FileFolders.CHAT_GENERATED_IMAGES,
            resolveFile = { relativePath -> resolveManagedMediaFile(context, relativePath) },
            registrationsByAssetId = registrationsByAssetId,
        )
        val assets = genMediaRepository.reconcileLocalMetadata(
            resolveFile = { relativePath -> resolveManagedMediaFile(context, relativePath) },
        )
        return pending.copy(
            inspected = pending.inspected + orphanedChatFiles.inspected + assets.inspected,
            registered = pending.registered + orphanedChatFiles.registered,
            metadataRepaired = assets.repaired,
            missingFiles = orphanedChatFiles.missing + assets.missing,
            failures = buildList {
                addAll(pending.failures)
                chatFolderSyncFailure?.let { error ->
                    add("chat_generated_images: ${error.message ?: error::class.java.simpleName}")
                }
                addAll(orphanedChatFiles.failures)
                addAll(assets.failures)
            },
        )
    }

    private fun pendingStore() = PendingImageRegistrationStore(
        imagesDir = filesManager.getImagesDir(),
        insert = { metadata, imageFile ->
            val managedFile = filesManager.registerExistingManagedFile(
                folder = FileFolders.LEGACY_GENERATED_IMAGES,
                file = imageFile,
                mimeType = metadata.mimeType,
                createdAt = metadata.createAt,
            )
            genMediaRepository.registerGeneratedAsset(
                managedFile = managedFile,
                file = imageFile,
                registration = GeneratedMediaAssetRegistration(
                    assetId = metadata.stableAssetId(),
                    origin = if (metadata.type == GenMediaEntity.TYPE_IMAGE_EDIT) {
                        MediaAssetEntity.ORIGIN_AI_EDITED
                    } else {
                        MediaAssetEntity.ORIGIN_AI_GENERATED
                    },
                    modelId = metadata.modelId,
                    modelDisplayName = metadata.modelDisplayName,
                    providerId = metadata.providerId,
                    prompt = metadata.prompt,
                    createdAt = metadata.createAt,
                    sourcePaths = metadata.sourcePaths,
                ),
            ).id.toLong()
        },
    )
}

internal fun resolveManagedMediaFile(context: Context, relativePath: String): File {
    require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
        "Media path must be relative"
    }
    val root = context.filesDir.canonicalFile
    val resolved = File(root, relativePath).canonicalFile
    require(resolved.toPath().startsWith(root.toPath()) && resolved != root) {
        "Media path escapes app storage"
    }
    return resolved
}

internal data class ValidatedImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeValidatedImage(item: ImageGenerationItem): ValidatedImagePayload {
    val encoded = item.data.substringAfter("base64,", item.data).trim()
    val bytes = Base64.decode(encoded)
    val detected = detectImageFormat(bytes)
        ?: throw IllegalArgumentException("Unsupported or invalid generated image payload")
    val declared = item.mimeType.normalizedImageMime()
    if (declared != null && declared != detected.first) {
        throw IllegalArgumentException("Generated image MIME mismatch: declared $declared, detected ${detected.first}")
    }
    return ValidatedImagePayload(bytes, detected.first, detected.second)
}

/**
 * Structural checks reject incomplete containers early; BitmapFactory is the
 * final production authority that the Android client can actually decode it.
 */
private fun decodeImageForPersistence(item: ImageGenerationItem): ValidatedImagePayload {
    val payload = decodeValidatedImage(item)
    require(isAndroidDecodableImage(payload.bytes)) {
        "Generated image cannot be decoded by Android"
    }
    return payload
}

internal fun isAndroidDecodableImage(bytes: ByteArray): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > DECODE_PROBE_MAX_DIMENSION ||
        bounds.outHeight / sampleSize > DECODE_PROBE_MAX_DIMENSION
    ) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return false
    decoded.recycle()
    return true
}

private const val DECODE_PROBE_MAX_DIMENSION = 64

private fun String.normalizedImageMime(): String? = when (lowercase().substringBefore(';').trim()) {
    "image/png" -> "image/png"
    "image/jpeg", "image/jpg" -> "image/jpeg"
    "image/webp" -> "image/webp"
    "image/gif" -> "image/gif"
    else -> null
}

private fun detectImageFormat(bytes: ByteArray): Pair<String, String>? = when {
    isCompletePng(bytes) -> "image/png" to "png"
    isCompleteJpeg(bytes) -> "image/jpeg" to "jpg"
    isCompleteWebp(bytes) -> "image/webp" to "webp"
    isCompleteGif(bytes) -> "image/gif" to "gif"
    else -> null
}

private fun isCompletePng(bytes: ByteArray): Boolean {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (bytes.size < 45 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return false
    var offset = 8
    var firstChunk = true
    var hasImageData = false
    while (offset + 12 <= bytes.size) {
        val length = bytes.readUInt32BigEndian(offset) ?: return false
        if (length > Int.MAX_VALUE || offset + 12L + length > bytes.size) return false
        val chunkLength = length.toInt()
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        if (firstChunk) {
            if (type != "IHDR" || chunkLength != 13) return false
            val width = bytes.readUInt32BigEndian(offset + 8) ?: return false
            val height = bytes.readUInt32BigEndian(offset + 12) ?: return false
            if (width == 0L || height == 0L) return false
            firstChunk = false
        }
        if (type == "IDAT") hasImageData = true
        offset += 12 + chunkLength
        if (type == "IEND") return chunkLength == 0 && hasImageData && offset == bytes.size
    }
    return false
}

private fun isCompleteJpeg(bytes: ByteArray): Boolean {
    if (bytes.size < 12 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return false
    if (bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) return false
    var offset = 2
    var hasDimensions = false
    var hasScan = false
    while (offset < bytes.size - 2) {
        if (bytes[offset] != 0xff.toByte()) {
            if (!hasScan) return false
            offset++
            continue
        }
        while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
        if (offset >= bytes.size) return false
        val marker = bytes[offset].toInt() and 0xff
        offset++
        if (marker == 0xd9) break
        if (marker == 0x00 || marker in 0xd0..0xd7 || marker == 0x01) continue
        if (offset + 2 > bytes.size) return false
        val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        if (length < 2 || offset + length > bytes.size) return false
        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
            if (length < 7) return false
            val height = ((bytes[offset + 3].toInt() and 0xff) shl 8) or (bytes[offset + 4].toInt() and 0xff)
            val width = ((bytes[offset + 5].toInt() and 0xff) shl 8) or (bytes[offset + 6].toInt() and 0xff)
            if (width == 0 || height == 0) return false
            hasDimensions = true
        }
        if (marker == 0xda) hasScan = true
        offset += length
    }
    return hasDimensions && hasScan
}

private fun isCompleteWebp(bytes: ByteArray): Boolean {
    if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP"
    ) return false
    val riffSize = bytes.readUInt32LittleEndian(4) ?: return false
    if (riffSize + 8L != bytes.size.toLong()) return false
    var offset = 12
    var hasImageChunk = false
    while (offset + 8 <= bytes.size) {
        val type = String(bytes, offset, 4, Charsets.US_ASCII)
        val length = bytes.readUInt32LittleEndian(offset + 4) ?: return false
        if (length > Int.MAX_VALUE) return false
        val paddedLength = length + (length and 1L)
        if (offset + 8L + paddedLength > bytes.size) return false
        if (type in setOf("VP8 ", "VP8L", "VP8X")) hasImageChunk = true
        offset += (8L + paddedLength).toInt()
    }
    return hasImageChunk && offset == bytes.size
}

private fun isCompleteGif(bytes: ByteArray): Boolean {
    if (bytes.size < 14 || String(bytes, 0, 6, Charsets.US_ASCII) !in setOf("GIF87a", "GIF89a")) return false
    val width = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
    val height = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
    return width > 0 && height > 0 && bytes.last() == 0x3b.toByte()
}

private fun ByteArray.readUInt32BigEndian(offset: Int): Long? {
    if (offset < 0 || offset + 4 > size) return null
    return (0 until 4).fold(0L) { value, index -> (value shl 8) or (this[offset + index].toLong() and 0xff) }
}

private fun ByteArray.readUInt32LittleEndian(offset: Int): Long? {
    if (offset < 0 || offset + 4 > size) return null
    return (0 until 4).fold(0L) { value, index -> value or ((this[offset + index].toLong() and 0xff) shl (8 * index)) }
}

internal fun atomicWrite(target: File, bytes: ByteArray): File {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    } finally {
        temporary.delete()
    }
}

internal fun Iterable<ChatImageGenerationTaskRecord>.toPendingMediaRegistrations():
    Map<String, GeneratedMediaAssetRegistration> = flatMap { task ->
    task.reservedOutputAssetIds.map { assetId ->
        assetId to GeneratedMediaAssetRegistration(
            assetId = assetId,
            origin = task.mediaOrigin,
            modelId = task.modelId.ifBlank { task.modelName },
            modelDisplayName = task.modelName,
            providerId = task.providerId,
            prompt = task.prompt,
            createdAt = task.startedAtEpochMillis,
            conversationId = task.conversationId,
            toolCallId = task.toolCallId,
            parentAssetId = task.parentAssetId,
        )
    }
}.toMap()

@Serializable
internal data class PendingImageMetadata(
    val version: Int = 2,
    /** Absent in v1 sidecars; [stableAssetId] deterministically upgrades them. */
    val assetId: String? = null,
    val path: String,
    val mimeType: String,
    val modelId: String,
    val modelDisplayName: String,
    val providerId: String? = null,
    val prompt: String,
    val createAt: Long,
    val type: String,
    val sourcePaths: List<String> = emptyList(),
    val databaseId: Int? = null,
    val registrationAttempts: Int = 0,
    val lastError: String? = null,
) {
    fun toEntity() = GenMediaEntity(
        path = path,
        modelId = modelId,
        modelDisplayName = modelDisplayName,
        providerId = providerId,
        prompt = prompt,
        createAt = createAt,
        type = type,
        sourcePaths = sourcePaths.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        assetId = stableAssetId(),
        origin = if (type == GenMediaEntity.TYPE_IMAGE_EDIT) {
            MediaAssetEntity.ORIGIN_AI_EDITED
        } else {
            MediaAssetEntity.ORIGIN_AI_GENERATED
        },
        mimeType = mimeType,
    )

    fun stableAssetId(): String = assetId?.takeIf { it.isNotBlank() }
        ?: UUID.nameUUIDFromBytes("pending-media:$path".encodeToByteArray()).toString()
}

internal class PendingImageRegistrationStore(
    private val imagesDir: File,
    private val insert: suspend (PendingImageMetadata, File) -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun persist(metadata: PendingImageMetadata) {
        writeMetadata(metadata)
    }

    fun discard(metadata: PendingImageMetadata) {
        metadataFile(metadata).delete()
    }

    suspend fun register(metadata: PendingImageMetadata): Int {
        val image = requireNotNull(resolveImage(metadata.path)?.takeIf(File::isFile)) {
            "Generated image file is missing: ${metadata.path}"
        }
        val id = try {
            insert(metadata, image).toInt()
        } catch (error: Exception) {
            runCatching {
                writeMetadata(
                    metadata.copy(
                        registrationAttempts = metadata.registrationAttempts + 1,
                        lastError = error.message?.take(500) ?: error::class.java.simpleName,
                    ),
                )
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        // A sidecar update failure must not turn a successful DB commit into a database
        // failure. Reconciliation may replay this metadata safely because path is a
        // unique DB key and insert-or-get returns the already committed row.
        runCatching {
            writeMetadata(
                metadata.copy(
                    databaseId = id,
                    registrationAttempts = metadata.registrationAttempts + 1,
                    lastError = null,
                ),
            )
        }
        return id
    }

    suspend fun reconcile(): ImageMediaReconciliationResult {
        val failures = mutableListOf<String>()
        var inspected = 0
        var registered = 0
        metadataFiles().forEach { file ->
            val metadata = runCatching { json.decodeFromString<PendingImageMetadata>(file.readText()) }
                .getOrElse {
                    failures += "${file.name}: unreadable metadata"
                    return@forEach
                }
            if (metadata.databaseId != null) return@forEach
            inspected++
            val image = resolveImage(metadata.path)
            if (image == null || !image.isFile) {
                failures += "${file.name}: image file is missing"
                return@forEach
            }
            runCatching { register(metadata) }
                .onSuccess { registered++ }
                .onFailure { failures += "${file.name}: ${it.message ?: it::class.java.simpleName}" }
        }
        return ImageMediaReconciliationResult(
            inspected = inspected,
            registered = registered,
            failures = failures,
        )
    }

    private fun metadataFiles(): List<File> =
        imagesDir.listFiles { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun writeMetadata(metadata: PendingImageMetadata) {
        atomicWrite(metadataFile(metadata), json.encodeToString(metadata).encodeToByteArray())
    }

    private fun metadataFile(metadata: PendingImageMetadata): File =
        File(imagesDir, "${metadata.path.substringAfterLast('/')}$METADATA_SUFFIX")

    private fun resolveImage(relativePath: String): File? {
        if (!relativePath.startsWith("images/") || relativePath.substringAfter("images/").contains('/')) return null
        val image = File(imagesDir, relativePath.substringAfter("images/")).canonicalFile
        return image.takeIf { it.parentFile == imagesDir.canonicalFile }
    }

    private companion object {
        const val METADATA_SUFFIX = ".imgmeta.json"
    }
}

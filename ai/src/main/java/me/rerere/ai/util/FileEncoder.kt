package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

private const val BYTES_PER_ARGB_8888_PIXEL = 4L

/**
 * Mobile-safe limits for attachments that must be materialized inside a JSON request.
 *
 * The aggregate limit is deliberately lower than common 32 MiB request limits: Base64 expands
 * binary data by 4/3 and the remaining request still needs room for text, tools and JSON syntax.
 * Larger media should use a provider file-upload API instead of an inline data URL.
 */
data class AttachmentBudgetLimits(
    val maxSourceFileBytes: Long = DEFAULT_MAX_SOURCE_FILE_BYTES,
    val maxInlinePartBytes: Long = DEFAULT_MAX_INLINE_PART_BYTES,
    val maxInlineRequestBytes: Long = DEFAULT_MAX_INLINE_REQUEST_BYTES,
    val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
    val maxImagePixels: Long = DEFAULT_MAX_IMAGE_PIXELS,
    val maxDecodedBitmapBytes: Long = DEFAULT_MAX_DECODED_BITMAP_BYTES,
    val maxExifTransformPeakBytes: Long = DEFAULT_MAX_EXIF_TRANSFORM_PEAK_BYTES,
) {
    init {
        require(maxSourceFileBytes > 0)
        require(maxInlinePartBytes > 0)
        require(maxInlineRequestBytes >= maxInlinePartBytes)
        require(maxImageDimension > 0)
        require(maxImagePixels > 0)
        require(maxDecodedBitmapBytes > 0)
        require(maxExifTransformPeakBytes >= maxDecodedBitmapBytes)
    }

    companion object {
        const val DEFAULT_MAX_SOURCE_FILE_BYTES = 32L * 1024 * 1024
        const val DEFAULT_MAX_INLINE_PART_BYTES = 16L * 1024 * 1024
        const val DEFAULT_MAX_INLINE_REQUEST_BYTES = 20L * 1024 * 1024
        const val DEFAULT_MAX_IMAGE_DIMENSION = 10_000
        const val DEFAULT_MAX_IMAGE_PIXELS = 16_000_000L
        const val DEFAULT_MAX_DECODED_BITMAP_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_EXIF_TRANSFORM_PEAK_BYTES = 96L * 1024 * 1024

        val DEFAULT = AttachmentBudgetLimits()
    }
}

enum class AttachmentMediaKind(private val diagnosticName: String) {
    IMAGE("image"),
    GIF("GIF"),
    VIDEO("video"),
    AUDIO("audio");

    override fun toString(): String = diagnosticName
}

open class PayloadBudgetExceededException(
    message: String,
    val actualBytes: Long,
    val limitBytes: Long,
) : IllegalArgumentException(message)

class AttachmentBudgetExceededException(
    val mediaKind: AttachmentMediaKind,
    val budgetName: String,
    actualBytes: Long,
    limitBytes: Long,
) : PayloadBudgetExceededException(
    message = "$mediaKind attachment exceeds $budgetName budget: $actualBytes bytes > $limitBytes bytes",
    actualBytes = actualBytes,
    limitBytes = limitBytes,
)

class AttachmentBudgetTracker(
    val limits: AttachmentBudgetLimits = AttachmentBudgetLimits.DEFAULT,
) {
    private var usedInlineBytes = 0L

    @Synchronized
    fun reserveInlineBytes(mediaKind: AttachmentMediaKind, bytes: Long) {
        require(bytes >= 0) { "Attachment byte count must be non-negative" }
        if (bytes > limits.maxInlinePartBytes) {
            throw AttachmentBudgetExceededException(
                mediaKind = mediaKind,
                budgetName = "single inline part",
                actualBytes = bytes,
                limitBytes = limits.maxInlinePartBytes,
            )
        }
        val remaining = limits.maxInlineRequestBytes - usedInlineBytes
        if (bytes > remaining) {
            throw AttachmentBudgetExceededException(
                mediaKind = mediaKind,
                budgetName = "aggregate inline request",
                actualBytes = saturatedAdd(usedInlineBytes, bytes),
                limitBytes = limits.maxInlineRequestBytes,
            )
        }
        usedInlineBytes += bytes
    }

    @Synchronized
    fun usedInlineBytes(): Long = usedInlineBytes

    @Synchronized
    internal fun releaseInlineBytes(bytes: Long) {
        check(bytes in 0..usedInlineBytes) { "Invalid attachment budget rollback" }
        usedInlineBytes -= bytes
    }
}

data class Base64PayloadInfo(
    val decodedBytes: Long,
    val payloadStartIndex: Int,
    val payloadEndIndex: Int,
)

/** Validates Base64 grammar and computes decoded size without allocating the decoded byte array. */
fun inspectBase64Payload(
    source: CharSequence,
    startIndex: Int = 0,
    endIndex: Int = source.length,
    maxDecodedBytes: Long = Long.MAX_VALUE,
): Base64PayloadInfo {
    require(startIndex in 0..source.length && endIndex in startIndex..source.length) {
        "Invalid Base64 payload range"
    }
    require(maxDecodedBytes >= 0) { "Base64 decoded byte limit must be non-negative" }

    var first = -1
    var lastExclusive = -1
    var significantCharacters = 0L
    var padding = 0
    var sawPadding = false
    var sawTrailingWhitespace = false

    for (index in startIndex until endIndex) {
        val character = source[index]
        if (character.isBase64Whitespace()) {
            if (first >= 0) sawTrailingWhitespace = true
            continue
        }
        require(!sawTrailingWhitespace) { "Base64 payload contains embedded whitespace" }
        if (first < 0) first = index
        lastExclusive = index + 1
        significantCharacters++
        when {
            character == '=' -> {
                sawPadding = true
                padding++
                require(padding <= 2) { "Base64 payload has invalid padding" }
            }

            character.isBase64AlphabetCharacter() -> {
                require(!sawPadding) { "Base64 payload has data after padding" }
            }

            else -> throw IllegalArgumentException("Base64 payload contains an invalid character")
        }
    }

    require(first >= 0) { "Base64 payload is empty" }
    val remainder = (significantCharacters % 4L).toInt()
    if (padding > 0) {
        require(remainder == 0) { "Base64 payload has invalid padded length" }
    } else {
        require(remainder != 1) { "Base64 payload has invalid length" }
    }
    val decodedBytes = if (padding > 0) {
        (significantCharacters / 4L) * 3L - padding
    } else {
        (significantCharacters / 4L) * 3L + when (remainder) {
            2 -> 1L
            3 -> 2L
            else -> 0L
        }
    }
    if (decodedBytes > maxDecodedBytes) {
        throw PayloadBudgetExceededException(
            message = "Base64 payload exceeds decoded byte budget: $decodedBytes bytes > $maxDecodedBytes bytes",
            actualBytes = decodedBytes,
            limitBytes = maxDecodedBytes,
        )
    }
    return Base64PayloadInfo(
        decodedBytes = decodedBytes,
        payloadStartIndex = first,
        payloadEndIndex = lastExclusive,
    )
}

fun base64EncodedSize(decodedBytes: Long): Long {
    require(decodedBytes >= 0) { "Decoded byte count must be non-negative" }
    if (decodedBytes == 0L) return 0L
    require(decodedBytes <= (Long.MAX_VALUE / 4L) * 3L - 2L) {
        "Decoded byte count is too large"
    }
    return ((decodedBytes + 2L) / 3L) * 4L
}

internal fun Throwable.rethrowIfPayloadBudgetExceeded() {
    if (this is PayloadBudgetExceededException) throw this
}

private inline fun <T> attachmentEncodingResult(
    mediaKind: AttachmentMediaKind,
    block: () -> T,
): Result<T> = try {
    Result.success(block())
} catch (error: PayloadBudgetExceededException) {
    Result.failure(error)
} catch (error: IllegalArgumentException) {
    Result.failure(error)
} catch (error: IllegalStateException) {
    Result.failure(error)
} catch (_: Exception) {
    // Do not retain filesystem paths from FileNotFoundException/IOException in a user-visible result.
    Result.failure(IllegalArgumentException("$mediaKind attachment encoding failed"))
}

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(
    withPrefix: Boolean = true,
    budgetTracker: AttachmentBudgetTracker = AttachmentBudgetTracker(),
): Result<EncodedImage> = attachmentEncodingResult(AttachmentMediaKind.IMAGE) {
    when {
        this.url.startsWith("file://") -> {
            val file = url.requireLocalAttachmentFile(AttachmentMediaKind.IMAGE)
            file.requireSourceBudget(AttachmentMediaKind.IMAGE, budgetTracker.limits)
            val mimeType = file.guessMimeType()
            val (encoded, outputMimeType) = file.compressAndEncode(
                mimeType = mimeType,
                withPrefix = withPrefix,
                budgetTracker = budgetTracker,
            )
            EncodedImage(
                base64 = encoded,
                mimeType = outputMimeType
            )
        }

        this.url.startsWith("data:") -> {
            val parsed = parseDataUrl(
                dataUrl = url,
                expectedMimePrefix = "image/",
                mediaKind = AttachmentMediaKind.IMAGE,
                budgetTracker = budgetTracker,
            )
            EncodedImage(
                base64 = parsed.materialize(url, withPrefix),
                mimeType = parsed.mimeType,
            )
        }

        this.url.startsWith("http://") || this.url.startsWith("https://") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }

        else -> throw IllegalArgumentException("Unsupported image attachment source scheme")
    }
}

fun UIMessagePart.Video.encodeBase64(
    withPrefix: Boolean = true,
    budgetTracker: AttachmentBudgetTracker = AttachmentBudgetTracker(),
): Result<String> = attachmentEncodingResult(AttachmentMediaKind.VIDEO) {
    when {
        this.url.startsWith("file://") -> {
            val file = url.requireLocalAttachmentFile(AttachmentMediaKind.VIDEO)
            val sourceBytes = file.requireSourceBudget(AttachmentMediaKind.VIDEO, budgetTracker.limits)
            file.encodeToBase64Materialized(
                sourceBytes = sourceBytes,
                prefix = if (withPrefix) "data:video/mp4;base64," else "",
                mediaKind = AttachmentMediaKind.VIDEO,
                budgetTracker = budgetTracker,
            )
        }

        this.url.startsWith("data:") -> parseDataUrl(
            dataUrl = url,
            expectedMimePrefix = "video/",
            allowedFallbackMimeTypes = setOf("application/octet-stream"),
            mediaKind = AttachmentMediaKind.VIDEO,
            budgetTracker = budgetTracker,
        ).materialize(url, withPrefix)

        else -> throw IllegalArgumentException("Unsupported video attachment source scheme")
    }
}

fun UIMessagePart.Audio.encodeBase64(
    withPrefix: Boolean = true,
    budgetTracker: AttachmentBudgetTracker = AttachmentBudgetTracker(),
): Result<String> = attachmentEncodingResult(AttachmentMediaKind.AUDIO) {
    when {
        this.url.startsWith("file://") -> {
            val file = url.requireLocalAttachmentFile(AttachmentMediaKind.AUDIO)
            val sourceBytes = file.requireSourceBudget(AttachmentMediaKind.AUDIO, budgetTracker.limits)
            file.encodeToBase64Materialized(
                sourceBytes = sourceBytes,
                prefix = if (withPrefix) "data:audio/mp3;base64," else "",
                mediaKind = AttachmentMediaKind.AUDIO,
                budgetTracker = budgetTracker,
            )
        }

        this.url.startsWith("data:") -> parseDataUrl(
            dataUrl = url,
            expectedMimePrefix = "audio/",
            allowedFallbackMimeTypes = setOf("application/octet-stream"),
            mediaKind = AttachmentMediaKind.AUDIO,
            budgetTracker = budgetTracker,
        ).materialize(url, withPrefix)

        else -> throw IllegalArgumentException("Unsupported audio attachment source scheme")
    }
}

private fun File.compressAndEncode(
    mimeType: String,
    withPrefix: Boolean,
    budgetTracker: AttachmentBudgetTracker,
    quality: Int = 85,
): Pair<String, String> {
    val limits = budgetTracker.limits
    // GIF 必须保持原始帧，因此只能做严格的文件和内联预算检查，不能静默转成静态 JPEG。
    if (mimeType == "image/gif") {
        val sourceBytes = requireSourceBudget(AttachmentMediaKind.GIF, limits)
        return Pair(
            encodeToBase64Materialized(
                sourceBytes = sourceBytes,
                prefix = if (withPrefix) "data:image/gif;base64," else "",
                mediaKind = AttachmentMediaKind.GIF,
                budgetTracker = budgetTracker,
            ),
            mimeType,
        )
    }

    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)
    require(options.outWidth > 0 && options.outHeight > 0) {
        "Image attachment dimensions cannot be decoded"
    }

    val exifTransform = readExifTransform()

    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = limits.maxImageDimension,
        maxPixels = limits.maxImagePixels,
        maxDecodedBitmapBytes = limits.maxDecodedBitmapBytes,
        maxTransformPeakBytes = limits.maxExifTransformPeakBytes,
        requiresExifTransform = exifTransform != ExifTransformType.NONE,
    )
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.ARGB_8888

    val temporary = createTemporaryJpeg()

    return try {
        val bitmap = BitmapFactory.decodeFile(absolutePath, options)
            ?: throw IllegalArgumentException("Image attachment cannot be decoded")
        val normalizedBitmap = try {
            applyExifTransform(bitmap, exifTransform)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        run {
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    BoundedOutputStream(
                        delegate = fileOutput,
                        maxBytes = limits.maxInlinePartBytes,
                        mediaKind = AttachmentMediaKind.IMAGE,
                    ).use { boundedOutput ->
                        require(normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, boundedOutput)) {
                            "Image attachment compression failed"
                        }
                    }
                }
            } finally {
                if (normalizedBitmap !== bitmap) {
                    normalizedBitmap.recycle()
                }
                bitmap.recycle()
            }

            val outputMimeType = "image/jpeg"
            val encoded = temporary.encodeToBase64Materialized(
                sourceBytes = temporary.length(),
                prefix = if (withPrefix) "data:$outputMimeType;base64," else "",
                mediaKind = AttachmentMediaKind.IMAGE,
                budgetTracker = budgetTracker,
            )
            Pair(encoded, outputMimeType)
        }
    } finally {
        temporary.removeTemporaryAttachment()
    }
}

private fun File.readExifTransform(): ExifTransformType {
    val orientation = try {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    return mapExifOrientationToTransform(orientation)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Image attachment EXIF transform failed", error)
    }
}

private data class ParsedDataUrl(
    val mimeType: String,
    val payload: Base64PayloadInfo,
) {
    fun materialize(source: String, withPrefix: Boolean): String = if (withPrefix) {
        source
    } else {
        source.substring(payload.payloadStartIndex, payload.payloadEndIndex)
    }
}

private fun parseDataUrl(
    dataUrl: String,
    expectedMimePrefix: String,
    allowedFallbackMimeTypes: Set<String> = emptySet(),
    mediaKind: AttachmentMediaKind,
    budgetTracker: AttachmentBudgetTracker,
): ParsedDataUrl {
    val separator = dataUrl.indexOf(',')
    require(separator in 6..MAX_DATA_URL_METADATA_END_INDEX) {
        "$mediaKind attachment data URL metadata is invalid"
    }
    val metadata = dataUrl.substring(5, separator)
    require(metadata.contains(";base64", ignoreCase = true)) {
        "$mediaKind attachment data URL is not Base64 encoded"
    }
    val mimeType = metadata.substringBefore(';').trim().lowercase()
    require(mimeType.startsWith(expectedMimePrefix) || mimeType in allowedFallbackMimeTypes) {
        "$mediaKind attachment data URL has an incompatible MIME type"
    }
    val payload = inspectBase64Payload(
        source = dataUrl,
        startIndex = separator + 1,
        maxDecodedBytes = budgetTracker.limits.maxInlinePartBytes,
    )
    budgetTracker.reserveInlineBytes(mediaKind, payload.decodedBytes)
    return ParsedDataUrl(mimeType = mimeType, payload = payload)
}

private fun String.requireLocalAttachmentFile(mediaKind: AttachmentMediaKind): File {
    val path = toUri().path ?: throw IllegalArgumentException("$mediaKind attachment file URI is invalid")
    val file = File(path)
    require(file.isFile) { "$mediaKind attachment file does not exist" }
    return file
}

private fun File.requireSourceBudget(
    mediaKind: AttachmentMediaKind,
    limits: AttachmentBudgetLimits,
): Long {
    val bytes = length()
    validateAttachmentSourceSize(mediaKind, bytes, limits)
    return bytes
}

internal fun validateAttachmentSourceSize(
    mediaKind: AttachmentMediaKind,
    bytes: Long,
    limits: AttachmentBudgetLimits,
) {
    require(bytes >= 0) { "Attachment source byte count must be non-negative" }
    if (bytes > limits.maxSourceFileBytes) {
        throw AttachmentBudgetExceededException(
            mediaKind = mediaKind,
            budgetName = "source file",
            actualBytes = bytes,
            limitBytes = limits.maxSourceFileBytes,
        )
    }
}

/**
 * Streams file input through Base64 into an exactly-sized byte buffer. A complete String is still
 * required by the current kotlinx.serialization JSON model, but no raw file ByteArray or growing
 * ByteArrayOutputStream is created, and all limits are checked before the encoded buffer allocation.
 */
private fun File.encodeToBase64Materialized(
    sourceBytes: Long,
    prefix: String,
    mediaKind: AttachmentMediaKind,
    budgetTracker: AttachmentBudgetTracker,
): String {
    budgetTracker.reserveInlineBytes(mediaKind, sourceBytes)
    var completed = false
    try {
        val encodedBytes = base64EncodedSize(sourceBytes)
        val totalBytes = saturatedAdd(prefix.length.toLong(), encodedBytes)
        require(totalBytes <= Int.MAX_VALUE.toLong()) { "$mediaKind attachment encoded payload is too large" }

        val output = FixedSizeByteArrayOutputStream(totalBytes.toInt())
        output.writeAscii(prefix)
        FileInputStream(this).use { input ->
            val openedSize = input.channel.size()
            require(openedSize == sourceBytes) { "$mediaKind attachment changed while it was being encoded" }
            Base64OutputStream(output, Base64.NO_WRAP).use { base64Output ->
                input.copyTo(base64Output, bufferSize = DEFAULT_BUFFER_SIZE)
            }
        }
        require(output.size == totalBytes.toInt()) { "$mediaKind attachment changed while it was being encoded" }
        return output.toLatin1String().also { completed = true }
    } finally {
        if (!completed) budgetTracker.releaseInlineBytes(sourceBytes)
    }
}

private fun File.createTemporaryJpeg(): File {
    // Android maps java.io.tmpdir to app-private cache. Prefer it so a crash cannot leave an
    // intermediate JPEG beside a user-visible attachment or conversation file.
    return try {
        File.createTempFile("rkh-attachment-", ".jpg")
    } catch (_: Exception) {
        val siblingDirectory = parentFile?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw IllegalArgumentException("Temporary storage for image attachment is unavailable")
        try {
            File.createTempFile(".rkh-attachment-", ".jpg", siblingDirectory)
        } catch (_: Exception) {
            throw IllegalArgumentException("Temporary storage for image attachment is unavailable")
        }
    }
}

private fun File.removeTemporaryAttachment() {
    if (delete()) return
    // If deletion is transiently unavailable, remove the encoded payload before one final delete
    // attempt. Do not use runCatching here: VM-level Errors (especially OOM) must remain visible.
    try {
        FileOutputStream(this, false).use { }
    } catch (_: Exception) {
        // Best effort only; the file is already inside app-private storage in the normal path.
    }
    delete()
}

private class BoundedOutputStream(
    delegate: OutputStream,
    private val maxBytes: Long,
    private val mediaKind: AttachmentMediaKind,
) : FilterOutputStream(delegate) {
    private var written = 0L

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Invalid output buffer range" }
        ensureCapacity(length)
        out.write(buffer, offset, length)
        written += length
    }

    private fun ensureCapacity(additionalBytes: Int) {
        val attempted = saturatedAdd(written, additionalBytes.toLong())
        if (attempted > maxBytes) {
            throw AttachmentBudgetExceededException(
                mediaKind = mediaKind,
                budgetName = "single inline part",
                actualBytes = attempted,
                limitBytes = maxBytes,
            )
        }
    }
}

private class FixedSizeByteArrayOutputStream(capacity: Int) : OutputStream() {
    private val buffer = ByteArray(capacity)
    var size: Int = 0
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length) { "Invalid Base64 buffer range" }
        ensureCapacity(length)
        source.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    fun writeAscii(value: String) {
        ensureCapacity(value.length)
        value.forEach { character ->
            require(character.code <= 0x7f) { "Base64 prefix must be ASCII" }
            buffer[size++] = character.code.toByte()
        }
    }

    fun toLatin1String(): String = String(buffer, 0, size, Charsets.ISO_8859_1)

    private fun ensureCapacity(additionalBytes: Int) {
        require(additionalBytes <= buffer.size - size) { "Base64 encoder exceeded its preflight size" }
    }
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long,
    maxDecodedBitmapBytes: Long = Long.MAX_VALUE,
    maxTransformPeakBytes: Long = Long.MAX_VALUE,
    requiresExifTransform: Boolean = false,
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (imageSampleExceedsBudget(
            width = width,
            height = height,
            inSampleSize = inSampleSize,
            maxDimension = maxDimension,
            maxPixels = maxPixels,
            maxDecodedBitmapBytes = maxDecodedBitmapBytes,
            maxTransformPeakBytes = maxTransformPeakBytes,
            requiresExifTransform = requiresExifTransform,
        )
    ) {
        if (inSampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun imageSampleExceedsBudget(
    width: Int,
    height: Int,
    inSampleSize: Int,
    maxDimension: Int,
    maxPixels: Long,
    maxDecodedBitmapBytes: Long,
    maxTransformPeakBytes: Long,
    requiresExifTransform: Boolean,
): Boolean {
    val sampledWidth = ceilDivide(width.toLong(), inSampleSize.toLong())
    val sampledHeight = ceilDivide(height.toLong(), inSampleSize.toLong())
    val pixels = sampledWidth * sampledHeight
    val peakCopies = if (requiresExifTransform) 2L else 1L
    return sampledWidth > maxDimension ||
        sampledHeight > maxDimension ||
        pixels > maxPixels ||
        pixels > maxDecodedBitmapBytes / BYTES_PER_ARGB_8888_PIXEL ||
        pixels > maxTransformPeakBytes / (BYTES_PER_ARGB_8888_PIXEL * peakCopies)
}

private fun ceilDivide(value: Long, divisor: Long): Long = value / divisor + if (value % divisor == 0L) 0L else 1L

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun Char.isBase64Whitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

private fun Char.isBase64AlphabetCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/'

private const val MAX_DATA_URL_METADATA_END_INDEX = 512

private fun File.guessMimeType(): String {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return "image/heic"

                "avif", "avis" -> return "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return "image/gif"
        }

        error("Unsupported image attachment format")
    }
}

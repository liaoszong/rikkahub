package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.PayloadBudgetExceededException
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaAssetEntity
import me.rerere.rikkahub.data.repository.MediaAssetIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ImageGenerationResultStoreTest {
    @Test
    fun `generated Base64 decode allows exact byte limit and rejects one extra byte before allocation`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))

        assertTrue(
            byteArrayOf(1, 2, 3, 4).contentEquals(
                decodeGeneratedImageBase64(encoded, maxDecodedBytes = 4),
            ),
        )
        val failure = runCatching {
            decodeGeneratedImageBase64(encoded, maxDecodedBytes = 3)
        }.exceptionOrNull()

        assertTrue(failure is PayloadBudgetExceededException)
        assertEquals(4L, (failure as PayloadBudgetExceededException).actualBytes)
        assertEquals(3L, failure.limitBytes)
    }

    @Test
    fun `generated Base64 decode rejects malformed payload without echoing content`() {
        val malformed = "data:image/png;base64,AA=A-private-content"

        val failure = runCatching {
            decodeGeneratedImageBase64(malformed, maxDecodedBytes = 32)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure?.message.orEmpty().contains("private-content"))
    }

    @Test
    fun `chat media asset identity is stable per tool output and distinct per index`() {
        val first = MediaAssetIds.forChatToolOutput("tool-call", 0)

        assertEquals(first, MediaAssetIds.forChatToolOutput("tool-call", 0))
        assertTrue(first != MediaAssetIds.forChatToolOutput("tool-call", 1))
    }

    @Test
    fun `validated payload chooses extension from bytes rather than requested file convention`() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        )
        val payload = decodeValidatedImage(
            ImageGenerationItem(
                data = Base64.getEncoder().encodeToString(jpeg),
                mimeType = "image/jpeg",
            ),
        )

        assertEquals("image/jpeg", payload.mimeType)
        assertEquals("jpg", payload.extension)
        assertTrue(jpeg.contentEquals(payload.bytes))
    }

    @Test
    fun `declared MIME mismatch is rejected before persistence`() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        val failure = runCatching {
            decodeValidatedImage(
                ImageGenerationItem(
                    data = Base64.getEncoder().encodeToString(png),
                    mimeType = "image/jpeg",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("MIME mismatch"))
    }

    @Test
    fun `truncated magic header is rejected as incomplete image`() {
        val failure = runCatching {
            decodeValidatedImage(
                ImageGenerationItem(
                    data = Base64.getEncoder().encodeToString(
                        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00),
                    ),
                    mimeType = "image/jpeg",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("invalid generated image payload"))
    }

    @Test
    fun `atomic write replaces target and leaves no temporary file`() {
        val directory = Files.createTempDirectory("imggen-atomic").toFile()
        val target = directory.resolve("result.png")
        target.writeText("old")

        atomicWrite(target, "new".encodeToByteArray())

        assertEquals("new", target.readText())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `failed database registration retains diagnostics and reconciles once`() = runBlocking {
        val root = Files.createTempDirectory("imggen-reconcile").toFile()
        val imagesDir = root.resolve("images").apply { mkdirs() }
        imagesDir.resolve("task-0.png").writeBytes(byteArrayOf(1))
        val inserted = mutableListOf<GenMediaEntity>()
        var fail = true
        val store = PendingImageRegistrationStore(imagesDir) { metadata, _ ->
            if (fail) error("database unavailable")
            inserted += metadata.toEntity()
            42L
        }
        val metadata = PendingImageMetadata(
            path = "images/task-0.png",
            mimeType = "image/png",
            modelId = "provider/model-id",
            modelDisplayName = "Friendly model name",
            providerId = "provider-uuid",
            prompt = "prompt",
            createAt = 123,
            type = GenMediaEntity.TYPE_IMAGE_GENERATION,
        )
        store.persist(metadata)

        assertTrue(runCatching { store.register(metadata) }.isFailure)
        val failedSidecar = imagesDir.resolve("task-0.png.imgmeta.json").readText()
        assertTrue(failedSidecar.contains("IllegalStateException"))
        assertFalse(failedSidecar.contains("database unavailable"))
        assertTrue(failedSidecar.contains("provider/model-id"))
        assertTrue(failedSidecar.contains("Friendly model name"))
        assertTrue(failedSidecar.contains("provider-uuid"))

        fail = false
        val first = store.reconcile()
        val second = store.reconcile()

        assertEquals(1, first.inspected)
        assertEquals(1, first.registered)
        assertTrue(first.failures.isEmpty())
        assertEquals("provider/model-id", inserted.single().modelId)
        assertEquals(metadata.stableAssetId(), inserted.single().assetId)
        assertEquals(0, second.inspected)
        assertEquals(0, second.registered)
    }

    @Test
    fun `reconciliation safely retries sidecar without database error marker`() = runBlocking {
        val root = Files.createTempDirectory("imggen-committed-sidecar").toFile()
        val imagesDir = root.resolve("images").apply { mkdirs() }
        val image = imagesDir.resolve("task-0.png").apply { writeBytes(byteArrayOf(1)) }
        val managedFile = ManagedFileEntity(
            id = 9,
            folder = "images",
            relativePath = "images/task-0.png",
            displayName = image.name,
            mimeType = "image/png",
            sizeBytes = image.length(),
            createdAt = 123,
            updatedAt = 123,
        )
        val migratedAsset = MediaAssetEntity(
            id = 7,
            path = "images/task-0.png",
            modelId = "model-id",
            modelDisplayName = "Display name",
            providerId = "provider-id",
            prompt = "prompt",
            createAt = 123,
            assetId = "legacy-genmedia-7",
            managedFileId = managedFile.id,
        )
        val store = PendingImageRegistrationStore(imagesDir) { metadata, _ ->
            requireNotNull(
                existingPendingMediaDatabaseId(
                    metadata = metadata,
                    imageFile = image,
                    managedFile = managedFile,
                    existingAsset = migratedAsset,
                ),
            )
        }
        val metadata = PendingImageMetadata(
            path = "images/task-0.png",
            mimeType = "image/png",
            modelId = "model-id",
            modelDisplayName = "Display name",
            providerId = "provider-id",
            prompt = "prompt",
            createAt = 123,
            type = GenMediaEntity.TYPE_IMAGE_GENERATION,
        )
        // This is the state left when the DB commit succeeded but the sidecar
        // acknowledgement did not: no databaseId and no lastError.
        store.persist(metadata)
        assertTrue(metadata.stableAssetId() != migratedAsset.assetId)

        val result = store.reconcile()

        assertEquals(1, result.inspected)
        assertEquals(1, result.registered)
        assertEquals("legacy-genmedia-7", migratedAsset.assetId)
        val repaired = Json.decodeFromString<PendingImageMetadata>(
            imagesDir.resolve("task-0.png.imgmeta.json").readText(),
        )
        assertEquals(7, repaired.databaseId)
    }
}

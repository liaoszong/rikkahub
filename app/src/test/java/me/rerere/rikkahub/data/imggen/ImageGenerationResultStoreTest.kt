package me.rerere.rikkahub.data.imggen

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ImageGenerationResultStoreTest {
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
    fun `legacy generated image JSON restores both model identities`() {
        val legacy = Json.decodeFromString<GeneratedImage>(
            """{"id":1,"prompt":"p","filePath":"/a.png","timestamp":2,"model":"Visible Model"}""",
        )

        assertEquals("Visible Model", legacy.model)
        assertEquals("Visible Model", legacy.modelId)
        assertEquals("Visible Model", legacy.modelDisplayName)
        assertEquals(null, legacy.providerId)
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
        val store = PendingImageRegistrationStore(imagesDir) { entity ->
            if (fail) error("database unavailable")
            inserted += entity
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
        assertTrue(failedSidecar.contains("database unavailable"))
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
        assertEquals(0, second.inspected)
        assertEquals(0, second.registered)
    }

    @Test
    fun `reconciliation safely retries sidecar without database error marker`() = runBlocking {
        val root = Files.createTempDirectory("imggen-committed-sidecar").toFile()
        val imagesDir = root.resolve("images").apply { mkdirs() }
        imagesDir.resolve("task-0.png").writeBytes(byteArrayOf(1))
        val rows = linkedMapOf<String, GenMediaEntity>()
        val store = PendingImageRegistrationStore(imagesDir) { entity ->
            rows.getOrPut(entity.path) { entity.copy(id = rows.size + 1) }.id.toLong()
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
        rows[metadata.path] = metadata.toEntity().copy(id = 7)

        val result = store.reconcile()

        assertEquals(1, result.inspected)
        assertEquals(1, result.registered)
        assertEquals(1, rows.size)
        val repaired = Json.decodeFromString<PendingImageMetadata>(
            imagesDir.resolve("task-0.png.imgmeta.json").readText(),
        )
        assertEquals(7, repaired.databaseId)
    }
}

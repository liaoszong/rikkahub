package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEncoderBudgetTest {
    @Test
    fun `source file budget allows exact limit and rejects one extra byte`() {
        val limits = testLimits(maxInlinePartBytes = 4, maxInlineRequestBytes = 8)

        validateAttachmentSourceSize(AttachmentMediaKind.VIDEO, 16, limits)
        val failure = assertThrows(AttachmentBudgetExceededException::class.java) {
            validateAttachmentSourceSize(AttachmentMediaKind.VIDEO, 17, limits)
        }

        assertEquals(17L, failure.actualBytes)
        assertEquals(16L, failure.limitBytes)
        assertEquals("source file", failure.budgetName)
    }

    @Test
    fun `single inline budget allows exact limit and rejects one extra byte`() {
        val limits = testLimits(maxInlinePartBytes = 4, maxInlineRequestBytes = 8)
        val exact = AttachmentBudgetTracker(limits)

        exact.reserveInlineBytes(AttachmentMediaKind.IMAGE, 4)

        assertEquals(4L, exact.usedInlineBytes())
        val failure = assertThrows(AttachmentBudgetExceededException::class.java) {
            AttachmentBudgetTracker(limits).reserveInlineBytes(AttachmentMediaKind.IMAGE, 5)
        }
        assertEquals(5L, failure.actualBytes)
        assertEquals(4L, failure.limitBytes)
        assertEquals("single inline part", failure.budgetName)
    }

    @Test
    fun `aggregate budget is shared and failed reservation does not mutate usage`() {
        val tracker = AttachmentBudgetTracker(
            testLimits(maxInlinePartBytes = 4, maxInlineRequestBytes = 6),
        )
        tracker.reserveInlineBytes(AttachmentMediaKind.IMAGE, 3)
        tracker.reserveInlineBytes(AttachmentMediaKind.AUDIO, 3)

        assertEquals(6L, tracker.usedInlineBytes())
        val failure = assertThrows(AttachmentBudgetExceededException::class.java) {
            tracker.reserveInlineBytes(AttachmentMediaKind.VIDEO, 1)
        }

        assertEquals(7L, failure.actualBytes)
        assertEquals(6L, failure.limitBytes)
        assertEquals("aggregate inline request", failure.budgetName)
        assertEquals(6L, tracker.usedInlineBytes())
    }

    @Test
    fun `data URLs use the same aggregate tracker across media kinds`() {
        val tracker = AttachmentBudgetTracker(
            testLimits(maxInlinePartBytes = 3, maxInlineRequestBytes = 5),
        )

        val image = UIMessagePart.Image("data:image/png;base64,QUJD")
            .encodeBase64(withPrefix = false, budgetTracker = tracker)
            .getOrThrow()
        val audio = UIMessagePart.Audio("data:audio/wav;base64,QUE=")
            .encodeBase64(withPrefix = false, budgetTracker = tracker)
            .getOrThrow()

        assertEquals("QUJD", image.base64)
        assertEquals("QUE=", audio)
        assertEquals(5L, tracker.usedInlineBytes())
        assertTrue(
            UIMessagePart.Video("data:video/mp4;base64,AA==")
                .encodeBase64(withPrefix = false, budgetTracker = tracker)
                .exceptionOrNull() is AttachmentBudgetExceededException,
        )
    }

    @Test
    fun `base64 inspection allows exact decoded boundary without allocating bytes`() {
        val info = inspectBase64Payload(
            source = "  QUJD\n",
            maxDecodedBytes = 3,
        )

        assertEquals(3L, info.decodedBytes)
        assertEquals(2, info.payloadStartIndex)
        assertEquals(6, info.payloadEndIndex)
        assertEquals(4L, base64EncodedSize(info.decodedBytes))
    }

    @Test
    fun `base64 inspection rejects one decoded byte over budget and malformed inputs`() {
        val tooLarge = assertThrows(PayloadBudgetExceededException::class.java) {
            inspectBase64Payload("QUJD", maxDecodedBytes = 2)
        }
        assertEquals(3L, tooLarge.actualBytes)
        assertEquals(2L, tooLarge.limitBytes)

        listOf("A", "AA=A", "A===", "QU JD", "***=").forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                inspectBase64Payload(malformed)
            }
        }
    }

    @Test
    fun `EXIF copy peak downsamples 16 megapixels while ordinary decode stays full size`() {
        val common = mapOf(
            "width" to 4_000,
            "height" to 4_000,
        )
        val withoutTransform = calculateImageInSampleSize(
            width = common.getValue("width"),
            height = common.getValue("height"),
            maxDimension = 10_000,
            maxPixels = 16_000_000,
            maxDecodedBitmapBytes = 64L * 1024 * 1024,
            maxTransformPeakBytes = 96L * 1024 * 1024,
            requiresExifTransform = false,
        )
        val withTransform = calculateImageInSampleSize(
            width = common.getValue("width"),
            height = common.getValue("height"),
            maxDimension = 10_000,
            maxPixels = 16_000_000,
            maxDecodedBitmapBytes = 64L * 1024 * 1024,
            maxTransformPeakBytes = 96L * 1024 * 1024,
            requiresExifTransform = true,
        )

        assertEquals(1, withoutTransform)
        assertEquals(2, withTransform)
    }

    @Test
    fun `twelve megapixel phone image remains full resolution even with EXIF transform`() {
        assertEquals(
            1,
            calculateImageInSampleSize(
                width = 4_000,
                height = 3_000,
                maxDimension = 10_000,
                maxPixels = 16_000_000,
                maxDecodedBitmapBytes = 64L * 1024 * 1024,
                maxTransformPeakBytes = 96L * 1024 * 1024,
                requiresExifTransform = true,
            ),
        )
    }

    @Test
    fun `budget errors never expose attachment source`() {
        val secretMarker = "private-user-path"
        val error = UIMessagePart.Image("data:image/png;name=$secretMarker;base64,QUJD")
            .encodeBase64(
                budgetTracker = AttachmentBudgetTracker(
                    testLimits(maxInlinePartBytes = 2, maxInlineRequestBytes = 2),
                ),
            )
            .exceptionOrNull()

        assertTrue(error is PayloadBudgetExceededException)
        assertFalse(error?.message.orEmpty().contains(secretMarker))
        assertFalse(error?.message.orEmpty().contains("data:image"))
    }

    private fun testLimits(
        maxInlinePartBytes: Long,
        maxInlineRequestBytes: Long,
    ) = AttachmentBudgetLimits(
        maxSourceFileBytes = 16,
        maxInlinePartBytes = maxInlinePartBytes,
        maxInlineRequestBytes = maxInlineRequestBytes,
        maxImageDimension = 100,
        maxImagePixels = 10_000,
        maxDecodedBitmapBytes = 40_000,
        maxExifTransformPeakBytes = 80_000,
    )
}

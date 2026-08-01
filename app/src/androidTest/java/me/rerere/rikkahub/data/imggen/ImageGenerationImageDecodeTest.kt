package me.rerere.rikkahub.data.imggen

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ImageGenerationImageDecodeTest {
    @Test
    fun androidDecoderAcceptsCompletePngAndRejectsStructurallyPlausibleFakeJpeg() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val fakeJpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        )

        assertTrue(isAndroidDecodableImage(png))
        assertFalse(isAndroidDecodableImage(fakeJpeg))
    }
}

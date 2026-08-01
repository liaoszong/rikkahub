package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRepositoryChunkedReadTest {
    @Test
    fun `chunked reader reconstructs oversized unicode message without loss`() = runBlocking {
        val source = "图像与长工具结果".repeat(300_000)

        val restored = readChunkedText(chunkSize = 256 * 1024) { start, length ->
            source.substring(
                startIndex = (start - 1).coerceAtMost(source.length),
                endIndex = (start - 1 + length).coerceAtMost(source.length),
            )
        }

        assertEquals(source, restored)
    }

    @Test
    fun `chunked reader reports missing row instead of returning partial content`() = runBlocking {
        var calls = 0

        val restored = readChunkedText(chunkSize = 4) { _, _ ->
            calls++
            if (calls == 1) "abcd" else null
        }

        assertNull(restored)
    }
}

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRepositoryChunkedReadTest {
    @Test
    fun `chunked reader reconstructs oversized unicode message without loss`() = runBlocking {
        val source = "图像😀与长工具结果🧪".repeat(300_000)

        val restored = readChunkedText(chunkSize = 256 * 1024) { start, length ->
            source.sqliteSubstr(start, length)
        }

        assertEquals(source, restored)
    }

    @Test
    fun `supplementary character at chunk boundary does not skip the next character`() = runBlocking {
        val chunkSize = 256 * 1024
        val source = "a".repeat(chunkSize - 1) + "😀下一段"

        val restored = readChunkedText(chunkSize) { start, length ->
            source.sqliteSubstr(start, length)
        }

        assertEquals(source, restored)
    }

    @Test
    fun `exact chunk and empty text terminate without adding or dropping content`() = runBlocking {
        val exact = "界😀".repeat(8)
        val exactCodePoints = exact.codePointCount(0, exact.length)
        var exactCalls = 0

        val exactRestored = readChunkedText(exactCodePoints) { start, length ->
            exactCalls++
            exact.sqliteSubstr(start, length)
        }
        var emptyCalls = 0
        val emptyRestored = readChunkedText(chunkSize = 4) { start, length ->
            emptyCalls++
            "".sqliteSubstr(start, length)
        }

        assertEquals(exact, exactRestored)
        assertEquals(2, exactCalls)
        assertEquals("", emptyRestored)
        assertEquals(1, emptyCalls)
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

/** Mirrors SQLite substr(TEXT, start, length): one-based Unicode code-point offsets. */
private fun String.sqliteSubstr(start: Int, length: Int): String {
    require(start >= 1)
    require(length >= 0)
    val codePoints = codePointCount(0, this.length)
    if (start > codePoints || length == 0) return ""
    val startIndex = offsetByCodePoints(0, start - 1)
    val consumed = minOf(length, codePoints - start + 1)
    val endIndex = offsetByCodePoints(startIndex, consumed)
    return substring(startIndex, endIndex)
}

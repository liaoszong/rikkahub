package me.rerere.rikkahub.data.sync.v2

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncEnvelopeCipherTest {
    @Test
    fun `remote layout is deterministic and traversal safe`() {
        assertEquals(
            "rikkahub-sync/v2/space-1/ops/device-1/00000000000000000042.json.enc",
            SyncRemoteLayout.operation("space-1", "device-1", 42).value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SyncRemoteLayout.operation("../escape", "device-1", 1)
        }
    }

    @Test
    fun `envelope is path authenticated and randomized`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "private sync payload".toByteArray()
        val path = SyncRemoteLayout.deviceHead("space-1", "device-1")
        SyncEnvelopeCipher(key).use { cipher ->
            val first = cipher.encrypt(path, plaintext)
            val second = cipher.encrypt(path, plaintext)
            assertNotEquals(first.toList(), second.toList())
            assertArrayEquals(plaintext, cipher.decrypt(path, first))
            assertThrows(GeneralSecurityException::class.java) {
                cipher.decrypt(SyncRemoteLayout.deviceHead("space-1", "device-2"), first)
            }
        }
    }

    @Test
    fun `keyed content hash is stable inside one space and unlinkable across spaces`() {
        val content = "same attachment".toByteArray()
        val first = SyncEnvelopeCipher(ByteArray(32) { 1 })
        val second = SyncEnvelopeCipher(ByteArray(32) { 2 })
        try {
            assertEquals(first.keyedContentHash(content), first.keyedContentHash(content))
            assertNotEquals(first.keyedContentHash(content), second.keyedContentHash(content))
        } finally {
            first.close()
            second.close()
        }
    }
}

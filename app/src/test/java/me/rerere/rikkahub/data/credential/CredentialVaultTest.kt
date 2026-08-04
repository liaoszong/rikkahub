package me.rerere.rikkahub.data.credential

import java.nio.file.Files
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `slot id is stable and reference parser is strict`() {
        val first = CredentialSlotId.of("provider", "owner-1", "api-key")
        val second = CredentialSlotId.of("provider", "owner-1", "api-key")
        assertEquals(first, second)
        assertNotEquals(first, CredentialSlotId.of("provider", "owner-2", "api-key"))

        val ref = CredentialRefId.new()
        assertTrue(CredentialRefId.isReference(ref.referenceString()))
        assertEquals(ref, CredentialRefId.parseReference(ref.referenceString()))
        assertFalse(CredentialRefId.isReference("vault:v1:not-a-uuid"))
        assertFalse(CredentialRefId.isReference("plain-secret"))
    }

    @Test
    fun `create encrypts secret and resolves by active slot and immutable ref`() = withVault { root, vault ->
        val secret = "sk-do-not-persist-in-plain-text".toByteArray()
        val written = vault.create(address(), secret) as CredentialWriteResult.Written

        val envelope = root.listFiles()!!.single { it.name.startsWith("credential.") }.readBytes()
        assertFalse(String(envelope).contains(String(secret)))
        val byReference = vault.resolve(written.reference) as CredentialReadResult.Found
        val bySlot = vault.resolveActive(address().slotId, address().audience) as CredentialReadResult.Found
        assertEquals(written.refId, byReference.value.refId)
        assertArrayEquals(secret, byReference.value.secret)
        assertEquals(byReference.value, bySlot.value)
        assertTrue((vault.create(address(), "other".toByteArray()) as CredentialWriteResult.Conflict).currentRefId != null)
    }

    @Test
    fun `revision cas creates immutable ref and rejects stale writer`() = withVault { _, vault ->
        val created = vault.create(address(), "first".toByteArray()) as CredentialWriteResult.Written
        val updated = vault.compareAndSet(created.refId, 1, "second".toByteArray()) as CredentialWriteResult.Written
        assertNotEquals(created.refId, updated.refId)
        assertEquals(2, updated.revision)
        assertTrue(vault.compareAndSet(created.refId, 1, "stale".toByteArray()) is CredentialWriteResult.Conflict)
        val oldValue = (vault.resolve(created.refId) as CredentialReadResult.Found).value
        assertEquals(1, oldValue.revision)
        assertArrayEquals("first".toByteArray(), oldValue.secret)
        val value = (vault.resolve(updated.refId) as CredentialReadResult.Found).value
        assertEquals(2, value.revision)
        assertArrayEquals("second".toByteArray(), value.secret)
    }

    @Test
    fun `rotation changes active immutable ref while old reference remains resolvable`() = withVault { _, vault ->
        val created = vault.create(address(), "old".toByteArray()) as CredentialWriteResult.Written
        val rotated = vault.rotate(
            address(),
            CredentialExpectation(created.refId, created.revision),
            "new".toByteArray(),
        ) as CredentialWriteResult.Written
        assertNotEquals(created.refId, rotated.refId)
        assertArrayEquals("old".toByteArray(), (vault.resolve(created.refId) as CredentialReadResult.Found).value.secret)
        val active = vault.resolveActive(address().slotId, address().audience) as CredentialReadResult.Found
        assertEquals(rotated.refId, active.value.refId)
        assertArrayEquals("new".toByteArray(), active.value.secret)
    }

    @Test
    fun `audience rebind requires old binding proof and only stores replacement secret`() = withVault { _, vault ->
        val originalAddress = address()
        val created = vault.create(originalAddress, "old-secret".toByteArray()) as CredentialWriteResult.Written
        val reboundAddress = originalAddress.copy(audience = "https://new-api.example.test")

        assertTrue(
            vault.rebindAudience(
                reboundAddress,
                CredentialExpectation(created.refId, created.revision + 1),
                "replacement-secret".toByteArray(),
            ) is CredentialWriteResult.Conflict,
        )

        val rebound = vault.rebindAudience(
            reboundAddress,
            CredentialExpectation(created.refId, created.revision),
            "replacement-secret".toByteArray(),
        ) as CredentialWriteResult.Written
        assertNotEquals(created.refId, rebound.refId)
        assertArrayEquals(
            "old-secret".toByteArray(),
            (vault.resolve(created.refId) as CredentialReadResult.Found).value.secret,
        )
        val active = vault.resolveActive(reboundAddress.slotId, reboundAddress.audience) as CredentialReadResult.Found
        assertEquals(rebound.refId, active.value.refId)
        assertArrayEquals("replacement-secret".toByteArray(), active.value.secret)
        assertTrue(vault.resolveActive(originalAddress.slotId, originalAddress.audience) is CredentialReadResult.Missing)
    }

    @Test
    fun `tamper is reported as corrupt and ciphertext is retained`() = withVault { root, vault ->
        val created = vault.create(address(), "secret".toByteArray()) as CredentialWriteResult.Written
        val file = root.listFiles()!!.single { it.name.startsWith("credential.") }
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last() xor 1) }
        file.writeBytes(bytes)
        assertTrue(vault.resolve(created.refId) is CredentialReadResult.Corrupt)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `key failure becomes locked and does not clear envelope`() {
        val root = Files.createTempDirectory("credential-vault-locked").toFile()
        try {
            val normal = CredentialVault(root, InMemoryWrappingKeyProvider(key))
            val created = normal.create(address(), "secret".toByteArray()) as CredentialWriteResult.Written
            val file = root.listFiles()!!.single { it.name.startsWith("credential.") }
            val before = file.readBytes()
            val locked = CredentialVault(root, object : CredentialWrappingKeyProvider {
                override fun getOrCreate(): SecretKey = throw CredentialKeyException(CredentialLockReason.KEY_INVALIDATED)
                override fun getExisting(): SecretKey = throw CredentialKeyException(CredentialLockReason.KEY_INVALIDATED)
            })
            val result = locked.resolve(created.refId)
            assertTrue(result is CredentialReadResult.Locked)
            assertArrayEquals(before, file.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `authenticated reference index rejects tamper without losing credential envelope`() = withVault { root, vault ->
        val created = vault.create(address(), "secret".toByteArray()) as CredentialWriteResult.Written
        val index = root.resolve("references.v1")
        val bytes = index.readBytes().also { it[it.lastIndex] = (it.last() xor 1) }
        index.writeBytes(bytes)

        assertTrue(vault.resolveActive(address().slotId, address().audience) is CredentialReadResult.Corrupt)
        assertTrue(vault.resolve(created.refId) is CredentialReadResult.Found)
    }

    @Test
    fun `index write failure returns recoverable orphan`() = withVault { root, vault ->
        val blockedIndex = root.resolve("references.v1")
        assertTrue(blockedIndex.mkdir())
        val result = vault.create(address(), "secret".toByteArray()) as CredentialWriteResult.Orphaned
        assertTrue(vault.resolve(result.refId) is CredentialReadResult.Found)

        assertTrue(blockedIndex.delete())
        val recovered = vault.recoverOrphan(address(), result.refId) as CredentialWriteResult.Written
        assertEquals(result.refId, recovered.refId)
        assertEquals(result.refId, (vault.resolveActive(address().slotId, address().audience) as CredentialReadResult.Found).value.refId)
    }

    @Test
    fun `cas crash rollback can restore old immutable active ref`() = withVault { root, vault ->
        val created = vault.create(address(), "first".toByteArray()) as CredentialWriteResult.Written
        val indexFile = root.resolve("references.v1")
        val staleIndex = indexFile.readBytes()
        val updated = vault.compareAndSet(created.refId, 1, "second".toByteArray()) as CredentialWriteResult.Written

        // Models DataStore remaining on the previous reference after the vault index switched.
        indexFile.writeBytes(staleIndex)
        val resolved = vault.resolveActive(address().slotId, address().audience) as CredentialReadResult.Found
        assertEquals(created.refId, resolved.value.refId)
        assertEquals(1, resolved.value.revision)
        assertArrayEquals("first".toByteArray(), resolved.value.secret)
        assertTrue(vault.resolve(updated.refId) is CredentialReadResult.Found)
    }

    private fun address(): CredentialAddress {
        val namespace = "provider"
        val owner = "provider-stable-id"
        val field = "api-key"
        return CredentialAddress(
            slotId = CredentialSlotId.of(namespace, owner, field),
            namespace = namespace,
            ownerStableId = owner,
            fieldSlot = field,
            kind = "api_key",
            audience = "https://api.example.test",
        )
    }

    private fun withVault(block: (java.io.File, CredentialVault) -> Unit) {
        val root = Files.createTempDirectory("credential-vault-test").toFile()
        try {
            block(root, CredentialVault(root, InMemoryWrappingKeyProvider(key)))
        } finally {
            root.deleteRecursively()
        }
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}

package me.rerere.rikkahub.data.credential

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialVaultTest {
    @Test
    fun keystoreWrappedEnvelopeSurvivesVaultReopenInsideNoBackupDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "credential_vault_instrumentation")
        root.deleteRecursively()
        val provider = AndroidKeystoreWrappingKeyProvider("rikkahub_credential_vault_instrumentation_v1")
        try {
            val owner = "instrumentation-${UUID.randomUUID()}"
            val address = CredentialAddress(
                slotId = CredentialSlotId.of("test", owner, "token"),
                namespace = "test",
                ownerStableId = owner,
                fieldSlot = "token",
                kind = "oauth_refresh_token",
                audience = "instrumentation",
            )
            val secret = "android-keystore-secret".toByteArray()
            val writeResult = CredentialVault(root, provider).create(address, secret)
            assertTrue("Credential Vault write failed: $writeResult", writeResult is CredentialWriteResult.Written)
            val written = writeResult as CredentialWriteResult.Written
            assertTrue(root.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))

            val envelope = root.listFiles()!!.single { it.name.startsWith("credential.") }.readBytes()
            assertFalse(String(envelope).contains(String(secret)))
            val reopened = CredentialVault(root, provider).resolve(written.reference) as CredentialReadResult.Found
            assertEquals(written.refId, reopened.value.refId)
            assertArrayEquals(secret, reopened.value.secret)
        } finally {
            root.deleteRecursively()
        }
    }
}

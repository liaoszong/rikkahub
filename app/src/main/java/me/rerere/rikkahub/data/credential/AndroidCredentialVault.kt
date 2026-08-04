package me.rerere.rikkahub.data.credential

import android.content.Context
import java.io.File

object AndroidCredentialVault {
    const val DIRECTORY_NAME = "credential_vault_v1"

    fun create(context: Context): CredentialVault = CredentialVault(
        root = root(context),
        wrappingKeys = AndroidKeystoreWrappingKeyProvider(),
    )

    fun migrationJournal(context: Context): CredentialMigrationJournal = CredentialMigrationJournal(root(context))

    fun root(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME).also { directory ->
        check(directory.exists() || directory.mkdirs()) { "Unable to create no-backup credential vault" }
        check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile) {
            "Credential vault escaped noBackupFilesDir"
        }
    }
}

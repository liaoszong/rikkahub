package me.rerere.rikkahub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupSettingsSanitizer
import me.rerere.rikkahub.data.sync.BackupRestoreCoordinator
import me.rerere.rikkahub.data.sync.PendingRestoreManager
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupDatabaseSnapshotTest {
    @Test
    fun vacuumIntoProducesReadableStandaloneDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val coordinator = BackupRestoreCoordinator(
            context = context,
            json = koin.get<Json>(),
            database = koin.get<AppDatabase>(),
        )

        val snapshot = coordinator.createDatabaseSnapshot()
        try {
            assertTrue(snapshot.isFile)
            assertTrue(snapshot.length() >= 100)
            SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).equals("ok", ignoreCase = true))
                }
            }
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun stagedRestoreRejectsPayloadTamperingBeforeTouchingLiveDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val json = koin.get<Json>()
        val database = koin.get<AppDatabase>()
        val coordinator = BackupRestoreCoordinator(context, json, database)
        val archive = File(context.cacheDir, "restore-integrity-test.zip")
        PendingRestoreManager.restoreRoot(context).deleteRecursively()

        val snapshot = coordinator.createDatabaseSnapshot()
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(
                    BackupSettingsSanitizer.encode(
                        koin.get<SettingsStore>().settingsFlow.value,
                        json,
                    ).toByteArray()
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("rikka_hub.db"))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            coordinator.stageRestore(archive, restoreDatabase = true, restoreFiles = false)
            val pending = PendingRestoreManager.pendingDirectory(context)
            assertTrue(File(pending, "manifest.json").isFile)
            File(pending, "payload/rikka_hub.db").appendBytes(byteArrayOf(1))

            assertThrows(IllegalStateException::class.java) {
                PendingRestoreManager.applyFilesBeforeDatabase(context)
            }
            database.openHelper.readableDatabase.query("SELECT 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            snapshot.delete()
            archive.delete()
            PendingRestoreManager.restoreRoot(context).deleteRecursively()
        }
    }
}

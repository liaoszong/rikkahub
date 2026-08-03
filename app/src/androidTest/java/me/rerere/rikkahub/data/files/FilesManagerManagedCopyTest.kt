package me.rerere.rikkahub.data.files

import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilesManagerManagedCopyTest {
    @Test
    fun failedSourceOpenRemovesTheReservedTargetAndDoesNotRegisterIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val appScope = AppScope()
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val beforeFiles = uploadDir.listFiles().orEmpty().map(File::getName).toSet()
        val manager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        try {
            val missing = File(context.cacheDir, "missing-fork-source-${System.nanoTime()}.bin")

            assertThrows(Exception::class.java) {
                runBlocking {
                    manager.saveManagedFromUri(
                        folder = FileFolders.UPLOAD,
                        uri = missing.toUri(),
                        displayName = "fork-copy.bin",
                    )
                }
            }

            assertEquals(beforeFiles, uploadDir.listFiles().orEmpty().map(File::getName).toSet())
            assertEquals(0, database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)
        } finally {
            appScope.cancel()
            database.close()
        }
    }
}

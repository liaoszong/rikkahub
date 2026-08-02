package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedFileDAOTest {
    @Test
    fun pathReplayAndUpdatesPreserveNumericAndStableIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        try {
            val first = database.managedFileDao().insertOrGet(file(fileId = "file-stable"))
            val replay = database.managedFileDao().insertOrGet(file(fileId = "file-replay"))

            assertEquals(first.id, replay.id)
            assertEquals("file-stable", replay.fileId)
            assertEquals(
                0,
                database.managedFileDao().update(first.copy(fileId = "file-rebound", sizeBytes = 20)),
            )
            assertEquals("file-stable", database.managedFileDao().getById(first.id)?.fileId)
            assertEquals(1, database.managedFileDao().update(first.copy(sizeBytes = 30)))
            assertEquals(30L, database.managedFileDao().getByFileId("file-stable")?.sizeBytes)
        } finally {
            database.close()
        }
    }

    private fun file(fileId: String) = ManagedFileEntity(
        fileId = fileId,
        folder = "images",
        relativePath = "images/result.png",
        displayName = "result.png",
        mimeType = "image/png",
        sizeBytes = 10,
        createdAt = 1,
        updatedAt = 1,
    )
}

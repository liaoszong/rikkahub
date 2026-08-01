package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenMediaDAOTest {
    @Test
    fun insertOrGetRegistersAPathExactlyOnceAndReturnsCommittedIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        try {
            val first = database.genMediaDao().insertOrGet(
                media(path = "images/result.png", prompt = "first"),
            )
            val replay = database.genMediaDao().insertOrGet(
                media(path = "images/result.png", prompt = "replay"),
            )

            assertEquals(first.id, replay.id)
            assertEquals("first", replay.prompt)
            assertEquals(1, database.genMediaDao().getAllMedia().size)
        } finally {
            database.close()
        }
    }

    private fun media(path: String, prompt: String) = GenMediaEntity(
        path = path,
        modelId = "model-id",
        modelDisplayName = "Display name",
        providerId = "provider-id",
        prompt = prompt,
        createAt = 1,
    )
}

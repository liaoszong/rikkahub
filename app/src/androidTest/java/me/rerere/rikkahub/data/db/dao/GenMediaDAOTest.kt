package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MediaMigrationJournalEntity
import me.rerere.rikkahub.data.db.entity.MediaV2Values
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun visibilityIsReversibleAndParentDeletionDoesNotDeleteEditedAsset() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        try {
            val parent = database.genMediaDao().insertOrGet(
                media(path = "images/parent.png", prompt = "parent").copy(assetId = "asset-parent"),
            )
            val child = database.genMediaDao().insertOrGet(
                media(path = "images/child.png", prompt = "child").copy(
                    assetId = "asset-child",
                    parentAssetId = parent.assetId,
                ),
            )

            assertEquals(2, database.genMediaDao().getAllMedia().size)
            assertEquals(1, database.genMediaDao().hide(parent.assetId, 20))
            assertEquals(listOf(child.assetId), database.genMediaDao().getAllMedia().map { it.assetId })
            assertEquals(2, database.genMediaDao().getAllMediaIncludingHidden().size)
            assertEquals(1, database.genMediaDao().restore(parent.assetId, 30))

            database.genMediaDao().insertJournalIgnore(
                MediaMigrationJournalEntity(
                    journalId = "journal-parent-reference",
                    scopeKind = "asset",
                    scopeKey = parent.assetId,
                    stage = MediaV2Values.STAGE_REFERENCE_BACKFILL,
                    state = MediaV2Values.JOURNAL_COMPLETE,
                    updatedAt = 30,
                ),
            )

            database.genMediaDao().delete(parent.id)
            val survivingChild = database.genMediaDao().getByAssetId(child.assetId)
            assertTrue(survivingChild != null)
            assertNull(survivingChild?.parentAssetId)
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

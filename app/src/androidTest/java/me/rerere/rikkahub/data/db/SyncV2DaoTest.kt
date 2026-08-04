package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncRecordHeadEntity
import me.rerere.rikkahub.data.db.entity.SyncReplicaEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncV2DaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun counterHeadAndOutboxCommitAtomically() = runBlocking {
        val dao = database.syncV2Dao()
        dao.insertReplica(replica())
        dao.commitLocalOperation(
            expectedPreviousCounter = 0,
            head = head(operationId = "operation-1", entityId = "conversation-1", counter = 1),
            operation = outbox(operationId = "operation-1", entityId = "conversation-1", counter = 1),
        )

        assertEquals(1L, dao.getReplica(SPACE, EPOCH, REPLICA)?.operationCounter)
        assertNotNull(dao.getRecordHead(SPACE, EPOCH, "conversation", "conversation-1"))
        assertNotNull(dao.getOutbox("operation-1"))

        assertThrows(Exception::class.java) {
            runBlocking {
                dao.commitLocalOperation(
                    expectedPreviousCounter = 1,
                    head = head(operationId = "operation-1", entityId = "conversation-2", counter = 2),
                    operation = outbox(operationId = "operation-1", entityId = "conversation-2", counter = 2),
                )
            }
        }

        assertEquals(1L, dao.getReplica(SPACE, EPOCH, REPLICA)?.operationCounter)
        assertNull(dao.getRecordHead(SPACE, EPOCH, "conversation", "conversation-2"))
    }

    @Test
    fun equalEntityIdsRemainIsolatedAcrossSpacesAndEpochs() = runBlocking {
        val dao = database.syncV2Dao()
        dao.upsertRecordHead(
            head(
                "operation-a",
                "shared",
                1,
                spaceId = "space-a",
                syncEpoch = "epoch-a",
                replicaId = "replica-a",
            ),
        )
        dao.upsertRecordHead(
            head(
                "operation-b",
                "shared",
                1,
                spaceId = "space-b",
                syncEpoch = "epoch-b",
                replicaId = "replica-b",
            ),
        )

        assertEquals(
            "operation-a",
            dao.getRecordHead("space-a", "epoch-a", "conversation", "shared")?.operationId,
        )
        assertEquals(
            "operation-b",
            dao.getRecordHead("space-b", "epoch-b", "conversation", "shared")?.operationId,
        )
    }

    private fun replica() = SyncReplicaEntity(
        replicaId = REPLICA,
        spaceId = SPACE,
        syncEpoch = EPOCH,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun head(
        operationId: String,
        entityId: String,
        counter: Long,
        spaceId: String = SPACE,
        syncEpoch: String = EPOCH,
        replicaId: String = REPLICA,
    ) = SyncRecordHeadEntity(
        spaceId = spaceId,
        syncEpoch = syncEpoch,
        entityType = "conversation",
        entityId = entityId,
        operationId = operationId,
        dotReplicaId = replicaId,
        dotCounter = counter,
        writerReplicaId = replicaId,
        causalVectorJson = "{}",
        hlcPhysicalMs = counter,
        hlcLogical = 0,
        updatedAt = counter,
    )

    private fun outbox(
        operationId: String,
        entityId: String,
        counter: Long,
    ) = SyncOutboxEntity(
        operationId = operationId,
        spaceId = SPACE,
        syncEpoch = EPOCH,
        replicaId = REPLICA,
        sequence = counter,
        entityType = "conversation",
        entityId = entityId,
        baseVectorJson = "{}",
        dotCounter = counter,
        hlcPhysicalMs = counter,
        hlcLogical = 0,
        envelopeBytes = byteArrayOf(counter.toByte()),
        state = "pending",
        nextAttemptAt = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val SPACE = "space-a"
        const val EPOCH = "epoch-a"
        const val REPLICA = "replica-a"
    }
}

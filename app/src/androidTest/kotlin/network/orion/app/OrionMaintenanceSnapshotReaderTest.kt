package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.model.Sensitivity
import network.orion.core.observability.*
import network.orion.core.security.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.RetentionResult
import network.orion.data.tasks.OrionTaskStore
import network.orion.data.tasks.TaskCreationResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionMaintenanceSnapshotReaderTest {
    @Test fun aggregateObservationDoesNotMutateOrExposeBusinessRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        val crypto = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 43 }, "AES"))
            override fun keyForVersion(version: Int) = currentKey()
        })
        try {
            val clock = AndroidOrionClock(context.contentResolver)
            val id = UUID.randomUUID()
            assertTrue(OrionTaskStore(db, crypto, clock).create(id, "SECRET_FIXTURE_CONTENT".toByteArray(),
                Sensitivity.SECRET, id) is TaskCreationResult.Created)
            val original = checkNotNull(db.tasks().find(id.toString())).contentEnvelope.copyOf()
            val observation = readMaintenanceSnapshot(context, db, clock, OrionMaintenanceOrigin.RECONCILIATION,
                true, RetentionResult(0, 0, 0, 0, 0))
            assertEquals(1L, observation.metrics[OrionMaintenanceMetric.TASK_TOTAL])
            assertEquals(1L, observation.metrics[OrionMaintenanceMetric.EVENT_TOTAL])
            assertEquals(0L, observation.metrics[OrionMaintenanceMetric.ACTION_UNKNOWN])
            assertFalse(observation.toLogLine().contains("SECRET_FIXTURE_CONTENT"))
            assertFalse(observation.toLogLine().contains(id.toString()))
            assertArrayEquals(original, db.tasks().find(id.toString())?.contentEnvelope)
            assertEquals(1, db.events().count())
        } finally { db.close() }
    }
}

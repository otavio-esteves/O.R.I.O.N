package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.repository.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableIngressStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_ingress_test_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private var now = 1000L
    private val barrier = StartupBarrier()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var keysAvailable = true
    private val keys = object : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = if (keysAvailable) FieldKeyResult.Available(1, key) else FieldKeyResult.Unavailable
        override fun keyForVersion(version: Int): FieldKeyResult = currentKey()
    }
    private var calls = 0
    private var consume: (LocalIngressPayload) -> LocalIngressDisposition = { calls++; LocalIngressDisposition.CONSUMED }
    private lateinit var store: OrionDurableIngressStore
    @Before fun open() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        db.openHelper.writableDatabase
        store = OrionDurableIngressStore(db, OrionFieldEncryption(keys), WallClock { Instant.ofEpochMilli(now) }, barrier,
            LocalIngressConsumer { consume(it) })
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun release() { assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)) }
    private fun request(id: UUID = UUID.randomUUID(), key: UUID = UUID.randomUUID(), priority: Int = 0,
        bytes: ByteArray = "synthetic SECRET marker ingress".toByteArray()) = DurableIngressRequest(id, "task.create", key,
        "ui", "CreateTask", 1, Sensitivity.SECRET, false, null, bytes, 1000, 2000, priority, UUID.randomUUID())

    @Test fun enqueueWhileGatedIsEncryptedAndDirectStoreDrainAlsoCannotBypassBarrier() {
        val request = request()
        assertEquals(DurableIngressOutcome.QUEUED, store.enqueue(request))
        val row = db.ingress().find(request.ingressId.toString())!!
        assertFalse(String(row.payloadEnvelope, Charsets.ISO_8859_1).contains("synthetic SECRET marker ingress"))
        assertFalse(store.consumeNext())
        assertEquals(0, calls)
        release()
        assertTrue(store.consumeNext())
        assertEquals(1, calls)
        assertEquals(DurableIngressStatus.CONSUMED, db.ingress().find(row.ingressId)?.status)
    }

    @Test fun replayDeduplicatesAndChangedCanonicalPayloadConflicts() {
        val key = UUID.randomUUID()
        assertEquals(DurableIngressOutcome.QUEUED, store.enqueue(request(key = key)))
        now += 20
        assertEquals(DurableIngressOutcome.DEDUP, store.enqueue(request(key = key)))
        assertEquals(DurableIngressOutcome.IDEMPOTENCY_CONFLICT, store.enqueue(request(key = key, bytes = byteArrayOf(8))))
    }

    @Test fun consumerFailureRollsBackLocalMutationAndIngressClaimTogether() {
        val request = request()
        store.enqueue(request); release()
        val technicalId = UUID.randomUUID().toString()
        consume = {
            db.records().appendTechnical(TechnicalEventEntity(technicalId, "INGRESS", "APPLIED", now, UUID.randomUUID().toString()))
            error("Synthetic interrupted transaction")
        }
        assertThrows(IllegalStateException::class.java) { store.consumeNext() }
        val row = db.ingress().find(request.ingressId.toString())!!
        assertEquals(DurableIngressStatus.QUEUED, row.status)
        assertEquals(0, row.attemptCount)
        db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_technical_events WHERE technicalEventId = ?", arrayOf(technicalId)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun priorityAndExpiryAreRespectedWithoutInvokingExpiredConsumer() {
        val low = request(priority = 1)
        val high = request(priority = 20)
        store.enqueue(low); store.enqueue(high); release()
        val seen = mutableListOf<UUID>()
        consume = { seen.add(it.ingressId); LocalIngressDisposition.CONSUMED }
        assertTrue(store.consumeNext())
        assertEquals(listOf(high.ingressId), seen)
        now = 2000
        assertTrue(store.consumeNext())
        assertEquals(DurableIngressStatus.EXPIRED, db.ingress().find(low.ingressId.toString())?.status)
        assertEquals(1, seen.size)
    }

    @Test fun missingKeysPreserveQueueAndTamperedMetadataNeverReachesConsumer() {
        val request = request()
        store.enqueue(request); release(); keysAvailable = false
        assertFalse(store.consumeNext())
        assertEquals(DurableIngressStatus.QUEUED, db.ingress().find(request.ingressId.toString())?.status)
        keysAvailable = true
        db.openHelper.writableDatabase.execSQL("UPDATE orion_ingress_envelopes SET commandType = 'ChangedCommand'")
        assertTrue(store.consumeNext())
        assertEquals(0, calls)
        assertEquals(DurableIngressStatus.REJECTED, db.ingress().find(request.ingressId.toString())?.status)
    }

    @Test fun wallRollbackBlocksConsumptionUntilTimeRecovers() {
        val request = request()
        store.enqueue(request); release(); now = 999
        assertFalse(store.consumeNext())
        now = 1000
        assertTrue(store.consumeNext())
        assertEquals(1, calls)
    }
}

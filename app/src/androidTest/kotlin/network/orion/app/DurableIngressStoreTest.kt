package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.security.InvalidAlgorithmParameterException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
    private var cryptoUnavailable = false
    // Inject a checked provider failure inside Cipher.init, without replacing crypto or storage.
    private val unavailableKey = object : SecretKey {
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        override fun getEncoded(): ByteArray = throw InvalidAlgorithmParameterException("Synthetic provider unavailable")
    }
    private val keys = object : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = if (keysAvailable) FieldKeyResult.Available(1, key) else FieldKeyResult.Unavailable
        override fun keyForVersion(version: Int): FieldKeyResult =
            if (cryptoUnavailable) FieldKeyResult.Available(1, unavailableKey) else currentKey()
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
        bytes: ByteArray = "synthetic SECRET marker ingress".toByteArray(), scope: String = "task.create") = DurableIngressRequest(id, scope, key,
        "ui", "CreateTask", 1, Sensitivity.SECRET, false, null, bytes, 1000, 2000, priority, UUID.randomUUID())

    private fun scoped(scope: String, consumer: LocalIngressConsumer) = OrionDurableIngressStore(db,
        OrionFieldEncryption(keys), WallClock { Instant.ofEpochMilli(now) }, barrier, consumer, consumerScope = scope)

    @Test fun scopedConsumersNeverClaimOrExpireForeignRequestsRegardlessOfPriority() {
        val seen = mutableListOf<String>()
        val tasks = scoped("task.create", LocalIngressConsumer { seen += it.scope; LocalIngressDisposition.CONSUMED })
        val timers = scoped("timer.create", LocalIngressConsumer { seen += it.scope; LocalIngressDisposition.CONSUMED })
        val timer = request(scope = "timer.create", priority = 100)
        val task = request(priority = 1)
        assertEquals(DurableIngressOutcome.QUEUED, timers.enqueue(timer))
        assertEquals(DurableIngressOutcome.QUEUED, tasks.enqueue(task))
        assertFalse(tasks.consumeNext())
        release()
        assertTrue(tasks.consumeNext())
        assertEquals(listOf("task.create"), seen)
        val before = checkNotNull(db.ingress().find(timer.ingressId.toString()))
        assertEquals(DurableIngressStatus.QUEUED, before.status)
        assertEquals(0L, before.version)
        assertEquals(0, before.attemptCount)
        now = 2000
        assertFalse(tasks.consumeNext())
        val unchanged = checkNotNull(db.ingress().find(timer.ingressId.toString()))
        assertEquals(before.status, unchanged.status)
        assertEquals(before.version, unchanged.version)
        assertEquals(before.attemptCount, unchanged.attemptCount)
        assertArrayEquals(before.payloadEnvelope, unchanged.payloadEnvelope)
        assertTrue(timers.consumeNext())
        assertEquals(DurableIngressStatus.EXPIRED, db.ingress().find(timer.ingressId.toString())?.status)
        assertEquals(listOf("task.create"), seen)
    }

    @Test fun scopedStoreRefusesToAdmitAnotherDomainsRequest() {
        val tasks = scoped("task.create", LocalIngressConsumer { error("Must not consume") })
        val foreign = request(scope = "reminder.create")
        assertThrows(IllegalArgumentException::class.java) { tasks.enqueue(foreign) }
        assertNull(db.ingress().find(foreign.ingressId.toString()))
        assertThrows(IllegalArgumentException::class.java) { db.ingress().queuedInScope("", 1) }
        assertThrows(IllegalArgumentException::class.java) { db.ingress().queuedInScope("task.create", 101) }
    }

    @Test fun scopedFailureRollsBackOnlyItsOwnIngressAndDomainWrites() {
        val foreign = request(scope = "reminder.create", priority = 100)
        val own = request()
        store.enqueue(foreign); store.enqueue(own); release()
        val technicalId = UUID.randomUUID().toString()
        val tasks = scoped("task.create", LocalIngressConsumer {
            db.records().appendTechnical(TechnicalEventEntity(technicalId, "INGRESS", "APPLIED", now, UUID.randomUUID().toString()))
            error("Synthetic scoped transaction failure")
        })
        assertThrows(IllegalStateException::class.java) { tasks.consumeNext() }
        for (request in listOf(foreign, own)) {
            val row = checkNotNull(db.ingress().find(request.ingressId.toString()))
            assertEquals(DurableIngressStatus.QUEUED, row.status)
            assertEquals(0, row.attemptCount)
            assertEquals(0L, row.version)
        }
        db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_technical_events WHERE technicalEventId = ?", arrayOf(technicalId)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

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

    @Test fun transientCryptoFailurePreservesQueuedRequestUntilProviderRecovers() {
        val request = request()
        assertEquals(DurableIngressOutcome.QUEUED, store.enqueue(request))
        val before = checkNotNull(db.ingress().find(request.ingressId.toString()))
        release()
        cryptoUnavailable = true
        // Verify that the fixture reaches the intended typed failure on this Android provider.
        val envelope = (FieldEnvelopeCodec.decode(before.payloadEnvelope) as FieldEnvelopeDecodingResult.Decoded).envelope
        val failure = OrionFieldEncryption(keys).decrypt(envelope, FieldEncryptionContext("fixture", "payload", 1))
        assertEquals(FieldCryptoFailure.CRYPTO_UNAVAILABLE, (failure as FieldDecryptionResult.Failure).reason)
        assertFalse(store.consumeNext())
        val pending = checkNotNull(db.ingress().find(before.ingressId))
        assertEquals(DurableIngressStatus.QUEUED, pending.status)
        assertEquals(before.version, pending.version)
        assertEquals(before.attemptCount, pending.attemptCount)
        assertArrayEquals(before.payloadEnvelope, pending.payloadEnvelope)
        assertEquals(0, calls)
        cryptoUnavailable = false
        assertTrue(store.consumeNext())
        assertEquals(1, calls)
        assertEquals(DurableIngressStatus.CONSUMED, db.ingress().find(before.ingressId)?.status)
    }
}

package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivatePayloadStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ids = mutableListOf<UUID>()
    private var now = Instant.ofEpochMilli(1000)
    private var key: FieldKeyResult = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 6 }, "AES"))
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = key
        override fun keyForVersion(version: Int) = key
    })
    private val clock = WallClock { now }
    private fun store() = OrionPrivatePayloadStore.open(context, encryption, clock)
    private fun file(id: UUID) = File(context.noBackupFilesDir, "orion_temporary_payloads/$id.v1")
    private fun write(bytes: ByteArray = byteArrayOf(1), ttl: Long = 1000): PrivatePayloadReference {
        val ref = (store().write(bytes, Sensitivity.SECRET, ttl) as PrivatePayloadWrite.Stored).reference
        ids += ref.id
        return ref
    }
    @After fun cleanFixtures() {
        ids.forEach { id -> listOf("", ".bak", ".new").forEach { File(file(id).path + it).delete() } }
    }

    @Test fun largeContentReopensEncryptedAndExpiresAtTheBoundary() {
        val secret = "orion-private-secret-fixture-180C".repeat(12_000).toByteArray()
        assertTrue(secret.size > DurableIngressRequest.MAX_CONTENT_BYTES)
        val ref = write(secret)
        assertArrayEquals(secret, (store().read(ref.id) as PrivatePayloadRead.Ready).copyContent())
        assertFalse(file(ref.id).readBytes().toString(Charsets.ISO_8859_1).contains("orion-private-secret-fixture-180C"))
        now = Instant.ofEpochMilli(ref.expiresAt)
        assertEquals(PrivatePayloadFailure.EXPIRED, (store().read(ref.id) as PrivatePayloadRead.Blocked).reason)
        assertEquals(1, store().cleanupExpiredBatch().deleted)
        assertFalse(file(ref.id).exists())
    }

    @Test fun unavailableKeysAndClockRollbackPreserveCommittedFiles() {
        val ref = write()
        now = Instant.ofEpochMilli(999)
        assertEquals(PrivatePayloadFailure.CLOCK_ROLLBACK, (store().read(ref.id) as PrivatePayloadRead.Blocked).reason)
        assertEquals(1, store().cleanupExpiredBatch().blocked)
        now = Instant.ofEpochMilli(5000)
        key = FieldKeyResult.Unavailable
        assertEquals(PrivatePayloadFailure.KEY_UNAVAILABLE, (store().read(ref.id) as PrivatePayloadRead.Blocked).reason)
        assertEquals(0, store().cleanupExpiredBatch().deleted)
        assertTrue(file(ref.id).exists())
    }

    @Test fun tamperedExpiryCannotAuthorizeCleanupAndSwappedFilesFailAuthentication() {
        val first = write("first".toByteArray())
        val second = write("second".toByteArray())
        file(second.id).writeBytes(file(first.id).readBytes())
        assertEquals(PrivatePayloadFailure.UNREADABLE, (store().read(second.id) as PrivatePayloadRead.Blocked).reason)
        val bytes = file(first.id).readBytes()
        bytes[23] = (bytes[23].toInt() xor 1).toByte()
        file(first.id).writeBytes(bytes)
        now = Instant.ofEpochMilli(10000)
        assertEquals(0, store().cleanupExpiredBatch().deleted)
        assertTrue(file(first.id).exists())
    }

    @Test fun fileCountSizeAndCleanupBatchesAreBounded() {
        repeat(OrionPrivatePayloadStore.MAX_FILES) { write() }
        assertEquals(PrivatePayloadFailure.CAPACITY_EXCEEDED,
            (store().write(byteArrayOf(1), Sensitivity.SECRET, 1000) as PrivatePayloadWrite.Blocked).reason)
        assertEquals(PrivatePayloadFailure.PAYLOAD_TOO_LARGE,
            (store().write(ByteArray(FieldEnvelope.MAX_FIELD_BYTES + 1), Sensitivity.SECRET, 1000) as PrivatePayloadWrite.Blocked).reason)
        now = Instant.ofEpochMilli(5000)
        val first = store().cleanupExpiredBatch(2)
        assertEquals(2, first.deleted)
        assertNotNull(first.nextAfterId)
        assertEquals(2, store().cleanupExpiredBatch(2, first.nextAfterId).deleted)
    }

    @Test fun interruptedUncommittedWritesAreRemovedWithoutReplacingCommittedFiles() {
        val ref = write()
        val orphan = UUID.randomUUID().also(ids::add)
        File(file(orphan).path + ".new").writeBytes(byteArrayOf(1, 2, 3))
        store().cleanupExpiredBatch()
        assertFalse(File(file(orphan).path + ".new").exists())
        assertTrue(file(ref.id).exists())
        assertTrue(OrionPrivatePayloadStore.hasArtifacts(context))
    }

    @Test fun referencedIngressCannotOutlivePrivateContentEvenWithDifferentQueueClock() {
        val ref = write(ByteArray(300_000) { 5 })
        val name = "orion_private_ingress_${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        try {
            val barrier = StartupBarrier()
            var consumed = 0
            val queue = OrionDurableIngressStore(database, encryption, clock, barrier, LocalIngressConsumer {
                assertArrayEquals(ByteArray(300_000) { 5 }, (store().read(checkNotNull(it.referenceId)) as PrivatePayloadRead.Ready).copyContent())
                consumed++; LocalIngressDisposition.CONSUMED
            })
            fun request(lifetime: Long) = ref.ingressRequest(UUID.randomUUID(), "private.import", UUID.randomUUID(),
                "UI", "ImportPrivate", 1, lifetime, 5000, UUID.randomUUID())
            now = now.plusMillis(500)
            assertThrows(IllegalArgumentException::class.java) { store().enqueue(ref, request(1000), queue) }
            val skewed = OrionDurableIngressStore(database, encryption, WallClock { now.plusMillis(501) }, barrier,
                LocalIngressConsumer { LocalIngressDisposition.CONSUMED })
            assertThrows(IllegalArgumentException::class.java) { store().enqueue(ref, request(500), skewed) }
            assertEquals(DurableIngressOutcome.QUEUED, store().enqueue(ref, request(500), queue))
            assertFalse(queue.consumeNext())
            barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
            assertTrue(queue.consumeNext())
            assertEquals(1, consumed)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}

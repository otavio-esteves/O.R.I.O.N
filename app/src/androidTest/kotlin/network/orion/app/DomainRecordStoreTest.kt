package network.orion.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.memory.*
import network.orion.data.reminders.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DomainRecordStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_records_${UUID.randomUUID()}.db"
    private var db = open()
    private var now = Instant.ofEpochMilli(1000)
    private var key: FieldKeyResult = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 9 }, "AES"))
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = key
        override fun keyForVersion(version: Int) = key
    })
    private val clock = WallClock { now }
    private val secret = "orion-record-secret-fixture-92C81"
    private fun reminders() = OrionReminderStore(db, encryption, clock)
    private fun memories() = OrionMemoryStore(db, encryption, clock)
    private fun open() = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun reminderCiphertextReopensAndEditsAreVersionedAndUnscheduleDelivery() {
        val id = UUID.randomUUID()
        assertTrue(reminders().create(id, secret.toByteArray(), Sensitivity.SECRET, now.plusSeconds(60),
            ZoneId.of("America/Sao_Paulo"), UUID.randomUUID()) is ReminderWriteResult.Stored)
        db.close(); db = open()
        assertEquals(secret, plaintext(reminders().readContent(id)))
        now = now.plusMillis(1)
        assertTrue(reminders().update(id, 0, "edited".toByteArray(), Sensitivity.SENSITIVE, now.plusSeconds(120),
            ZoneId.of("UTC"), UUID.randomUUID()) is ReminderWriteResult.Stored)
        assertEquals(ReminderPrecision.UNSCHEDULED, db.records().reminder(id.toString())!!.precision)
        assertEquals(ActionMutation.CONFLICT, reminders().cancel(id, 0, UUID.randomUUID()))
        assertEquals(ActionMutation.APPLIED, reminders().cancel(id, 1, UUID.randomUUID()))
        assertEquals(ActionMutation.CONFLICT, reminders().cancel(id, 2, UUID.randomUUID()))
        assertEquals(3, db.events().count())
    }

    @Test fun missingKeysBlockCreationAndReadsWithoutPlaintextFallback() {
        val id = UUID.randomUUID()
        reminders().create(id, secret.toByteArray(), Sensitivity.SECRET, now, ZoneId.of("UTC"), UUID.randomUUID())
        key = FieldKeyResult.Unavailable
        assertEquals(FieldCryptoFailure.KEY_UNAVAILABLE, (reminders().readContent(id) as FieldDecryptionResult.Failure).reason)
        assertTrue(memories().create(UUID.randomUUID(), secret.toByteArray(), Sensitivity.SECRET,
            MemoryOrigin.USER_DECLARED, 1.0, emptyList(), UUID.randomUUID()) is MemoryWriteResult.Blocked)
        assertEquals(1, db.events().count())
    }

    @Test fun memorySupersessionPreservesHistoryAndClaimsThenDeletionRemovesDerivedContent() {
        val prior = UUID.randomUUID()
        val next = UUID.randomUUID()
        val claim = UUID.randomUUID()
        assertTrue(memories().create(prior, secret.toByteArray(), Sensitivity.SECRET, MemoryOrigin.USER_DECLARED,
            1.0, listOf(MemoryClaimInput(claim, secret.toByteArray(), Sensitivity.SECRET, MemoryOrigin.USER_DECLARED, 1.0)),
            UUID.randomUUID()) is MemoryWriteResult.Stored)
        now = now.plusSeconds(1)
        assertTrue(memories().supersede(prior, 0, next, "new".toByteArray(), Sensitivity.SECRET,
            MemoryOrigin.USER_DECLARED, 1.0, emptyList(), UUID.randomUUID()) is MemoryWriteResult.Stored)
        assertEquals(MemoryStatus.SUPERSEDED, db.records().memory(prior.toString())!!.status)
        assertEquals(prior.toString(), db.records().memory(next.toString())!!.supersedesId)
        assertEquals(MemoryStatus.SUPERSEDED, db.records().claims(prior.toString()).single().status)
        assertEquals(now.toEpochMilli(), db.records().claims(prior.toString()).single().validUntil)
        assertEquals(secret, plaintext(memories().readClaim(prior, claim)))
        assertTrue(memories().supersede(prior, 0, UUID.randomUUID(), byteArrayOf(1), Sensitivity.SECRET,
            MemoryOrigin.USER_DECLARED, 1.0, emptyList(), UUID.randomUUID()) is MemoryWriteResult.Rejected)
        assertEquals(ActionMutation.CONFLICT, memories().delete(prior, 0, UUID.randomUUID()))
        assertEquals(ActionMutation.APPLIED, memories().delete(prior, 1, UUID.randomUUID()))
        assertNull(memories().readContent(prior)); assertNull(memories().readClaim(prior, claim))
        assertTrue(db.records().claims(prior.toString()).isEmpty())
        assertEquals(4, db.events().count())
    }

    @Test fun memorySupersessionNeverExtendsClaimValidityAndClosesOpenOrFutureBounds() {
        val (prior, originalClaims) = memoryWithClaimValidity(listOf(1000L to 1500L, 1000L to 2500L, 1000L to null))
        val next = UUID.randomUUID()
        now = Instant.ofEpochMilli(2000)
        assertTrue(memories().supersede(prior, 0, next, "replacement".toByteArray(), Sensitivity.SECRET,
            MemoryOrigin.USER_DECLARED, 1.0, emptyList(), UUID.randomUUID()) is MemoryWriteResult.Stored)
        val closed = db.records().claims(prior.toString()).associateBy { it.claimId }
        originalClaims.forEach { original ->
            val claim = closed.getValue(original.claimId)
            assertEquals(minOf(original.validUntil ?: 2000L, 2000L), claim.validUntil)
            assertEquals(original.validFrom, claim.validFrom)
            assertEquals(MemoryStatus.SUPERSEDED, claim.status)
            assertEquals(1L, claim.version)
            assertArrayEquals(original.statementEnvelope, claim.statementEnvelope)
        }
        assertEquals(1L, db.records().memory(prior.toString())!!.version)
        assertEquals(prior.toString(), db.records().memory(next.toString())!!.supersedesId)
        assertEquals(3, db.events().count())
    }

    @Test fun sameTimestampSupersessionRejectsEmptyClaimIntervalWithoutChangingAnyRecord() {
        val (prior, originalClaims) = memoryWithClaimValidity(listOf(999L to null, 1000L to null))
        val next = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            memories().supersede(prior, 0, next, "replacement".toByteArray(), Sensitivity.SECRET,
                MemoryOrigin.USER_DECLARED, 1.0, emptyList(), UUID.randomUUID())
        }
        assertNull(db.records().memory(next.toString()))
        val unchanged = db.records().memory(prior.toString())!!
        assertEquals(MemoryStatus.ACTIVE, unchanged.status)
        assertEquals(0L, unchanged.version)
        assertEquals(1000L, unchanged.updatedAt)
        assertClaimValidityUnchanged(prior, originalClaims)
        assertEquals(1, db.events().count())
    }

    @Test fun inferredConfirmationAndSensitivityDowngradeRejectTheWholeWrite() {
        val id = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            memories().create(id, secret.toByteArray(), Sensitivity.SECRET, MemoryOrigin.LLM_INFERRED, 0.5,
                emptyList(), UUID.randomUUID(), userConfirmed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            memories().create(id, secret.toByteArray(), Sensitivity.PUBLIC, MemoryOrigin.USER_DECLARED, 1.0,
                listOf(MemoryClaimInput(UUID.randomUUID(), secret.toByteArray(), Sensitivity.SECRET,
                    MemoryOrigin.USER_DECLARED, 1.0)), UUID.randomUUID())
        }
        assertNull(db.records().memory(id.toString())); assertEquals(0, db.events().count())
    }

    @Test fun failedSecondSupersessionEventRollsBackReplacementHistoryAndClaims() {
        val (id, originalClaims) = memoryWithClaimValidity(listOf(1000L to 1500L, 1000L to 2500L, 1000L to null))
        val existing = db.events().due(1000, 10).single()
        val replacement = db.records().memory(id.toString())!!.copy(memoryId = UUID.randomUUID().toString(),
            supersedesId = id.toString(), createdAt = 2000, updatedAt = 2000)
        val priorEvent = existing.copy(eventType = "MEMORY_SUPERSEDED", aggregateVersion = 1,
            occurredAt = 2000, nextAttemptAt = 2000)
        val newEvent = existing.copy(eventId = UUID.randomUUID().toString(), aggregateId = replacement.memoryId,
            occurredAt = 2000, nextAttemptAt = 2000)
        assertThrows(SQLiteConstraintException::class.java) {
            db.records().supersedeWithEvents(id.toString(), 0, replacement, emptyList(), priorEvent, newEvent)
        }
        assertNull(db.records().memory(replacement.memoryId))
        assertEquals(MemoryStatus.ACTIVE, db.records().memory(id.toString())!!.status)
        assertEquals(0L, db.records().memory(id.toString())!!.version)
        assertClaimValidityUnchanged(id, originalClaims)
        assertEquals(1, db.events().count())
    }

    @Test fun concurrentReminderCancellationHasOneWinnerAndNoDuplicateFact() {
        val id = UUID.randomUUID()
        reminders().create(id, byteArrayOf(1), Sensitivity.PERSONAL, now, ZoneId.of("UTC"), UUID.randomUUID())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit(Callable {
                start.await(10, TimeUnit.SECONDS)
                reminders().cancel(id, 0, UUID.randomUUID())
            }) }
            start.countDown()
            assertEquals(setOf(ActionMutation.APPLIED, ActionMutation.CONFLICT), results.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(2, db.events().count())
        } finally { executor.shutdownNow() }
    }

    @Test fun metadataQueriesAreBoundedAndCiphertextsRejectCrossEntitySubstitution() {
        val ids = List(3) { UUID.randomUUID() }.sortedBy { it.toString() }
        ids.forEach { reminders().create(it, secret.toByteArray(), Sensitivity.SECRET, now, ZoneId.of("UTC"), UUID.randomUUID()) }
        assertEquals(ids.take(2).map(UUID::toString), reminders().list(limit = 2).map { it.reminderId })
        assertEquals(ids.drop(2).map(UUID::toString), reminders().list(ids[1], 2).map { it.reminderId })
        assertThrows(IllegalArgumentException::class.java) { reminders().list(limit = 101) }
        val source = db.records().reminder(ids[0].toString())!!.contentEnvelope
        db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET contentEnvelope = ? WHERE reminderId = ?",
            arrayOf(source, ids[1].toString()))
        assertEquals(FieldCryptoFailure.AUTHENTICATION_FAILED, (reminders().readContent(ids[1]) as FieldDecryptionResult.Failure).reason)
        db.close()
        context.getDatabasePath(name).parentFile!!.listFiles()!!.filter { it.name.startsWith(name) }.forEach {
            assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        db = open()
    }

    private fun memoryWithClaimValidity(validity: List<Pair<Long, Long?>>): Pair<UUID, List<MemoryClaimEntity>> {
        val id = UUID.randomUUID()
        fun encrypted(context: FieldEncryptionContext) = FieldEnvelopeCodec.encode(
            (encryption.encrypt(secret.toByteArray(), context) as FieldEncryptionResult.Encrypted).envelope)
        val claims = validity.map { (from, until) ->
            val claim = UUID.randomUUID()
            MemoryClaimEntity(claim.toString(), id.toString(),
                encrypted(FieldEncryptionContext("memory/$id/claim/$claim", "statement", 1)),
                Sensitivity.SECRET, MemoryOrigin.USER_DECLARED, 1.0, false, validFrom = from, validUntil = until)
        }
        val at = now.toEpochMilli()
        db.records().createMemoryWithClaimsAndEvent(MemoryEntity(id.toString(),
            encrypted(FieldEncryptionContext("memory/$id", "content", 1)), Sensitivity.SECRET,
            MemoryOrigin.USER_DECLARED, 1.0, false, at, at), claims,
            DomainEventOutboxEntity(UUID.randomUUID().toString(), id.toString(), 0, "MEMORY_CREATED", 1,
                Sensitivity.SECRET, at, UUID.randomUUID().toString()))
        return id to claims
    }

    private fun assertClaimValidityUnchanged(memoryId: UUID, originals: List<MemoryClaimEntity>) {
        val restored = db.records().claims(memoryId.toString()).associateBy { it.claimId }
        assertEquals(originals.size, restored.size)
        originals.forEach { original ->
            val claim = restored.getValue(original.claimId)
            assertEquals(original.status, claim.status)
            assertEquals(original.version, claim.version)
            assertEquals(original.validFrom, claim.validFrom)
            assertEquals(original.validUntil, claim.validUntil)
            assertArrayEquals(original.statementEnvelope, claim.statementEnvelope)
        }
    }

    private fun plaintext(result: FieldDecryptionResult?) = String((result as FieldDecryptionResult.Plaintext).copyBytes())
}

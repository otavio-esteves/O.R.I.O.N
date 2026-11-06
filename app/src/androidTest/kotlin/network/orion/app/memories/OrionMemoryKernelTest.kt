package network.orion.app.memories

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.memories.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.memories.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.queries.memories.*
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionMemoryKernelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_kernel_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var kernel: OrionMemoryKernel
    private var wall = Instant.parse("2026-11-05T04:00:00Z")
    private var capable = true
    private val clock = object : OrionClock {
        override fun wallNow() = wall
        override fun monotonicNow() = MonotonicInstant(BootSessionId(7), 100)
    }
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 31 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val barrier = StartupBarrier()
    private val owner = OrionMemoryAdmissionOwner(OrionProcessRole.MAIN, barrier,
        { if (capable) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED }, { OrionPolicyEvidence.SATISFIED })
    private var channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
    private val command = CreateMemory(UUID.randomUUID(), "Preparar viagem", UUID.randomUUID(), UUID.randomUUID())
    private fun wire() = OrionMemoryIngressWire("CREATE", command.memoryId, command.idempotencyKey, command.correlationId, command.content)
    @Before fun open() { bind(); release() }
    private fun bind() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        kernel = OrionMemoryKernel(db, crypto, clock, barrier, owner, { CoreReadiness.DEGRADED_SAFE }, { true })
    }
    private fun release() { barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE) }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun prepared(): OrionMemoryPending {
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(wire(), command.idempotencyKey))
        return kernel.pending().single()
    }
    private fun consent(pending: OrionMemoryPending): OrionMemoryIngressWire {
        val stored = (kernel.actions.read(pending.display.actionId) as network.orion.data.actions.ActionReadResult.Ready).action
        return pending.wire(ActionConfirmationBinding(clock).recordConfirmation(stored.request, wall.plusSeconds(60)))
    }
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    @Test fun gatedQueuePersistsAndOnlyPreparesAfterRelease() {
        barrier.beginRecovery()
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(wire(), command.idempotencyKey))
        assertEquals(0, count("orion_action_requests")); assertEquals(0, count("orion_memories"))
        release(); assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, kernel.pending().size)
        assertEquals(0, count("orion_memories"))
    }
    @Test fun actualIngressCommitsConfirmationMemoryEventSuccessAndReplayTogether() {
        val pending = prepared(); val receipt = consent(pending); val key = UUID.randomUUID()
        val replayTrace = OrionMemoryIngressWire("CREATE", command.memoryId, command.idempotencyKey, UUID.randomUUID(), command.content)
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(replayTrace, command.idempotencyKey))
        assertEquals(1, count("orion_action_requests"))
        assertEquals(MemoryOperationOutcome.APPLIED, kernel.submit(receipt, key))
        assertEquals(MemoryOperationOutcome.APPLIED, kernel.submit(receipt, key))
        assertEquals(1, count("orion_memories")); assertEquals(1, count("orion_domain_event_outbox"))
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(pending.display.actionId.toString())!!.status)
        assertTrue(kernel.pending().isEmpty())
        val queuedBefore = db.ingress().count(); val eventsBefore = count("orion_domain_event_outbox")
        assertEquals(command.content, kernel.query.get(GetMemories()).items.single().content)
        assertEquals(queuedBefore, db.ingress().count()); assertEquals(eventsBefore, count("orion_domain_event_outbox"))
    }
    @Test fun expiredQueuedConsentIsRejectedWithoutRenewingOrConfirming() {
        val pending = prepared(); val receipt = consent(pending)
        wall = wall.plusSeconds(61)
        assertEquals(MemoryOperationOutcome.REJECTED, kernel.submit(receipt, UUID.randomUUID()))
        assertEquals(0, count("orion_memories"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        assertNull(db.actions().find(pending.display.actionId.toString())!!.confirmedAt)
    }
    @Test fun capabilityLossRollsBackProcessingAndPreservesOriginalReceipt() {
        val pending = prepared(); val receipt = consent(pending); val at = wall
        capable = false
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(receipt, UUID.randomUUID()))
        assertEquals(0, count("orion_memories")); assertEquals(1, db.ingress().queued(100).size)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        capable = true; wall = wall.plusSeconds(5)
        assertEquals(1, kernel.ingress.drain(25))
        assertEquals(at.toEpochMilli(), db.actions().find(pending.display.actionId.toString())!!.confirmedAt)
    }
    @Test fun durableOriginSurvivesReopenButFreshFocusedPresenceIsStillRequired() {
        val pending = prepared(); val receipt = consent(pending)
        channel.close()
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(receipt, UUID.randomUUID()))
        db.close(); bind(); kernel.recoverActions(); release()
        assertEquals(0, kernel.ingress.drain(25))
        channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, count("orion_memories"))
    }
    @Test fun wrongProducerCannotManufactureUserOrigin() {
        val bytes = wire().encode()
        try { kernel.ingress.submit(DurableIngressRequest(UUID.randomUUID(), OrionMemoryIngressWire.SCOPE, UUID.randomUUID(),
            "UNTRUSTED", OrionMemoryIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, bytes, 1000, 1000,
            correlationId = UUID.randomUUID())) } finally { bytes.fill(0) }
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(0, count("orion_action_requests"))
        assertEquals(0, count("orion_memories"))
    }
    @Test fun unreadablePendingRequestCanOnlyBeDiscardedWithFreshPresence() {
        val pending = prepared()
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope = X'010203' WHERE actionId = ?",
            arrayOf(pending.display.actionId.toString()))
        val unreadable = kernel.pending().single()
        assertNull(unreadable.display.content)
        channel.onWindowFocus(false)
        assertEquals(MemoryOperationOutcome.QUEUED, kernel.submit(unreadable.wire(dismiss = true), UUID.randomUUID()))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        channel.onWindowFocus(true)
        assertEquals(1, kernel.ingress.drain(25))
        assertEquals(ActionStatus.CANCELLED, db.actions().find(pending.display.actionId.toString())!!.status)
        assertEquals(0, count("orion_memories"))
    }
    @Test fun alteredFingerprintAndOverlongReceiptCannotAuthorizeEffect() {
        val pending = prepared(); val good = consent(pending)
        val altered = OrionMemoryIngressWire(good.operation, good.memoryId, good.key, good.trace,
            actionId = good.actionId, actionVersion = good.actionVersion, fingerprint = "altered",
            policyVersion = good.policyVersion, receipt = good.receipt)
        assertEquals(MemoryOperationOutcome.REJECTED, kernel.submit(altered, UUID.randomUUID()))
        val overlong = pending.wire(checkNotNull(good.receipt).copy(confirmationExpiresAt = wall.plusSeconds(61)))
        assertEquals(MemoryOperationOutcome.REJECTED, kernel.submit(overlong, UUID.randomUUID()))
        assertEquals(0, count("orion_memories"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
    }

    @Test fun databaseFailureRollsBackEnclosingIngressAndAllowsExplicitSafeReplay() {
        val pending = prepared(); val receipt = consent(pending)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER orion_fail_event BEFORE INSERT ON orion_domain_event_outbox BEGIN SELECT RAISE(ABORT, 'orion_injected_failure'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { kernel.submit(receipt, UUID.randomUUID()) }
        assertEquals(0, count("orion_memories")); assertEquals(0, count("orion_domain_event_outbox"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        assertEquals(1, db.ingress().queued(100).size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_fail_event")
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, count("orion_memories"))
    }

    private fun created(): OrionMemoryPending {
        val pending=prepared()
        assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(pending),UUID.randomUUID()))
        return pending
    }
    private fun forgetPending(): OrionMemoryPending {
        val key=UUID.randomUUID()
        assertEquals(MemoryOperationOutcome.QUEUED,kernel.submit(OrionMemoryIngressWire("DELETE",command.memoryId,key,
            UUID.randomUUID(),expectedVersion=0),key))
        return kernel.pending().single { it.display.operation==MemoryOperation.DELETE }
    }
    @Test fun ownedCreationFixesProvenanceAndForgetErasesEntityClaimsAndEveryPersistedContentCopy() {
        val original=created()
        val memory=db.memories().find(command.memoryId.toString())!!
        assertEquals(MemoryOrigin.USER_DECLARED,memory.origin); assertTrue(memory.userConfirmed)
        assertEquals(Sensitivity.PERSONAL,memory.sensitivity)
        val claim=UUID.randomUUID()
        val statement=(crypto.encrypt(command.content.toByteArray(),FieldEncryptionContext("memory/${command.memoryId}/claim/$claim","statement",1))
            as FieldEncryptionResult.Encrypted).envelope
        db.openHelper.writableDatabase.execSQL("""INSERT INTO orion_memory_claims
            (claimId,memoryId,statementEnvelope,sensitivity,origin,confidence,userConfirmed,status,validFrom,validUntil,version)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(claim.toString(),command.memoryId.toString(),FieldEnvelopeCodec.encode(statement),
            "PERSONAL","USER_DECLARED",1.0,1,"ACTIVE",wall.toEpochMilli(),null,0))
        val originalIngress=db.memories().memoryIngress().map { it.ingressId }
        val forget=forgetPending()
        assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(forget),UUID.randomUUID()))
        assertNull(db.memories().find(command.memoryId.toString()))
        assertTrue(db.records().claims(command.memoryId.toString()).isEmpty())
        assertTrue(kernel.query.get(GetMemories()).items.isEmpty())
        val retained=db.actions().find(original.display.actionId.toString())!!
        assertEquals(ActionStatus.SUCCEEDED,retained.status)
        assertTrue(retained.argumentsEnvelope.isEmpty()); assertTrue(retained.targetEnvelope.isEmpty())
        assertTrue(kernel.actions.read(original.display.actionId) is network.orion.data.actions.ActionReadResult.Blocked)
        for (id in originalIngress) assertTrue(db.ingress().find(id)!!.payloadEnvelope.isEmpty())
        assertEquals(1,db.memories().forgotten(command.memoryId.toString()))
        assertEquals(2,count("orion_domain_event_outbox"))
        assertTrue(kernel.pending().isEmpty())
    }
    @Test fun forgottenReplayWithFreshProducerKeyNeverPersistsAnotherCopyOrResurrects() {
        created(); val forget=forgetPending()
        assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(forget),UUID.randomUUID()))
        val before=db.ingress().count()
        assertEquals(MemoryOperationOutcome.FORGOTTEN,kernel.submit(wire(),UUID.randomUUID()))
        assertEquals(MemoryOperationOutcome.REJECTED,kernel.submit(OrionMemoryIngressWire("CREATE",command.memoryId,
            command.idempotencyKey,command.correlationId,"changed statement"),UUID.randomUUID()))
        assertEquals(MemoryOperationOutcome.REJECTED,kernel.submit(OrionMemoryIngressWire("CREATE",command.memoryId,
            UUID.randomUUID(),command.correlationId,command.content),UUID.randomUUID()))
        assertEquals(before,db.ingress().count()); assertEquals(0,count("orion_memories"))
        assertEquals(2,count("orion_domain_event_outbox"))
        assertTrue(db.memories().creationActions().all { it.argumentsEnvelope.isEmpty() && it.targetEnvelope.isEmpty() })
    }
    @Test fun forgetCancelsAndErasesOtherCreationDraftsForTheExactTargetOnly() {
        val original=created()
        val otherKey=UUID.randomUUID()
        assertEquals(MemoryOperationOutcome.QUEUED,kernel.submit(OrionMemoryIngressWire("CREATE",command.memoryId,
            otherKey,UUID.randomUUID(),"Another statement for same ID"),otherKey))
        val otherAction=db.actions().findByKey("memory.create",otherKey.toString())!!
        val otherId=UUID.randomUUID(); val unrelatedKey=UUID.randomUUID()
        kernel.submit(OrionMemoryIngressWire("CREATE",otherId,unrelatedKey,UUID.randomUUID(),"Unrelated personal statement"),unrelatedKey)
        val unrelated=db.actions().findByKey("memory.create",unrelatedKey.toString())!!
        val forget=forgetPending()
        assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(forget),UUID.randomUUID()))
        assertEquals(ActionStatus.CANCELLED,db.actions().find(otherAction.actionId)!!.status)
        assertTrue(db.actions().find(otherAction.actionId)!!.argumentsEnvelope.isEmpty())
        assertTrue(db.actions().find(original.display.actionId.toString())!!.argumentsEnvelope.isEmpty())
        val kept=db.actions().find(unrelated.actionId)!!
        assertEquals(ActionStatus.NEEDS_CONFIRMATION,kept.status); assertArrayEquals(unrelated.argumentsEnvelope,kept.argumentsEnvelope)
        assertEquals("Unrelated personal statement",kernel.pending().single().display.content)
    }
    @Test fun erasureFailureRollsBackDeletionClaimRemovalTombstoneAndEarlierCiphertextScrubs() {
        val original=created(); val forget=forgetPending()
        val ciphertext=db.actions().find(original.display.actionId.toString())!!.argumentsEnvelope.copyOf()
        val before=db.memories().memoryIngress().associate { it.ingressId to it.payloadEnvelope.copyOf() }
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_fail_memory_erase BEFORE UPDATE OF payloadEnvelope
            ON orion_ingress_envelopes WHEN length(NEW.payloadEnvelope)=0
            BEGIN SELECT RAISE(ABORT,'orion_injected_erase_failure'); END""")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { kernel.submit(consent(forget),UUID.randomUUID()) }
        assertNotNull(db.memories().find(command.memoryId.toString())); assertEquals(0,db.memories().forgotten(command.memoryId.toString()))
        assertArrayEquals(ciphertext,db.actions().find(original.display.actionId.toString())!!.argumentsEnvelope)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION,db.actions().find(forget.display.actionId.toString())!!.status)
        for ((id,bytes) in before) assertArrayEquals(bytes,db.ingress().find(id)!!.payloadEnvelope)
        assertEquals(1,count("orion_domain_event_outbox"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_fail_memory_erase")
        assertEquals(1,kernel.ingress.drain(25)); assertNull(db.memories().find(command.memoryId.toString()))
    }
    @Test fun missingPrivacyTombstoneCannotTurnCorruptTerminalEnvelopesIntoForgottenProof() {
        val original=created()
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope=X'',targetEnvelope=X'' WHERE actionId=?",
            arrayOf(original.display.actionId.toString()))
        db.runInTransaction {
            val validated=(OrionCreateMemoryPreparer.prepare(command) as CreateMemoryPreparation.Ready).memory
            assertFalse(orionMemoryWasForgotten(db,clock,validated))
        }
        assertNotNull(db.memories().find(command.memoryId.toString())); assertEquals(0,db.memories().forgotten(command.memoryId.toString()))
    }
    @Test fun enqueueBlockedBehindForgetWriterObservesCommittedTombstoneBeforePersistingAnyCopy() {
        created(); val forget=forgetPending(); val receipt=consent(forget)
        val before=db.ingress().count()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        val staged=java.util.concurrent.CountDownLatch(1); val releaseWriter=java.util.concurrent.CountDownLatch(1)
        try {
            val writer=pool.submit<MemoryOperationOutcome> {
                barrier.whileOpen {
                    db.runInTransaction(java.util.concurrent.Callable {
                        val result=kernel.submit(receipt,UUID.randomUUID())
                        staged.countDown(); assertTrue(releaseWriter.await(10,java.util.concurrent.TimeUnit.SECONDS)); result
                    })
                }!!
            }
            assertTrue(staged.await(10,java.util.concurrent.TimeUnit.SECONDS))
            val replay=pool.submit<MemoryOperationOutcome> { kernel.submit(wire(),UUID.randomUUID()) }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { replay.get(100,java.util.concurrent.TimeUnit.MILLISECONDS) }
            releaseWriter.countDown()
            assertEquals(MemoryOperationOutcome.APPLIED,writer.get(10,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(MemoryOperationOutcome.FORGOTTEN,replay.get(10,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before+1,db.ingress().count()); assertNull(db.memories().find(command.memoryId.toString()))
        } finally { releaseWriter.countDown(); pool.shutdownNow() }
    }

    @Test fun inferredExistingMemoryCannotBePromotedThroughUserCreateReplayOrShownAsDeclared() {
        val validated=(OrionCreateMemoryPreparer.prepare(command) as CreateMemoryPreparation.Ready).memory
        val f2=network.orion.data.memory.OrionMemoryStore(db,crypto,clock)
        assertTrue(f2.create(command.memoryId,validated.canonicalNormalizedArguments.toByteArray(),Sensitivity.PERSONAL,
            MemoryOrigin.LLM_INFERRED,0.3,emptyList(),command.correlationId,userConfirmed=false)
            is network.orion.data.memory.MemoryWriteResult.Stored)
        assertTrue(kernel.query.get(GetMemories()).items.isEmpty())
        val pending=prepared()
        assertEquals(MemoryOperationOutcome.REJECTED,kernel.submit(consent(pending),UUID.randomUUID()))
        val row=db.memories().find(command.memoryId.toString())!!
        assertEquals(MemoryOrigin.LLM_INFERRED,row.origin); assertFalse(row.userConfirmed)
        assertEquals(1,count("orion_domain_event_outbox")); assertTrue(kernel.query.get(GetMemories()).items.isEmpty())
    }
}

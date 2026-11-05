package network.orion.app.tasks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.tasks.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.tasks.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.queries.tasks.*
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionTaskKernelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_kernel_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var kernel: OrionTaskKernel
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
    private val owner = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier,
        { if (capable) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED }, { OrionPolicyEvidence.SATISFIED })
    private var channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
    private val command = CreateTask(UUID.randomUUID(), "Preparar viagem", UUID.randomUUID(), UUID.randomUUID())
    private fun wire() = OrionTaskIngressWire("CREATE", command.taskId, command.idempotencyKey, command.correlationId, command.title)
    @Before fun open() { bind(); release() }
    private fun bind() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        kernel = OrionTaskKernel(db, crypto, clock, barrier, owner, { CoreReadiness.DEGRADED_SAFE }, { true })
    }
    private fun release() { barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE) }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun prepared(): OrionTaskPending {
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(wire(), command.idempotencyKey))
        return kernel.pending().single()
    }
    private fun consent(pending: OrionTaskPending): OrionTaskIngressWire {
        val stored = (kernel.actions.read(pending.display.actionId) as network.orion.data.actions.ActionReadResult.Ready).action
        return pending.wire(ActionConfirmationBinding(clock).recordConfirmation(stored.request, wall.plusSeconds(60)))
    }
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    @Test fun gatedQueuePersistsAndOnlyPreparesAfterRelease() {
        barrier.beginRecovery()
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(wire(), command.idempotencyKey))
        assertEquals(0, count("orion_action_requests")); assertEquals(0, count("orion_tasks"))
        release(); assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, kernel.pending().size)
        assertEquals(0, count("orion_tasks"))
    }
    @Test fun actualIngressCommitsConfirmationTaskEventSuccessAndReplayTogether() {
        val pending = prepared(); val receipt = consent(pending); val key = UUID.randomUUID()
        val replayTrace = OrionTaskIngressWire("CREATE", command.taskId, command.idempotencyKey, UUID.randomUUID(), command.title)
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(replayTrace, command.idempotencyKey))
        assertEquals(1, count("orion_action_requests"))
        assertEquals(TaskOperationOutcome.APPLIED, kernel.submit(receipt, key))
        assertEquals(TaskOperationOutcome.APPLIED, kernel.submit(receipt, key))
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(pending.display.actionId.toString())!!.status)
        assertTrue(kernel.pending().isEmpty())
        val queuedBefore = db.ingress().count(); val eventsBefore = count("orion_domain_event_outbox")
        assertEquals(command.title, kernel.query.get(GetTasks()).items.single().title)
        assertEquals(queuedBefore, db.ingress().count()); assertEquals(eventsBefore, count("orion_domain_event_outbox"))
    }
    @Test fun expiredQueuedConsentIsRejectedWithoutRenewingOrConfirming() {
        val pending = prepared(); val receipt = consent(pending)
        wall = wall.plusSeconds(61)
        assertEquals(TaskOperationOutcome.REJECTED, kernel.submit(receipt, UUID.randomUUID()))
        assertEquals(0, count("orion_tasks"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        assertNull(db.actions().find(pending.display.actionId.toString())!!.confirmedAt)
    }
    @Test fun capabilityLossRollsBackProcessingAndPreservesOriginalReceipt() {
        val pending = prepared(); val receipt = consent(pending); val at = wall
        capable = false
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(receipt, UUID.randomUUID()))
        assertEquals(0, count("orion_tasks")); assertEquals(1, db.ingress().queued(100).size)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        capable = true; wall = wall.plusSeconds(5)
        assertEquals(1, kernel.ingress.drain(25))
        assertEquals(at.toEpochMilli(), db.actions().find(pending.display.actionId.toString())!!.confirmedAt)
    }
    @Test fun durableOriginSurvivesReopenButFreshFocusedPresenceIsStillRequired() {
        val pending = prepared(); val receipt = consent(pending)
        channel.close()
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(receipt, UUID.randomUUID()))
        db.close(); bind(); kernel.recoverActions(); release()
        assertEquals(0, kernel.ingress.drain(25))
        channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, count("orion_tasks"))
    }
    @Test fun wrongProducerCannotManufactureUserOrigin() {
        val bytes = wire().encode()
        try { kernel.ingress.submit(DurableIngressRequest(UUID.randomUUID(), OrionTaskIngressWire.SCOPE, UUID.randomUUID(),
            "UNTRUSTED", OrionTaskIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, bytes, 1000, 1000,
            correlationId = UUID.randomUUID())) } finally { bytes.fill(0) }
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(0, count("orion_action_requests"))
        assertEquals(0, count("orion_tasks"))
    }
    @Test fun unreadablePendingRequestCanOnlyBeDiscardedWithFreshPresence() {
        val pending = prepared()
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope = X'010203' WHERE actionId = ?",
            arrayOf(pending.display.actionId.toString()))
        val unreadable = kernel.pending().single()
        assertNull(unreadable.display.title)
        channel.onWindowFocus(false)
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(unreadable.wire(dismiss = true), UUID.randomUUID()))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        channel.onWindowFocus(true)
        assertEquals(1, kernel.ingress.drain(25))
        assertEquals(ActionStatus.CANCELLED, db.actions().find(pending.display.actionId.toString())!!.status)
        assertEquals(0, count("orion_tasks"))
    }
    @Test fun alteredFingerprintAndOverlongReceiptCannotAuthorizeEffect() {
        val pending = prepared(); val good = consent(pending)
        val altered = OrionTaskIngressWire(good.operation, good.taskId, good.key, good.trace,
            actionId = good.actionId, actionVersion = good.actionVersion, fingerprint = "altered",
            policyVersion = good.policyVersion, receipt = good.receipt)
        assertEquals(TaskOperationOutcome.REJECTED, kernel.submit(altered, UUID.randomUUID()))
        val overlong = pending.wire(checkNotNull(good.receipt).copy(confirmationExpiresAt = wall.plusSeconds(61)))
        assertEquals(TaskOperationOutcome.REJECTED, kernel.submit(overlong, UUID.randomUUID()))
        assertEquals(0, count("orion_tasks"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
    }

    @Test fun databaseFailureRollsBackEnclosingIngressAndAllowsExplicitSafeReplay() {
        val pending = prepared(); val receipt = consent(pending)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER orion_fail_event BEFORE INSERT ON orion_domain_event_outbox BEGIN SELECT RAISE(ABORT, 'orion_injected_failure'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { kernel.submit(receipt, UUID.randomUUID()) }
        assertEquals(0, count("orion_tasks")); assertEquals(0, count("orion_domain_event_outbox"))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(pending.display.actionId.toString())!!.status)
        assertEquals(1, db.ingress().queued(100).size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_fail_event")
        assertEquals(1, kernel.ingress.drain(25)); assertEquals(1, count("orion_tasks"))
    }
    @Test fun completionAndCancellationFlowThroughSameDurablePipeline() {
        val pending = prepared(); kernel.submit(consent(pending), UUID.randomUUID())
        val key = UUID.randomUUID()
        assertEquals(TaskOperationOutcome.QUEUED, kernel.submit(OrionTaskIngressWire("COMPLETE", command.taskId, key,
            UUID.randomUUID(), expectedVersion = 0), key))
        val complete = kernel.pending().single()
        assertEquals(command.title, complete.display.title)
        assertEquals(TaskOperationOutcome.APPLIED, kernel.submit(consent(complete), UUID.randomUUID()))
        assertEquals(TaskStatus.COMPLETED, db.tasks().find(command.taskId.toString())!!.status)
        assertEquals(2, count("orion_domain_event_outbox"))
        val cancelKey = UUID.randomUUID()
        assertEquals(TaskOperationOutcome.REJECTED, kernel.submit(OrionTaskIngressWire("CANCEL", command.taskId,
            cancelKey, UUID.randomUUID(), expectedVersion = 0), cancelKey))
        assertEquals(TaskStatus.COMPLETED, db.tasks().find(command.taskId.toString())!!.status)
        assertEquals(2, count("orion_action_requests"))
        val other = UUID.randomUUID(); val createKey = UUID.randomUUID()
        kernel.submit(OrionTaskIngressWire("CREATE", other, createKey, UUID.randomUUID(), "Outra tarefa"), createKey)
        kernel.submit(consent(kernel.pending().single()), UUID.randomUUID())
        val statusKey = UUID.randomUUID()
        kernel.submit(OrionTaskIngressWire("CANCEL", other, statusKey, UUID.randomUUID()), statusKey)
        assertEquals(TaskOperationOutcome.APPLIED, kernel.submit(consent(kernel.pending().single()), UUID.randomUUID()))
        assertEquals(TaskStatus.CANCELLED, db.tasks().find(other.toString())!!.status)
        assertEquals(4, count("orion_domain_event_outbox"))
    }
}

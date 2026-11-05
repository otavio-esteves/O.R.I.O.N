package network.orion.app.tasks

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.policy.tasks.OrionCreateTaskPolicy
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionCreateTaskActionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_task_preparation_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var actions: OrionActionStore
    private lateinit var preparation: OrionCreateTaskActionStore
    private val barrier = StartupBarrier()
    private val clock = WallClock { Instant.parse("2026-11-05T03:00:00Z") }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 9 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private var available = true
    private val owner = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier,
        { if (available) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED },
        { OrionPolicyEvidence.SATISFIED })
    private val channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
    private val command = CreateTask(UUID.randomUUID(), "Preparar relatório confidencial", UUID.randomUUID(), UUID.randomUUID())

    @Before fun open() {
        db = database()
        bindStores()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
    }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun database() = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    private fun bindStores() {
        actions = OrionActionStore(db, encryption, clock)
        preparation = OrionCreateTaskActionStore(db, actions, clock, barrier)
    }
    private fun admission(command: CreateTask = this.command) =
        (channel.captureCreateFromUser(command) as OrionTaskAdmissionResult.Admitted).request
    private fun preview(command: CreateTask = this.command) =
        (preparation.prepare(admission(command)) as OrionTaskActionResult.Ready).preview
    private fun blocked(result: OrionTaskActionResult, reason: OrionTaskActionFailure) =
        assertEquals(reason, (result as OrionTaskActionResult.Blocked).reason)
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun preparationAtomicallyPersistsEncryptedActionAndWaitingAuditWithoutTask() {
        val preview = preview()
        assertNotEquals(command.taskId, preview.actionId)
        val row = checkNotNull(db.actions().find(preview.actionId.toString()))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, row.status)
        assertEquals(1L, row.version)
        assertEquals(2, db.actions().auditFor(row.actionId).size)
        assertEquals(Sensitivity.SENSITIVE, row.payloadSensitivity)
        assertFalse(row.argumentsEnvelope.toString(Charsets.ISO_8859_1).contains(command.title))
        assertNull(row.confirmedAt)
        assertEquals(0, count("orion_tasks"))
        assertEquals(0, count("orion_domain_event_outbox"))
    }

    @Test fun normalizedReplayWithNewTraceReturnsOriginalActionAndNoExtraAudit() {
        val first = preview(command.copy(title = "  Cafe\u0301  "))
        val replay = preview(command.copy(title = "Café", correlationId = UUID.randomUUID()))
        assertEquals(first.actionId, replay.actionId)
        assertEquals(first.confirmationFingerprint, replay.confirmationFingerprint)
        assertEquals(1, count("orion_action_requests"))
        assertEquals(2, db.actions().auditFor(first.actionId.toString()).size)
    }

    @Test fun differentPayloadCannotReplaceOperationUnderSameKey() {
        val original = preview()
        blocked(preparation.prepare(admission(command.copy(title = "Outro conteúdo"))), OrionTaskActionFailure.IDEMPOTENCY_CONFLICT)
        val stored = (actions.read(original.actionId) as ActionReadResult.Ready).action
        assertEquals(original.task.canonicalNormalizedArguments, stored.request.canonicalNormalizedArguments)
        assertEquals(1, count("orion_action_requests"))
    }

    @Test fun capabilityRevocationBeforePreparationCreatesNoDraft() {
        val request = admission()
        available = false
        blocked(preparation.prepare(request), OrionTaskActionFailure.POLICY_DENIED)
        assertEquals(0, count("orion_action_requests"))
    }

    @Test fun rejectionInsideIngressMustEscapeWithoutHidingAParentRollback() {
        val request = admission()
        available = false
        assertThrows(OrionTaskTransactionBlocked::class.java) {
            barrier.whileOpen { db.runInTransaction { preparation.prepare(request) } }
        }
        assertEquals(0, count("orion_action_requests"))
        assertEquals(0, count("orion_action_audit"))
    }

    @Test fun closedOperationalBarrierCannotPrepareOrConfirm() {
        val preview = preview()
        val request = admission()
        barrier.beginRecovery()
        blocked(preparation.prepare(request), OrionTaskActionFailure.STARTUP_GATED)
        blocked(preparation.confirm(request, preview), OrionTaskActionFailure.STARTUP_GATED)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(preview.actionId.toString())?.status)
    }

    @Test fun exactFreshGestureConfirmsOnlyTheDisplayedRequestAndCreatesNoTask() {
        val preview = preview()
        val result = preparation.confirm(admission(), preview) as OrionTaskActionResult.Ready
        assertEquals(ActionStatus.CONFIRMED, result.preview.status)
        val stored = (actions.read(preview.actionId) as ActionReadResult.Ready).action
        assertEquals(2L, stored.version)
        assertEquals(preview.confirmationFingerprint, stored.confirmation?.confirmationFingerprint)
        assertEquals(clock.wallNow().plusSeconds(60), stored.confirmation?.confirmationExpiresAt)
        assertEquals(0, count("orion_tasks"))
    }

    @Test fun changedFingerprintPayloadAndVersionCannotConfirmOriginalAction() {
        val preview = preview()
        blocked(preparation.confirm(admission(), preview.copy(confirmationFingerprint = "0".repeat(64))),
            OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        blocked(preparation.confirm(admission(command.copy(title = "Alterado")), preview), OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        blocked(preparation.confirm(admission(command.copy(idempotencyKey = UUID.randomUUID())), preview),
            OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        blocked(preparation.confirm(admission(), preview.copy(version = 0)), OrionTaskActionFailure.CONFLICT)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(2, db.actions().auditFor(preview.actionId.toString()).size)
    }

    @Test fun revokedCapabilityOrLifecycleCannotConfirmAnEarlierPreview() {
        val preview = preview()
        val request = admission()
        available = false
        blocked(preparation.confirm(request, preview), OrionTaskActionFailure.POLICY_DENIED)
        available = true
        channel.onResumed(false); channel.onResumed(true)
        blocked(preparation.confirm(request, preview), OrionTaskActionFailure.POLICY_UNAVAILABLE)
        assertNull(db.actions().find(preview.actionId.toString())?.confirmedAt)
    }

    @Test fun olderPolicyRequiresNewPreparationAndConfirmation() {
        val admission = admission()
        val normal = OrionCreateTaskActionStore.normalized(UUID.randomUUID(), admission.task, "old_policy")
        val policy = IdempotencyKeyPolicy("MAIN_UI", "task.create", Duration.ofDays(90), 1,
            OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
        actions.register(normal, policy, command.idempotencyKey.toString(), command.correlationId, Sensitivity.SENSITIVE, true)
        actions.requireConfirmation(normal.actionId, 0)
        val preview = OrionTaskActionPreview(admission.task, normal.actionId, 1,
            ActionConfirmationBinding(clock).prepare(normal).confirmationFingerprint, "old_policy", ActionStatus.NEEDS_CONFIRMATION)
        blocked(preparation.confirm(admission, preview), OrionTaskActionFailure.POLICY_CHANGED)
        assertNotEquals(OrionCreateTaskPolicy.POLICY_VERSION, preview.policyVersion)
        assertNull(db.actions().find(normal.actionId.toString())?.confirmedAt)
    }

    @Test fun waitingAuditFailureRollsBackRegistrationAndBothAuditRows() {
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_wait BEFORE INSERT ON orion_action_audit
            WHEN NEW.toStatus = 'NEEDS_CONFIRMATION' BEGIN SELECT RAISE(ABORT, 'synthetic audit failure'); END""")
        assertThrows(SQLiteException::class.java) { preparation.prepare(admission()) }
        assertEquals(0, count("orion_action_requests"))
        assertEquals(0, count("orion_action_audit"))
    }

    @Test fun reopeningPreservesPendingActionAndFreshReplayDoesNotDuplicateIt() {
        val first = preview()
        db.close(); db = database(); bindStores()
        val replay = preview()
        assertEquals(first.actionId, replay.actionId)
        assertEquals(first.confirmationFingerprint, replay.confirmationFingerprint)
        assertEquals(1, count("orion_action_requests"))
        assertEquals(0, count("orion_tasks"))
    }
}

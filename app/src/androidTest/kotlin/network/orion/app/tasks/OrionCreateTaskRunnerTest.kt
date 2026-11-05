package network.orion.app.tasks

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.ActionStatus
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.OrionIntent
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.OrionDatabase
import network.orion.data.tasks.*
import network.orion.skills.api.*
import network.orion.skills.tasks.OrionCreateTaskSkill
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionCreateTaskRunnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_task_runner_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var actions: OrionActionStore
    private lateinit var preparation: OrionCreateTaskActionStore
    private lateinit var owned: OrionOwnedTaskStore
    private lateinit var runner: OrionCreateTaskRunner
    private var wall = Instant.parse("2026-11-05T03:00:00Z")
    private var elapsed = 100L
    private var boot: BootSessionId? = BootSessionId(7)
    private var monotonicReads = 0
    private var expireOnThirdRead = false
    private val clock = object : OrionClock {
        override fun wallNow() = wall
        override fun monotonicNow() = MonotonicInstant(boot,
            if (expireOnThirdRead && ++monotonicReads >= 3) 100_000 else elapsed)
    }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 11 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val barrier = StartupBarrier()
    private var capable = true
    private val owner = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier,
        { if (capable) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED },
        { OrionPolicyEvidence.SATISFIED })
    private val channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
    private val command = CreateTask(UUID.randomUUID(), "Concluir relatório", UUID.randomUUID(), UUID.randomUUID())

    @Before fun open() {
        db = database(); bind()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
    }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun database() = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    private fun bind() {
        actions = OrionActionStore(db, encryption, clock)
        preparation = OrionCreateTaskActionStore(db, actions, clock, barrier)
        owned = OrionOwnedTaskStore(db, encryption, clock)
        runner = OrionCreateTaskRunner(db, actions, owned, clock, barrier)
    }
    private fun admission(command: CreateTask = this.command) =
        (channel.captureCreateFromUser(command) as OrionTaskAdmissionResult.Admitted).request
    private fun confirmed(command: CreateTask = this.command): OrionTaskActionPreview {
        val pending = (preparation.prepare(admission(command)) as OrionTaskActionResult.Ready).preview
        return (preparation.confirm(admission(command), pending) as OrionTaskActionResult.Ready).preview
    }
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
    private fun noEffect(preview: OrionTaskActionPreview) {
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(2L, db.actions().find(preview.actionId.toString())?.version)
        assertEquals(3, db.actions().auditFor(preview.actionId.toString()).size)
        assertEquals(0, count("orion_tasks")); assertEquals(0, count("orion_domain_event_outbox"))
    }

    @Test fun createCommitsTaskEventActionAndAuditTogetherAndReplayIsHarmless() {
        val preview = confirmed()
        val result = runner.execute(admission(), preview) as OrionTaskExecutionResult.Completed
        assertEquals(command.taskId, result.taskId)
        assertFalse(result.deduplicated)
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(4L, db.actions().find(preview.actionId.toString())?.version)
        assertEquals(5, db.actions().auditFor(preview.actionId.toString()).size)
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
        assertTrue(owned.matchesExisting(admission().task))
        assertTrue((runner.execute(admission(), preview) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(5, db.actions().auditFor(preview.actionId.toString()).size)
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
    }

    @Test fun unconfirmedActionCannotCreateTask() {
        val preview = (preparation.prepare(admission()) as OrionTaskActionResult.Ready).preview
        assertEquals(OrionTaskExecutionFailure.INVALID_STATE,
            (runner.execute(admission(), preview) as OrionTaskExecutionResult.Blocked).reason)
        assertEquals(0, count("orion_tasks"))
    }

    @Test fun forgedSkillContextAndModelMetadataCannotWriteInsideOrOutsideTransaction() {
        val preview = confirmed()
        val skill = OrionCreateTaskSkill(owned)
        val input = admission().task
        val intent = OrionIntent("task.create", 1.0, input, "LLM_granted_permission", false, command.correlationId)
        val context = SkillContext(preview.actionId, UUID.randomUUID(), 3)
        assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        db.runInTransaction {
            assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        }
        noEffect(preview)
    }

    @Test fun changedPayloadOrFingerprintCannotExecuteTheConfirmedAction() {
        val preview = confirmed()
        assertTrue(runner.execute(admission(command.copy(title = "Alterado")), preview) is OrionTaskExecutionResult.Blocked)
        assertTrue(runner.execute(admission(), preview.copy(confirmationFingerprint = "0".repeat(64))) is OrionTaskExecutionResult.Blocked)
        noEffect(preview)
    }

    @Test fun capabilityRevocationAndStartupClosureCannotExecute() {
        val preview = confirmed()
        val request = admission()
        capable = false
        assertEquals(OrionTaskExecutionFailure.POLICY_DENIED, (runner.execute(request, preview) as OrionTaskExecutionResult.Blocked).reason)
        capable = true; barrier.beginRecovery()
        assertEquals(OrionTaskExecutionFailure.STARTUP_GATED, (runner.execute(request, preview) as OrionTaskExecutionResult.Blocked).reason)
        noEffect(preview)
    }

    @Test fun expiredConfirmationOrUnavailableBootIdentityCannotExecute() {
        val preview = confirmed()
        wall = wall.plusSeconds(60)
        assertEquals(OrionTaskExecutionFailure.CONFIRMATION_EXPIRED, (runner.execute(admission(), preview) as OrionTaskExecutionResult.Blocked).reason)
        wall = wall.minusSeconds(60); boot = null
        assertEquals(OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE, (runner.execute(admission(), preview) as OrionTaskExecutionResult.Blocked).reason)
        noEffect(preview)
    }

    @Test fun failedEventInsertionRollsBackTaskClaimAndRunningAudit() {
        val preview = confirmed()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_task_event BEFORE INSERT ON orion_domain_event_outbox
            BEGIN SELECT RAISE(ABORT, 'synthetic event failure'); END""")
        assertThrows(SQLiteException::class.java) { runner.execute(admission(), preview) }
        noEffect(preview)
    }

    @Test fun failedTerminalAuditRollsBackEvenAnAlreadyWrittenTaskAndEvent() {
        val preview = confirmed()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_task_success BEFORE INSERT ON orion_action_audit
            WHEN NEW.toStatus = 'SUCCEEDED' BEGIN SELECT RAISE(ABORT, 'synthetic terminal audit failure'); END""")
        assertThrows(SQLiteException::class.java) { runner.execute(admission(), preview) }
        noEffect(preview)
    }

    @Test fun leaseExpiringAfterTaskWriteRollsBackTheEntireOperation() {
        val preview = confirmed()
        expireOnThirdRead = true
        assertEquals(OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE,
            (runner.execute(admission(), preview) as OrionTaskExecutionResult.Blocked).reason)
        noEffect(preview)
    }

    @Test fun taskIdCollisionNeverOverwritesDifferentContent() {
        val first = confirmed()
        runner.execute(admission(), first)
        val conflicting = command.copy(title = "Outro título", idempotencyKey = UUID.randomUUID())
        val second = confirmed(conflicting)
        assertEquals(OrionTaskExecutionFailure.TASK_ID_CONFLICT,
            (runner.execute(admission(conflicting), second) as OrionTaskExecutionResult.Blocked).reason)
        assertTrue(owned.matchesExisting(admission().task))
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(second.actionId.toString())?.status)
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
    }

    @Test fun sameTaskIdAndContentUnderAnotherKeyDeduplicateWithoutAnotherCreatedEvent() {
        val first = confirmed()
        runner.execute(admission(), first)
        val repeat = command.copy(idempotencyKey = UUID.randomUUID(), correlationId = UUID.randomUUID())
        val second = confirmed(repeat)
        assertNotEquals(first.actionId, second.actionId)
        assertTrue((runner.execute(admission(repeat), second) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(second.actionId.toString())?.status)
        assertEquals(2, count("orion_action_requests"))
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
    }

    @Test fun successfulActionSurvivesReopenAndCannotProduceASecondTaskOrEvent() {
        val preview = confirmed()
        runner.execute(admission(), preview)
        db.close(); db = database(); bind()
        assertTrue((runner.execute(admission(), preview) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(1, count("orion_tasks")); assertEquals(1, count("orion_domain_event_outbox"))
    }
}

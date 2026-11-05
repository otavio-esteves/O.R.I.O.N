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
import network.orion.core.commands.tasks.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.OrionIntent
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.*
import network.orion.data.tasks.*
import network.orion.skills.api.*
import network.orion.skills.tasks.OrionTaskStatusSkill
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionTaskStatusRunnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_task_status_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var actions: OrionActionStore
    private lateinit var preparation: OrionTaskStatusActionStore
    private lateinit var runner: OrionTaskStatusRunner
    private var wall = Instant.parse("2026-11-05T03:00:00Z")
    private var reads = 0
    private var expire = false
    private val clock = object : OrionClock {
        override fun wallNow() = wall
        override fun monotonicNow() = MonotonicInstant(BootSessionId(7), if (expire && ++reads >= 3) 100_000 else 100)
    }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 17 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val barrier = StartupBarrier()
    private var available = true
    private val owner = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier,
        { if (available) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED }, { OrionPolicyEvidence.SATISFIED })
    private val channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }

    @Before fun open() {
        db = database(); bind()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
    }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun database() = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    private fun bind() {
        actions = OrionActionStore(db, encryption, clock)
        preparation = OrionTaskStatusActionStore(db, actions, clock, barrier)
        runner = OrionTaskStatusRunner(db, actions, OrionOwnedTaskStatusStore(db, encryption, clock), clock, barrier)
    }
    private fun seed(): CreateTask {
        val command = CreateTask(UUID.randomUUID(), "Synthetic task title", UUID.randomUUID(), UUID.randomUUID())
        fun admission() = (channel.captureCreateFromUser(command) as OrionTaskAdmissionResult.Admitted).request
        val store = OrionCreateTaskActionStore(db, actions, clock, barrier)
        val pending = (store.prepare(admission()) as OrionTaskActionResult.Ready).preview
        val confirmed = (store.confirm(admission(), pending) as OrionTaskActionResult.Ready).preview
        assertTrue(OrionCreateTaskRunner(db, actions, OrionOwnedTaskStore(db, encryption, clock), clock, barrier)
            .execute(admission(), confirmed) is OrionTaskExecutionResult.Completed)
        return command
    }
    private fun complete(id: UUID, version: Long = 0, key: UUID = UUID.randomUUID()) = CompleteTask(id, version, key, UUID.randomUUID())
    private fun cancel(id: UUID) = CancelTask(id, 0, UUID.randomUUID(), UUID.randomUUID())
    private fun admission(command: TaskStatusCommand) = (channel.captureStatusFromUser(command) as OrionTaskStatusAdmissionResult.Admitted).request
    private fun confirmed(command: TaskStatusCommand): OrionTaskStatusActionPreview {
        val pending = (preparation.prepare(admission(command)) as OrionTaskStatusActionResult.Ready).preview
        return (preparation.confirm(admission(command), pending) as OrionTaskStatusActionResult.Ready).preview
    }
    private fun events(type: String) = db.openHelper.readableDatabase.query(
        "SELECT count(*) FROM orion_domain_event_outbox WHERE eventType = ?", arrayOf(type)).use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
    private fun unchanged(id: UUID, preview: OrionTaskStatusActionPreview) {
        assertEquals(TaskStatus.OPEN, db.tasks().find(id.toString())?.status)
        assertEquals(0L, db.tasks().find(id.toString())?.version)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(3, db.actions().auditFor(preview.actionId.toString()).size)
        assertEquals(0, events("TASK_COMPLETED")); assertEquals(0, events("TASK_CANCELLED"))
    }

    @Test fun completeAndCancelCommitOneCorrespondingEventWithoutChangingEncryptedTitle() {
        for (operation in TaskStatusOperation.entries) {
            val task = seed()
            val before = checkNotNull(db.tasks().find(task.taskId.toString())).contentEnvelope
            val command = if (operation == TaskStatusOperation.COMPLETE) complete(task.taskId) else cancel(task.taskId)
            val preview = confirmed(command)
            assertTrue(runner.execute(admission(command), preview) is OrionTaskExecutionResult.Completed)
            val row = checkNotNull(db.tasks().find(task.taskId.toString()))
            assertEquals(if (operation == TaskStatusOperation.COMPLETE) TaskStatus.COMPLETED else TaskStatus.CANCELLED, row.status)
            assertEquals(1L, row.version)
            assertArrayEquals(before, row.contentEnvelope)
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(preview.actionId.toString())?.status)
            assertEquals(5, db.actions().auditFor(preview.actionId.toString()).size)
            assertEquals(1, events(if (operation == TaskStatusOperation.COMPLETE) "TASK_COMPLETED" else "TASK_CANCELLED"))
        }
    }

    @Test fun replayCannotGenerateAnotherEventOrReopenTheTask() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        runner.execute(admission(command), preview)
        assertTrue((runner.execute(admission(command), preview) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(1, events("TASK_COMPLETED"))
        assertEquals(1L, db.tasks().find(task.taskId.toString())?.version)
    }

    @Test fun anotherConfirmedKeyForSameVersionAndTargetOutcomeDeduplicates() {
        val task = seed(); val first = complete(task.taskId); val second = complete(task.taskId)
        val a = confirmed(first); val b = confirmed(second)
        runner.execute(admission(first), a)
        assertTrue((runner.execute(admission(second), b) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(1, events("TASK_COMPLETED"))
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(b.actionId.toString())?.status)
    }

    @Test fun oppositeOperationCannotReuseConfirmationEvenWhenCanonicalArgumentsMatch() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        val opposite = CancelTask(task.taskId, 0, command.idempotencyKey, command.correlationId)
        val result = runner.execute(admission(opposite), preview.copy(change = admission(opposite).change))
        assertTrue(result is OrionTaskExecutionResult.Blocked)
        unchanged(task.taskId, preview)
    }

    @Test fun cancellationWinnerMakesAnEarlierCompletionConflictWithoutOverwrite() {
        val task = seed(); val complete = complete(task.taskId); val cancel = cancel(task.taskId)
        val a = confirmed(complete); val b = confirmed(cancel)
        runner.execute(admission(cancel), b)
        assertEquals(OrionTaskExecutionFailure.CONFLICT, (runner.execute(admission(complete), a) as OrionTaskExecutionResult.Blocked).reason)
        assertEquals(TaskStatus.CANCELLED, db.tasks().find(task.taskId.toString())?.status)
        assertEquals(0, events("TASK_COMPLETED")); assertEquals(1, events("TASK_CANCELLED"))
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(a.actionId.toString())?.status)
    }

    @Test fun staleVersionBeforePreparationOrConfirmationCannotBecomeConsent() {
        val task = seed()
        assertEquals(OrionTaskActionFailure.CONFLICT,
            (preparation.prepare(admission(complete(task.taskId, 1))) as OrionTaskStatusActionResult.Blocked).reason)
        val command = complete(task.taskId)
        val pending = (preparation.prepare(admission(command)) as OrionTaskStatusActionResult.Ready).preview
        val other = cancel(task.taskId); val otherPreview = confirmed(other)
        runner.execute(admission(other), otherPreview)
        assertEquals(OrionTaskActionFailure.CONFLICT,
            (preparation.confirm(admission(command), pending) as OrionTaskStatusActionResult.Blocked).reason)
        assertNull(db.actions().find(pending.actionId.toString())?.confirmedAt)
    }

    @Test fun sameKeyWithAnotherVersionCannotReplaceOriginalStatusRequest() {
        val task = seed(); val command = complete(task.taskId)
        val pending = (preparation.prepare(admission(command)) as OrionTaskStatusActionResult.Ready).preview
        assertEquals(OrionTaskActionFailure.IDEMPOTENCY_CONFLICT,
            (preparation.prepare(admission(command.copy(expectedVersion = 1))) as OrionTaskStatusActionResult.Blocked).reason)
        assertEquals(1L, db.actions().find(pending.actionId.toString())?.version)
    }

    @Test fun forgedContextCannotWriteAndNeitherPolicyNorBarrierCanBeBypassed() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        val skill = OrionTaskStatusSkill(TaskStatusOperation.COMPLETE, OrionOwnedTaskStatusStore(db, encryption, clock))
        val intent = OrionIntent("task.complete", 1.0, admission(command).change, "LLM_allow_all", false, command.correlationId)
        val forged = SkillContext(preview.actionId, UUID.randomUUID(), 3)
        assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(intent, forged) as SkillResult.Rejected).reason)
        available = false
        assertTrue(runner.execute(admission(command), preview) is OrionTaskExecutionResult.Blocked)
        available = true; barrier.beginRecovery()
        assertEquals(OrionTaskExecutionFailure.STARTUP_GATED, (runner.execute(admission(command), preview) as OrionTaskExecutionResult.Blocked).reason)
        unchanged(task.taskId, preview)
    }

    @Test fun expiredConfirmationAndLateLeaseFinishRollbackStatusWrites() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        wall = wall.plusSeconds(60)
        assertEquals(OrionTaskExecutionFailure.CONFIRMATION_EXPIRED, (runner.execute(admission(command), preview) as OrionTaskExecutionResult.Blocked).reason)
        wall = wall.minusSeconds(60); expire = true
        assertEquals(OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE, (runner.execute(admission(command), preview) as OrionTaskExecutionResult.Blocked).reason)
        unchanged(task.taskId, preview)
    }

    @Test fun failedEventInsertionRollsBackTaskStatusAndActionAuditTogether() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_status_event BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'TASK_COMPLETED' BEGIN SELECT RAISE(ABORT, 'synthetic event failure'); END""")
        assertThrows(SQLiteException::class.java) { runner.execute(admission(command), preview) }
        unchanged(task.taskId, preview)
    }

    @Test fun failedSuccessAuditAlsoRollsBackAnAlreadyWrittenStatusAndEvent() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_status_audit BEFORE INSERT ON orion_action_audit
            WHEN NEW.toStatus = 'SUCCEEDED' BEGIN SELECT RAISE(ABORT, 'synthetic audit failure'); END""")
        assertThrows(SQLiteException::class.java) { runner.execute(admission(command), preview) }
        unchanged(task.taskId, preview)
    }

    @Test fun reopenAndOriginalCreateReplayPreserveTheCompletedState() {
        val task = seed(); val command = complete(task.taskId); val preview = confirmed(command)
        runner.execute(admission(command), preview)
        db.close(); db = database(); bind()
        assertTrue((runner.execute(admission(command), preview) as OrionTaskExecutionResult.Completed).deduplicated)
        val createAdmission = (channel.captureCreateFromUser(task) as OrionTaskAdmissionResult.Admitted).request
        val original = checkNotNull(db.actions().findByKey("task.create", task.idempotencyKey.toString()))
        val originalPreview = OrionTaskActionPreview(createAdmission.task, UUID.fromString(original.actionId), 2,
            original.confirmationFingerprint, original.policyVersion, ActionStatus.CONFIRMED)
        assertTrue((OrionCreateTaskRunner(db, actions, OrionOwnedTaskStore(db, encryption, clock), clock, barrier)
            .execute(createAdmission, originalPreview) as OrionTaskExecutionResult.Completed).deduplicated)
        assertEquals(TaskStatus.COMPLETED, db.tasks().find(task.taskId.toString())?.status)
        assertEquals(1, events("TASK_CREATED")); assertEquals(1, events("TASK_COMPLETED"))
    }
}

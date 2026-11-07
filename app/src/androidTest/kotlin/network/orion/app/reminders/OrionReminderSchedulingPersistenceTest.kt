package network.orion.app.reminders

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import network.orion.core.actions.ActionStatus
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite-only metadata fixtures. These synthetic rows never grant platform execution authority. */
@RunWith(AndroidJUnit4::class)
class OrionReminderSchedulingPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_reminder_schedule_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private val schedules get() = db.reminderSchedules()

    @Before fun open() { db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build() }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun legacyRowsAreNotScheduledAndOnlyExplicitConfirmedBindingCanJoinQueue() {
        val id = reminder()
        val missingAction = uuid()
        assertTrue(schedules.schedulingPage().isEmpty())
        assertEquals(ActionMutation.CONFLICT, schedules.bindDelivery(id, 0, 1, missingAction, 100))
        val wrongSkill = action(skill = "reminder.create")
        assertEquals(ActionMutation.CONFLICT, schedules.bindDelivery(id, 0, 1, wrongSkill, 100))
        val draft = action(status = ActionStatus.NEEDS_CONFIRMATION)
        assertEquals(ActionMutation.CONFLICT, schedules.bindDelivery(id, 0, 1, draft, 100))
        val owner = action()
        assertEquals(ActionMutation.APPLIED, schedules.bindDelivery(id, 0, 1, owner, 100))
        val row = checkNotNull(schedules.find(id))
        assertEquals(1L, row.version)
        assertEquals(1L, row.fireGeneration)
        assertEquals(owner, row.deliveryActionId)
        assertEquals(ReminderPrecision.UNSCHEDULED, row.precision)
        assertEquals(listOf(id), schedules.schedulingPage().map { it.reminderId })
    }

    @Test fun registrationUsesCasAndRetainsGenerationAcrossReconstruction() {
        val (id, owner) = bound()
        assertEquals(ActionMutation.APPLIED, register(id, owner, 1))
        val registered = checkNotNull(schedules.find(id))
        assertEquals(2L, registered.version)
        assertEquals(1L, registered.fireGeneration)
        assertEquals(1L, registered.lastScheduledGeneration)
        assertEquals(17, registered.requestCode)
        assertEquals(ReminderPrecision.APPROXIMATE, registered.precision)
        assertEquals(ReminderScheduleMethod.INEXACT_ALARM, registered.scheduleMethod)
        assertFalse(registered.registrationConfirmed)
        assertEquals(ActionMutation.CONFLICT, register(id, owner, 1))
        assertEquals(ActionMutation.APPLIED, register(id, owner, 2))
        assertEquals(1L, schedules.find(id)?.fireGeneration)
        assertEquals(1L, schedules.find(id)?.lastScheduledGeneration)
        assertEquals(3L, schedules.find(id)?.version)
    }

    @Test fun staleBindingEarlyCallbackAndUnprovenActionCannotMarkFired() {
        val (id, owner) = bound()
        register(id, owner, 1)
        fun finish(version: Long = 2, generation: Long = 1, actionId: String = owner, at: Long = 1_000) =
            schedules.completeDeliveryWithEvent(id, version, generation, actionId, at, event(id, version + 1, "REMINDER_FIRED", at))
        assertEquals(ActionMutation.CONFLICT, finish()) // CONFIRMED is not proof of registration/posting.
        setActionStatus(owner, ActionStatus.SUCCEEDED)
        assertEquals(ActionMutation.CONFLICT, finish(at = 999))
        assertEquals(ActionMutation.CONFLICT, finish(version = 1))
        assertEquals(ActionMutation.CONFLICT, finish(generation = 2))
        assertEquals(ActionMutation.CONFLICT, finish(actionId = uuid()))
        assertEquals(ActionMutation.APPLIED, finish())
        assertEquals(ReminderStatus.FIRED, schedules.find(id)?.status)
        assertEquals(1_000L, schedules.find(id)?.lastFiredAt)
        assertEquals(1L, schedules.find(id)?.fireGeneration)
        assertEquals(ActionMutation.CONFLICT, finish(version = 3))
        assertEquals(4, db.events().count())
    }

    @Test fun registrationAcknowledgementIsCasProtectedAndRecoveryResetDoesNotChangeBusinessVersion() {
        val (id, owner) = bound()
        register(id, owner, 1)
        assertFalse(checkNotNull(schedules.find(id)).registrationConfirmed)
        assertEquals(ActionMutation.CONFLICT, schedules.confirmRegistration(id, 1, 1, owner, 200))
        assertEquals(ActionMutation.CONFLICT, schedules.confirmRegistration(id, 2, 1, uuid(), 200))
        assertEquals(ActionMutation.APPLIED, schedules.confirmRegistration(id, 2, 1, owner, 200))
        assertTrue(checkNotNull(schedules.find(id)).registrationConfirmed)
        assertEquals(3L, schedules.find(id)?.version)
        assertEquals(4, db.events().count())
        assertEquals(ActionMutation.APPLIED, schedules.confirmRegistration(id, 3, 1, owner, 200))
        assertEquals(3L, schedules.find(id)?.version)
        assertEquals(4, db.events().count())
        assertEquals(1, schedules.invalidateRegistrationObservations())
        assertFalse(checkNotNull(schedules.find(id)).registrationConfirmed)
        assertEquals(3L, schedules.find(id)?.version)
        assertEquals(4, db.events().count())
        assertEquals(ActionMutation.APPLIED, schedules.confirmRegistration(id, 3, 1, owner, 300))
        assertTrue(checkNotNull(schedules.find(id)).registrationConfirmed)
        assertEquals(4L, schedules.find(id)?.version)
    }

    @Test fun acknowledgementEventFailureRollsBackObservationAndVersion() {
        val (id, owner) = bound()
        register(id, owner, 1)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_reject_registered BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_SCHEDULED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertThrows(SQLiteException::class.java) { schedules.confirmRegistration(id, 2, 1, owner, 200) }
            assertFalse(checkNotNull(schedules.find(id)).registrationConfirmed)
            assertEquals(2L, schedules.find(id)?.version)
            assertEquals(3, db.events().count())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_reject_registered") }
        assertEquals(ActionMutation.APPLIED, schedules.confirmRegistration(id, 2, 1, owner, 200))
        assertTrue(checkNotNull(schedules.find(id)).registrationConfirmed)
    }

    @Test fun eventFailureRollsBackActionSuccessAndReminderFiredInSameOuterTransaction() {
        val (id, owner) = bound()
        register(id, owner, 1)
        setActionStatus(owner, ActionStatus.RUNNING)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER orion_reject_fired BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_FIRED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertThrows(SQLiteException::class.java) {
                db.runInTransaction {
                    setActionStatus(owner, ActionStatus.SUCCEEDED)
                    schedules.completeDeliveryWithEvent(id, 2, 1, owner, 1_000, event(id, 3, "REMINDER_FIRED", 1_000))
                }
            }
            assertEquals(ActionStatus.RUNNING, db.actions().find(owner)?.status)
            assertEquals(ReminderStatus.PENDING, schedules.find(id)?.status)
            assertNull(schedules.find(id)?.lastFiredAt)
            assertEquals(2L, schedules.find(id)?.version)
            assertEquals(3, db.events().count())
        } finally { sql.execSQL("DROP TRIGGER orion_reject_fired") }
    }

    @Test fun cancelSnoozeAndEditInvalidateConsentButRetainLastArtifactGeneration() {
        for (operation in listOf("cancel", "snooze", "edit")) {
            val (id, owner) = bound()
            register(id, owner, 1)
            val changed = when (operation) {
                "cancel" -> db.records().cancelReminderWithEvent(id, 2, 500, event(id, 3, "REMINDER_CANCELLED", 500))
                "snooze" -> db.reminders().snoozeWithEvent(id, 2, 2_000, "UTC", 500,
                    event(id, 3, "REMINDER_SNOOZED", 500))
                else -> db.records().updateReminderWithEvent(id, 2, byteArrayOf(5), Sensitivity.SENSITIVE,
                    2_000, "UTC", 500, event(id, 3, "REMINDER_UPDATED", 500))
            }
            assertEquals(ActionMutation.APPLIED, changed)
            val row = checkNotNull(schedules.find(id))
            assertEquals(2L, row.fireGeneration)
            assertNull(row.deliveryActionId)
            assertEquals(ReminderPrecision.UNSCHEDULED, row.precision)
            assertEquals(ReminderScheduleMethod.UNSCHEDULED, row.scheduleMethod)
            assertEquals(1L, row.lastScheduledGeneration)
            assertEquals(17, row.requestCode)
            assertTrue(schedules.artifactPage().any { it.reminderId == id })
            assertTrue(schedules.schedulingPage().none { it.reminderId == id })
            setActionStatus(owner, ActionStatus.SUCCEEDED)
            assertEquals(ActionMutation.CONFLICT, schedules.completeDeliveryWithEvent(id, row.version, 1, owner,
                3_000, event(id, row.version + 1, "REMINDER_FIRED", 3_000)))
        }
    }

    @Test fun rebindingRetainsOldArtifactForCleanupUntilNewRegistrationCommits() {
        val (id, oldOwner) = bound()
        register(id, oldOwner, 1)
        val nextOwner = action()
        assertEquals(ActionMutation.APPLIED, schedules.bindDelivery(id, 2, 2, nextOwner, 200))
        val row = checkNotNull(schedules.find(id))
        assertEquals(2L, row.fireGeneration)
        assertEquals(1L, row.lastScheduledGeneration)
        assertEquals(17, row.requestCode)
        assertEquals(100L, row.lastScheduledAt)
        assertEquals(nextOwner, row.deliveryActionId)
        assertEquals(ActionMutation.APPLIED, schedules.prepareSchedule(id, 3, 2, nextOwner,
            ReminderPrecision.EXACT, ReminderScheduleMethod.EXACT_ALARM, 19, 200,
            "1970-01-01T00:00:01", 0, "NORMAL"))
        assertEquals(2L, schedules.find(id)?.lastScheduledGeneration)
        assertEquals(19, schedules.find(id)?.requestCode)
    }

    @Test fun pendingQueueUsesBoundedKeysetPagesWithoutIncludingLegacyRows() {
        repeat(3) { reminder() }
        val ids = (1..5).map { bound().first }.sorted()
        val first = schedules.schedulingPage(limit = 2)
        assertEquals(ids.take(2), first.map { it.reminderId })
        val second = schedules.schedulingPage(first.last().reminderId, 2)
        assertEquals(ids.drop(2).take(2), second.map { it.reminderId })
        assertEquals(ids.takeLast(1), schedules.schedulingPage(second.last().reminderId, 2).map { it.reminderId })
        for (limit in listOf(0, 101)) {
            assertThrows(IllegalArgumentException::class.java) { schedules.schedulingPage(limit = limit) }
            assertThrows(IllegalArgumentException::class.java) { schedules.artifactPage(limit = limit) }
        }
    }

    @Test fun invalidCivilMetadataAndCounterExhaustionCannotPersistRegistrationOrWrapGeneration() {
        val (id, owner) = bound()
        assertThrows(IllegalArgumentException::class.java) {
            schedules.prepareSchedule(id, 1, 1, owner, ReminderPrecision.EXACT, ReminderScheduleMethod.INEXACT_ALARM,
                17, 100, "1970-01-01T00:00:01", 0, "NORMAL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            schedules.prepareSchedule(id, 1, 1, owner, ReminderPrecision.EXACT, ReminderScheduleMethod.EXACT_ALARM,
                17, 100, "1970-01-01T00:00:02", 0, "NORMAL")
        }
        db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET fireGeneration = ? WHERE reminderId = ?",
            arrayOf<Any>(Long.MAX_VALUE, id))
        assertEquals(ActionMutation.CONFLICT, schedules.bindDelivery(id, 1, 1, action(), 100))
        assertEquals(ActionMutation.CONFLICT, db.records().cancelReminderWithEvent(id, 1, 100,
            event(id, 2, "REMINDER_CANCELLED", 100)))
        assertEquals(Long.MAX_VALUE, schedules.find(id)?.fireGeneration)
        assertEquals(1L, schedules.find(id)?.version)
    }

    @Test fun dueQueueUsesKeysetAndExcludesFutureUncertainAndUnconfirmedActions() {
        val due = (1..5).map { bound() }.sortedBy { it.first }
        val future = bound()
        db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET scheduledAt=2000 WHERE reminderId=?",
            arrayOf(future.first))
        for (status in listOf(ActionStatus.UNKNOWN, ActionStatus.MANUAL_REVIEW, ActionStatus.RUNNING,
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.FAILED, ActionStatus.CANCELLED, ActionStatus.SUCCEEDED)) {
            val (_, owner) = bound()
            setActionStatus(owner, status)
        }
        assertTrue(schedules.dueDeliveryPage(now = 999).isEmpty())
        val first = schedules.dueDeliveryPage(now = 1000, limit = 2)
        assertEquals(due.take(2).map { it.first }, first.map { it.reminderId })
        val second = schedules.dueDeliveryPage(first.last().reminderId, 1000, 2)
        assertEquals(due.drop(2).take(2).map { it.first }, second.map { it.reminderId })
        val last = schedules.dueDeliveryPage(second.last().reminderId, 1000, 2)
        assertEquals(due.takeLast(1).map { it.first }, last.map { it.reminderId })
        assertTrue(schedules.dueDeliveryPage(last.last().reminderId, 1000, 2).isEmpty())
        assertEquals(6, schedules.dueDeliveryPage(now = 2000).size)
        for (limit in listOf(0, 101)) {
            assertThrows(IllegalArgumentException::class.java) { schedules.dueDeliveryPage(now = 1000, limit = limit) }
        }
    }

    private fun reminder(): String {
        val id = uuid()
        db.records().createReminderWithEvent(ReminderEntity(id, byteArrayOf(1, 2, 3), Sensitivity.SENSITIVE,
            1_000, "UTC", 100, 100), event(id, 0, "REMINDER_CREATED", 100))
        return id
    }
    private fun bound(): Pair<String, String> {
        val id = reminder(); val owner = action()
        assertEquals(ActionMutation.APPLIED, schedules.bindDelivery(id, 0, 1, owner, 100))
        return id to owner
    }
    private fun register(id: String, owner: String, version: Long) = schedules.prepareSchedule(id, version, 1, owner,
        ReminderPrecision.APPROXIMATE, ReminderScheduleMethod.INEXACT_ALARM, 17, 100, "1970-01-01T00:00:01", 0, "NORMAL")
    private fun event(id: String, version: Long, type: String, at: Long) = DomainEventOutboxEntity(uuid(), id,
        version, type, 1, Sensitivity.SENSITIVE, at, uuid())
    private fun setActionStatus(id: String, status: ActionStatus) {
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET status=? WHERE actionId=?", arrayOf(status.name, id))
    }
    private fun action(status: ActionStatus = ActionStatus.CONFIRMED, skill: String = "reminder.deliver"): String {
        val values = ContentValues()
        db.openHelper.writableDatabase.query("PRAGMA table_info(orion_action_requests)").use { columns ->
            while (columns.moveToNext()) {
                if (columns.getInt(3) == 0) continue
                when (columns.getString(2)) {
                    "INTEGER" -> values.put(columns.getString(1), 0L)
                    "BLOB" -> values.put(columns.getString(1), byteArrayOf(1))
                    else -> values.put(columns.getString(1), "synthetic")
                }
            }
        }
        val id = uuid()
        values.put("actionId", id); values.put("skill", skill); values.put("status", status.name)
        values.put("payloadSensitivity", "SENSITIVE"); values.put("authorizationLevel", "EXTERNAL_ACTION")
        values.put("sideEffectClass", "EXTERNAL_REVERSIBLE"); values.put("recoveryStrategy", "MANUAL_REVIEW")
        values.put("idempotencyMode", "KEYED"); values.put("idempotencyKey", id); values.put("correlationId", uuid())
        db.openHelper.writableDatabase.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
        return id
    }
    private fun uuid() = UUID.randomUUID().toString()
}

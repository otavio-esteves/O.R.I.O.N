package network.orion.app.scheduling

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.app.reminderdelivery.OrionReminderDeliveryAdmissions
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.core.actions.ActionStatus
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.DurableIngressOutcome
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.queries.reminders.ReminderAlertState
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.*
import network.orion.data.reminders.OrionReminderStore
import network.orion.data.reminders.OrionReminderQuery
import network.orion.scheduler.alarms.*
import network.orion.skills.notifications.NotificationPostResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite/ingress/action ownership with synthetic UI and platform proof; no real OS artifacts. */
@RunWith(AndroidJUnit4::class)
class OrionSchedulingCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_coordinator_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var pipeline: OrionReminderDeliveryPipeline
    private lateinit var coordinator: OrionSchedulingCoordinator
    private lateinit var channel: OrionReminderDeliveryAdmissions.UiChannel
    private lateinit var gateway: ReminderDeliveryGateway
    private val barrier = StartupBarrier()
    private var readiness = CoreReadiness.DEGRADED_SAFE
    private var wall = Instant.parse("2026-11-07T10:00:00Z")
    private var permission = true
    private var invocations = 0
    private var afterWallRead: (() -> Unit)? = null
    private val clock = object : OrionClock {
        override fun wallNow(): Instant {
            val sampled = wall
            afterWallRead?.invoke()
            return sampled
        }
        override fun monotonicNow() = MonotonicInstant(BootSessionId(7), 100)
    }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val positions = mutableMapOf<String, String?>()
    private val cursors = object : OrionReconciliationCursors {
        override suspend fun read(name: String) = positions[name]
        override suspend fun write(name: String, cursor: String?) { positions[name] = cursor }
    }
    private val scheduled = mutableListOf<OrionAlarmToken>()
    private val cancelled = mutableListOf<OrionAlarmToken>()
    private var afterRegistration: ((OrionAlarmToken) -> Unit)? = null
    private var registrationAvailable = true
    private val alarms = object : OrionReminderAlarmPort {
        override fun schedule(token: OrionAlarmToken, triggerAt: Instant, requirements: AlarmRequirements): AlarmRegistrationResult {
            assertFalse(db.inTransaction())
            assertFalse(Thread.holdsLock(barrier))
            scheduled += token
            afterRegistration?.invoke(token)
            if (!registrationAvailable) return AlarmRegistrationResult.Unavailable
            return AlarmRegistrationResult.Registered(AlarmSelection(AlarmPrecision.APPROXIMATE,
                AlarmScheduleMethod.INEXACT_ALARM, AlarmSelectionReason.EXACT_ACCESS_DENIED))
        }
        override fun cancel(token: OrionAlarmToken): Boolean { cancelled += token; return true }
    }
    private val poster = object : OrionNotificationPoster {
        override fun permissionGranted() = permission
        override fun availability() = if (permission) NotificationAvailability.AVAILABLE else NotificationAvailability.PERMISSION_DENIED
        override fun post(actionId: UUID, notification: ValidatedPostNotification,
            permit: OrionNotificationInvocationPermit): NotificationPostResult {
            assertFalse(db.inTransaction())
            assertFalse(Thread.holdsLock(barrier))
            if (!permit.allows()) return NotificationPostResult.NOT_INVOKED
            invocations++
            return NotificationPostResult.POSTED
        }
    }

    @Before fun open() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), readiness))
        pipeline = OrionReminderDeliveryPipeline(db, encryption, clock, barrier, { readiness }, { true },
            OrionProcessRole.MAIN, { true }, poster)
        channel = pipeline.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        gateway = pipeline.gateway(channel)
        coordinator = OrionSchedulingCoordinator(db, encryption, clock, barrier, { readiness }, { true }, cursors, pipeline, alarms)
    }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }

    @Test fun deadLetterKeepsMaintenanceDegradedWithoutRetryingTheEventOrInvokingEffects() = runBlocking {
        val event = DomainEventOutboxEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0,
            "TASK_CREATED", 1, Sensitivity.SENSITIVE, wall.toEpochMilli(), UUID.randomUUID().toString())
        db.events().append(event)
        assertTrue(db.events().deadLetter(event.eventId, 0))
        val before = db.events().find(event.eventId)!!
        repeat(3) {
            val published = coordinator.publishEvents()
            val result = network.orion.app.maintenanceOutcome(RetentionResult(0, 0, 0, 0, 0), 50,
                0, 100, false, coordinator.reconcileRegistrations(), coordinator.deliverDue(),
                published, coordinator.hasDueEvents())
            assertFalse(result.healthy)
            assertFalse(result.needsContinuation)
        }
        val after = db.events().find(event.eventId)!!
        assertEquals(DomainEventDeliveryStatus.DEAD_LETTER, after.status)
        assertEquals(before.attemptCount, after.attemptCount)
        assertEquals(before.version, after.version)
        assertEquals(0, invocations)
    }

    @Test fun staleEarlyCallbacksNeverPoisonValidKeyAndGatedFactDrainsWithoutExternalExecution() = runBlocking {
        val preview = enable()
        assertNull(coordinator.acceptAlarm(preview.reminderId, preview.generation))
        assertNull(coordinator.acceptAlarm(preview.reminderId, preview.generation + 1))
        assertNull(dueReceipt(preview))
        wall = preview.scheduledAt
        assertNull(coordinator.acceptAlarm(preview.reminderId, preview.generation + 1))
        val ticket = barrier.beginRecovery()
        readiness = CoreReadiness.RECOVERING
        assertEquals(DurableIngressOutcome.QUEUED, coordinator.acceptAlarm(preview.reminderId, preview.generation))
        assertEquals(DurableIngressStatus.QUEUED, dueReceipt(preview)?.status)
        assertEquals(0, coordinator.ingress.drain(100))
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(0, invocations)
        readiness = CoreReadiness.DEGRADED_SAFE
        assertTrue(barrier.releaseAfterRecovery(ticket, readiness))
        assertEquals(1, coordinator.ingress.drain(100))
        assertEquals(DurableIngressStatus.CONSUMED, dueReceipt(preview)?.status)
        assertEquals(0, invocations) // Durable consumer only commits a fact/receipt.
        assertEquals(ReminderDeliveryOutcome.POSTED,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(ReminderStatus.FIRED, db.reminders().find(preview.reminderId.toString())?.status)
        assertEquals(ReminderDeliveryOutcome.POSTED,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun revokedPermissionLeavesConsumedFactAndPendingActionSafeForLaterGrant() = runBlocking {
        val preview = enable(); wall = preview.scheduledAt
        permission = false
        assertEquals(DurableIngressOutcome.QUEUED, coordinator.acceptAlarm(preview.reminderId, preview.generation))
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        val consumed = checkNotNull(dueReceipt(preview))
        assertEquals(DurableIngressStatus.CONSUMED, consumed.status)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(preview.reminderId.toString())?.status)
        assertEquals(0, invocations)
        permission = true
        assertEquals(ReminderDeliveryOutcome.POSTED,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(consumed, dueReceipt(preview)?.copy(payloadEnvelope = consumed.payloadEnvelope))
        assertEquals(1, invocations)
        assertEquals(ReminderStatus.FIRED, db.reminders().find(preview.reminderId.toString())?.status)
    }

    @Test fun clockRollbackAfterStoreSampleAcknowledgesFactWithoutAllowingEarlyInvocation() = runBlocking {
        val preview = enable(); wall = preview.scheduledAt
        assertEquals(DurableIngressOutcome.QUEUED, coordinator.acceptAlarm(preview.reminderId, preview.generation))
        val queued = checkNotNull(dueReceipt(preview))
        // The store samples a valid receipt time; the actual clock rolls back
        // immediately afterward, before the consumer acknowledges the received fact.
        afterWallRead = {
            if (db.inTransaction() && dueReceipt(preview)?.status == DurableIngressStatus.QUEUED) {
                wall = preview.scheduledAt.minusSeconds(1)
                afterWallRead = null
            }
        }
        assertEquals(1, coordinator.ingress.drain(100))
        val consumed = checkNotNull(dueReceipt(preview))
        assertArrayEquals(queued.payloadEnvelope, consumed.payloadEnvelope)
        assertEquals(queued.ingressId, consumed.ingressId)
        assertEquals(queued.canonicalPayloadHash, consumed.canonicalPayloadHash)
        assertEquals(DurableIngressStatus.CONSUMED, consumed.status)
        assertEquals(ReminderDeliveryOutcome.REJECTED,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(0, invocations)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(preview.reminderId.toString())?.status)
        wall = preview.scheduledAt
        assertEquals(0, coordinator.ingress.drain(100))
        assertEquals(DurableIngressStatus.CONSUMED, dueReceipt(preview)?.status)
        assertEquals(ReminderDeliveryOutcome.POSTED,
            coordinator.deliverAcceptedAlarm(preview.reminderId, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun cancellationDuringPlatformRegistrationRevokesBindingAndCompensatesCommittedPlan() = runBlocking {
        val preview = enable()
        afterRegistration = { token ->
            val row = checkNotNull(db.reminders().find(token.reminderId.toString()))
            assertEquals(ActionMutation.APPLIED, db.records().cancelReminderWithEvent(row.reminderId, row.version,
                wall.toEpochMilli(), event(row, "REMINDER_CANCELLED")))
        }
        val result = coordinator.reconcileRegistrations()
        assertFalse(result.healthy)
        assertEquals(1, scheduled.size)
        assertTrue(cancelled.contains(scheduled.single()))
        val row = checkNotNull(db.reminders().find(preview.reminderId.toString()))
        assertEquals(ReminderStatus.CANCELLED, row.status)
        assertNull(row.deliveryActionId)
        assertEquals(wall.toEpochMilli(), row.lastScheduledAt)
        assertEquals(preview.generation, row.lastScheduledGeneration)
        assertEquals(ReminderPrecision.UNSCHEDULED, row.precision)
        assertEquals(0, invocations)
    }

    @Test fun newlyAuthorizedReminderRegistersDirectlyEvenAfterMaintenanceScanAlreadyCompleted() = runBlocking {
        val finished = coordinator.reconcileRegistrations()
        assertTrue(finished.complete)
        assertTrue(scheduled.isEmpty())
        val preview = enable()
        assertTrue(scheduled.isEmpty())
        // A concurrent KEEP maintenance request may be coalesced. Registration
        // must already exist before that deferrable handoff matters.
        assertTrue(coordinator.reconcileOne(preview.reminderId))
        assertEquals(listOf(preview.reminderId), scheduled.map { it.reminderId })
        val row = checkNotNull(db.reminders().find(preview.reminderId.toString()))
        assertEquals(preview.generation, row.lastScheduledGeneration)
        assertEquals(ReminderPrecision.APPROXIMATE, row.precision)
        assertTrue(row.registrationConfirmed)
        assertEquals(ReminderStatus.PENDING, row.status)
        assertEquals(0, invocations)
    }

    @Test fun unavailableBackendNeverPromotesPersistedPlanToScheduledUiState() = runBlocking {
        val preview = enable()
        registrationAvailable = false
        assertFalse(coordinator.reconcileOne(preview.reminderId))
        val row = checkNotNull(db.reminders().find(preview.reminderId.toString()))
        assertFalse(row.registrationConfirmed)
        assertEquals(ReminderStatus.PENDING, row.status)
        assertEquals(ReminderAlertState.AWAITING_SCHEDULE,
            OrionReminderQuery(db, encryption, clock).find(preview.reminderId)?.alertState)
        registrationAvailable = true
        assertTrue(coordinator.reconcileOne(preview.reminderId))
        assertTrue(checkNotNull(db.reminders().find(preview.reminderId.toString())).registrationConfirmed)
        assertEquals(ReminderAlertState.SCHEDULED,
            OrionReminderQuery(db, encryption, clock).find(preview.reminderId)?.alertState)
        assertEquals(0, invocations)
    }

    @Test fun failedRegistrationClearsCompetingSuccessObservationBeforeCompensatingCancellation() = runBlocking {
        val preview = enable()
        // A competing observation is committed while this platform attempt is in flight.
        afterRegistration = { token ->
            val planned = checkNotNull(db.reminderSchedules().find(token.reminderId.toString()))
            assertEquals(ActionMutation.APPLIED, db.reminderSchedules().confirmRegistration(planned.reminderId,
                planned.version, preview.generation, preview.actionId.toString(), wall.toEpochMilli()))
            assertTrue(checkNotNull(db.reminders().find(planned.reminderId)).registrationConfirmed)
        }
        registrationAvailable = false
        assertFalse(coordinator.reconcileOne(preview.reminderId))
        val failed = checkNotNull(db.reminders().find(preview.reminderId.toString()))
        assertFalse(failed.registrationConfirmed)
        assertTrue(cancelled.contains(scheduled.single()))
        assertEquals(ReminderAlertState.AWAITING_SCHEDULE,
            OrionReminderQuery(db, encryption, clock).find(preview.reminderId)?.alertState)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, failed.status)
        assertEquals(0, invocations)
    }

    @Test fun obsoleteTokenCleanupUsesPersistedRequestCodeAndGeneration() = runBlocking {
        val preview = enable()
        val at = preview.scheduledAt.atZone(ZoneOffset.UTC)
        val persistedCode = (preview.reminderId.toString().hashCode() and Int.MAX_VALUE).let { if (it == 7) 8 else 7 }
        assertEquals(ActionMutation.APPLIED, db.reminderSchedules().prepareSchedule(preview.reminderId.toString(), 1,
            preview.generation, preview.actionId.toString(), ReminderPrecision.APPROXIMATE, ReminderScheduleMethod.INEXACT_ALARM,
            persistedCode, wall.toEpochMilli(), at.toLocalDateTime().toString(), 0, "NORMAL"))
        val registered = checkNotNull(db.reminders().find(preview.reminderId.toString()))
        assertEquals(ActionMutation.APPLIED, db.records().cancelReminderWithEvent(registered.reminderId, registered.version,
            wall.toEpochMilli(), event(registered, "REMINDER_CANCELLED")))
        val result = coordinator.reconcileRegistrations()
        assertTrue(result.complete)
        assertTrue(cancelled.contains(OrionAlarmToken(preview.reminderId, preview.generation, persistedCode)))
        assertTrue(scheduled.isEmpty())
        assertEquals(0, invocations)
    }

    @Test fun dueCursorSurvivesCoordinatorRecreationAndPassesUnreadableConfirmedPrefix() = runBlocking {
        val valid = enable(UUID(0, 106))
        for (index in 1L..105L) seedUnreadablePrefix(UUID(0, index), UUID(1, index), valid)
        wall = valid.scheduledAt
        val first = coordinator.deliverDue()
        assertFalse(first.complete)
        assertFalse(first.healthy)
        assertNotNull(positions["due_reminders"])
        assertEquals(0, invocations)
        val persisted = positions["due_reminders"]
        assertEquals(UUID(0, 100).toString(), persisted)
        // Recreate the MAIN coordinator while preserving its durable scan position.
        // Recovery may reset OS-registration cursors, but must retain due fairness.
        coordinator = OrionSchedulingCoordinator(db, encryption, clock, barrier, { readiness }, { true },
            cursors, pipeline, alarms)
        assertEquals(persisted, positions["due_reminders"])
        val second = coordinator.deliverDue()
        assertTrue(second.complete)
        assertEquals(1, invocations)
        assertEquals(ReminderStatus.FIRED, db.reminders().find(valid.reminderId.toString())?.status)
        assertNull(positions["due_reminders"])
        coordinator.deliverDue()
        assertEquals(1, invocations)
    }

    private suspend fun enable(id: UUID = UUID.randomUUID()): ReminderDeliveryPreview {
        OrionReminderStore(db, encryption, clock).create(id, byteArrayOf(1), Sensitivity.SENSITIVE,
            wall.plusSeconds(60), ZoneOffset.UTC, UUID.randomUUID())
        val preview = (gateway.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(preview))
        return preview
    }
    private fun dueReceipt(preview: ReminderDeliveryPreview) = db.ingress().findByKey(
        OrionSchedulingCoordinator.DUE_SCOPE, preview.actionId.toString())
    private fun event(row: ReminderEntity, type: String) = DomainEventOutboxEntity(UUID.randomUUID().toString(),
        row.reminderId, row.version + 1, type, 1, row.sensitivity, wall.toEpochMilli(), UUID.randomUUID().toString())

    /** Clone ciphertext under different AAD: it must stay unreadable and must never gain execution authority. */
    private fun seedUnreadablePrefix(id: UUID, actionId: UUID, valid: ReminderDeliveryPreview) {
        val values = ContentValues()
        db.openHelper.readableDatabase.query("SELECT * FROM orion_action_requests WHERE actionId=?",
            arrayOf(valid.actionId.toString())).use { row ->
            assertTrue(row.moveToFirst())
            for (column in 0 until row.columnCount) {
                val key = row.getColumnName(column)
                when (row.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> values.putNull(key)
                    Cursor.FIELD_TYPE_INTEGER -> values.put(key, row.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> values.put(key, row.getDouble(column))
                    Cursor.FIELD_TYPE_BLOB -> values.put(key, row.getBlob(column))
                    else -> values.put(key, row.getString(column))
                }
            }
        }
        values.put("actionId", actionId.toString()); values.put("idempotencyKey", actionId.toString())
        db.openHelper.writableDatabase.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
        val row = ReminderEntity(id.toString(), byteArrayOf(1), Sensitivity.SENSITIVE, valid.scheduledAt.toEpochMilli(),
            "UTC", wall.toEpochMilli(), wall.toEpochMilli())
        db.records().createReminderWithEvent(row, DomainEventOutboxEntity(UUID.randomUUID().toString(), row.reminderId,
            0, "REMINDER_CREATED", 1, row.sensitivity, wall.toEpochMilli(), UUID.randomUUID().toString()))
        assertEquals(ActionMutation.APPLIED, db.reminderSchedules().bindDelivery(id.toString(), 0,
            1, actionId.toString(), wall.toEpochMilli()))
    }
}

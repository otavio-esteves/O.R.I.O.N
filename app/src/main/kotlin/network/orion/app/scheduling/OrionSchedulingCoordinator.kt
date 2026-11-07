package network.orion.app.scheduling

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.core.actions.ActionStatus
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.reminders.ReminderDeliveryOutcome
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.database.*
import network.orion.data.events.*
import network.orion.data.repository.*
import network.orion.scheduler.alarms.*

internal interface OrionReconciliationCursors {
    suspend fun read(name: String): String?
    suspend fun write(name: String, cursor: String?)
}

internal data class OrionReconciliationResult(val complete: Boolean, val healthy: Boolean)

/** MAIN owner. OS registrations are replayable artifacts, never the reminder source of truth. */
internal class OrionSchedulingCoordinator(
    private val db: OrionDatabase,
    encryption: OrionFieldEncryption,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    readiness: () -> CoreReadiness,
    storageAvailable: () -> Boolean,
    private val cursors: OrionReconciliationCursors,
    private val delivery: OrionReminderDeliveryPipeline,
    private val alarms: OrionReminderAlarmPort,
    private val exactCapability: ExactAlarmCapability = ExactAlarmCapability { ExactAlarmAvailability.UNAVAILABLE },
) {
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { input ->
        val persisted = db.ingress().find(input.ingressId.toString())
        val reference = input.referenceId
        if (input.scope != DUE_SCOPE || input.schemaVersion != 1 || input.commandType != DUE_TYPE ||
            input.sensitivity != Sensitivity.SENSITIVE || persisted?.source != DUE_SOURCE ||
            persisted.status != DurableIngressStatus.PROCESSING || reference == null ||
            !delivery.dueReferenceAllowed(reference)) LocalIngressDisposition.REJECTED
        else LocalIngressDisposition.CONSUMED
        // Consumer commits receipt only. It never posts or calls the external runner.
    }, consumerScope = DUE_SCOPE)
    val ingress = DurableIngressCoordinator(store, barrier, readiness, storageAvailable)
    private val events = OrionEventDispatcher(db.events(), clock, OrionTechnicalEventConsumer.routes(db.events(), clock))

    fun publishEvents(): Boolean = try {
        val result = events.dispatchBatch(100)
        result.deadLettered == 0 && result.deferred == 0 && count("orion_domain_event_outbox", "status = 'DEAD_LETTER'") == 0
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
    catch (_: Exception) { false } // Reservation/backoff survives; never log payload/error text.

    /** Safe during recovery: registration cannot execute a business effect before the real barrier opens. */
    suspend fun reconcileRegistrations(): OrionReconciliationResult {
        var healthy = true
        var complete = true
        var cleanup = cursors.read("alarm_cleanup")
        for (pageIndex in 0 until MAX_PAGES) {
            val page = db.reminderSchedules().artifactPage(cleanup, PAGE_SIZE)
            for (row in page) {
                val old = row.lastScheduledGeneration ?: continue
                if (row.status != ReminderStatus.PENDING || row.deliveryActionId == null || old != row.fireGeneration) {
                    if (!alarms.cancel(token(row, old))) healthy = false
                }
            }
            cleanup = page.lastOrNull()?.reminderId
            if (page.size < PAGE_SIZE) cleanup = null
            cursors.write("alarm_cleanup", cleanup)
            if (cleanup == null) break
            if (pageIndex == MAX_PAGES - 1) complete = false
        }
        var cursor = cursors.read("reminders")
        for (pageIndex in 0 until MAX_PAGES) {
            val page = db.reminderSchedules().schedulingPage(cursor, PAGE_SIZE)
            for (row in page) if (!registerRow(row)) healthy = false
            cursor = page.lastOrNull()?.reminderId
            if (page.size < PAGE_SIZE) cursor = null
            cursors.write("reminders", cursor)
            if (cursor == null) break
            if (pageIndex == MAX_PAGES - 1) complete = false
        }
        return OrionReconciliationResult(complete, healthy)
    }

    fun reconcileOne(reminderId: UUID): Boolean {
        val row = db.reminderSchedules().find(reminderId.toString()) ?: return false
        return row.status == ReminderStatus.PENDING && registerRow(row, includeDue = true)
    }

    private fun registerRow(row: ReminderEntity, includeDue: Boolean = false): Boolean = try {
        tryRegisterRow(row, includeDue)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
    catch (_: Exception) {
        // Optional scheduling failure cannot grant delivery or pretend the OS artifact exists.
        failRegistration(row)
    }

    private fun failRegistration(row: ReminderEntity): Boolean {
        // Reset before cancellation, including a failed/throwing cancellation. A newer
        // generation owns its own observation and is protected by the DAO predicate.
        db.reminderSchedules().invalidateRegistrationObservation(row.reminderId, row.fireGeneration)
        alarms.cancel(token(row, row.fireGeneration))
        return false
    }

    private fun tryRegisterRow(row: ReminderEntity, includeDue: Boolean): Boolean {
        val actionId = row.deliveryActionId ?: return true
        val action = db.actions().find(actionId)
        if (action?.status != ActionStatus.CONFIRMED) {
            db.reminderSchedules().invalidateRegistrationObservation(row.reminderId, row.fireGeneration)
            val cancelled = alarms.cancel(token(row, row.lastScheduledGeneration ?: row.fireGeneration))
            return action?.status == ActionStatus.SUCCEEDED && cancelled
        }
        val at = Instant.ofEpochMilli(row.scheduledAt)
        if (!at.isAfter(clock.wallNow()) && !includeDue) return true
        if (action.confirmationExpiresAt?.let { it <= clock.wallNow().toEpochMilli() } != false) return failRegistration(row)
        val oldGeneration = row.lastScheduledGeneration
        if (oldGeneration != null && oldGeneration != row.fireGeneration && !alarms.cancel(token(row, oldGeneration))) return failRegistration(row)
        val currentToken = token(row, row.fireGeneration)
        val requested = OrionAlarmStrategy(exactCapability).select(AlarmRequirements(true, true, true))
        if (row.registrationConfirmed && row.lastScheduledGeneration == row.fireGeneration &&
            row.precision.name == requested.precision.name && row.scheduleMethod.name == requested.method.name &&
            row.requestCode == currentToken.requestCode) return true
        val zoned = at.atZone(ZoneId.of(row.zoneId))
        fun persistSelection(current: ReminderEntity, selected: AlarmSelection): Boolean {
            val precision = ReminderPrecision.valueOf(selected.precision.name)
            val method = ReminderScheduleMethod.valueOf(selected.method.name)
            if (current.lastScheduledGeneration == current.fireGeneration && current.precision == precision &&
                current.scheduleMethod == method && current.requestCode == currentToken.requestCode) return true
            return db.reminderSchedules().prepareSchedule(current.reminderId, current.version,
                current.fireGeneration, actionId, precision, method, currentToken.requestCode,
                clock.wallNow().toEpochMilli(), zoned.toLocalDateTime().toString(), zoned.offset.totalSeconds, "NORMAL") == ActionMutation.APPLIED
        }
        // Persist the desired mechanism before Android. Same-token replacement never changes confirmation.
        if (!persistSelection(row, requested)) return failRegistration(row)
        val planned = db.reminderSchedules().find(row.reminderId) ?: return failRegistration(row)
        if (planned.deliveryActionId != actionId || planned.fireGeneration != row.fireGeneration || planned.status != ReminderStatus.PENDING) return failRegistration(row)
        val registered = alarms.schedule(currentToken, at, AlarmRequirements(true, true, true))
        if (registered !is AlarmRegistrationResult.Registered) return failRegistration(row)
        val fresh = db.reminderSchedules().find(row.reminderId)
        if (fresh == null || fresh.deliveryActionId != actionId || fresh.fireGeneration != row.fireGeneration || fresh.status != ReminderStatus.PENDING) {
            return failRegistration(row)
        }
        if (!persistSelection(fresh, registered.selection)) return failRegistration(row)
        val selected = db.reminderSchedules().find(row.reminderId) ?: return failRegistration(row)
        if (db.reminderSchedules().confirmRegistration(row.reminderId, selected.version, row.fireGeneration,
                actionId, clock.wallNow().toEpochMilli()) != ActionMutation.APPLIED) {
            return failRegistration(row)
        }
        return true
    }

    /** Atomic next-binding advancement has committed; install its derived artifact immediately. */
    private fun afterPosted(previous: ReminderEntity, generation: Long): Boolean {
        val cancelled = alarms.cancel(token(previous, generation))
        val next = db.reminderSchedules().find(previous.reminderId) ?: return cancelled
        return if (next.status == ReminderStatus.PENDING && next.fireGeneration != generation)
            registerRow(next, includeDue = true) && cancelled else cancelled
    }

    /** Validation before enqueue prevents early/stale callbacks from poisoning a valid occurrence's replay key. */
    fun acceptAlarm(reminderId: UUID, generation: Long): DurableIngressOutcome? {
        val row = db.reminderSchedules().find(reminderId.toString()) ?: return null
        if (row.status != ReminderStatus.PENDING || row.fireGeneration != generation || row.deliveryActionId == null ||
            row.scheduledAt > clock.wallNow().toEpochMilli()) return null
        val actionId = UUID.fromString(row.deliveryActionId)
        if (!delivery.dueAllowed(actionId)) return null
        return ingress.submit(DurableIngressRequest(UUID.randomUUID(), DUE_SCOPE, actionId, DUE_SOURCE, DUE_TYPE,
            1, Sensitivity.SENSITIVE, true, actionId, null, Duration.ofDays(1).toMillis(),
            Duration.ofDays(455).toMillis(), correlationId = reminderId))
    }

    /** Exact-alarm delivery does not wait for deferrable WorkManager execution. */
    fun deliverAcceptedAlarm(reminderId: UUID, generation: Long): ReminderDeliveryOutcome {
        if (!barrier.isOpen() || db.inTransaction() || Thread.holdsLock(barrier)) return ReminderDeliveryOutcome.UNAVAILABLE
        ingress.drain(100)
        val row = db.reminderSchedules().find(reminderId.toString()) ?: return ReminderDeliveryOutcome.REJECTED
        val actionId = row.deliveryActionId ?: return ReminderDeliveryOutcome.REJECTED
        if (row.fireGeneration != generation || db.ingress().findByKey(DUE_SCOPE, actionId)?.status != DurableIngressStatus.CONSUMED)
            return ReminderDeliveryOutcome.REJECTED
        val result = delivery.executeDue(reminderId, generation)
        if (result == ReminderDeliveryOutcome.POSTED) afterPosted(row, generation)
        return result
    }

    /** Call only after recovery has returned; external invocation is outside ingress/Room/barrier monitor. */
    suspend fun deliverDue(): OrionReconciliationResult {
        if (!barrier.isOpen() || db.inTransaction() || Thread.holdsLock(barrier)) return OrionReconciliationResult(false, false)
        var healthy = true
        val cursor = cursors.read("due_reminders")
        val page = db.reminderSchedules().dueDeliveryPage(cursor, clock.wallNow().toEpochMilli(), 100)
        val rows = page.map { UUID.fromString(it.reminderId) to it.fireGeneration }
        for ((id, generation) in rows) acceptAlarm(id, generation)
        ingress.drain(100)
        for ((id, generation) in rows) {
            val row = db.reminderSchedules().find(id.toString()) ?: continue
            val actionId = row.deliveryActionId ?: continue
            val receipt = db.ingress().findByKey(DUE_SCOPE, actionId)
            if (receipt?.status != DurableIngressStatus.CONSUMED) { healthy = false; continue }
            when (delivery.executeDue(id, generation)) {
                ReminderDeliveryOutcome.POSTED -> if (!afterPosted(row, generation)) healthy = false
                else -> healthy = false
            }
        }
        val complete = rows.size < 100
        cursors.write("due_reminders", if (complete) null else page.last().reminderId)
        return OrionReconciliationResult(complete, healthy)
    }

    fun count(table: String, predicate: String = "1"): Int {
        require(table in setOf("orion_reminders", "orion_domain_event_outbox"))
        require(predicate in setOf("1", "status = 'PENDING'", "status = 'DEAD_LETTER'", "precision = 'APPROXIMATE' AND status = 'PENDING'"))
        return db.openHelper.readableDatabase.query("SELECT count(*) FROM $table WHERE $predicate").use { it.moveToFirst(); it.getInt(0) }
    }

    private fun token(row: ReminderEntity, generation: Long) = OrionAlarmToken(UUID.fromString(row.reminderId), generation,
        if (generation == row.lastScheduledGeneration) row.requestCode else row.reminderId.hashCode() and Int.MAX_VALUE)

    fun hasDueEvents(): Boolean = db.openHelper.readableDatabase.query(
        "SELECT EXISTS(SELECT 1 FROM orion_domain_event_outbox WHERE status='PENDING' AND nextAttemptAt <= ?)",
        arrayOf(clock.wallNow().toEpochMilli())).use { it.moveToFirst(); it.getInt(0) != 0 }

    companion object {
        const val DUE_SCOPE = "reminder.delivery.due"
        const val DUE_SOURCE = "ANDROID_SCHEDULER"
        const val DUE_TYPE = "REMINDER_DUE"
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 4
    }
}

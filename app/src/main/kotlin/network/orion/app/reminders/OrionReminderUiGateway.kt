package network.orion.app.reminders

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.commands.reminders.*
import network.orion.core.orchestrator.reminders.*
import network.orion.core.queries.reminders.*
import network.orion.core.time.OrionClock

/** Trusted Activity-scoped adapter. Capture the actual gesture before the first IO suspension. */
internal class OrionReminderUiGateway(private val channel: OrionReminderAdmissionOwner.UiChannel,
    private val kernel: () -> OrionReminderKernel?, private val clock: OrionClock, private val onStorageFailure: () -> Unit = {}) : ReminderGateway {
    @Volatile private var displayed: Map<UUID, OrionReminderPending> = emptyMap()

    override fun prepareCreate(id: UUID, title: String, afterMinutes: Long, key: UUID, trace: UUID): CreateReminder? {
        val at = future(afterMinutes) ?: return null
        return CreateReminder(id, title, at, java.time.ZoneId.systemDefault(), key, trace)
    }
    override fun prepareSnooze(id: UUID, version: Long, currentScheduledAt: java.time.Instant, afterMinutes: Long, key: UUID, trace: UUID): SnoozeReminder? {
        val at = future(afterMinutes, currentScheduledAt) ?: return null
        return SnoozeReminder(id, version, at, java.time.ZoneId.systemDefault(), key, trace)
    }
    private fun future(minutes: Long, previous: java.time.Instant? = null): java.time.Instant? {
        if (minutes !in 1..525_600) return null
        return try { maxOf(clock.wallNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), previous ?: java.time.Instant.MIN).plusSeconds(minutes * 60) }
        catch (_: java.time.DateTimeException) { null } catch (_: ArithmeticException) { null }
    }

    override suspend fun create(command: CreateReminder): CreateReminderResult {
        val captured = channel.captureCreateFromUser(command)
        if (captured is OrionReminderAdmissionResult.Invalid) return CreateReminderResult.REJECTED
        val reminder = (captured as? OrionReminderAdmissionResult.Admitted)?.request?.reminder ?: return CreateReminderResult.UNAVAILABLE
        val wire = OrionReminderIngressWire("CREATE", reminder.reminderId, reminder.idempotencyKey, reminder.correlationId, reminder.title, scheduledAtMillis = reminder.scheduledAtEpochMillis, zoneId = reminder.zoneId.id)
        return when (send(wire, reminder.idempotencyKey)) {
            ReminderOperationOutcome.QUEUED, ReminderOperationOutcome.APPLIED -> CreateReminderResult.QUEUED
            ReminderOperationOutcome.REJECTED -> CreateReminderResult.REJECTED
            ReminderOperationOutcome.UNAVAILABLE -> CreateReminderResult.UNAVAILABLE
        }
    }

    override suspend fun change(command: ReminderStatusCommand): ReminderStatusResult {
        val captured = channel.captureStatusFromUser(command)
        if (captured is OrionReminderStatusAdmissionResult.Invalid) return ReminderStatusResult.REJECTED
        val change = (captured as? OrionReminderStatusAdmissionResult.Admitted)?.request?.change ?: return ReminderStatusResult.UNAVAILABLE
        return when (send(OrionReminderIngressWire(change.operation.name, change.reminderId, change.idempotencyKey,
            change.correlationId, expectedVersion = change.expectedVersion, scheduledAtMillis = change.scheduledAt?.toEpochMilli() ?: 0, zoneId = change.zoneId?.id ?: "UTC"), change.idempotencyKey)) {
            ReminderOperationOutcome.QUEUED, ReminderOperationOutcome.APPLIED -> ReminderStatusResult.QUEUED
            ReminderOperationOutcome.REJECTED -> ReminderStatusResult.REJECTED
            ReminderOperationOutcome.UNAVAILABLE -> ReminderStatusResult.UNAVAILABLE
        }
    }

    override suspend fun list(request: GetReminders): ReminderPage? = withContext(Dispatchers.IO) { kernel()?.query?.get(request) }
    override suspend fun pending(): List<ReminderConfirmation> {
        val selected = withContext(Dispatchers.IO) { kernel()?.pending() ?: emptyList() }
        displayed = selected.associateBy { it.display.actionId }
        return selected.map { it.display }
    }

    override suspend fun confirm(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion && it.display.title != null }
            ?: return ReminderOperationOutcome.REJECTED
        val normalized = selected.create?.let { p ->
            if (channel.captureCreateFromUser(CreateReminder(p.reminder.reminderId, p.reminder.title, p.reminder.scheduledAt, p.reminder.zoneId, p.reminder.idempotencyKey,
                    p.reminder.correlationId)) !is OrionReminderAdmissionResult.Admitted) return ReminderOperationOutcome.UNAVAILABLE
            OrionCreateReminderActionStore.normalized(p.actionId, p.reminder, p.policyVersion)
        } ?: selected.change?.let { p ->
            val c = p.change
            val command = if (c.operation == ReminderStatusOperation.SNOOZE) SnoozeReminder(c.reminderId, c.expectedVersion, checkNotNull(c.scheduledAt), checkNotNull(c.zoneId), c.idempotencyKey, c.correlationId)
                else CancelReminder(c.reminderId, c.expectedVersion, c.idempotencyKey, c.correlationId)
            if (channel.captureStatusFromUser(command) !is OrionReminderStatusAdmissionResult.Admitted) return ReminderOperationOutcome.UNAVAILABLE
            OrionReminderStatusActionStore.normalized(p.actionId, c, p.policyVersion)
        } ?: return ReminderOperationOutcome.REJECTED
        // Recorded now, at the live callback; persisted replay must retain these exact times.
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(normalized, clock.wallNow().plusSeconds(60))
        return send(selected.wire(receipt), UUID.randomUUID())
    }

    override suspend fun dismiss(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion } ?: return ReminderOperationOutcome.REJECTED
        if (!channel.captureDismissFromUser()) return ReminderOperationOutcome.UNAVAILABLE
        return send(selected.wire(dismiss = true), UUID.randomUUID())
    }
    override suspend fun processPending(): Int = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.ingress?.drain(25) ?: 0 }
    }
    private suspend fun send(wire: OrionReminderIngressWire, key: UUID) = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.submit(wire, key) ?: ReminderOperationOutcome.UNAVAILABLE }
    }
    private fun <T> guardStorage(block: () -> T): T = try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (failure: Exception) { onStorageFailure(); throw failure }

}

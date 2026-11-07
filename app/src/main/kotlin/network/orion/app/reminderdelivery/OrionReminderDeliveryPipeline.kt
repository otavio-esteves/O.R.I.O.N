package network.orion.app.reminderdelivery

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.core.actions.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*

/** Separate explicit opt-in, never a reinterpretation of reminder.create's local-only consent. */
internal class OrionReminderDeliveryPipeline(
    private val db: OrionDatabase,
    encryption: OrionFieldEncryption,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    readiness: () -> CoreReadiness,
    storageAvailable: () -> Boolean,
    role: OrionProcessRole,
    presence: () -> Boolean,
    private val poster: OrionNotificationPoster,
    private val onAuthorized: (UUID) -> Unit = {},
) {
    val actions = OrionActionStore(db, encryption, clock)
    private val handoffs = OrionReminderAuthorizationHandoffs(db, barrier, onAuthorized)
    private val owner = OrionReminderDeliveryAdmissions(role, barrier, presence) { available() && storageAvailable() }
    private val series = OrionReminderSeriesPipeline(db, encryption, clock, barrier, owner, actions, readiness, storageAvailable, onAuthorized)
    val seriesIngress get() = series.ingress
    private val runner = OrionReminderDeliveryRunner(db, actions, clock, barrier, poster)
    private var processingId: UUID? = null
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        if (!owner.replayAllowed()) throw DeliveryUnavailable()
        val row = db.ingress().find(payload.ingressId.toString()) ?: return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        if (row.status != DurableIngressStatus.PROCESSING || row.source != OrionReminderDeliveryWire.SOURCE ||
            payload.scope != OrionReminderDeliveryWire.SCOPE || payload.commandType != OrionReminderDeliveryWire.TYPE ||
            payload.schemaVersion != 1 || payload.sensitivity != Sensitivity.SENSITIVE || payload.referenceId != null)
            return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        val bytes = payload.copyContent() ?: return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        val wire = try { OrionReminderDeliveryWire.decode(bytes) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            finally { bytes.fill(0) }
        if (wire.confirm) confirmLocal(wire) else prepareLocal(wire.preview)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionReminderDeliveryWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            return try { processingId = null; store.consumeNext() }
            catch (_: DeliveryUnavailable) { false }
            catch (_: DeliveryRejected) {
                val row = processingId?.let { db.ingress().find(it.toString()) } ?: return false
                check(row.status == DurableIngressStatus.QUEUED)
                check(db.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED,
                    clock.wallNow().toEpochMilli()) == ActionMutation.APPLIED)
                true
            }
        }
    }
    val ingress = DurableIngressCoordinator(queue, barrier, readiness, storageAvailable)
    fun openUiChannel() = owner.openUiChannel()
    fun invalidateAdmissions() = owner.invalidateAdmissions()
    fun flushAuthorized() {
        handoffs.flush()
        series.flushAuthorized()
    }
    fun drainUi(limit: Int): Int {
        require(limit in 1..100)
        return try {
            val first = ingress.drain(limit)
            first + if (first < limit) seriesIngress.drain(limit - first) else 0
        } finally {
            flushAuthorized()
        }
    }

    fun gateway(channel: OrionReminderDeliveryAdmissions.UiChannel): ReminderDeliveryGateway = object : ReminderDeliveryGateway {
        @Volatile private var displayed: ReminderDeliveryPreview? = null
        private val seriesDelegate = series.gateway(channel)
        override suspend fun prepareSeries(reminderId: UUID, expectedVersion: Long,
            rule: network.orion.core.model.OrionReminderRecurrenceRule) = seriesDelegate.prepareSeries(reminderId, expectedVersion, rule)
        override suspend fun confirmSeries(preview: ReminderSeriesPreview) = seriesDelegate.confirmSeries(preview)
        override suspend fun prepare(reminderId: UUID, expectedVersion: Long): ReminderDeliveryPreparation {
            val admission = channel.capture() ?: return blocked(ReminderDeliveryOutcome.UNAVAILABLE)
            return withContext(Dispatchers.IO) {
                if (!admission.valid()) return@withContext blocked(ReminderDeliveryOutcome.UNAVAILABLE)
                val row = db.reminders().find(reminderId.toString()) ?: return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                if (row.status != ReminderStatus.PENDING || row.version != expectedVersion || row.deliveryActionId != null ||
                    row.fireGeneration == Long.MAX_VALUE || row.version == Long.MAX_VALUE)
                    return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                val at = Instant.ofEpochMilli(row.scheduledAt)
                val now = clock.wallNow()
                if (!at.isAfter(now) || at.isAfter(now.plus(OrionReminderDeliveryPolicy.MAX_FUTURE)))
                    return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                val generation = row.fireGeneration + 1
                val id = UUID.randomUUID() // New proposal gesture; this ID remains stable through its queued replay.
                val initial = ReminderDeliveryPreview(reminderId, row.version, generation, at, id, 0, "",
                    OrionDeliverReminderDescriptor.POLICY_VERSION)
                when (submit(OrionReminderDeliveryWire(false, initial), id)) {
                    ReminderDeliveryOutcome.REJECTED -> blocked(ReminderDeliveryOutcome.REJECTED)
                    ReminderDeliveryOutcome.UNAVAILABLE -> blocked(ReminderDeliveryOutcome.UNAVAILABLE)
                    else -> {
                        val stored = read(id) ?: return@withContext blocked(ReminderDeliveryOutcome.QUEUED)
                        if (stored.status != ActionStatus.NEEDS_CONFIRMATION) return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                        val preview = initial.copy(actionVersion = stored.version,
                            confirmationFingerprint = ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint)
                        displayed = preview
                        ReminderDeliveryPreparation.Ready(preview)
                    }
                }
            }
        }
        override suspend fun confirm(preview: ReminderDeliveryPreview): ReminderDeliveryOutcome {
            val admission = channel.capture() ?: return ReminderDeliveryOutcome.UNAVAILABLE
            if (displayed != preview || !admission.valid()) return ReminderDeliveryOutcome.REJECTED
            // This is the original live callback. Durable replay only receives this receipt.
            val receipt = try {
                ActionConfirmationBinding(clock).recordConfirmation(OrionReminderDeliveryPolicy.normalized(preview),
                    OrionReminderDeliveryPolicy.expiresAt(preview.scheduledAt))
            } catch (_: IllegalArgumentException) { return ReminderDeliveryOutcome.REJECTED }
            val wire = OrionReminderDeliveryWire(true, preview, receipt)
            return withContext(Dispatchers.IO) {
                if (!admission.valid()) return@withContext ReminderDeliveryOutcome.UNAVAILABLE
                val key = UUID.nameUUIDFromBytes("${preview.actionId}:confirm:${receipt.confirmedAt}".toByteArray(Charsets.UTF_8))
                val outcome = submit(wire, key)
                if (outcome == ReminderDeliveryOutcome.ENABLED) displayed = null
                outcome
            }
        }
    }

    /** Root calls only outside the consumed ingress transaction, after startup recovery. */
    fun executeDue(reminderId: UUID, generation: Long): ReminderDeliveryOutcome = runner.executeDue(reminderId, generation)
    /** Read-only eligibility for reference-only due ingress; grants no invocation authority. */
    fun dueAllowed(actionId: UUID): Boolean {
        val row = referenceBinding(actionId) ?: return false
        return runner.dueAllowed(row.first, row.second, actionId)
    }

    /** Structural acknowledgement of a received fact, independent of the current wall clock or permission. */
    fun dueReferenceAllowed(actionId: UUID): Boolean {
        val row = referenceBinding(actionId) ?: return false
        return runner.dueReferenceAllowed(row.first, row.second, actionId)
    }

    private fun referenceBinding(actionId: UUID): Pair<UUID, Long>? {
        val action = read(actionId) ?: return null
        if (action.status != ActionStatus.CONFIRMED || action.request.skill != OrionDeliverReminderDescriptor.value.name) return null
        return db.openHelper.readableDatabase.query(
            "SELECT reminderId, fireGeneration FROM orion_reminders WHERE deliveryActionId = ? AND status = 'PENDING' LIMIT 2",
            arrayOf(actionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val result = UUID.fromString(cursor.getString(0)) to cursor.getLong(1)
            if (cursor.moveToNext()) return null
            result
        }
    }

    private fun submit(wire: OrionReminderDeliveryWire, key: UUID): ReminderDeliveryOutcome {
        val bytes = wire.encode()
        val result = try {
            ingress.submit(DurableIngressRequest(UUID.randomUUID(), OrionReminderDeliveryWire.SCOPE, key,
                OrionReminderDeliveryWire.SOURCE, OrionReminderDeliveryWire.TYPE, 1, Sensitivity.SENSITIVE, false, null,
                bytes, Duration.ofDays(1).toMillis(), Duration.ofDays(455).toMillis(), correlationId = wire.preview.reminderId))
        } finally { bytes.fill(0) }
        if (result !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (result == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) ReminderDeliveryOutcome.REJECTED else ReminderDeliveryOutcome.UNAVAILABLE
        try { ingress.drain(25) } finally { handoffs.flush() }
        val outcome = when (db.ingress().findByKey(OrionReminderDeliveryWire.SCOPE, key.toString())?.status) {
            DurableIngressStatus.CONSUMED -> if (wire.confirm) ReminderDeliveryOutcome.ENABLED else ReminderDeliveryOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> ReminderDeliveryOutcome.REJECTED
            else -> ReminderDeliveryOutcome.QUEUED
        }
        return outcome
    }

    private fun prepareLocal(preview: ReminderDeliveryPreview) {
        val row = db.reminders().find(preview.reminderId.toString()) ?: throw DeliveryRejected()
        if (row.status != ReminderStatus.PENDING || row.version != preview.reminderVersion || row.deliveryActionId != null ||
            row.fireGeneration == Long.MAX_VALUE || preview.generation != row.fireGeneration + 1 || row.scheduledAt != preview.scheduledAt.toEpochMilli() ||
            preview.policyVersion != OrionDeliverReminderDescriptor.POLICY_VERSION) throw DeliveryRejected()
        val now = clock.wallNow()
        if (!preview.scheduledAt.isAfter(now) || preview.scheduledAt.isAfter(now.plus(OrionReminderDeliveryPolicy.MAX_FUTURE))) throw DeliveryRejected()
        val request = OrionReminderDeliveryPolicy.normalized(preview)
        if (!OrionReminderDeliveryRetention(db, actions, clock).prepareProposal(preview.reminderId, setOf(preview.actionId)))
            throw DeliveryRejected()
        val retention = Duration.between(now, preview.scheduledAt).plusDays(90)
        val policy = IdempotencyKeyPolicy("MAIN_UI", "reminder.deliver", retention, 1, OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
        val registration = actions.register(request, policy, preview.actionId.toString(), preview.reminderId, Sensitivity.SENSITIVE, true)
        if (registration.outcome !in setOf(ActionStoreOutcome.REGISTERED, ActionStoreOutcome.DEDUP)) {
            if (registration.outcome in setOf(ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED)) throw DeliveryUnavailable()
            throw DeliveryRejected()
        }
        var stored = read(preview.actionId) ?: throw DeliveryUnavailable()
        if (stored.request != request) throw DeliveryRejected()
        if (stored.status == ActionStatus.PENDING) {
            if (actions.requireConfirmation(preview.actionId, stored.version).outcome != ActionStoreOutcome.APPLIED) throw DeliveryRejected()
            stored = read(preview.actionId) ?: throw DeliveryUnavailable()
        }
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION) throw DeliveryRejected()
    }

    private fun confirmLocal(wire: OrionReminderDeliveryWire) {
        val preview = wire.preview
        val original = wire.receipt ?: throw DeliveryRejected()
        val stored = read(preview.actionId) ?: throw DeliveryUnavailable()
        val row = db.reminders().find(preview.reminderId.toString()) ?: throw DeliveryRejected()
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || stored.version != preview.actionVersion ||
            stored.request != OrionReminderDeliveryPolicy.normalized(preview) ||
            preview.policyVersion != OrionDeliverReminderDescriptor.POLICY_VERSION ||
            ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint != preview.confirmationFingerprint ||
            original.confirmationFingerprint != preview.confirmationFingerprint ||
            original.confirmationExpiresAt != OrionReminderDeliveryPolicy.expiresAt(preview.scheduledAt) ||
            !preview.scheduledAt.isAfter(original.confirmedAt) || row.status != ReminderStatus.PENDING ||
            row.version != preview.reminderVersion || row.deliveryActionId != null || row.fireGeneration == Long.MAX_VALUE ||
            row.fireGeneration + 1 != preview.generation || row.scheduledAt != preview.scheduledAt.toEpochMilli()) throw DeliveryRejected()
        if (actions.confirm(preview.actionId, stored.version, original, preview.policyVersion).outcome != ActionStoreOutcome.APPLIED) throw DeliveryRejected()
        if (db.reminderSchedules().bindDelivery(row.reminderId, row.version, preview.generation, preview.actionId.toString(),
                clock.wallNow().toEpochMilli()) != ActionMutation.APPLIED) throw DeliveryRejected()
        if (!handoffs.remember(checkNotNull(processingId), preview.reminderId, preview.actionId)) throw DeliveryUnavailable()
    }
    private fun read(id: UUID) = (actions.read(id) as? ActionReadResult.Ready)?.action
    private fun available() = poster.permissionGranted() && poster.availability() == NotificationAvailability.AVAILABLE &&
        clock.monotonicNow().bootSessionId != null
    private fun blocked(outcome: ReminderDeliveryOutcome) = ReminderDeliveryPreparation.Blocked(outcome)
    private class DeliveryUnavailable : RuntimeException("Reminder delivery unavailable.")
    private class DeliveryRejected : RuntimeException("Reminder delivery rejected.")
}

package network.orion.app.reminderdelivery

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.OrionReminderRecurrenceRule
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*

/** Finite exact batch: every child obtains its own genuine original-gesture confirmation. */
internal class OrionReminderSeriesPipeline(private val db: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock, private val barrier: StartupBarrier, private val owner: OrionReminderDeliveryAdmissions,
    private val actions: OrionActionStore, readiness: () -> CoreReadiness, storageAvailable: () -> Boolean,
    private val onAuthorized: (UUID) -> Unit = {}) {
    private val handoffs = OrionReminderAuthorizationHandoffs(db, barrier, onAuthorized)
    private var processingId: UUID? = null
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        if (!owner.replayAllowed()) throw Unavailable()
        val row = db.ingress().find(payload.ingressId.toString()) ?: return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        if (row.status != DurableIngressStatus.PROCESSING || row.source != OrionReminderSeriesWire.SOURCE ||
            payload.scope != OrionReminderSeriesWire.SCOPE || payload.commandType != OrionReminderSeriesWire.TYPE ||
            payload.schemaVersion != 1 || payload.sensitivity != Sensitivity.SENSITIVE || payload.referenceId != null)
            return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        val bytes = payload.copyContent() ?: return@LocalIngressConsumer LocalIngressDisposition.REJECTED
        val wire = try { OrionReminderSeriesWire.decode(bytes) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.time.DateTimeException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            finally { bytes.fill(0) }
        if (wire.confirm) confirmLocal(wire) else prepareLocal(wire.preview)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionReminderSeriesWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            return try { processingId = null; store.consumeNext() }
            catch (_: Unavailable) { false }
            catch (_: Rejected) {
                val row = processingId?.let { db.ingress().find(it.toString()) } ?: return false
                check(row.status == DurableIngressStatus.QUEUED)
                check(db.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED,
                    clock.wallNow().toEpochMilli()) == ActionMutation.APPLIED)
                true
            }
        }
    }
    val ingress = DurableIngressCoordinator(queue, barrier, readiness, storageAvailable)
    fun flushAuthorized() = handoffs.flush()

    fun gateway(channel: OrionReminderDeliveryAdmissions.UiChannel): ReminderDeliveryGateway = object : ReminderDeliveryGateway {
        @Volatile private var displayed: ReminderSeriesPreview? = null
        override suspend fun prepare(reminderId: UUID, expectedVersion: Long) = ReminderDeliveryPreparation.Blocked(ReminderDeliveryOutcome.UNAVAILABLE)
        override suspend fun confirm(preview: ReminderDeliveryPreview) = ReminderDeliveryOutcome.UNAVAILABLE
        override suspend fun prepareSeries(reminderId: UUID, expectedVersion: Long, rule: OrionReminderRecurrenceRule): ReminderSeriesPreparation {
            val admitted = channel.capture() ?: return blocked(ReminderDeliveryOutcome.UNAVAILABLE)
            return withContext(Dispatchers.IO) {
                if (!admitted.valid()) return@withContext blocked(ReminderDeliveryOutcome.UNAVAILABLE)
                val row = db.reminders().find(reminderId.toString()) ?: return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                if (row.status != ReminderStatus.PENDING || row.version != expectedVersion || row.deliveryActionId != null)
                    return@withContext blocked(ReminderDeliveryOutcome.REJECTED)
                val plan = try { OrionReminderSeriesPlan.prepare(reminderId, row.version, row.fireGeneration,
                    Instant.ofEpochMilli(row.scheduledAt), rule, clock.wallNow(), UUID.randomUUID()) }
                    catch (_: IllegalArgumentException) { return@withContext blocked(ReminderDeliveryOutcome.REJECTED) }
                    catch (_: java.time.DateTimeException) { return@withContext blocked(ReminderDeliveryOutcome.REJECTED) }
                val outcome = submit(OrionReminderSeriesWire(false, plan), plan.seriesId)
                if (outcome in setOf(ReminderDeliveryOutcome.REJECTED, ReminderDeliveryOutcome.UNAVAILABLE)) return@withContext blocked(outcome)
                val prepared = readPrepared(plan) ?: return@withContext blocked(ReminderDeliveryOutcome.QUEUED)
                displayed = prepared
                ReminderSeriesPreparation.Ready(prepared)
            }
        }
        override suspend fun confirmSeries(preview: ReminderSeriesPreview): ReminderDeliveryOutcome {
            val admitted = channel.capture() ?: return ReminderDeliveryOutcome.UNAVAILABLE
            if (displayed != preview || !admitted.valid() || OrionReminderSeriesPlan.fingerprint(preview) != preview.confirmationFingerprint)
                return ReminderDeliveryOutcome.REJECTED
            // All these already-prepared exact payloads were disclosed before THIS original gesture.
            val gestureAt = clock.wallNow()
            val gestureBinding = ActionConfirmationBinding(network.orion.core.time.WallClock { gestureAt })
            val receipts = try {
                preview.occurrences.map { child -> gestureBinding.recordConfirmation(
                    OrionReminderDeliveryPolicy.normalized(child.delivery), OrionReminderDeliveryPolicy.expiresAt(child.delivery.scheduledAt)) }
            } catch (_: IllegalArgumentException) { return ReminderDeliveryOutcome.REJECTED }
            return withContext(Dispatchers.IO) {
                if (!admitted.valid()) return@withContext ReminderDeliveryOutcome.UNAVAILABLE
                val key = UUID.nameUUIDFromBytes("${preview.seriesId}:confirm:${receipts.first().confirmedAt}".toByteArray(Charsets.UTF_8))
                val outcome = submit(OrionReminderSeriesWire(true, preview, receipts), key)
                if (outcome == ReminderDeliveryOutcome.ENABLED) displayed = null
                outcome
            }
        }
    }

    private fun prepareLocal(plan: ReminderSeriesPreview) {
        val row = rowFor(plan)
        val expected = OrionReminderSeriesPlan.prepare(UUID.fromString(row.reminderId), row.version, row.fireGeneration,
            Instant.ofEpochMilli(row.scheduledAt), plan.rule, clock.wallNow(), plan.seriesId)
        if (expected != plan) throw Rejected()
        if (!OrionReminderDeliveryRetention(db, actions, clock).prepareProposal(UUID.fromString(row.reminderId),
                plan.occurrences.map { it.delivery.actionId }.toSet())) throw Rejected()
        val retention = Duration.between(clock.wallNow(), plan.occurrences.last().delivery.scheduledAt).plusDays(90)
        val policy = IdempotencyKeyPolicy("MAIN_UI", "reminder.deliver", retention, 1, OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
        for (occurrence in plan.occurrences) {
            val child = occurrence.delivery
            val registered = actions.register(OrionReminderDeliveryPolicy.normalized(child), policy, child.actionId.toString(),
                child.reminderId, Sensitivity.SENSITIVE, true)
            requireWrite(registered.outcome, setOf(ActionStoreOutcome.REGISTERED, ActionStoreOutcome.DEDUP))
            var stored = read(child.actionId)
            if (stored.request != OrionReminderDeliveryPolicy.normalized(child)) throw Rejected()
            if (stored.status == ActionStatus.PENDING) {
                requireWrite(actions.requireConfirmation(child.actionId, stored.version).outcome, setOf(ActionStoreOutcome.APPLIED))
                stored = read(child.actionId)
            }
            if (stored.status != ActionStatus.NEEDS_CONFIRMATION) throw Rejected()
        }
    }

    private fun confirmLocal(wire: OrionReminderSeriesWire) {
        val preview = wire.preview
        val row = rowFor(preview)
        val original = OrionReminderSeriesPlan.prepare(UUID.fromString(row.reminderId), row.version, row.fireGeneration,
            Instant.ofEpochMilli(row.scheduledAt), preview.rule, wire.receipts.first().confirmedAt, preview.seriesId)
        val prepared = readPrepared(original) ?: throw Rejected()
        if (prepared != preview || OrionReminderSeriesPlan.fingerprint(preview) != preview.confirmationFingerprint ||
            wire.receipts.size != preview.occurrences.size) throw Rejected()
        preview.occurrences.forEachIndexed { index, occurrence ->
            val child = occurrence.delivery
            val receipt = wire.receipts[index]
            if (receipt.actionId != child.actionId || receipt.confirmationFingerprint != child.confirmationFingerprint ||
                receipt.confirmationExpiresAt != OrionReminderDeliveryPolicy.expiresAt(child.scheduledAt) ||
                !receipt.confirmedAt.isBefore(preview.occurrences.first().delivery.scheduledAt)) throw Rejected()
            requireWrite(actions.confirm(child.actionId, child.actionVersion, receipt, child.policyVersion).outcome,
                setOf(ActionStoreOutcome.APPLIED))
        }
        val first = preview.occurrences.first().delivery
        if (db.reminderSchedules().bindDelivery(row.reminderId, row.version, first.generation, first.actionId.toString(),
                clock.wallNow().toEpochMilli()) != ActionMutation.APPLIED) throw Rejected()
        val now = clock.wallNow().toEpochMilli()
        val series = OrionReminderSeriesEntity(row.reminderId, preview.seriesId.toString(),
            network.orion.core.model.OrionReminderRecurrenceCodec.encode(preview.rule), preview.occurrences.size,
            createdAt = now, updatedAt = now)
        val occurrences = preview.occurrences.mapIndexed { index, occurrence ->
            val child = occurrence.delivery
            OrionReminderOccurrenceEntity(row.reminderId, index, preview.seriesId.toString(), child.actionId.toString(),
                child.generation, child.scheduledAt.toEpochMilli(), occurrence.requestedLocalDateTime.toString(),
                occurrence.resolvedLocalDateTime.toString(), occurrence.offsetSeconds, occurrence.decision)
        }
        if (db.reminderSeries().attachSeries(series, occurrences) != ActionMutation.APPLIED) throw Rejected()
        if (!handoffs.remember(checkNotNull(processingId), first.reminderId, first.actionId)) throw Unavailable()
    }

    private fun rowFor(preview: ReminderSeriesPreview): ReminderEntity {
        if (preview.occurrences.size !in 2..31 || preview.occurrences.size != preview.rule.count) throw Rejected()
        val first = preview.occurrences.first().delivery
        val row = db.reminders().find(first.reminderId.toString()) ?: throw Rejected()
        if (row.status != ReminderStatus.PENDING || row.version != first.reminderVersion || row.deliveryActionId != null ||
            row.fireGeneration == Long.MAX_VALUE || first.generation != row.fireGeneration + 1 ||
            row.scheduledAt != first.scheduledAt.toEpochMilli()) throw Rejected()
        return row
    }

    private fun readPrepared(plan: ReminderSeriesPreview): ReminderSeriesPreview? {
        val prepared = plan.occurrences.map { occurrence ->
            val action = (actions.read(occurrence.delivery.actionId) as? ActionReadResult.Ready)?.action ?: return null
            if (action.status != ActionStatus.NEEDS_CONFIRMATION || action.request != OrionReminderDeliveryPolicy.normalized(occurrence.delivery)) return null
            occurrence.copy(delivery = occurrence.delivery.copy(actionVersion = action.version,
                confirmationFingerprint = ActionConfirmationBinding(clock).prepare(action.request).confirmationFingerprint))
        }
        val result = plan.copy(occurrences = prepared, confirmationFingerprint = "")
        return result.copy(confirmationFingerprint = OrionReminderSeriesPlan.fingerprint(result))
    }
    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> if (result.reason in setOf(ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED)) throw Unavailable() else throw Rejected()
    }
    private fun requireWrite(outcome: ActionStoreOutcome, allowed: Set<ActionStoreOutcome>) {
        if (outcome in allowed) return
        if (outcome in setOf(ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED)) throw Unavailable()
        throw Rejected()
    }
    private fun submit(wire: OrionReminderSeriesWire, key: UUID): ReminderDeliveryOutcome {
        val bytes = wire.encode()
        val result = try {
            ingress.submit(DurableIngressRequest(UUID.randomUUID(), OrionReminderSeriesWire.SCOPE, key,
                OrionReminderSeriesWire.SOURCE, OrionReminderSeriesWire.TYPE, 1, Sensitivity.SENSITIVE, false, null,
                bytes, Duration.ofDays(1).toMillis(), Duration.ofDays(455).toMillis(), correlationId = wire.preview.seriesId))
        } finally { bytes.fill(0) }
        if (result !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (result == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) ReminderDeliveryOutcome.REJECTED else ReminderDeliveryOutcome.UNAVAILABLE
        try { ingress.drain(25) } finally { handoffs.flush() }
        val outcome = when (db.ingress().findByKey(OrionReminderSeriesWire.SCOPE, key.toString())?.status) {
            DurableIngressStatus.CONSUMED -> if (wire.confirm) ReminderDeliveryOutcome.ENABLED else ReminderDeliveryOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> ReminderDeliveryOutcome.REJECTED
            else -> ReminderDeliveryOutcome.QUEUED
        }
        return outcome
    }
    private fun blocked(outcome: ReminderDeliveryOutcome) = ReminderSeriesPreparation.Blocked(outcome)
    private class Unavailable : RuntimeException("Reminder series unavailable.")
    private class Rejected : RuntimeException("Reminder series rejected.")
}

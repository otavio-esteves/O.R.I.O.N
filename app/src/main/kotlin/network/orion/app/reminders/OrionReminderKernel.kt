package network.orion.app.reminders

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.reminders.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.reminders.*
import network.orion.core.queries.reminders.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.data.reminders.*
import org.json.JSONObject

internal class OrionReminderPending(val display: ReminderConfirmation, val create: OrionReminderActionPreview? = null,
    val change: OrionReminderStatusActionPreview? = null, private val unreadable: OrionReminderIngressWire? = null) {
    override fun toString() = "OrionReminderPending(<redacted>)"
    fun wire(receipt: ActionConfirmationSnapshot? = null, dismiss: Boolean = false): OrionReminderIngressWire {
        create?.let { p -> return OrionReminderIngressWire(if (dismiss) "DISMISS" else "CREATE", p.reminder.reminderId,
            p.reminder.idempotencyKey, p.reminder.correlationId, actionId = p.actionId, actionVersion = p.version,
            fingerprint = p.confirmationFingerprint, policyVersion = p.policyVersion, receipt = receipt) }
        if (change == null) {
            check(dismiss && receipt == null)
            return checkNotNull(unreadable)
        }
        val p = change
        return OrionReminderIngressWire(if (dismiss) "DISMISS" else p.change.operation.name, p.change.reminderId,
            p.change.idempotencyKey, p.change.correlationId, expectedVersion = p.change.expectedVersion,
            actionId = p.actionId, actionVersion = p.version, fingerprint = p.confirmationFingerprint,
            policyVersion = p.policyVersion, receipt = receipt,
            scheduledAtMillis = if (dismiss) 0 else p.change.scheduledAt?.toEpochMilli() ?: 0,
            zoneId = if (dismiss) "UTC" else p.change.zoneId?.id ?: "UTC")
    }
}

/** MAIN-only local reminder slice. All writes run through the same durable ingress transaction and barrier. */
internal class OrionReminderKernel(private val db: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock, private val barrier: StartupBarrier, private val owner: OrionReminderAdmissionOwner,
    readiness: () -> CoreReadiness, storageAvailable: () -> Boolean) {
    val actions = OrionActionStore(db, encryption, clock)
    private val create = OrionCreateReminderActionStore(db, actions, clock, barrier)
    private val statusOwned = OrionOwnedReminderStatusStore(db, encryption, clock)
    private val status = OrionReminderStatusActionStore(db, actions, statusOwned::matchesExisting, clock, barrier)
    private val owned = OrionOwnedReminderStore(db, encryption, clock)
    private val createRunner = OrionCreateReminderRunner(db, actions, owned, clock, barrier)
    private val statusRunner = OrionReminderStatusRunner(db, actions, statusOwned, clock, barrier)
    val query = OrionReminderQuery(db, encryption, clock)
    private var processingId: UUID? = null // Only touched under barrier admission; no coroutine suspension.
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        val origin = try { OrionVerifiedReminderIngress.from(payload, db, actions) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: org.json.JSONException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
        consume(origin)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionReminderIngressWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            check(Thread.holdsLock(barrier))
            processingId = null
            return try { store.consumeNext() } catch (failure: OrionReminderTransactionBlocked) {
                // Catch OUTSIDE Room. No partially staged action/effect can survive this branch.
                if (transient(failure)) false else {
                    val row = processingId?.let { db.ingress().find(it.toString()) } ?: throw failure
                    check(row.status == DurableIngressStatus.QUEUED)
                    check(db.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED,
                        clock.wallNow().toEpochMilli()) == ActionMutation.APPLIED)
                    true
                }
            }
        }
    }
    val ingress = DurableIngressCoordinator(queue, barrier, readiness, storageAvailable)

    fun submit(wire: OrionReminderIngressWire, producerKey: UUID): ReminderOperationOutcome {
        val clear = wire.encode()
        val id = UUID.randomUUID()
        val outcome = try { ingress.submit(DurableIngressRequest(id, OrionReminderIngressWire.SCOPE, producerKey,
            OrionReminderIngressWire.SOURCE, OrionReminderIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, clear,
            Duration.ofDays(1).toMillis(), Duration.ofDays(90).toMillis(), correlationId = wire.trace)) }
        finally { clear.fill(0) }
        if (outcome !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (outcome == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) ReminderOperationOutcome.REJECTED else ReminderOperationOutcome.UNAVAILABLE
        ingress.drain(25)
        val row = db.ingress().findByKey(OrionReminderIngressWire.SCOPE, producerKey.toString()) ?: return ReminderOperationOutcome.UNAVAILABLE
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> if (wire.actionId != null) ReminderOperationOutcome.APPLIED else ReminderOperationOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> ReminderOperationOutcome.REJECTED
            else -> ReminderOperationOutcome.QUEUED
        }
    }

    private fun consume(origin: OrionVerifiedReminderIngress) {
        val wire = origin.wire
        if (wire.operation == "DISMISS") {
            if (!owner.presentForQueuedRequest(origin)) unavailable()
            val id = wire.actionId ?: invalid()
            val row = db.actions().find(id.toString()) ?: invalid()
            if (row.skill !in setOf("reminder.create", "reminder.snooze", "reminder.cancel") || row.version != wire.actionVersion ||
                row.status != ActionStatus.NEEDS_CONFIRMATION || row.confirmationFingerprint != wire.fingerprint) invalid()
            if (actions.cancel(id, wire.actionVersion).outcome != ActionStoreOutcome.APPLIED) invalid()
            return
        }
        if (wire.operation == "CREATE") {
            val admission = owner.replayCreate(origin) ?: unavailable()
            if (wire.actionId == null) {
                val prepared = create.prepare(admission)
                if (prepared is OrionReminderActionResult.AlreadySucceeded && !owned.matchesExisting(admission.reminder)) invalid()
            } else {
                val preview = OrionReminderActionPreview(admission.reminder, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = create.confirmWithReceipt(admission, preview, receipt) as? OrionReminderActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { createRunner.execute(admission, confirmed.preview) }
            }
        } else {
            val admission = owner.replayStatus(origin) ?: unavailable()
            if (wire.actionId == null) status.prepare(admission) else {
                val preview = OrionReminderStatusActionPreview(admission.change, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = status.confirmWithReceipt(admission, preview, receipt) as? OrionReminderStatusActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { statusRunner.execute(admission, confirmed.preview) }
            }
        }
    }

    /** The normative ActionEngine port and local entry use the same non-suspending body. */
    private fun executeBound(id: UUID, body: () -> OrionReminderExecutionResult) {
        val engine = object : ActionEngine {
            fun executeLocal(actionId: UUID): ActionExecutionResult {
                if (actionId != id) return ActionExecutionResult.UNAVAILABLE
                return when (body()) {
                    is OrionReminderExecutionResult.Completed -> ActionExecutionResult.SUCCEEDED
                    is OrionReminderExecutionResult.Blocked -> ActionExecutionResult.BLOCKED
                }
            }
            override suspend fun execute(actionId: UUID) = executeLocal(actionId)
        }
        if (engine.executeLocal(id) != ActionExecutionResult.SUCCEEDED) invalid()
    }

    fun diagnostics(): network.orion.core.health.TaskQueueDiagnostics {
        fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
        return network.orion.core.health.TaskQueueDiagnostics(
            count("SELECT count(*) FROM orion_ingress_envelopes WHERE status = 'QUEUED'"),
            count("SELECT count(*) FROM orion_action_requests WHERE status = 'NEEDS_CONFIRMATION'"),
            count("SELECT count(*) FROM orion_action_requests WHERE status = 'RUNNING'"),
            count("SELECT count(*) FROM orion_action_requests WHERE status = 'UNKNOWN'"),
            count("SELECT count(*) FROM orion_action_requests WHERE status IN ('MANUAL_REVIEW', 'CONFIRMED')"),
            count("SELECT count(*) FROM orion_domain_event_outbox WHERE status != 'DELIVERED'"))
    }

    fun recoverActions() {
        val denied = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = false
            override fun descriptorFor(skill: String) = null
            override fun currentPolicyVersion() = "orion_recovery"
            override fun policyAllows(request: NormalizedActionRequest) = false
            override fun capabilityAvailable(capability: network.orion.core.model.Capability) = false
            override fun permissionGranted(permission: String) = false
            override fun userPresent() = false
        }
        val recovery = OrionActionExecutionStore(db, actions, clock, denied)
        var cursor: UUID? = null
        do { cursor = recovery.recoverAbandoned(100, cursor).nextAfterActionId } while (cursor != null)
        // MAIN Core owns operational retention and its counters. This slice only recovers ownership;
        // it never prunes history, publishes facts, invokes Skills or installs schedulers.
    }

    fun pending(): List<OrionReminderPending> = db.actions().reminderConfirmations(50).mapNotNull { row ->
        val id = UUID.fromString(row.actionId)
        fun unreadable(): OrionReminderPending {
            val operation = when (row.skill) { "reminder.create" -> ReminderOperation.CREATE; "reminder.snooze" -> ReminderOperation.SNOOZE; else -> ReminderOperation.CANCEL }
            return OrionReminderPending(ReminderConfirmation(id, row.version, operation, null), unreadable =
                OrionReminderIngressWire("DISMISS", id, UUID.fromString(row.idempotencyKey), UUID.fromString(row.correlationId),
                    actionId = id, actionVersion = row.version, fingerprint = row.confirmationFingerprint, policyVersion = row.policyVersion))
        }
        val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return@mapNotNull unreadable()
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || stored.version != row.version) return@mapNotNull null
        try {
            val args = JSONObject(stored.request.canonicalNormalizedArguments)
            val reminderId = UUID.fromString(args.getString("reminderId"))
            val key = UUID.fromString(row.idempotencyKey); val trace = UUID.fromString(row.correlationId)
            val fp = ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint
            if (row.skill == "reminder.create") {
                val reminder = (OrionCreateReminderPreparer.prepare(CreateReminder(reminderId, args.getString("title"), java.time.Instant.ofEpochMilli(args.getLong("scheduledAtEpochMillis")), java.time.ZoneId.of(args.getString("zoneId")), key, trace))
                    as? CreateReminderPreparation.Ready)?.reminder ?: return@mapNotNull unreadable()
                if (stored.request != OrionCreateReminderActionStore.normalized(id, reminder, stored.request.policyVersion)) return@mapNotNull unreadable()
                OrionReminderPending(ReminderConfirmation(id, stored.version, ReminderOperation.CREATE, "${reminder.title} — ${formatReminderTime(reminder.scheduledAt, reminder.zoneId)}"),
                    create = OrionReminderActionPreview(reminder, id, stored.version, fp, stored.request.policyVersion, stored.status))
            } else {
                val op = if (row.skill == "reminder.snooze") ReminderStatusOperation.SNOOZE else ReminderStatusOperation.CANCEL
                val command = if (op == ReminderStatusOperation.SNOOZE) SnoozeReminder(reminderId, args.getLong("expectedVersion"), java.time.Instant.ofEpochMilli(args.getLong("scheduledAtEpochMillis")), java.time.ZoneId.of(args.getString("zoneId")), key, trace)
                    else CancelReminder(reminderId, args.getLong("expectedVersion"), key, trace)
                val change = (OrionReminderStatusPreparer.prepare(command) as? ReminderStatusPreparation.Ready)?.change ?: return@mapNotNull unreadable()
                if (stored.request != OrionReminderStatusActionStore.normalized(id, change, stored.request.policyVersion)) return@mapNotNull unreadable()
                val current = query.find(reminderId)
                val title = current?.title?.takeIf { current.state == ReminderState.PENDING && current.version == change.expectedVersion }?.let {
                    if (op == ReminderStatusOperation.SNOOZE) "$it — ${formatReminderTime(checkNotNull(change.scheduledAt), checkNotNull(change.zoneId))}" else it }
                OrionReminderPending(ReminderConfirmation(id, stored.version, ReminderOperation.valueOf(op.name), title),
                    change = OrionReminderStatusActionPreview(change, id, stored.version, fp, stored.request.policyVersion, stored.status))
            }
        } catch (_: org.json.JSONException) { unreadable() } catch (_: IllegalArgumentException) { unreadable() } catch (_: java.time.DateTimeException) { unreadable() }
    }

    private fun transient(failure: OrionReminderTransactionBlocked) = when (failure) {
        is OrionReminderTransactionBlocked.Action -> failure.reason in setOf(OrionReminderActionFailure.STARTUP_GATED,
            OrionReminderActionFailure.POLICY_DENIED, OrionReminderActionFailure.POLICY_UNAVAILABLE, OrionReminderActionFailure.KEY_UNAVAILABLE)
        is OrionReminderTransactionBlocked.Execution -> failure.reason in setOf(OrionReminderExecutionFailure.STARTUP_GATED,
            OrionReminderExecutionFailure.POLICY_DENIED, OrionReminderExecutionFailure.STORAGE_UNAVAILABLE)
    }
    private fun unavailable(): Nothing = throw OrionReminderTransactionBlocked.Action(OrionReminderActionFailure.POLICY_UNAVAILABLE)
    private fun invalid(): Nothing = throw OrionReminderTransactionBlocked.Action(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
}

internal fun formatReminderTime(at: java.time.Instant, zone: java.time.ZoneId): String = try {
    java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss.SSS XXX '['VV']'").withZone(zone).format(at)
} catch (_: java.time.DateTimeException) { "$at (${zone.id})" }

package network.orion.app.timers

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.timers.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.timers.*
import network.orion.core.queries.timers.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.data.timers.*
import org.json.JSONObject

internal class OrionTimerPending(val display: TimerConfirmation, val create: OrionTimerActionPreview? = null,
    val change: OrionTimerStatusActionPreview? = null, private val unreadable: OrionTimerIngressWire? = null) {
    override fun toString() = "OrionTimerPending(<redacted>)"
    fun wire(receipt: ActionConfirmationSnapshot? = null, dismiss: Boolean = false): OrionTimerIngressWire {
        create?.let { p -> return OrionTimerIngressWire(if (dismiss) "DISMISS" else "CREATE", p.timer.timerId,
            p.timer.idempotencyKey, p.timer.correlationId, actionId = p.actionId, actionVersion = p.version,
            fingerprint = p.confirmationFingerprint, policyVersion = p.policyVersion, receipt = receipt) }
        if (change == null) {
            check(dismiss && receipt == null)
            return checkNotNull(unreadable)
        }
        val p = change
        return OrionTimerIngressWire(if (dismiss) "DISMISS" else p.change.operation.name, p.change.timerId,
            p.change.idempotencyKey, p.change.correlationId, expectedVersion = p.change.expectedVersion,
            actionId = p.actionId, actionVersion = p.version, fingerprint = p.confirmationFingerprint,
            policyVersion = p.policyVersion, receipt = receipt)
    }
}

/** MAIN-only local timer slice. All writes run through the same durable ingress transaction and barrier. */
internal class OrionTimerKernel(private val db: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock, private val barrier: StartupBarrier, private val owner: OrionTimerAdmissionOwner,
    readiness: () -> CoreReadiness, storageAvailable: () -> Boolean) {
    val actions = OrionActionStore(db, encryption, clock)
    private val create = OrionCreateTimerActionStore(db, actions, clock, barrier)
    private val status = OrionTimerStatusActionStore(db, actions, clock, barrier)
    private val owned = OrionOwnedTimerStore(db, encryption, clock)
    private val createRunner = OrionCreateTimerRunner(db, actions, owned, clock, barrier)
    private val statusRunner = OrionTimerStatusRunner(db, actions, OrionOwnedTimerStatusStore(db, encryption, clock), clock, barrier)
    val query = OrionTimerQuery(db, encryption, clock)
    private var processingId: UUID? = null // Only touched under barrier admission; no coroutine suspension.
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        val origin = try { OrionVerifiedTimerIngress.from(payload, db, actions) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: org.json.JSONException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
        consume(origin)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionTimerIngressWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            check(Thread.holdsLock(barrier))
            processingId = null
            return try { store.consumeNext() } catch (failure: OrionTimerTransactionBlocked) {
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

    fun submit(wire: OrionTimerIngressWire, producerKey: UUID): TimerOperationOutcome {
        val clear = wire.encode()
        val id = UUID.randomUUID()
        val outcome = try { ingress.submit(DurableIngressRequest(id, OrionTimerIngressWire.SCOPE, producerKey,
            OrionTimerIngressWire.SOURCE, OrionTimerIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, clear,
            Duration.ofDays(1).toMillis(), Duration.ofDays(90).toMillis(), correlationId = wire.trace)) }
        finally { clear.fill(0) }
        if (outcome !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (outcome == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) TimerOperationOutcome.REJECTED else TimerOperationOutcome.UNAVAILABLE
        ingress.drain(25)
        val row = db.ingress().findByKey(OrionTimerIngressWire.SCOPE, producerKey.toString()) ?: return TimerOperationOutcome.UNAVAILABLE
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> if (wire.actionId != null) TimerOperationOutcome.APPLIED else TimerOperationOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> TimerOperationOutcome.REJECTED
            else -> TimerOperationOutcome.QUEUED
        }
    }

    private fun consume(origin: OrionVerifiedTimerIngress) {
        val wire = origin.wire
        if (wire.operation == "DISMISS") {
            if (!owner.presentForQueuedRequest(origin)) unavailable()
            val id = wire.actionId ?: invalid()
            val row = db.actions().find(id.toString()) ?: invalid()
            if (row.skill !in setOf("timer.create", "timer.cancel") || row.version != wire.actionVersion ||
                row.status != ActionStatus.NEEDS_CONFIRMATION || row.confirmationFingerprint != wire.fingerprint) invalid()
            if (actions.cancel(id, wire.actionVersion).outcome != ActionStoreOutcome.APPLIED) invalid()
            return
        }
        if (wire.operation == "CREATE") {
            val admission = owner.replayCreate(origin) ?: unavailable()
            if (wire.actionId == null) {
                val prepared = create.prepare(admission)
                if (prepared is OrionTimerActionResult.AlreadySucceeded && !owned.matchesExisting(admission.timer)) invalid()
            } else {
                val preview = OrionTimerActionPreview(admission.timer, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = create.confirmWithReceipt(admission, preview, receipt) as? OrionTimerActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { createRunner.execute(admission, confirmed.preview) }
            }
        } else {
            val admission = owner.replayStatus(origin) ?: unavailable()
            if (wire.actionId == null) status.prepare(admission) else {
                val preview = OrionTimerStatusActionPreview(admission.change, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = status.confirmWithReceipt(admission, preview, receipt) as? OrionTimerStatusActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { statusRunner.execute(admission, confirmed.preview) }
            }
        }
    }

    /** The normative ActionEngine port and local entry use the same non-suspending body. */
    private fun executeBound(id: UUID, body: () -> OrionTimerExecutionResult) {
        val engine = object : ActionEngine {
            fun executeLocal(actionId: UUID): ActionExecutionResult {
                if (actionId != id) return ActionExecutionResult.UNAVAILABLE
                return when (body()) {
                    is OrionTimerExecutionResult.Completed -> ActionExecutionResult.SUCCEEDED
                    is OrionTimerExecutionResult.Blocked -> ActionExecutionResult.BLOCKED
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
        val now = clock.wallNow().toEpochMilli()
        val retention = now - Duration.ofDays(90).toMillis()
        db.retention().prune(now, retention, retention, retention, retention, retention, 2000, 100)
        // Domain events are facts, not action commands. With no registered durable
        // consumers yet, retain pending/backoff/manual rows unchanged for F4.
        // No reminders/workers are installed by this timer-only slice.
    }

    fun pending(): List<OrionTimerPending> = db.actions().timerConfirmations(50).mapNotNull { row ->
        val id = UUID.fromString(row.actionId)
        fun unreadable(): OrionTimerPending {
            val operation = when (row.skill) { "timer.create" -> TimerOperation.CREATE; else -> TimerOperation.CANCEL }
            return OrionTimerPending(TimerConfirmation(id, row.version, operation, null), unreadable =
                OrionTimerIngressWire("DISMISS", id, UUID.fromString(row.idempotencyKey), UUID.fromString(row.correlationId),
                    actionId = id, actionVersion = row.version, fingerprint = row.confirmationFingerprint, policyVersion = row.policyVersion))
        }
        val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return@mapNotNull unreadable()
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || stored.version != row.version) return@mapNotNull null
        try {
            val args = JSONObject(stored.request.canonicalNormalizedArguments)
            val timerId = UUID.fromString(args.getString("timerId"))
            val key = UUID.fromString(row.idempotencyKey); val trace = UUID.fromString(row.correlationId)
            val fp = ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint
            if (row.skill == "timer.create") {
                val timer = (OrionCreateTimerPreparer.prepare(CreateTimer(timerId, args.getString("title"), args.getLong("durationMillis"), key, trace))
                    as? CreateTimerPreparation.Ready)?.timer ?: return@mapNotNull unreadable()
                if (stored.request != OrionCreateTimerActionStore.normalized(id, timer, stored.request.policyVersion)) return@mapNotNull unreadable()
                OrionTimerPending(TimerConfirmation(id, stored.version, TimerOperation.CREATE, timer.title, timer.durationMillis),
                    create = OrionTimerActionPreview(timer, id, stored.version, fp, stored.request.policyVersion, stored.status))
            } else {
                val op = TimerStatusOperation.CANCEL
                val command = CancelTimer(timerId, args.getLong("expectedVersion"), key, trace)
                val change = (OrionTimerStatusPreparer.prepare(command) as? TimerStatusPreparation.Ready)?.change ?: return@mapNotNull unreadable()
                if (stored.request != OrionTimerStatusActionStore.normalized(id, change, stored.request.policyVersion)) return@mapNotNull unreadable()
                val current = query.find(timerId)
                val title = current?.title?.takeIf { current.state != TimerState.CANCELLED && current.version == change.expectedVersion }
                OrionTimerPending(TimerConfirmation(id, stored.version, TimerOperation.valueOf(op.name), title, current?.durationMillis),
                    change = OrionTimerStatusActionPreview(change, id, stored.version, fp, stored.request.policyVersion, stored.status))
            }
        } catch (_: org.json.JSONException) { unreadable() } catch (_: IllegalArgumentException) { unreadable() }
    }

    private fun transient(failure: OrionTimerTransactionBlocked) = when (failure) {
        is OrionTimerTransactionBlocked.Action -> failure.reason in setOf(OrionTimerActionFailure.STARTUP_GATED,
            OrionTimerActionFailure.POLICY_DENIED, OrionTimerActionFailure.POLICY_UNAVAILABLE, OrionTimerActionFailure.KEY_UNAVAILABLE)
        is OrionTimerTransactionBlocked.Execution -> failure.reason in setOf(OrionTimerExecutionFailure.STARTUP_GATED,
            OrionTimerExecutionFailure.POLICY_DENIED, OrionTimerExecutionFailure.STORAGE_UNAVAILABLE)
    }
    private fun unavailable(): Nothing = throw OrionTimerTransactionBlocked.Action(OrionTimerActionFailure.POLICY_UNAVAILABLE)
    private fun invalid(): Nothing = throw OrionTimerTransactionBlocked.Action(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
}

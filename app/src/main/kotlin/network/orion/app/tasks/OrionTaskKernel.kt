package network.orion.app.tasks

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.tasks.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.tasks.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.data.tasks.*
import org.json.JSONObject

internal class OrionTaskPending(val display: TaskConfirmation, val create: OrionTaskActionPreview? = null,
    val change: OrionTaskStatusActionPreview? = null, private val unreadable: OrionTaskIngressWire? = null) {
    override fun toString() = "OrionTaskPending(<redacted>)"
    fun wire(receipt: ActionConfirmationSnapshot? = null, dismiss: Boolean = false): OrionTaskIngressWire {
        create?.let { p -> return OrionTaskIngressWire(if (dismiss) "DISMISS" else "CREATE", p.task.taskId,
            p.task.idempotencyKey, p.task.correlationId, actionId = p.actionId, actionVersion = p.version,
            fingerprint = p.confirmationFingerprint, policyVersion = p.policyVersion, receipt = receipt) }
        if (change == null) {
            check(dismiss && receipt == null)
            return checkNotNull(unreadable)
        }
        val p = change
        return OrionTaskIngressWire(if (dismiss) "DISMISS" else p.change.operation.name, p.change.taskId,
            p.change.idempotencyKey, p.change.correlationId, expectedVersion = p.change.expectedVersion,
            actionId = p.actionId, actionVersion = p.version, fingerprint = p.confirmationFingerprint,
            policyVersion = p.policyVersion, receipt = receipt)
    }
}

/** MAIN-only local task slice. All writes run through the same durable ingress transaction and barrier. */
internal class OrionTaskKernel(private val db: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock, private val barrier: StartupBarrier, private val owner: OrionTaskAdmissionOwner,
    readiness: () -> CoreReadiness, storageAvailable: () -> Boolean) {
    val actions = OrionActionStore(db, encryption, clock)
    private val create = OrionCreateTaskActionStore(db, actions, clock, barrier)
    private val status = OrionTaskStatusActionStore(db, actions, clock, barrier)
    private val owned = OrionOwnedTaskStore(db, encryption, clock)
    private val createRunner = OrionCreateTaskRunner(db, actions, owned, clock, barrier)
    private val statusRunner = OrionTaskStatusRunner(db, actions, OrionOwnedTaskStatusStore(db, encryption, clock), clock, barrier)
    val query = OrionTaskQuery(db, encryption)
    private var processingId: UUID? = null // Only touched under barrier admission; no coroutine suspension.
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        val origin = try { OrionVerifiedTaskIngress.from(payload, db, actions) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: org.json.JSONException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
        consume(origin)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionTaskIngressWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            check(Thread.holdsLock(barrier))
            processingId = null
            return try { store.consumeNext() } catch (failure: OrionTaskTransactionBlocked) {
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

    fun submit(wire: OrionTaskIngressWire, producerKey: UUID): TaskOperationOutcome {
        val clear = wire.encode()
        val id = UUID.randomUUID()
        val outcome = try { ingress.submit(DurableIngressRequest(id, OrionTaskIngressWire.SCOPE, producerKey,
            OrionTaskIngressWire.SOURCE, OrionTaskIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, clear,
            Duration.ofDays(1).toMillis(), Duration.ofDays(90).toMillis(), correlationId = wire.trace)) }
        finally { clear.fill(0) }
        if (outcome !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (outcome == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) TaskOperationOutcome.REJECTED else TaskOperationOutcome.UNAVAILABLE
        ingress.drain(25)
        val row = db.ingress().findByKey(OrionTaskIngressWire.SCOPE, producerKey.toString()) ?: return TaskOperationOutcome.UNAVAILABLE
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> if (wire.actionId != null) TaskOperationOutcome.APPLIED else TaskOperationOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> TaskOperationOutcome.REJECTED
            else -> TaskOperationOutcome.QUEUED
        }
    }

    private fun consume(origin: OrionVerifiedTaskIngress) {
        val wire = origin.wire
        if (wire.operation == "DISMISS") {
            if (!owner.presentForQueuedRequest(origin)) unavailable()
            val id = wire.actionId ?: invalid()
            val row = db.actions().find(id.toString()) ?: invalid()
            if (row.skill !in setOf("task.create", "task.complete", "task.cancel") || row.version != wire.actionVersion ||
                row.status != ActionStatus.NEEDS_CONFIRMATION || row.confirmationFingerprint != wire.fingerprint) invalid()
            if (actions.cancel(id, wire.actionVersion).outcome != ActionStoreOutcome.APPLIED) invalid()
            return
        }
        if (wire.operation == "CREATE") {
            val admission = owner.replayCreate(origin) ?: unavailable()
            if (wire.actionId == null) {
                val prepared = create.prepare(admission)
                if (prepared is OrionTaskActionResult.AlreadySucceeded && !owned.matchesExisting(admission.task)) invalid()
            } else {
                val preview = OrionTaskActionPreview(admission.task, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = create.confirmWithReceipt(admission, preview, receipt) as? OrionTaskActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { createRunner.execute(admission, confirmed.preview) }
            }
        } else {
            val admission = owner.replayStatus(origin) ?: unavailable()
            if (wire.actionId == null) status.prepare(admission) else {
                val preview = OrionTaskStatusActionPreview(admission.change, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = status.confirmWithReceipt(admission, preview, receipt) as? OrionTaskStatusActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { statusRunner.execute(admission, confirmed.preview) }
            }
        }
    }

    /** The normative ActionEngine port and local entry use the same non-suspending body. */
    private fun executeBound(id: UUID, body: () -> OrionTaskExecutionResult) {
        val engine = object : ActionEngine {
            fun executeLocal(actionId: UUID): ActionExecutionResult {
                if (actionId != id) return ActionExecutionResult.UNAVAILABLE
                return when (body()) {
                    is OrionTaskExecutionResult.Completed -> ActionExecutionResult.SUCCEEDED
                    is OrionTaskExecutionResult.Blocked -> ActionExecutionResult.BLOCKED
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

    fun pending(): List<OrionTaskPending> = db.actions().taskConfirmations(50).mapNotNull { row ->
        val id = UUID.fromString(row.actionId)
        fun unreadable(): OrionTaskPending {
            val operation = when (row.skill) { "task.create" -> TaskOperation.CREATE; "task.complete" -> TaskOperation.COMPLETE; else -> TaskOperation.CANCEL }
            return OrionTaskPending(TaskConfirmation(id, row.version, operation, null), unreadable =
                OrionTaskIngressWire("DISMISS", id, UUID.fromString(row.idempotencyKey), UUID.fromString(row.correlationId),
                    actionId = id, actionVersion = row.version, fingerprint = row.confirmationFingerprint, policyVersion = row.policyVersion))
        }
        val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return@mapNotNull unreadable()
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || stored.version != row.version) return@mapNotNull null
        try {
            val args = JSONObject(stored.request.canonicalNormalizedArguments)
            val taskId = UUID.fromString(args.getString("taskId"))
            val key = UUID.fromString(row.idempotencyKey); val trace = UUID.fromString(row.correlationId)
            val fp = ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint
            if (row.skill == "task.create") {
                val task = (OrionCreateTaskPreparer.prepare(CreateTask(taskId, args.getString("title"), key, trace))
                    as? CreateTaskPreparation.Ready)?.task ?: return@mapNotNull unreadable()
                if (stored.request != OrionCreateTaskActionStore.normalized(id, task, stored.request.policyVersion)) return@mapNotNull unreadable()
                OrionTaskPending(TaskConfirmation(id, stored.version, TaskOperation.CREATE, task.title),
                    create = OrionTaskActionPreview(task, id, stored.version, fp, stored.request.policyVersion, stored.status))
            } else {
                val op = if (row.skill == "task.complete") TaskStatusOperation.COMPLETE else TaskStatusOperation.CANCEL
                val command = if (op == TaskStatusOperation.COMPLETE) CompleteTask(taskId, args.getLong("expectedVersion"), key, trace)
                    else CancelTask(taskId, args.getLong("expectedVersion"), key, trace)
                val change = (OrionTaskStatusPreparer.prepare(command) as? TaskStatusPreparation.Ready)?.change ?: return@mapNotNull unreadable()
                if (stored.request != OrionTaskStatusActionStore.normalized(id, change, stored.request.policyVersion)) return@mapNotNull unreadable()
                val current = query.find(taskId)
                val title = current?.title?.takeIf { current.state == TaskState.OPEN && current.version == change.expectedVersion }
                OrionTaskPending(TaskConfirmation(id, stored.version, TaskOperation.valueOf(op.name), title),
                    change = OrionTaskStatusActionPreview(change, id, stored.version, fp, stored.request.policyVersion, stored.status))
            }
        } catch (_: org.json.JSONException) { unreadable() } catch (_: IllegalArgumentException) { unreadable() }
    }

    private fun transient(failure: OrionTaskTransactionBlocked) = when (failure) {
        is OrionTaskTransactionBlocked.Action -> failure.reason in setOf(OrionTaskActionFailure.STARTUP_GATED,
            OrionTaskActionFailure.POLICY_DENIED, OrionTaskActionFailure.POLICY_UNAVAILABLE, OrionTaskActionFailure.KEY_UNAVAILABLE)
        is OrionTaskTransactionBlocked.Execution -> failure.reason in setOf(OrionTaskExecutionFailure.STARTUP_GATED,
            OrionTaskExecutionFailure.POLICY_DENIED, OrionTaskExecutionFailure.STORAGE_UNAVAILABLE)
    }
    private fun unavailable(): Nothing = throw OrionTaskTransactionBlocked.Action(OrionTaskActionFailure.POLICY_UNAVAILABLE)
    private fun invalid(): Nothing = throw OrionTaskTransactionBlocked.Action(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
}

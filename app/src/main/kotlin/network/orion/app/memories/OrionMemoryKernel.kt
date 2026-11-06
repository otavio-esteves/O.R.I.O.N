package network.orion.app.memories

import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.actions.*
import network.orion.core.commands.memories.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.data.memory.*
import org.json.JSONObject

internal class OrionMemoryPending(val display: MemoryConfirmation, val create: OrionMemoryActionPreview? = null,
    val change: OrionMemoryStatusActionPreview? = null, private val unreadable: OrionMemoryIngressWire? = null) {
    override fun toString() = "OrionMemoryPending(<redacted>)"
    fun wire(receipt: ActionConfirmationSnapshot? = null, dismiss: Boolean = false): OrionMemoryIngressWire {
        create?.let { p -> return OrionMemoryIngressWire(if (dismiss) "DISMISS" else "CREATE", p.memory.memoryId,
            p.memory.idempotencyKey, p.memory.correlationId, actionId = p.actionId, actionVersion = p.version,
            fingerprint = p.confirmationFingerprint, policyVersion = p.policyVersion, receipt = receipt) }
        if (change == null) {
            check(dismiss && receipt == null)
            return checkNotNull(unreadable)
        }
        val p = change
        return OrionMemoryIngressWire(if (dismiss) "DISMISS" else p.change.operation.name, p.change.memoryId,
            p.change.idempotencyKey, p.change.correlationId, expectedVersion = p.change.expectedVersion,
            actionId = p.actionId, actionVersion = p.version, fingerprint = p.confirmationFingerprint,
            policyVersion = p.policyVersion, receipt = receipt)
    }
}

/** MAIN-only local memory slice. All writes run through the same durable ingress transaction and barrier. */
internal class OrionMemoryKernel(private val db: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock, private val barrier: StartupBarrier, private val owner: OrionMemoryAdmissionOwner,
    readiness: () -> CoreReadiness, storageAvailable: () -> Boolean) {
    val actions = OrionActionStore(db, encryption, clock)
    private val create = OrionCreateMemoryActionStore(db, actions, clock, barrier)
    private val status = OrionMemoryStatusActionStore(db, actions, clock, barrier)
    private val owned = OrionOwnedMemoryStore(db, encryption, clock)
    private val createRunner = OrionCreateMemoryRunner(db, actions, owned, clock, barrier)
    private val statusRunner = OrionMemoryStatusRunner(db, actions, OrionOwnedMemoryStatusStore(db, encryption, clock), clock, barrier, OrionMemoryPayloadErasure(db, encryption, clock))
    val query = OrionMemoryQuery(db, encryption)
    private var processingId: UUID? = null // Only touched under barrier admission; no coroutine suspension.
    private val store = OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        val origin = try { OrionVerifiedMemoryIngress.from(payload, db, actions) }
            catch (_: IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: org.json.JSONException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch (_: java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
        consume(origin)
        LocalIngressDisposition.CONSUMED
    }, consumerScope = OrionMemoryIngressWire.SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest): DurableIngressOutcome = db.runInTransaction(Callable {
            val bytes = checkNotNull(request.copyContent())
            val wire = try { OrionMemoryIngressWire.decode(bytes) } finally { bytes.fill(0) }
            // Serialize this guard with enqueue and forget. No plaintext copy can race
            // into durable storage after the same target's privacy tombstone commits.
            if (wire.operation == "CREATE" && db.memories().forgotten(wire.memoryId.toString()) != 0) {
                val memory = if (wire.actionId == null) (OrionCreateMemoryPreparer.prepare(wire.create()) as? CreateMemoryPreparation.Ready)?.memory else null
                if (memory != null && orionMemoryWasForgotten(db, clock, memory)) throw OrionMemoryForgottenReplay()
                return@Callable DurableIngressOutcome.IDEMPOTENCY_CONFLICT
            }
            store.enqueue(request)
        })
        override fun consumeNext(): Boolean {
            check(Thread.holdsLock(barrier))
            processingId = null
            return try { store.consumeNext() } catch (failure: OrionMemoryTransactionBlocked) {
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

    fun submit(wire: OrionMemoryIngressWire, producerKey: UUID): MemoryOperationOutcome {
        val clear = wire.encode()
        val id = UUID.randomUUID()
        val outcome = try { ingress.submit(DurableIngressRequest(id, OrionMemoryIngressWire.SCOPE, producerKey,
            OrionMemoryIngressWire.SOURCE, OrionMemoryIngressWire.TYPE, 1, Sensitivity.SENSITIVE, false, null, clear,
            Duration.ofDays(1).toMillis(), Duration.ofDays(90).toMillis(), correlationId = wire.trace)) }
        catch (_: OrionMemoryForgottenReplay) { return MemoryOperationOutcome.FORGOTTEN }
        finally { clear.fill(0) }
        if (outcome !in setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP))
            return if (outcome == DurableIngressOutcome.IDEMPOTENCY_CONFLICT) MemoryOperationOutcome.REJECTED else MemoryOperationOutcome.UNAVAILABLE
        ingress.drain(25)
        val row = db.ingress().findByKey(OrionMemoryIngressWire.SCOPE, producerKey.toString()) ?: return MemoryOperationOutcome.UNAVAILABLE
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> if (wire.actionId != null) MemoryOperationOutcome.APPLIED else MemoryOperationOutcome.QUEUED
            DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> MemoryOperationOutcome.REJECTED
            else -> MemoryOperationOutcome.QUEUED
        }
    }

    private fun consume(origin: OrionVerifiedMemoryIngress) {
        val wire = origin.wire
        if (wire.operation == "DISMISS") {
            if (!owner.presentForQueuedRequest(origin)) unavailable()
            val id = wire.actionId ?: invalid()
            val row = db.actions().find(id.toString()) ?: invalid()
            if (row.skill !in setOf("memory.create", "memory.delete") || row.version != wire.actionVersion ||
                row.status != ActionStatus.NEEDS_CONFIRMATION || row.confirmationFingerprint != wire.fingerprint) invalid()
            if (actions.cancel(id, wire.actionVersion).outcome != ActionStoreOutcome.APPLIED) invalid()
            return
        }
        if (wire.operation == "CREATE") {
            val admission = owner.replayCreate(origin) ?: unavailable()
            if (wire.actionId == null) {
                val prepared = create.prepare(admission)
                if (prepared is OrionMemoryActionResult.AlreadySucceeded && !owned.matchesExisting(admission.memory)) invalid()
            } else {
                val preview = OrionMemoryActionPreview(admission.memory, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = create.confirmWithReceipt(admission, preview, receipt) as? OrionMemoryActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { createRunner.execute(admission, confirmed.preview) }
            }
        } else {
            val admission = owner.replayStatus(origin) ?: unavailable()
            if (wire.actionId == null) status.prepare(admission) else {
                val preview = OrionMemoryStatusActionPreview(admission.change, wire.actionId, wire.actionVersion, wire.fingerprint,
                    wire.policyVersion, ActionStatus.NEEDS_CONFIRMATION)
                val receipt = wire.receipt ?: invalid()
                val confirmed = status.confirmWithReceipt(admission, preview, receipt) as? OrionMemoryStatusActionResult.Ready ?: invalid()
                executeBound(wire.actionId) { statusRunner.execute(admission, confirmed.preview) }
            }
        }
    }

    /** The normative ActionEngine port and local entry use the same non-suspending body. */
    private fun executeBound(id: UUID, body: () -> OrionMemoryExecutionResult) {
        val engine = object : ActionEngine {
            fun executeLocal(actionId: UUID): ActionExecutionResult {
                if (actionId != id) return ActionExecutionResult.UNAVAILABLE
                return when (body()) {
                    is OrionMemoryExecutionResult.Completed -> ActionExecutionResult.SUCCEEDED
                    is OrionMemoryExecutionResult.Blocked -> ActionExecutionResult.BLOCKED
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
        // No reminders/workers are installed by this memory-only slice.
    }

    fun pending(): List<OrionMemoryPending> = db.actions().memoryConfirmations(50).mapNotNull { row ->
        val id = UUID.fromString(row.actionId)
        fun unreadable(): OrionMemoryPending {
            val operation = when (row.skill) { "memory.create" -> MemoryOperation.CREATE; "memory.delete" -> MemoryOperation.DELETE; else -> MemoryOperation.DELETE }
            return OrionMemoryPending(MemoryConfirmation(id, row.version, operation, null), unreadable =
                OrionMemoryIngressWire("DISMISS", id, UUID.fromString(row.idempotencyKey), UUID.fromString(row.correlationId),
                    actionId = id, actionVersion = row.version, fingerprint = row.confirmationFingerprint, policyVersion = row.policyVersion))
        }
        val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return@mapNotNull unreadable()
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || stored.version != row.version) return@mapNotNull null
        try {
            val args = JSONObject(stored.request.canonicalNormalizedArguments)
            val memoryId = UUID.fromString(args.getString("memoryId"))
            val key = UUID.fromString(row.idempotencyKey); val trace = UUID.fromString(row.correlationId)
            val fp = ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint
            if (row.skill == "memory.create") {
                val memory = (OrionCreateMemoryPreparer.prepare(CreateMemory(memoryId, args.getString("content"), key, trace))
                    as? CreateMemoryPreparation.Ready)?.memory ?: return@mapNotNull unreadable()
                if (stored.request != OrionCreateMemoryActionStore.normalized(id, memory, stored.request.policyVersion)) return@mapNotNull unreadable()
                OrionMemoryPending(MemoryConfirmation(id, stored.version, MemoryOperation.CREATE, memory.content),
                    create = OrionMemoryActionPreview(memory, id, stored.version, fp, stored.request.policyVersion, stored.status))
            } else {
                val op = MemoryStatusOperation.DELETE
                val command = ForgetMemory(memoryId, args.getLong("expectedVersion"), key, trace)
                val change = (OrionMemoryStatusPreparer.prepare(command) as? MemoryStatusPreparation.Ready)?.change ?: return@mapNotNull unreadable()
                if (stored.request != OrionMemoryStatusActionStore.normalized(id, change, stored.request.policyVersion)) return@mapNotNull unreadable()
                val current = query.find(memoryId)
                val content = current?.content?.takeIf { current.state == MemoryState.ACTIVE && current.version == change.expectedVersion }
                OrionMemoryPending(MemoryConfirmation(id, stored.version, MemoryOperation.valueOf(op.name), content),
                    change = OrionMemoryStatusActionPreview(change, id, stored.version, fp, stored.request.policyVersion, stored.status))
            }
        } catch (_: org.json.JSONException) { unreadable() } catch (_: IllegalArgumentException) { unreadable() }
    }

    private fun transient(failure: OrionMemoryTransactionBlocked) = when (failure) {
        is OrionMemoryTransactionBlocked.Action -> failure.reason in setOf(OrionMemoryActionFailure.STARTUP_GATED,
            OrionMemoryActionFailure.POLICY_DENIED, OrionMemoryActionFailure.POLICY_UNAVAILABLE, OrionMemoryActionFailure.KEY_UNAVAILABLE)
        is OrionMemoryTransactionBlocked.Execution -> failure.reason in setOf(OrionMemoryExecutionFailure.STARTUP_GATED,
            OrionMemoryExecutionFailure.POLICY_DENIED, OrionMemoryExecutionFailure.STORAGE_UNAVAILABLE)
    }
    private fun unavailable(): Nothing = throw OrionMemoryTransactionBlocked.Action(OrionMemoryActionFailure.POLICY_UNAVAILABLE)
    private fun invalid(): Nothing = throw OrionMemoryTransactionBlocked.Action(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
}

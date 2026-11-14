package network.orion.app.ai

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock

/** Numeric, MAIN-local versions only. No client, Context, endpoint or connection is retained. */
internal data class OrionCortexUnloadOwnerStamp(
    val endpointVersion: Long?, val connectionEpoch: Long?, val executionEpoch: Long?, val closed: Boolean,
)

/** Captured facts from the original cleanup; no callback, Binder, model, request or content. */
internal data class OrionCortexUnloadFacts(
    val clientScope: Long?, val owner: OrionCortexUnloadOwnerStamp, val knownAiPid: Int?,
    val deadline: MonotonicDeadline?, val pollAt: MonotonicInstant?,
    val noEndpoint: Boolean, val failure: OrionCortexUnloadOutcome?,
    val unloadedReply: Boolean, val validUnloadedReply: Boolean,
    val currentAtLoopExit: Boolean, val closedAtLoopExit: Boolean,
)

/**
 * Owned numeric guard. The client updates it while holding its existing monitor.
 * Publication shares only this short lock: no clock, RPC, IO or arbitrary callback.
 */
internal class OrionCortexUnloadObservationOwner {
    private val lock = Any()
    private var state = OrionCortexUnloadOwnerStamp(0, 0, 0, false)
    @Volatile private var disabled = false

    /** Permanent fail-closed state; no allocation, monitor acquisition or provider call. */
    fun disable() { disabled = true }
    fun isDisabled(): Boolean = disabled

    fun update(value: OrionCortexUnloadOwnerStamp) = synchronized(lock) { if (!disabled) state = value }
    fun snapshot(): OrionCortexUnloadOwnerStamp = synchronized(lock) { state }

    fun publish(facts: OrionCortexUnloadFacts, checkedAt: MonotonicInstant?, buffer: OrionCortexUnloadObservationBuffer) {
        synchronized(lock) {
            val selected = facts.owner
            val current = state
            val outcome = when {
                disabled -> OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
                facts.noEndpoint -> OrionCortexUnloadOutcome.NO_ENDPOINT
                facts.failure != null -> facts.failure
                facts.unloadedReply -> when {
                    selected.closed || current.closed -> OrionCortexUnloadOutcome.CLIENT_CLOSED
                    selected.executionEpoch == null || selected.executionEpoch <= 0 ||
                        selected.endpointVersion == null || selected.connectionEpoch == null ||
                        selected.connectionEpoch <= 0 || facts.clientScope == null -> OrionCortexUnloadOutcome.IDENTITY_UNAVAILABLE
                    current.endpointVersion != selected.endpointVersion || current.connectionEpoch != selected.connectionEpoch ->
                        OrionCortexUnloadOutcome.ENDPOINT_CHANGED
                    current.executionEpoch != selected.executionEpoch -> OrionCortexUnloadOutcome.EXECUTION_CHANGED
                    else -> classifyCortexUnloadObservation(facts.deadline, checkedAt, facts.pollAt,
                        true, true, false, facts.validUnloadedReply, true)
                }
                else -> classifyCortexUnloadObservation(facts.deadline, facts.pollAt, facts.pollAt,
                    facts.clientScope != null && selected.connectionEpoch != null && selected.connectionEpoch > 0,
                    facts.currentAtLoopExit, selected.closed || facts.closedAtLoopExit, true, false)
            }
            buffer.offer(OrionCortexUnloadObservation(facts.clientScope, selected.connectionEpoch?.takeIf { it > 0 }, facts.knownAiPid,
                facts.deadline?.start, if (facts.unloadedReply || facts.failure != null || facts.noEndpoint) checkedAt else facts.pollAt,
                if (disabled) OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE else outcome,
                selected.executionEpoch?.takeIf { it > 0 }))
        }
    }
}

/** Exactly one typed in-flight job globally, including a stuck provider and a closed client. */
internal class OrionCortexUnloadDiagnosticTicket(
    val clock: OrionClock, val owner: OrionCortexUnloadObservationOwner,
    val facts: OrionCortexUnloadFacts, val buffer: OrionCortexUnloadObservationBuffer,
)

/**
 * Initialized only after the client's MAIN-role check, outside run/cleanup/turns.
 * No executor queue, replacement thread, caller Runnable or per-client worker.
 */
internal object OrionCortexUnloadDiagnosticDispatcher {
    private val pending = AtomicReference<OrionCortexUnloadDiagnosticTicket?>()
    private val worker = Thread({
        while (true) {
            val ticket = pending.get()
            if (ticket == null) { LockSupport.park(); continue }
            try {
                val at = try { ticket.clock.monotonicNow() } catch (_: Throwable) { null }
                ticket.owner.publish(ticket.facts, at, ticket.buffer)
            } catch (_: Throwable) {
                runCatching { ticket.buffer.recordingFailed() }
            } finally {
                pending.compareAndSet(ticket, null)
            }
        }
    }, "orion_cortex_unload_diagnostic").apply { isDaemon = true; start() }

    fun submit(ticket: OrionCortexUnloadDiagnosticTicket): Boolean {
        if (!pending.compareAndSet(null, ticket)) { ticket.buffer.samplingBusyDropped(); return false }
        LockSupport.unpark(worker)
        return true
    }

    /** Technical occupancy, not an await/drain primitive and never consulted by inference. */
    fun hasPendingSample(): Boolean = pending.get() != null
}

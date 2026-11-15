package network.orion.app.ai

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicInstant

internal enum class OrionCortexUnloadOutcome {
    UNLOADED_OBSERVED, NO_ENDPOINT, IDENTITY_UNAVAILABLE, ENDPOINT_CHANGED, CLIENT_CLOSED, EXECUTION_CHANGED,
    DEADLINE_EXPIRED, TIME_REFERENCE_INVALID, STATUS_INVALID, TRANSPORT_FAILURE,
    UNLOAD_REQUEST_FAILED, POLL_TIMEOUT, CANCELLED, OBSERVATION_UNAVAILABLE,
}

/** Technical, ephemeral identity only. No Binder/Context, request or model content is retained. */
internal data class OrionCortexUnloadObservation(
    val clientScope: Long?, val connectionEpoch: Long?, val knownAiPid: Int?,
    val deadlineStartedAt: MonotonicInstant?, val checkedAt: MonotonicInstant?,
    val outcome: OrionCortexUnloadOutcome, val executionEpoch: Long? = null,
)

/** Pure classification; the caller supplies only observations of the existing cleanup path. */
internal fun classifyCortexUnloadObservation(deadline: MonotonicDeadline?, checkedAt: MonotonicInstant?, pollAt: MonotonicInstant?,
    identityKnown: Boolean, endpointCurrent: Boolean, clientClosed: Boolean,
    validEnvelope: Boolean, unloaded: Boolean): OrionCortexUnloadOutcome = when {
    clientClosed -> OrionCortexUnloadOutcome.CLIENT_CLOSED
    !identityKnown -> OrionCortexUnloadOutcome.IDENTITY_UNAVAILABLE
    !endpointCurrent -> OrionCortexUnloadOutcome.ENDPOINT_CHANGED
    !validEnvelope -> OrionCortexUnloadOutcome.STATUS_INVALID
    deadline == null || checkedAt == null || pollAt == null || checkedAt.elapsedSince(pollAt) == null -> OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID
    deadline.statusAt(checkedAt) == MonotonicDeadlineStatus.INVALID_REFERENCE -> OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID
    deadline.statusAt(checkedAt) == MonotonicDeadlineStatus.EXPIRED -> OrionCortexUnloadOutcome.DEADLINE_EXPIRED
    unloaded -> OrionCortexUnloadOutcome.UNLOADED_OBSERVED
    else -> OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
}

private val unloadClientScopes = AtomicLong()

/** One bounded CAS attempt: contention/exhaustion yields unavailable identity, never a reused scope. */
internal fun newCortexUnloadObservationScope(): Long? {
    val previous = unloadClientScopes.get()
    return if (previous < Long.MAX_VALUE && unloadClientScopes.compareAndSet(previous, previous + 1)) previous + 1 else null
}

internal data class OrionCortexUnloadObservationBatch(
    val observations: List<OrionCortexUnloadObservation>,
    val droppedAtLeast: Long, val contendedAtLeast: Long, val recordingFailuresAtLeast: Long,
    val lossAccountingComplete: Boolean, val drainContended: Boolean,
    val samplingBusyDroppedAtLeast: Long,
)

/** Final owned collector. No callback, IO, waiting, policy or unbounded result retention. */
internal class OrionCortexUnloadObservationBuffer(private val capacity: Int = 16) {
    init { require(capacity in 1..64) }
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<OrionCortexUnloadObservation>(capacity)
    private val dropped = AtomicLong()
    private val contended = AtomicLong()
    private val failures = AtomicLong()
    private val samplingBusy = AtomicLong()
    private val complete = AtomicBoolean(true)

    private fun count(counter: AtomicLong) {
        val previous = counter.get()
        if (previous == Long.MAX_VALUE || !counter.compareAndSet(previous, previous + 1)) complete.set(false)
    }

    fun offer(observation: OrionCortexUnloadObservation): Boolean {
        if (!lock.tryLock()) {
            count(dropped); count(contended)
            return false
        }
        return try {
            if (entries.size >= capacity) { count(dropped); false }
            else { entries.addLast(observation); true }
        } finally { lock.unlock() }
    }

    fun samplingBusyDropped() { count(dropped); count(samplingBusy) }

    fun recordingFailed() { count(failures); complete.set(false) }

    /** A busy drain consumes nothing; its empty result is explicitly marked contended. */
    fun drain(): OrionCortexUnloadObservationBatch {
        if (!lock.tryLock()) return batch(emptyList(), true)
        return try {
            val copy = Collections.unmodifiableList(ArrayList(entries))
            entries.clear()
            batch(copy, false)
        } finally { lock.unlock() }
    }

    private fun batch(values: List<OrionCortexUnloadObservation>, busy: Boolean) = OrionCortexUnloadObservationBatch(
        values, dropped.get(), contended.get(), failures.get(), complete.get(), busy, samplingBusy.get())
}

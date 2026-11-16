package network.orion.ai.api

import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionObservationEventClock
import network.orion.core.time.OrionObservationMoment

enum class OrionLifecycleEventGap { SOURCE_REUSED, ACTIVATION_REUSED, WITNESS_UNAVAILABLE, CLOCK_UNAVAILABLE,
    TERMINAL_BEFORE_ACTIVATION, TERMINAL_UNAVAILABLE, UNPUBLISHED_UPDATE, CHUNK_METADATA_UNAVAILABLE,
    CHUNK_SEQUENCE, CONTENDED, CAPACITY, RECORDING_FAILURE }

/** The actual winning lifecycle status; a missing observation remains missing, never a deadline sample. */
class OrionLifecycleTerminalEvent internal constructor(val at: OrionObservationMoment?, val operation: OrionLifecycleOperation,
    val origin: OrionLifecycleTerminalOrigin, val status: OrionInferenceStatus)

/** Detached numeric metadata only. Never a chunk, content, original update or downstream List. */
class OrionLifecycleOrderedEvent internal constructor(val at: OrionObservationMoment, val metadata: OrionLifecycleChunkMetadata)

/** Constant-size read only. No clock, status transition, chunk copy or source mutation. */
class OrionLifecycleTerminalView internal constructor(val active: Boolean, val invalidated: Boolean,
    val transitionClaimed: Boolean, val firstTerminal: OrionLifecycleTerminalEvent?, val unavailable: Boolean)

class OrionLifecycleEventSnapshot internal constructor(val claimed: Boolean, val activationAttempted: Boolean,
    val active: Boolean, val invalidated: Boolean, val firstTerminal: OrionLifecycleTerminalEvent?,
    val firstTerminalRecordUnavailable: Boolean, val orderedEvents: List<OrionLifecycleOrderedEvent>,
    val gaps: Set<OrionLifecycleEventGap>, val pendingUpdatesAtLeast: Long, val droppedAtLeast: Long,
    val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean, val snapshotContended: Boolean,
    val chunkSequenceIntact: Boolean) {
    val sourceAuthenticated = false
    val runtimeEvidenceV1Complete = false
    val physicalVerified = false
    val profileApproved = false
    val productionInferenceApproved = false
    val fullF5GateClosed = false
    val fullSoakGateClosed = false
    val f6Authorized = false
    override fun toString() = "OrionLifecycleEventSnapshot(qualified=false)"
}

/**
 * One final optional passive source for one Lifecycle. No callback/interface, app dependency,
 * functional Clock/provider read, RPC, worker, policy, content or implicit export.
 * Explicit Lifecycle activation belongs only inside a future real runOnce owner; this package
 * supplies no app association or composition and cannot prove that ownership by itself.
 */
class OrionInferenceLifecycleEventSource(private val observationClock: OrionObservationEventClock? =
    OrionObservationEventClock.createInactive()) {
    private val lock = ReentrantLock()
    private val claimed = AtomicBoolean()
    private val activationAttempted = AtomicBoolean()
    private val invalidated = AtomicBoolean()
    private val terminalClaimed = AtomicBoolean()
    private val terminalUnavailable = AtomicBoolean()
    private val chunkLoss = AtomicBoolean()
    private val gaps = AtomicLong()
    private val pendingUpdates = AtomicLong()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)
    private val ordered = ArrayList<OrionLifecycleOrderedEvent>(MAX_ORDERED_EVENTS)
    @Volatile private var active = false
    @Volatile private var terminal: OrionLifecycleTerminalEvent? = null
    private var prepared: MonotonicInstant? = null
    private var witness: MonotonicInstant? = null
    private var classification: MonotonicDeadlineStatus? = null
    private var nextSequence = 0L

    private fun count(value: AtomicLong) {
        val before = value.get()
        if (before == Long.MAX_VALUE || !value.compareAndSet(before, before + 1)) accounting.set(false)
    }
    private fun gap(reason: OrionLifecycleEventGap) {
        val before = gaps.get()
        if (!gaps.compareAndSet(before, before or (1L shl reason.ordinal))) accounting.set(false)
    }
    private fun lost(reason: OrionLifecycleEventGap, invalidate: Boolean = false) {
        gap(reason); count(dropped); chunkLoss.set(true)
        if (invalidate) invalidated.set(true)
    }
    internal fun recordingFailed() {
        try { count(failures); accounting.set(false); lost(OrionLifecycleEventGap.RECORDING_FAILURE) }
        catch (_: Throwable) { /* Even diagnostic accounting cannot escape into the original guard. */ }
    }
    private inline fun <T> isolated(operation: () -> T): T? = try { operation() } catch (_: Throwable) { recordingFailed(); null }

    internal fun claim(preparation: MonotonicInstant): OrionInferenceLifecycleEventSource? = isolated {
        if (!claimed.compareAndSet(false, true)) { lost(OrionLifecycleEventGap.SOURCE_REUSED, true); return@isolated null }
        if (!lock.tryLock()) { lost(OrionLifecycleEventGap.CONTENDED, true); return@isolated null }
        try { prepared = preparation; this } finally { lock.unlock() }
    }

    /** Copies only an already-performed original read/classification; never queries that clock again. */
    internal fun originalWitness(at: MonotonicInstant, status: MonotonicDeadlineStatus) {
        isolated {
            if (!lock.tryLock()) { lost(OrionLifecycleEventGap.CONTENDED, true); return@isolated }
            try {
                witness = at; classification = status
                if (active && !invalidated.get() && observationClock?.confirmOriginalWitness(at, status) != true)
                    lost(OrionLifecycleEventGap.WITNESS_UNAVAILABLE, true)
            } finally { lock.unlock() }
        }
    }

    /** No observation read. The caller must already be the real original runOnce owner. */
    internal fun activate(alreadyTerminal: Boolean): Boolean = isolated {
        if (!activationAttempted.compareAndSet(false, true)) { lost(OrionLifecycleEventGap.ACTIVATION_REUSED, true); return@isolated false }
        if (!lock.tryLock()) { lost(OrionLifecycleEventGap.CONTENDED, true); return@isolated false }
        try {
            if (alreadyTerminal || terminalClaimed.get()) {
                terminalUnavailable.set(true); lost(OrionLifecycleEventGap.TERMINAL_BEFORE_ACTIVATION, true); return@isolated false
            }
            val preparation = prepared
            if (invalidated.get() || preparation == null || observationClock?.bindOriginalWitness(preparation, witness, classification) != true) {
                lost(OrionLifecycleEventGap.WITNESS_UNAVAILABLE, true); return@isolated false
            }
            active = true; true
        } finally { lock.unlock() }
    } ?: false

    private fun moment(): OrionObservationMoment? {
        if (!active || invalidated.get()) return null
        return observationClock?.observe().also {
            if (it == null) lost(OrionLifecycleEventGap.CLOCK_UNAVAILABLE, true)
        }
    }

    /** Called immediately after the first original successful status assignment, before update construction. */
    internal fun firstTerminal(operation: Int, origin: Int, status: OrionInferenceStatus) {
        isolated {
            if (!terminalClaimed.compareAndSet(false, true)) return@isolated
            if (!active) {
                terminalUnavailable.set(true); lost(OrionLifecycleEventGap.TERMINAL_BEFORE_ACTIVATION); return@isolated
            }
            // The reserved terminal is independent from the chunk capacity and remains unavailable on loss.
            val at = moment()
            if (!lock.tryLock()) {
                terminalUnavailable.set(true); lost(OrionLifecycleEventGap.CONTENDED); return@isolated
            }
            try {
                terminal = OrionLifecycleTerminalEvent(at, OrionLifecycleOperation.entries[operation],
                    OrionLifecycleTerminalOrigin.entries[origin], status)
                if (at == null) { terminalUnavailable.set(true); gap(OrionLifecycleEventGap.TERMINAL_UNAVAILABLE) }
            } finally { lock.unlock() }
        } ?: run { if (terminalClaimed.get() && terminal == null) terminalUnavailable.set(true) }
    }

    internal fun byteParts(oldFacts: OrionInferenceLifecycleFactBuffer?): OrionLifecycleByteParts? {
        if (!active || invalidated.get()) return oldFacts?.byteParts()
        return isolated { OrionLifecycleByteParts(oldFacts, this) }
    }

    internal fun beginAccept(): OrionLifecycleEventStage? = isolated {
        if (!active || invalidated.get()) return@isolated null
        if (!lock.tryLock()) { lost(OrionLifecycleEventGap.CONTENDED); return@isolated null }
        try {
            if (ordered.size >= MAX_ORDERED_EVENTS) { lost(OrionLifecycleEventGap.CAPACITY); return@isolated null }
            val stage = OrionLifecycleEventStage(this)
            count(pendingUpdates)
            stage
        } finally { lock.unlock() }
    }

    /** Stage only numeric values obtained after the original validation; never receive a chunk/List. */
    internal fun stage(stage: OrionLifecycleEventStage, sequence: Long, final: Boolean, finish: OrionInferenceTerminal?,
        failure: OrionInferenceFailure?, bytes: Int, parts: Long) {
        isolated {
            if (stage.owner !== this || stage.consumed.get() || stage.values.size >= MAX_RELEASES_PER_UPDATE) {
                lost(OrionLifecycleEventGap.CAPACITY); return@isolated
            }
            val at = if (!final) moment() else terminal?.takeIf {
                it.origin == OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION && it.status.terminal == finish && it.status.failure == failure
            }?.at
            if (at == null) lost(OrionLifecycleEventGap.CLOCK_UNAVAILABLE)
            check(sequence >= 0 && bytes in 0..OrionInferenceLifecycle.MAX_CHUNK_CONTENT_BYTES)
            val text = (parts ushr 32).toInt(); val tool = parts.toInt()
            val available = parts >= 0 && text >= 0 && tool >= 0 && text.toLong() + tool == bytes.toLong()
            if (!available) {
                lost(OrionLifecycleEventGap.CHUNK_METADATA_UNAVAILABLE)
                if (parts != -1L) recordingFailed()
            }
            stage.values.add(OrionLifecycleStagedEvent(at, OrionLifecycleChunkMetadata(sequence, final, finish, failure, bytes,
                if (available) text else null, if (available) tool else null)))
        }
    }

    /** A real constructed original update is required. Never retains, copies, inspects or replaces its List. */
    internal fun publish(stage: OrionLifecycleEventStage?, original: OrionInferenceUpdate) {
        if (stage == null) return
        isolated {
            if (stage.owner !== this || !stage.consumed.compareAndSet(false, true)) {
                lost(OrionLifecycleEventGap.CHUNK_SEQUENCE); return@isolated
            }
            if (!lock.tryLock()) { lost(OrionLifecycleEventGap.CONTENDED); return@isolated }
            try {
                val pending = pendingUpdates.get()
                if (pending == 0L || !pendingUpdates.compareAndSet(pending, pending - 1)) accounting.set(false)
                if (original.receipt != OrionInferenceReceipt.ACCEPTED) {
                    lost(OrionLifecycleEventGap.UNPUBLISHED_UPDATE); return@isolated
                }
                for (entry in stage.values) {
                    if (entry.metadata.sequence != nextSequence) { lost(OrionLifecycleEventGap.CHUNK_SEQUENCE); return@isolated }
                    nextSequence++
                    if (entry.at == null) { chunkLoss.set(true); continue }
                    if (ordered.size >= MAX_ORDERED_EVENTS) { lost(OrionLifecycleEventGap.CAPACITY); return@isolated }
                    ordered.add(OrionLifecycleOrderedEvent(entry.at, entry.metadata))
                }
            } finally { lock.unlock() }
        }
    }

    /** Constant-size passive view. No clock/status/list copy, mutation or source drain. */
    fun terminalView(): OrionLifecycleTerminalView? {
        if (!lock.tryLock()) return null
        return try { OrionLifecycleTerminalView(active, invalidated.get(), terminalClaimed.get(), terminal, terminalUnavailable.get()) }
        catch (_: Throwable) { null }
        finally { lock.unlock() }
    }

    /** Full bounded copy belongs only to manual finalization, never sampler polling. */
    fun snapshot(): OrionLifecycleEventSnapshot? = isolated {
        val acquired = lock.tryLock()
        if (!acquired) lost(OrionLifecycleEventGap.CONTENDED)
        try {
            val reasons = EnumSet.noneOf(OrionLifecycleEventGap::class.java)
            val bits = gaps.get()
            OrionLifecycleEventGap.entries.forEach { if (bits and (1L shl it.ordinal) != 0L) reasons.add(it) }
            val pending = pendingUpdates.get()
            if (pending > 0) reasons.add(OrionLifecycleEventGap.UNPUBLISHED_UPDATE)
            OrionLifecycleEventSnapshot(claimed.get(), activationAttempted.get(), active, invalidated.get(),
                if (acquired) terminal else null, terminalUnavailable.get() || !acquired,
                if (acquired) Collections.unmodifiableList(ArrayList(ordered)) else emptyList(), Collections.unmodifiableSet(reasons),
                pending, dropped.get(), failures.get(), accounting.get(), !acquired, acquired && !chunkLoss.get() && pending == 0L)
        } finally { if (acquired) lock.unlock() }
    }

    companion object {
        const val MAX_ORDERED_EVENTS = 4097
        internal const val MAX_RELEASES_PER_UPDATE = 9
    }
}

internal class OrionLifecycleStagedEvent(val at: OrionObservationMoment?, val metadata: OrionLifecycleChunkMetadata)

/** One original accept's local scalars. Not held by the source if original construction/validation throws. */
internal class OrionLifecycleEventStage(val owner: OrionInferenceLifecycleEventSource) {
    val consumed = AtomicBoolean()
    val values = ArrayList<OrionLifecycleStagedEvent>(OrionInferenceLifecycleEventSource.MAX_RELEASES_PER_UPDATE)
    fun ordered(sequence: Long, final: Boolean, finish: OrionInferenceTerminal?, failure: OrionInferenceFailure?, bytes: Int, parts: Long) =
        owner.stage(this, sequence, final, finish, failure, bytes, parts)
}

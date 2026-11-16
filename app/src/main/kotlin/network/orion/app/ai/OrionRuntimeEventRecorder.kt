package network.orion.app.ai

import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.OrionInferenceLifecycle
import network.orion.ai.api.OrionInferenceStage
import network.orion.ai.api.OrionInferenceFailure
import network.orion.ai.api.OrionInferenceStatus
import network.orion.ai.api.OrionInferenceTerminal
import network.orion.ai.api.OrionLifecycleChunkMetadata
import network.orion.ai.api.OrionLifecycleTerminalOrigin

/**
 * Pure capture foundation. No Clock, callback, resource evaluation, RPC, worker or algorithm hook.
 * The future final adapter must call these typed seams with genuine already-observed source data.
 * Constructor values describe one association, never admission/configuration or source authentication.
 */
internal class OrionRuntimeEventRecorder(private val expected: OrionRuntimeCaptureFence,
    private val originalDeadlineElapsed: Long?, private val maximumSampleAgeMillis: Long?) {
    private val lock = ReentrantLock()
    private val markers = ArrayList<OrionRuntimeOperationMarker>(MAX_MARKERS)
    private val chunks = ArrayList<OrionRuntimeOrderedChunk>(MAX_CHUNKS)
    private val memory = ArrayList<OrionRuntimeMemoryRow>(MAX_MEMORY_ROWS)
    private val invalidated = AtomicBoolean()
    private val chunkLoss = AtomicBoolean()
    private val terminalClaimed = AtomicBoolean()
    private val terminalUnavailable = AtomicBoolean()
    private val gaps = AtomicLong(1L shl OrionRuntimeCaptureGap.UNWIRED_SOURCE.ordinal)
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)
    private var started: OrionRuntimeEventTime? = null
    private var completed: OrionRuntimeEventTime? = null
    private var firstTerminal: OrionRuntimeFirstTerminal? = null
    private var lastEventElapsed = -1L
    private var lastChunkElapsed = -1L
    private var nextSequence = 0L
    private var finalChunkRetained = false
    private var runtimeSeen = false
    private var validationSeen = false
    private var successfulLoadObserved = false

    private fun count(counter: AtomicLong) {
        val before = counter.get()
        if (before == Long.MAX_VALUE || !counter.compareAndSet(before, before + 1)) accounting.set(false)
    }
    private fun gap(reason: OrionRuntimeCaptureGap) {
        val bit = 1L shl reason.ordinal
        val before = gaps.get()
        if (!gaps.compareAndSet(before, before or bit)) accounting.set(false)
    }
    private fun lost(reason: OrionRuntimeCaptureGap, invalidate: Boolean = false, chunk: Boolean = false) {
        gap(reason); count(dropped)
        if (invalidate) invalidated.set(true)
        if (chunk) chunkLoss.set(true)
    }
    private fun failed() {
        count(failures); accounting.set(false); chunkLoss.set(true); lost(OrionRuntimeCaptureGap.RECORDING_FAILURE)
    }

    private fun fence(current: OrionRuntimeCaptureFence): Boolean {
        if (invalidated.get()) return false
        if (!expected.known()) { lost(OrionRuntimeCaptureGap.IDENTITY_UNAVAILABLE, invalidate = true); return false }
        if (!expected.matches(current)) { lost(OrionRuntimeCaptureGap.IDENTITY_CHANGED, invalidate = true); return false }
        return true
    }
    private fun validTime(value: OrionRuntimeEventTime?): Boolean = value != null && value.bootCount != null &&
        value.bootCount == expected.bootCount && value.elapsedMillis != null && value.elapsedMillis >= 0

    private fun event(current: OrionRuntimeCaptureFence, value: OrionRuntimeEventTime,
        previousElapsed: Long = lastEventElapsed): Boolean {
        if (!fence(current)) return false
        if (!validTime(value)) { lost(OrionRuntimeCaptureGap.CLOCK_UNAVAILABLE, invalidate = true); return false }
        if (started == null) { lost(OrionRuntimeCaptureGap.NOT_STARTED); return false }
        if (completed != null) { lost(OrionRuntimeCaptureGap.ALREADY_COMPLETED); return false }
        if (checkNotNull(value.elapsedMillis) < previousElapsed || checkNotNull(value.elapsedMillis) < checkNotNull(started?.elapsedMillis)) {
            lost(OrionRuntimeCaptureGap.CLOCK_DISCONTINUOUS, invalidate = true); return false
        }
        return true
    }

    private inline fun record(operation: () -> Boolean): Boolean {
        if (!lock.tryLock()) { lost(OrionRuntimeCaptureGap.CONTENDED, chunk = true); return false }
        return try { operation() } catch (_: Throwable) { failed(); false } finally { lock.unlock() }
    }

    fun sessionStarted(current: OrionRuntimeCaptureFence, at: OrionRuntimeEventTime): Boolean = record {
        if (!fence(current)) return@record false
        if (started != null) { lost(OrionRuntimeCaptureGap.IDENTITY_CHANGED, invalidate = true); return@record false }
        if (!validTime(at)) { lost(OrionRuntimeCaptureGap.CLOCK_UNAVAILABLE, invalidate = true); return@record false }
        started = at; lastEventElapsed = checkNotNull(at.elapsedMillis); true
    }

    /** Supplied true dispatch/terminal/cleanup point, never a deadline/poll sample relabeled here. */
    fun trueOperation(current: OrionRuntimeCaptureFence, marker: OrionRuntimeOperationMarker): Boolean = record {
        if (!event(current, marker.at)) return@record false
        val valid = when (marker.kind) {
            OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH -> marker.hasIssuedId()
            OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL -> marker.hasIssuedId() && marker.status != null && marker.status.terminal != null &&
                (marker.status.terminal != OrionInferenceTerminal.SUCCEEDED || marker.loadedModelConfirmed == true)
            OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH -> marker.hasIssuedId() && marker.inputBytes != null && marker.inputBytes > 0
            OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP -> marker.cleanup != null
            else -> true
        }
        if (!valid) { lost(OrionRuntimeCaptureGap.MARKER_INVALID); return@record false }
        if (firstTerminal?.status?.terminal == OrionInferenceTerminal.PROCESS_DIED && (marker.kind == OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH || marker.kind == OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP)) {
            lost(OrionRuntimeCaptureGap.PROCESS_DIED); return@record false
        }
        if (markers.size == MAX_MARKERS) { lost(OrionRuntimeCaptureGap.CAPACITY); return@record false }
        markers.add(marker)
        if (marker.kind == OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL && marker.status?.terminal == OrionInferenceTerminal.SUCCEEDED)
            successfulLoadObserved = true
        lastEventElapsed = checkNotNull(marker.at.elapsedMillis)
        true
    }

    /** Caller invokes only for the original first successful transition; the reserved claim is never repaired. */
    fun trueFirstTerminal(current: OrionRuntimeCaptureFence, at: OrionRuntimeEventTime, status: OrionInferenceStatus,
        origin: OrionLifecycleTerminalOrigin): Boolean {
        if (!terminalClaimed.compareAndSet(false, true)) return false
        val retained = record {
            if (!event(current, at)) return@record false
            if (!validTerminal(status, origin)) { lost(OrionRuntimeCaptureGap.TERMINAL_MISMATCH); return@record false }
            firstTerminal = OrionRuntimeFirstTerminal(at, status, origin)
            lastEventElapsed = checkNotNull(at.elapsedMillis)
            true
        }
        if (!retained) { terminalUnavailable.set(true); gap(OrionRuntimeCaptureGap.TERMINAL_UNAVAILABLE) }
        return retained
    }

    private fun validTerminal(status: OrionInferenceStatus, origin: OrionLifecycleTerminalOrigin): Boolean {
        if (status.stage != OrionInferenceStage.TERMINAL || status.terminal == null ||
            status.failure != null && status.terminal != OrionInferenceTerminal.FAILED) return false
        return when (origin) {
            OrionLifecycleTerminalOrigin.INIT_SCHEMA -> status.terminal == OrionInferenceTerminal.FAILED && status.failure == OrionInferenceFailure.SCHEMA_MISMATCH
            OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED -> status.terminal == OrionInferenceTerminal.TIMED_OUT
            OrionLifecycleTerminalOrigin.INVALID_TIME -> status.terminal == OrionInferenceTerminal.FAILED && status.failure == OrionInferenceFailure.INVALID_TIME_REFERENCE
            OrionLifecycleTerminalOrigin.CANCELLATION -> status.terminal == OrionInferenceTerminal.CANCELLED
            OrionLifecycleTerminalOrigin.TRANSPORT_FAILURE -> status.terminal == OrionInferenceTerminal.FAILED && status.failure == OrionInferenceFailure.TRANSPORT_FAILURE
            OrionLifecycleTerminalOrigin.PROCESS_DEATH -> status.terminal == OrionInferenceTerminal.PROCESS_DIED
            OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION -> true
            OrionLifecycleTerminalOrigin.REJECTED_CHUNK -> status.terminal == OrionInferenceTerminal.FAILED && status.failure != null
        }
    }

    /** Publish only after successful construction of the original ordered update; never retain its List/content. */
    fun orderedChunk(current: OrionRuntimeCaptureFence, at: OrionRuntimeEventTime,
        metadata: OrionLifecycleChunkMetadata): Boolean = record {
        // Ordered decisions may have been staged before the first-terminal marker, then published only
        // after update construction. Preserve those original times; publication order is not event time.
        if (!event(current, at, lastChunkElapsed)) return@record false
        if (metadata.sequence != nextSequence || finalChunkRetained) {
            lost(OrionRuntimeCaptureGap.CHUNK_SEQUENCE, chunk = true); return@record false
        }
        val elapsed = checkNotNull(at.elapsedMillis)
        if (originalDeadlineElapsed == null || originalDeadlineElapsed < 0 || elapsed >= originalDeadlineElapsed) {
            lost(OrionRuntimeCaptureGap.CLOCK_DISCONTINUOUS, invalidate = true, chunk = true); return@record false
        }
        val final = metadata.finishReason
        if (metadata.isFinal != (final != null) || (!metadata.isFinal && metadata.failure != null) ||
            (metadata.failure != null && final != OrionInferenceTerminal.FAILED)) {
            lost(OrionRuntimeCaptureGap.CHUNK_METADATA_INVALID, chunk = true); return@record false
        }
        val terminal = firstTerminal
        if (terminal != null && (elapsed > checkNotNull(terminal.at.elapsedMillis) ||
            metadata.isFinal && (terminal.origin != OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION ||
                terminal.status.terminal != final || terminal.status.failure != metadata.failure || terminal.at != at))) {
            lost(OrionRuntimeCaptureGap.TERMINAL_MISMATCH, chunk = true); return@record false
        }
        if (metadata.isFinal && terminal == null) {
            lost(OrionRuntimeCaptureGap.TERMINAL_UNAVAILABLE, chunk = true); return@record false
        }
        val text = metadata.validatedTextDeltaUtf8Bytes
        val tool = metadata.validatedToolDeltaUtf8Bytes
        val total = metadata.validatedTotalContentUtf8Bytes
        if (text == null || tool == null || total == null) {
            gap(OrionRuntimeCaptureGap.CHUNK_METADATA_UNAVAILABLE); chunkLoss.set(true)
        } else if (text < 0 || tool < 0 || total !in 0..OrionInferenceLifecycle.MAX_CHUNK_CONTENT_BYTES ||
            text.toLong() + tool != total.toLong()) {
            lost(OrionRuntimeCaptureGap.CHUNK_METADATA_INVALID, chunk = true); return@record false
        }
        if (chunks.size == MAX_CHUNKS) { lost(OrionRuntimeCaptureGap.CAPACITY, chunk = true); return@record false }
        chunks.add(OrionRuntimeOrderedChunk(at, metadata)); nextSequence++; finalChunkRetained = metadata.isFinal
        lastChunkElapsed = elapsed
        if (elapsed > lastEventElapsed) lastEventElapsed = elapsed // Internal high-water only; no timestamp is rewritten.
        true
    }

    /** Complete, already-collected row inside a supplied closed source window; performs no measurement/RPC. */
    fun memoryRow(current: OrionRuntimeCaptureFence, row: OrionRuntimeMemoryRow): Boolean = record {
        if (!fence(current)) return@record false
        val times = listOf(row.window.started, row.started, row.mainSampled, row.aiSampled, row.completed, row.window.completed)
        if (!times.all(::validTime)) { lost(OrionRuntimeCaptureGap.CLOCK_UNAVAILABLE, invalidate = true); return@record false }
        val ordered = times.map { checkNotNull(it.elapsedMillis) }
        val main = row.mainPssBytes; val ai = row.aiPssBytes; val sum = row.aggregatePssBytes
        val maxAge = maximumSampleAgeMillis; val total = row.observedTotalMemoryBytes
        val previous = memory.lastOrNull()
        val valid = started != null && ordered.first() >= checkNotNull(started?.elapsedMillis) &&
            ordered.zipWithNext().all { (left, right) -> left <= right } &&
            (completed == null || ordered.last() <= checkNotNull(completed?.elapsedMillis)) &&
            row.mainPid == expected.mainPid && row.aiPid == expected.aiPid &&
            main != null && ai != null && main > 0 && ai > 0 && main <= Long.MAX_VALUE - ai && sum == main + ai &&
            total != null && sum != null && sum <= total && row.mainNativeIncluded == true && row.aiNativeIncluded == true &&
            row.nativeRuntimeLoaded != null && row.nativeValidationCompleted != null &&
            (row.nativeValidationCompleted != true || row.nativeRuntimeLoaded == true) &&
            (!runtimeSeen || row.nativeRuntimeLoaded == true) && (!validationSeen || row.nativeValidationCompleted == true) &&
            ((row.serviceState != OrionRuntimeServiceState.LOADED && row.serviceState != OrionRuntimeServiceState.INFERENCING &&
                !(successfulLoadObserved && row.window.phase.ordinal >= OrionRuntimeMemoryPhase.AFTER_LOAD.ordinal)) ||
                row.nativeRuntimeLoaded == true && row.nativeValidationCompleted == true) &&
            maxAge != null && maxAge > 0 && ordered[1] == ordered[2] &&
            ordered[4] - ordered[1] < maxAge && allowedState(row.window.phase, row.serviceState) &&
            (previous == null || row.window.phase.ordinal >= previous.window.phase.ordinal &&
                ordered[1] >= checkNotNull(previous.completed.elapsedMillis))
        if (!valid) { lost(OrionRuntimeCaptureGap.MEMORY_INVALID); return@record false }
        if (firstTerminal?.status?.terminal == OrionInferenceTerminal.PROCESS_DIED &&
            (row.window.phase.ordinal >= OrionRuntimeMemoryPhase.AFTER_TERMINAL.ordinal ||
                ordered[4] > checkNotNull(firstTerminal?.at?.elapsedMillis))) {
            lost(OrionRuntimeCaptureGap.PROCESS_DIED); return@record false
        }
        if (memory.size == MAX_MEMORY_ROWS) { lost(OrionRuntimeCaptureGap.CAPACITY); return@record false }
        memory.add(row)
        runtimeSeen = runtimeSeen || row.nativeRuntimeLoaded == true
        validationSeen = validationSeen || row.nativeValidationCompleted == true
        true
    }

    private fun allowedState(phase: OrionRuntimeMemoryPhase, state: OrionRuntimeServiceState): Boolean = when (phase) {
        OrionRuntimeMemoryPhase.BEFORE_LOAD, OrionRuntimeMemoryPhase.AFTER_UNLOAD -> state == OrionRuntimeServiceState.UNLOADED
        OrionRuntimeMemoryPhase.DURING_LOAD -> state == OrionRuntimeServiceState.LOADING || state == OrionRuntimeServiceState.LOADED
        OrionRuntimeMemoryPhase.AFTER_LOAD, OrionRuntimeMemoryPhase.AFTER_TERMINAL -> state == OrionRuntimeServiceState.LOADED
        OrionRuntimeMemoryPhase.DURING_INFERENCE -> state == OrionRuntimeServiceState.INFERENCING || state == OrionRuntimeServiceState.LOADED
        OrionRuntimeMemoryPhase.DURING_UNLOAD -> state == OrionRuntimeServiceState.UNLOADING || state == OrionRuntimeServiceState.UNLOADED
    }

    fun sessionCompleted(current: OrionRuntimeCaptureFence, at: OrionRuntimeEventTime): Boolean = record {
        if (!event(current, at)) return@record false
        if (memory.lastOrNull()?.completed?.elapsedMillis?.let { it > checkNotNull(at.elapsedMillis) } == true) {
            lost(OrionRuntimeCaptureGap.CLOCK_DISCONTINUOUS, invalidate = true); return@record false
        }
        completed = at; lastEventElapsed = checkNotNull(at.elapsedMillis); true
    }

    /** Separate manual copy only; never called implicitly by a functional return or cleanup. */
    fun snapshot(): OrionRuntimeCaptureSnapshot? {
        val acquired = lock.tryLock()
        if (!acquired) lost(OrionRuntimeCaptureGap.CONTENDED, chunk = true)
        return try {
            val reasons = EnumSet.noneOf(OrionRuntimeCaptureGap::class.java)
            val bits = gaps.get()
            OrionRuntimeCaptureGap.entries.forEach { if (bits and (1L shl it.ordinal) != 0L) reasons.add(it) }
            OrionRuntimeCaptureSnapshot(if (acquired && started != null && expected.known()) expected.snapshotAssociation() else null,
                if (acquired) started else null, if (acquired) completed else null,
                if (acquired) Collections.unmodifiableList(ArrayList(markers)) else emptyList(),
                if (acquired) Collections.unmodifiableList(ArrayList(chunks)) else emptyList(),
                if (acquired) firstTerminal else null, terminalUnavailable.get(),
                if (acquired) Collections.unmodifiableList(ArrayList(memory)) else emptyList(), Collections.unmodifiableSet(reasons),
                invalidated.get(), !chunkLoss.get(), dropped.get(), failures.get(), accounting.get(), !acquired)
        } catch (_: Throwable) { failed(); null }
        finally { if (acquired) lock.unlock() }
    }

    companion object {
        const val MAX_CHUNKS = 4097
        const val MAX_MEMORY_ROWS = 128
        const val MAX_MARKERS = 32
    }
}

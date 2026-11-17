package network.orion.app.ai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import java.util.ArrayDeque
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.*
import network.orion.core.time.OrionObservationEventClock
import network.orion.core.time.OrionObservationMoment

/** Reviewed observation configuration only; no default, workload/admission input or calibration claim. */
internal class OrionRuntimeSamplingConfiguration(val maximumSampleAgeMillis: Long) {
    init { require(maximumSampleAgeMillis in 1..1000) }
}

internal class OrionRuntimeMainMemoryReading(val pssBytes: Long?, val totalMemoryBytes: Long?)
internal class OrionRuntimeMainMemorySample(val at: OrionObservationMoment, val reading: OrionRuntimeMainMemoryReading)

/** Final MAIN self-PSS/actual-RAM boundary. No resource policy, functional clock or cross-process cache. */
internal class OrionRuntimeMainMemoryProbe private constructor(private val source: Source) {
    constructor(context: Context) : this(Native(context.applicationContext))
    private sealed class Source { abstract fun read(clock: OrionObservationEventClock): OrionRuntimeMainMemorySample? }
    private class Native(private val app: Context) : Source() {
        override fun read(clock: OrionObservationEventClock): OrionRuntimeMainMemorySample? {
            if (Application.getProcessName() != app.packageName) return null
            val at = clock.observe() ?: return null
            val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong()
            val manager = app.getSystemService(ActivityManager::class.java) ?: return null
            val total = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
            return OrionRuntimeMainMemorySample(at, OrionRuntimeMainMemoryReading(
                if (pss > 0 && pss <= Long.MAX_VALUE / 1024) pss * 1024 else null, total.takeIf { it > 0 }))
        }
    }
    private class Finite(readings: List<OrionRuntimeMainMemoryReading?>) : Source() {
        private val readings = readings.toList()
        private var index = 0
        override fun read(clock: OrionObservationEventClock): OrionRuntimeMainMemorySample? {
            val at = clock.observe() ?: return null
            val reading = if (index < readings.size) readings[index++] else null
            return reading?.let { OrionRuntimeMainMemorySample(at, it) }
        }
    }
    fun sample(clock: OrionObservationEventClock): OrionRuntimeMainMemorySample? = try { source.read(clock) } catch (_: Throwable) { null }
    companion object {
        /** Internal finite scalar fixture, no arbitrary callback/provider. */
        internal fun finiteForTests(readings: List<OrionRuntimeMainMemoryReading?>): OrionRuntimeMainMemoryProbe {
            require(readings.size <= 7); return OrionRuntimeMainMemoryProbe(Finite(readings))
        }
    }
}

internal enum class OrionRuntimeSamplingGap { CONFIGURATION_UNAVAILABLE, WORKER_UNAVAILABLE, QUEUE_FULL, CONTENDED,
    PHASE_MISSED, PHASE_CROSSED, IDENTITY_UNAVAILABLE, IDENTITY_CHANGED, TERMINAL_UNAVAILABLE, PROCESS_DIED,
    CLOCK_UNAVAILABLE, TELEMETRY_UNAVAILABLE, MEMORY_INVALID, UNFINISHED, SEALED, RECORDING_FAILURE }

internal class OrionRuntimeAiMemoryReading(val pid: Int, val boot: Int, val sampledElapsed: Long,
    val pssBytes: Long, val nativeIncluded: Boolean, val runtimeLoaded: Boolean, val validated: Boolean,
    val state: OrionRuntimeServiceState)

/** Only the actual current private endpoint. Failure never changes Core pin/connection/lifecycle. */
internal class OrionRuntimeDiagnosticEndpoint(private val client: OrionCortexClient,
    val frame: OrionRuntimeCaptureFrame, val stamp: OrionRuntimeConnectionStamp, private val mainPackage: String,
    private val mainPid: Int) {
    fun current(): Boolean = client.runtimeDiagnosticCurrent(frame, stamp)
    fun query(ticket: OrionRuntimeSamplingTicket): OrionRuntimeAiMemoryReading? { return try {
        if (!ticket.sampler.open()) return null
        if (!current()) { frame.capture.samplingIdentityRejected(); return null }
        val service = stamp.service as? IOrionCortex ?: return null
        ticket.healthCalls.incrementAndGet()
        val receipt = service.healthCheck()
        OrionCortexWire.requireEnvelope(receipt, HEALTH_KEYS)
        require(receipt.keySet() == HEALTH_KEYS && OrionCortexWire.text(receipt, "status") == "OK")
        val pid = OrionCortexWire.int(receipt, "pid")
        if (pid <= 0 || pid == mainPid || OrionCortexWire.text(receipt, "processName", 255) != "$mainPackage:ai") {
            frame.capture.samplingIdentityRejected(); return null
        }
        val boot = OrionCortexWire.int(receipt, "bootCount")
        val at = OrionCortexWire.long(receipt, "sampledElapsed")
        val pss = OrionCortexWire.long(receipt, "totalPssBytes")
        val native = OrionCortexWire.boolean(receipt, "nativeIncluded")
        val loaded = OrionCortexWire.boolean(receipt, "nativeRuntimeLoaded")
        val validated = OrionCortexWire.boolean(receipt, "nativeValidationCompleted")
        require(boot >= 0 && at >= 0 && pss > 0 && native && (!validated || loaded))
        if (!ticket.sampler.open()) return null
        if (!frame.capture.samplingIdentity(this, pid, boot)) return null
        ticket.statusCalls.incrementAndGet()
        val status = service.getStatus()
        OrionCortexWire.requireEnvelope(status, setOf("schemaVersion", "status", "modelId"))
        val state = when (OrionCortexWire.text(status, "status")) {
            "UNLOADED" -> OrionRuntimeServiceState.UNLOADED
            "LOADING" -> OrionRuntimeServiceState.LOADING
            "LOADED" -> OrionRuntimeServiceState.LOADED
            "INFERENCING" -> OrionRuntimeServiceState.INFERENCING
            "UNLOADING" -> OrionRuntimeServiceState.UNLOADING
            else -> return null
        }
        if ((state == OrionRuntimeServiceState.LOADED || state == OrionRuntimeServiceState.INFERENCING) &&
            !frame.capture.samplingModelMatches(OrionCortexWire.text(status, "modelId"))) {
            frame.capture.samplingIdentityRejected(); return null
        }
        if (!frame.capture.samplingIdentity(this, pid, boot)) null else
            OrionRuntimeAiMemoryReading(pid, boot, at, pss, native, loaded, validated, state)
    } catch (_: Throwable) { null } }
    companion object {
        private val HEALTH_KEYS = setOf("schemaVersion", "status", "processName", "pid", "bootCount", "sampledElapsed",
            "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")
    }
}

/** Immutable actual boundary state. Version changes also detect transitions within the same millisecond. */
internal data class OrionRuntimeSamplingTimeline(val version: Long = 0, val loadStart: OrionRuntimeEventTime? = null,
    val loadEnd: OrionRuntimeEventTime? = null, val loadSucceeded: Boolean = false,
    val inferenceStart: OrionRuntimeEventTime? = null, val terminal: OrionLifecycleTerminalEvent? = null,
    val terminalBeforeDispatch: Boolean = false,
    val unloadStart: OrionRuntimeEventTime? = null, val unloadEnd: OrionRuntimeEventTime? = null,
    val end: OrionRuntimeEventTime? = null) {
    fun phase(): OrionRuntimeMemoryPhase? = when {
        terminal?.status?.terminal == OrionInferenceTerminal.PROCESS_DIED -> null
        end != null -> null
        unloadEnd != null -> OrionRuntimeMemoryPhase.AFTER_UNLOAD
        unloadStart != null -> OrionRuntimeMemoryPhase.DURING_UNLOAD
        terminal != null && inferenceStart != null && !terminalBeforeDispatch -> OrionRuntimeMemoryPhase.AFTER_TERMINAL
        inferenceStart != null && terminal == null -> OrionRuntimeMemoryPhase.DURING_INFERENCE
        loadEnd != null -> if (loadSucceeded) OrionRuntimeMemoryPhase.AFTER_LOAD else null
        loadStart != null -> OrionRuntimeMemoryPhase.DURING_LOAD
        else -> OrionRuntimeMemoryPhase.BEFORE_LOAD
    }
    fun window(phase: OrionRuntimeMemoryPhase, start: OrionRuntimeEventTime): OrionRuntimePhaseWindow? {
        val terminalAt = terminal?.at?.let(OrionRuntimeEventTime::fromObservation)
        val bounds = when (phase) {
            OrionRuntimeMemoryPhase.BEFORE_LOAD -> start to loadStart
            OrionRuntimeMemoryPhase.DURING_LOAD -> loadStart to loadEnd
            OrionRuntimeMemoryPhase.AFTER_LOAD -> if (loadSucceeded) loadEnd to (inferenceStart ?: unloadStart ?: end) else null to null
            OrionRuntimeMemoryPhase.DURING_INFERENCE -> inferenceStart to terminalAt
            OrionRuntimeMemoryPhase.AFTER_TERMINAL -> if (inferenceStart != null && !terminalBeforeDispatch)
                terminalAt to (unloadStart ?: end) else null to null
            OrionRuntimeMemoryPhase.DURING_UNLOAD -> unloadStart to unloadEnd
            OrionRuntimeMemoryPhase.AFTER_UNLOAD -> unloadEnd to end
        }
        return if (bounds.first == null || bounds.second == null) null else OrionRuntimePhaseWindow(phase,
            checkNotNull(bounds.first), checkNotNull(bounds.second))
    }
}

internal class OrionRuntimeSamplingTicket(val sampler: OrionRuntimeMemorySampler, val phase: OrionRuntimeMemoryPhase) {
    val healthCalls = AtomicLong()
    val statusCalls = AtomicLong()
    @Volatile var started = false
    @Volatile var done = false
}

internal class OrionRuntimeCollectedMemory(val phase: OrionRuntimeMemoryPhase, val version: Long,
    val start: OrionObservationMoment, val end: OrionObservationMoment, val mainPid: Int,
    val main: OrionRuntimeMainMemoryReading, val ai: OrionRuntimeAiMemoryReading)

internal class OrionRuntimeMemorySamplingReport(val attemptedMask: Int, val startedMask: Int, val completedMask: Int,
    val retainedRows: Int, val diagnosticHealthCalls: Long, val diagnosticStateCalls: Long,
    val gaps: Set<OrionRuntimeSamplingGap>, val unfinished: Boolean, val droppedAtLeast: Long,
    val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean,
    val maximumSampleAgeMillis: Long) {
    val qualification = "NOT_QUALIFIED"
    val sourceAuthenticated = false
    val physicalVerified = false
    val profileApproved = false
    val productionInferenceApproved = false
    val runtimeEvidenceV1Complete = false
    val fullF5GateClosed = false
    val fullSoakGateClosed = false
    val f6Authorized = false
}

/** Exactly one daemon and RPC lane in this process. No cancellation/replacement, even after a stuck call. */
internal enum class OrionRuntimeSamplingOffer { QUEUED, WORKER_UNAVAILABLE, CONTENDED, QUEUE_FULL, RECORDING_FAILURE }
internal object OrionRuntimeMemorySamplingWorker {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<OrionRuntimeSamplingTicket>(7)
    private val prepared = AtomicBoolean()
    private val manualDiagnostic = AtomicBoolean()
    @Volatile private var thread: Thread? = null
    @Volatile private var failed = false
    @Volatile private var active: OrionRuntimeSamplingTicket? = null
    fun prepare(): Boolean {
        if (!prepared.compareAndSet(false, true)) return !failed && thread != null
        return try {
            val worker = Thread({
                while (true) {
                    val ticket = run {
                        lock.lock()
                        try { if (manualDiagnostic.get() || queue.isEmpty()) null else queue.removeFirst().also { active = it } }
                        finally { lock.unlock() }
                    }
                    if (ticket == null) LockSupport.park()
                    else {
                        try { ticket.sampler.collect(ticket) } catch (_: Throwable) { ticket.sampler.failed() }
                        finally { ticket.done = true; active = null }
                    }
                }
            }, "orion_runtime_memory_sampler").apply { isDaemon = true }
            thread = worker; worker.start(); true
        } catch (_: Throwable) { failed = true; false }
    }
    fun offer(ticket: OrionRuntimeSamplingTicket): OrionRuntimeSamplingOffer {
        if (failed || thread == null) return OrionRuntimeSamplingOffer.WORKER_UNAVAILABLE
        if (!lock.tryLock()) return OrionRuntimeSamplingOffer.CONTENDED
        return try {
            if (manualDiagnostic.get()) OrionRuntimeSamplingOffer.CONTENDED
            else if (queue.size >= 7) OrionRuntimeSamplingOffer.QUEUE_FULL
            else { queue.addLast(ticket); LockSupport.unpark(thread); OrionRuntimeSamplingOffer.QUEUED }
        } catch (_: Throwable) { failed = true; OrionRuntimeSamplingOffer.RECORDING_FAILURE }
        finally { lock.unlock() }
    }
    /** Same single diagnostic lane for explicit idle checkpoints; no worker creation or waiting. */
    fun tryManualDiagnostic(): Boolean {
        if (failed) return false
        if (!lock.tryLock()) return false
        return try { active == null && queue.isEmpty() && manualDiagnostic.compareAndSet(false, true) }
        finally { lock.unlock() }
    }
    fun finishManualDiagnostic() {
        manualDiagnostic.set(false)
        LockSupport.unpark(thread)
    }
    fun outstanding(): Boolean = manualDiagnostic.get() || active != null || !lock.tryLock().let { acquired ->
        if (!acquired) false else try { queue.isEmpty() } finally { lock.unlock() }
    }
}

/** Finite phase-triggered sampler. All hotpath methods use scalar state/tryLock and never await this worker. */
internal class OrionRuntimeMemorySampler(private val capture: OrionCortexRuntimeCaptureSession,
    private val configuration: OrionRuntimeSamplingConfiguration, private val clock: OrionObservationEventClock,
    private val probe: OrionRuntimeMainMemoryProbe, private val start: OrionObservationMoment, private val mainPid: Int) {
    private val lock = ReentrantLock()
    private val gapBits = AtomicLong()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)
    private val sealed = AtomicBoolean()
    private val tickets = arrayOfNulls<OrionRuntimeSamplingTicket>(7)
    private val rows = arrayOfNulls<OrionRuntimeCollectedMemory>(7)
    @Volatile private var timeline = OrionRuntimeSamplingTimeline()
    @Volatile private var timelineLost = false
    private var mask = 0

    fun open(): Boolean = !sealed.get()
    private fun count(counter: AtomicLong) {
        val before = counter.get()
        if (before == Long.MAX_VALUE || !counter.compareAndSet(before, before + 1)) accounting.set(false)
    }
    fun failed() { count(failures); gap(OrionRuntimeSamplingGap.RECORDING_FAILURE) }
    private fun gap(value: OrionRuntimeSamplingGap) {
        val before = gapBits.get()
        if (!gapBits.compareAndSet(before, before or (1L shl value.ordinal))) { accounting.set(false); timelineLost = true }
    }
    private inline fun <T> access(operation: () -> T): T? {
        if (!lock.tryLock()) { gap(OrionRuntimeSamplingGap.CONTENDED); timelineLost = true; return null }
        return try { operation() } catch (_: Throwable) { failed(); null } finally { lock.unlock() }
    }
    fun boundary(kind: OrionRuntimeMarkerKind, at: OrionRuntimeEventTime, succeeded: Boolean = false) {
        access {
            if (sealed.get()) return@access
            if (at.bootCount != start.observationalBootCount || at.elapsedMillis == null || at.elapsedMillis < start.elapsedRealtimeMillis) {
                timelineLost = true; gap(OrionRuntimeSamplingGap.CLOCK_UNAVAILABLE); return@access
            }
            val old = timeline
            if (old.version == Long.MAX_VALUE) { timelineLost = true; failed(); return@access }
            timeline = when (kind) {
                OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START -> when {
                    old.loadStart == null -> old.copy(version = old.version + 1, loadStart = at)
                    old.loadStart == at -> old
                    else -> { timelineLost = true; gap(OrionRuntimeSamplingGap.PHASE_CROSSED); old }
                }
                OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL -> old.copy(version = old.version + 1, loadEnd = at, loadSucceeded = succeeded)
                OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH -> old.copy(version = old.version + 1, inferenceStart = at)
                OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH -> old.copy(version = old.version + 1, unloadStart = at)
                OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP -> old.copy(version = old.version + 1, unloadEnd = at)
                else -> old
            }
        }
        hintCurrent()
    }
    fun terminalHint(offer: Boolean = true) {
        try {
        val state = capture.lifecycleSource.terminalView() ?: run {
            gap(OrionRuntimeSamplingGap.TERMINAL_UNAVAILABLE); timelineLost = true; return
        }
        if (!state.active || state.invalidated || state.unavailable || state.transitionClaimed && state.firstTerminal == null) {
            gap(OrionRuntimeSamplingGap.TERMINAL_UNAVAILABLE); timelineLost = true; return
        }
        state.firstTerminal?.let { terminal ->
            val at = terminal.at
            if (at == null || !start.sameObservationAs(at) || at.observationalBootCount != start.observationalBootCount) {
                gap(OrionRuntimeSamplingGap.CLOCK_UNAVAILABLE); timelineLost = true; return
            }
            access {
                if (!sealed.get() && timeline.terminal == null) {
                    if (timeline.version == Long.MAX_VALUE) { timelineLost = true; return@access }
                    timeline = timeline.copy(version = timeline.version + 1, terminal = terminal,
                        terminalBeforeDispatch = timeline.inferenceStart == null)
                }
            }
        }
        if (offer) hintCurrent()
        } catch (_: Throwable) { timelineLost = true; failed() }
    }
    fun hintCurrent() {
        access {
            if (sealed.get()) return@access
            val phase = timeline.phase() ?: return@access
            val bit = 1 shl phase.ordinal
            if (mask and bit != 0) return@access
            mask = mask or bit
            val ticket = OrionRuntimeSamplingTicket(this, phase)
            tickets[phase.ordinal] = ticket
            val offered = OrionRuntimeMemorySamplingWorker.offer(ticket)
            if (offered != OrionRuntimeSamplingOffer.QUEUED) {
                ticket.done = true
                when (offered) {
                    OrionRuntimeSamplingOffer.WORKER_UNAVAILABLE -> gap(OrionRuntimeSamplingGap.WORKER_UNAVAILABLE)
                    OrionRuntimeSamplingOffer.CONTENDED -> gap(OrionRuntimeSamplingGap.CONTENDED)
                    OrionRuntimeSamplingOffer.QUEUE_FULL -> gap(OrionRuntimeSamplingGap.QUEUE_FULL)
                    OrionRuntimeSamplingOffer.RECORDING_FAILURE -> failed()
                    OrionRuntimeSamplingOffer.QUEUED -> Unit
                }
            }
        }
    }
    fun collect(ticket: OrionRuntimeSamplingTicket) {
        if (sealed.get()) { gap(OrionRuntimeSamplingGap.SEALED); return }
        terminalHint()
        val before = timeline
        if (timelineLost || before.phase() != ticket.phase) { gap(OrionRuntimeSamplingGap.PHASE_MISSED); return }
        val endpoint = capture.samplingEndpoint() ?: run { gap(OrionRuntimeSamplingGap.IDENTITY_UNAVAILABLE); return }
        if (!endpoint.current()) { capture.samplingIdentityRejected(); gap(OrionRuntimeSamplingGap.IDENTITY_CHANGED); return }
        ticket.started = true
        // v1: start is exactly the MAIN collection-start instant, not a preceding worker timestamp.
        val mainSample = probe.sample(clock) ?: run { gap(OrionRuntimeSamplingGap.TELEMETRY_UNAVAILABLE); return }
        val at = mainSample.at
        if (!start.sameObservationAs(at)) { gap(OrionRuntimeSamplingGap.CLOCK_UNAVAILABLE); return }
        val main = mainSample.reading
        val ai = endpoint.query(ticket) ?: run { gap(OrionRuntimeSamplingGap.TELEMETRY_UNAVAILABLE); return }
        val end = clock.observe() ?: run { gap(OrionRuntimeSamplingGap.CLOCK_UNAVAILABLE); return }
        terminalHint()
        if (!capture.samplingIdentity(endpoint, ai.pid, ai.boot)) {
            gap(OrionRuntimeSamplingGap.IDENTITY_CHANGED); return
        }
        if (sealed.get() || timelineLost || before.version != timeline.version || before.phase() != timeline.phase()) {
            gap(OrionRuntimeSamplingGap.PHASE_CROSSED); return
        }
        val candidate = OrionRuntimeCollectedMemory(ticket.phase, before.version, at, end, mainPid, main, ai)
        if (!validMemory(candidate, configuration.maximumSampleAgeMillis, start)) { gap(OrionRuntimeSamplingGap.MEMORY_INVALID); return }
        access { if (!sealed.get()) rows[ticket.phase.ordinal] = candidate else gap(OrionRuntimeSamplingGap.SEALED) }
    }
    /** One bounded manual close. Does not await/join/interrupt a blocked RPC or imply it terminated. */
    fun seal(end: OrionObservationMoment?, destination: OrionRuntimeEventRecorder?, fence: OrionRuntimeCaptureFence?): OrionRuntimeMemorySamplingReport? {
        if (!sealed.compareAndSet(false, true)) return null
        return access {
        if (end != null) timeline = timeline.copy(version = timeline.version + 1, end = OrionRuntimeEventTime.fromObservation(end))
        var kept = 0
        val current = timeline
        for (phase in OrionRuntimeMemoryPhase.entries) {
            val ticket = tickets[phase.ordinal]
            if (ticket == null) { count(dropped); gap(OrionRuntimeSamplingGap.PHASE_MISSED); continue }
            if (!ticket.done) gap(OrionRuntimeSamplingGap.UNFINISHED)
            val row = rows[phase.ordinal]
            if (row == null) { count(dropped); continue }
            val window = current.window(phase, OrionRuntimeEventTime.fromObservation(start))
            if (timelineLost || window == null || end == null || !validWindow(row, window, current)) {
                count(dropped); gap(OrionRuntimeSamplingGap.PHASE_CROSSED); continue
            }
            val pss = row.main.pssBytes
            if (pss == null) { count(dropped); continue }
            val sum = pss + row.ai.pssBytes
            val mainSampleTime = OrionRuntimeEventTime.fromObservation(row.start)
            val value = OrionRuntimeMemoryRow(window, mainSampleTime,
                OrionRuntimeEventTime.fromObservation(row.end), mainSampleTime,
                OrionRuntimeEventTime(row.ai.boot, row.ai.sampledElapsed), row.mainPid, row.ai.pid, pss, row.ai.pssBytes, sum,
                row.main.totalMemoryBytes, true, row.ai.nativeIncluded, row.ai.state, row.ai.runtimeLoaded, row.ai.validated,
                OrionRuntimeMemorySource.SELF_DEBUG_MEMORY_INFO_PSS)
            if (destination != null && fence != null && destination.memoryRow(fence, value)) kept++
            else { count(dropped); gap(OrionRuntimeSamplingGap.MEMORY_INVALID) }
        }
        var offered = 0; var started = 0; var completed = 0; var health = 0L; var status = 0L
        for (index in tickets.indices) tickets[index]?.let {
            offered = offered or (1 shl index)
            if (it.started) started = started or (1 shl index)
            if (it.done) completed = completed or (1 shl index)
            health += it.healthCalls.get(); status += it.statusCalls.get()
        }
        val reasons = EnumSet.noneOf(OrionRuntimeSamplingGap::class.java)
        val bits = gapBits.get()
        OrionRuntimeSamplingGap.entries.forEach { if (bits and (1L shl it.ordinal) != 0L) reasons.add(it) }
        OrionRuntimeMemorySamplingReport(offered, started, completed, kept, health, status, Collections.unmodifiableSet(reasons),
            tickets.any { it != null && !it.done }, dropped.get(), failures.get(), accounting.get(), configuration.maximumSampleAgeMillis)
    } }

    companion object {
        internal fun validMemory(row: OrionRuntimeCollectedMemory, maximumAge: Long, scope: OrionObservationMoment): Boolean {
            val main = row.main.pssBytes; val ai = row.ai.pssBytes; val total = row.main.totalMemoryBytes
            return maximumAge in 1..1000 && scope.sameObservationAs(row.start) && scope.sameObservationAs(row.end) &&
                scope.elapsedRealtimeMillis <= row.start.elapsedRealtimeMillis &&
                row.start.observationalBootCount == row.end.observationalBootCount && row.ai.boot == row.start.observationalBootCount &&
                row.mainPid > 0 && row.ai.pid > 0 && row.mainPid != row.ai.pid &&
                row.start.elapsedRealtimeMillis <= row.ai.sampledElapsed && row.ai.sampledElapsed <= row.end.elapsedRealtimeMillis &&
                row.end.elapsedRealtimeMillis - row.start.elapsedRealtimeMillis < maximumAge &&
                main != null && main > 0 && ai > 0 && main <= Long.MAX_VALUE - ai && total != null && main + ai <= total &&
                row.ai.nativeIncluded && (!row.ai.validated || row.ai.runtimeLoaded) &&
                (row.ai.state != OrionRuntimeServiceState.LOADED && row.ai.state != OrionRuntimeServiceState.INFERENCING ||
                    row.ai.runtimeLoaded && row.ai.validated)
        }
        internal fun validWindow(row: OrionRuntimeCollectedMemory, window: OrionRuntimePhaseWindow, timeline: OrionRuntimeSamplingTimeline): Boolean {
            val begin = window.started.elapsedMillis; val end = window.completed.elapsedMillis
            return begin != null && end != null && window.started.bootCount == row.ai.boot && window.completed.bootCount == row.ai.boot &&
                begin <= row.start.elapsedRealtimeMillis && row.end.elapsedRealtimeMillis <= end &&
                !(timeline.terminal?.status?.terminal == OrionInferenceTerminal.PROCESS_DIED &&
                    (row.phase.ordinal >= OrionRuntimeMemoryPhase.AFTER_TERMINAL.ordinal ||
                        row.end.elapsedRealtimeMillis > (timeline.terminal?.at?.elapsedRealtimeMillis ?: -1))) &&
                when (row.phase) {
                    OrionRuntimeMemoryPhase.BEFORE_LOAD, OrionRuntimeMemoryPhase.AFTER_UNLOAD -> row.ai.state == OrionRuntimeServiceState.UNLOADED
                    OrionRuntimeMemoryPhase.DURING_LOAD -> row.ai.state == OrionRuntimeServiceState.LOADING || row.ai.state == OrionRuntimeServiceState.LOADED
                    OrionRuntimeMemoryPhase.AFTER_LOAD, OrionRuntimeMemoryPhase.AFTER_TERMINAL -> row.ai.state == OrionRuntimeServiceState.LOADED
                    OrionRuntimeMemoryPhase.DURING_INFERENCE -> row.ai.state == OrionRuntimeServiceState.INFERENCING || row.ai.state == OrionRuntimeServiceState.LOADED
                    OrionRuntimeMemoryPhase.DURING_UNLOAD -> row.ai.state == OrionRuntimeServiceState.UNLOADING || row.ai.state == OrionRuntimeServiceState.UNLOADED
                }
        }
    }
}

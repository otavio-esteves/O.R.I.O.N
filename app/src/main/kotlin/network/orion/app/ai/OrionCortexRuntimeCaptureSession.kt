package network.orion.app.ai

import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.*
import network.orion.core.time.*

internal enum class OrionRuntimeSessionGap { SOURCE_UNAVAILABLE, IDENTITY_UNAVAILABLE, IDENTITY_CHANGED,
    ACTIVATION_UNAVAILABLE, REUSED, RETAINED_OR_JOINED, CLOCK_UNAVAILABLE, CAPACITY, CONTENDED,
    RECORDING_FAILURE, LOAD_TERMINAL_UNAVAILABLE, UNLOAD_UNCONFIRMED, SAMPLING_UNAVAILABLE }

/** Published only while the original Client path already owns its monitor. No callbacks or queries. */
internal class OrionRuntimeConnectionStamp(val service: Any, val connection: Any, val binder: Any, val epoch: Long)
internal class OrionRuntimeCaptureFrame(val capture: OrionCortexRuntimeCaptureSession, val client: Any, val turn: Long) {
    @Volatile var valid = true
    @Volatile var ended = false
    @Volatile var connection: OrionRuntimeConnectionStamp? = null
    @Volatile var pid: Int? = null
    @Volatile var resourceThread: Thread? = null
}
internal class OrionRuntimeHealthWindow(val frame: OrionRuntimeCaptureFrame, val connection: OrionRuntimeConnectionStamp)

internal class OrionRuntimeSessionReceipt(val originalOwnerEntered: Boolean?, val actualTurnEntered: Boolean?,
    val identityKnown: Boolean, val invalidated: Boolean, val returned: Boolean, val threw: Boolean,
    val loadDispatchObserved: Boolean, val loadReplyAccepted: Boolean?,
    val pendingUnassociatedMarkers: List<OrionRuntimeOperationMarker>, val facts: OrionRuntimeCaptureSnapshot?,
    val lifecycle: OrionRuntimeLifecycleImportSummary?, val gaps: Set<OrionRuntimeSessionGap>,
    val droppedAtLeast: Long, val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean,
    val memorySampling: OrionRuntimeMemorySamplingReport? = null) {
    val runtimeEvidenceV1Complete = false
    val sourceAuthenticated = false
    val physicalVerified = false
    val profileApproved = false
    val productionInferenceApproved = false
    val fullF5GateClosed = false
    val fullSoakGateClosed = false
    val f6Authorized = false
    override fun toString() = "OrionRuntimeSessionReceipt(identityKnown=$identityKnown, qualified=false)"
}

/** One call's final optional source. It never retains request/model entities, content, paths or raw errors. */
internal class OrionCortexRuntimeCaptureSession private constructor(private val owner: Any, private val mainPid: Int,
    private val observationClock: OrionObservationEventClock?, val lifecycleSource: OrionInferenceLifecycleEventSource,
    private val samplingConfiguration: OrionRuntimeSamplingConfiguration? = null,
    private val memoryProbe: OrionRuntimeMainMemoryProbe? = null, private val workerPrepared: Boolean = false) {
    private val lock = ReentrantLock()
    private val token = Any()
    private val claimed = AtomicBoolean()
    private val invalidated = AtomicBoolean()
    private val readClaimed = AtomicBoolean()
    private val gapBits = AtomicLong()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)
    private val pending = ArrayList<OrionRuntimeOperationMarker>(OrionRuntimeEventRecorder.MAX_MARKERS)
    private var model: OrionRuntimeModelReference? = null
    private var logicalId: OrionRuntimeTechnicalId? = null
    private var preparation: MonotonicInstant? = null
    private var deadlineElapsed: Long? = null
    private var maximumAge: Long? = null
    private var candidateContext: Int? = null
    private var candidateThreads: Int? = null
    private var candidateMaxTokens: Int? = null
    private var originalInventory: List<String>? = null
    private var originalWireTemperature: Float? = null
    private var client: Any? = null
    private var start: OrionObservationMoment? = null
    private var fence: OrionRuntimeCaptureFence? = null
    private var pinnedConnection: OrionRuntimeConnectionStamp? = null
    private var recorder: OrionRuntimeEventRecorder? = null
    @Volatile private var frame: OrionRuntimeCaptureFrame? = null
    @Volatile private var ownerEntered = false
    @Volatile private var turnObserved = false
    @Volatile private var activated = false
    @Volatile private var runFinished = false
    @Volatile private var didThrow = false
    @Volatile private var retainedKnown = false
    @Volatile private var cached: OrionRuntimeSessionReceipt? = null
    private var loadIssued = false
    private var loadDispatched = false
    private var loadTerminal = false
    private var unloadIssued = false
    private var unloadConfirmed = false
    @Volatile private var memorySampler: OrionRuntimeMemorySampler? = null
    private var exported: OrionRuntimeExportSnapshot? = null

    private fun count(counter: AtomicLong) {
        val before = counter.get()
        if (before == Long.MAX_VALUE || !counter.compareAndSet(before, before + 1)) accounting.set(false)
    }
    private fun gap(value: OrionRuntimeSessionGap, fatal: Boolean = false) {
        val before = gapBits.get()
        if (!gapBits.compareAndSet(before, before or (1L shl value.ordinal))) accounting.set(false)
        count(dropped)
        if (fatal) invalidated.set(true)
    }
    private fun failed() { count(failures); accounting.set(false); gap(OrionRuntimeSessionGap.RECORDING_FAILURE, true) }
    private inline fun <T> isolate(operation: () -> T): T? = try { operation() } catch (_: Throwable) { failed(); null }
    private inline fun <T> access(operation: () -> T): T? = isolate {
        if (!lock.tryLock()) { gap(OrionRuntimeSessionGap.CONTENDED, true); return@isolate null }
        try { operation() } finally { lock.unlock() }
    }

    fun claim(actualOwner: Any): Boolean = isolate {
        if (actualOwner !== owner || !claimed.compareAndSet(false, true)) {
            gap(OrionRuntimeSessionGap.REUSED, true); false
        } else true
    } ?: false

    /** Called after the original READY/profile/openReadOnly checks, without retaining their objects. */
    fun selected(actualOwner: Any, sha256: String, size: Long, id: String, deadline: MonotonicDeadline, maximumAge: Long?,
        contextTokens: Int? = null, threads: Int? = null, maxTokens: Int? = null, inventory: Set<String>? = null) {
        access {
            if (actualOwner !== owner || !claimed.get() || invalidated.get() || model != null) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); return@access
            }
            model = OrionRuntimeModelReference(sha256, size)
            logicalId = OrionRuntimeTechnicalId(id)
            preparation = deadline.start; deadlineElapsed = deadline.expiresElapsedRealtimeMillis
            this.maximumAge = samplingConfiguration?.maximumSampleAgeMillis ?: maximumAge
            candidateContext = contextTokens; candidateThreads = threads; candidateMaxTokens = maxTokens
            originalInventory = inventory?.let { Collections.unmodifiableList(ArrayList(it)) }
        }
    }

    fun forClient(actualClient: Any, id: String, sha: String, size: Long): OrionCortexRuntimeCaptureSession? = access {
        if (invalidated.get() || model?.sha256 != sha || model?.sizeBytes != size || logicalId?.matchesRaw(id) != true ||
            client != null && client !== actualClient) {
            gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); null
        } else { client = actualClient; this }
    }

    /** Exactly the winning Client ledger operation; constructors/replays/joins never execute this. */
    fun runOnceEntered(lifecycle: OrionInferenceLifecycle) {
        val alreadyEntered = ownerEntered
        ownerEntered = true // Only the actual ledger operation calls this; retain that fact even on diagnostic failure.
        access {
            if (invalidated.get() || alreadyEntered) { gap(OrionRuntimeSessionGap.REUSED, true); return@access }
            activated = lifecycle.activateEventCapture() // The sole binder; no original Clock read.
            if (!activated) { gap(OrionRuntimeSessionGap.ACTIVATION_UNAVAILABLE, true); return@access }
            start = observationClock?.observe()
            if (start == null) gap(OrionRuntimeSessionGap.CLOCK_UNAVAILABLE, true)
            val config = samplingConfiguration; val probe = memoryProbe; val observed = start; val clock = observationClock
            if (config != null && probe != null && observed != null && clock != null && workerPrepared)
                memorySampler = OrionRuntimeMemorySampler(this, config, clock, probe, observed, mainPid)
            else if (config != null) gap(OrionRuntimeSessionGap.SAMPLING_UNAVAILABLE)
        }
    }

    /** Called only under the existing Client turn-entry monitor. */
    fun turnEntered(actualClient: Any, generation: Long?): OrionRuntimeCaptureFrame? {
        turnObserved = true
        return access {
            if (invalidated.get() || !activated || client !== actualClient || frame != null || generation == null || generation <= 0) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); null
            } else OrionRuntimeCaptureFrame(this, actualClient, generation).also { frame = it }
        }
    }

    /** Consumes an already-validated original resource telemetry value; never obtains/validates it again. */
    fun originalMemoryWitness(window: OrionRuntimeHealthWindow, pid: Int, at: MonotonicInstant) {
        access {
            val current = frame
            val boot = at.bootSessionId
            if (invalidated.get()) return@access
            if (readClaimed.get()) { gap(OrionRuntimeSessionGap.REUSED, true); return@access }
            if (current == null || current !== window.frame || !current.valid || current.connection !== window.connection || current.pid != pid ||
                current.resourceThread !== Thread.currentThread() || boot == null ||
                boot != preparation?.bootSessionId || at.elapsedRealtimeMillis < checkNotNull(preparation).elapsedRealtimeMillis) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); return@access
            }
            if (fence != null) {
                if (pinnedConnection !== window.connection || fence?.aiPid != pid || fence?.bootCount != boot.bootCount)
                    gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true)
                return@access
            }
            associate(current, window.connection, pid, boot.bootCount)
        }
    }

    private fun associate(current: OrionRuntimeCaptureFrame, connection: OrionRuntimeConnectionStamp, pid: Int, boot: Int) {
        val selected = OrionRuntimeCaptureFence(owner, token, current.client, connection.connection, connection.binder,
            connection.epoch, current.turn, mainPid, pid, boot, checkNotNull(model), checkNotNull(logicalId))
        if (!selected.known()) { gap(OrionRuntimeSessionGap.IDENTITY_UNAVAILABLE, true); return }
        val destination = OrionRuntimeEventRecorder(selected, deadlineElapsed, maximumAge, lifecycleSource)
        if (!destination.sessionStarted(selected, OrionRuntimeEventTime.fromObservation(checkNotNull(start)))) {
            gap(OrionRuntimeSessionGap.SOURCE_UNAVAILABLE, true); return
        }
        fence = selected; pinnedConnection = connection; recorder = destination
        for (marker in pending) if (!destination.trueOperation(selected, marker)) gap(OrionRuntimeSessionGap.SOURCE_UNAVAILABLE, true)
        pending.clear()
    }

    fun endpointPublished(selected: OrionRuntimeCaptureFrame) {
        if (frame === selected && selected.valid && selected.connection != null) memorySampler?.hintCurrent()
    }
    fun terminalHint() { memorySampler?.terminalHint() }
    fun loadPreparing(selected: OrionRuntimeCaptureFrame, service: Any, originalStart: MonotonicInstant) {
        if (memorySampler == null) return
        access {
            if (current(selected, service) && originalStart.bootSessionId == preparation?.bootSessionId)
                memorySampler?.boundary(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START,
                    OrionRuntimeEventTime(originalStart.bootSessionId?.bootCount, originalStart.elapsedRealtimeMillis))
        }
    }
    fun samplingModelMatches(id: String): Boolean = model?.sha256 == id
    fun samplingIdentityRejected() { gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true) }
    fun samplingEndpoint(): OrionRuntimeDiagnosticEndpoint? {
        val selected = frame ?: return null
        if (invalidated.get() || readClaimed.get() || !selected.valid) return null
        return (selected.client as? OrionCortexClient)?.runtimeDiagnosticEndpoint(selected)
    }
    /** PID comes only from the same private endpoint's real diagnostic health receipt. Never writes Core pin. */
    fun samplingIdentity(endpoint: OrionRuntimeDiagnosticEndpoint, pid: Int, boot: Int): Boolean = access {
        val selected = frame
        if (invalidated.get() || readClaimed.get()) return@access false
        if (selected !== endpoint.frame || selected == null || !endpoint.current() || pid <= 0 || pid == mainPid ||
            boot != preparation?.bootSessionId?.bootCount || selected.pid != null && selected.pid != pid) {
            samplingIdentityRejected(); return@access false
        }
        val known = fence
        if (known == null) associate(selected, endpoint.stamp, pid, boot)
        else if (known.aiPid != pid || known.bootCount != boot || pinnedConnection !== endpoint.stamp) {
            gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); return@access false
        }
        if (!endpoint.current()) { samplingIdentityRejected(); return@access false }
        fence != null && !invalidated.get()
    } ?: false

    private fun current(selected: OrionRuntimeCaptureFrame, service: Any): Boolean {
        if (invalidated.get() || readClaimed.get()) return false
        if (frame !== selected || !selected.valid || selected.client !== client || selected.connection?.service !== service ||
            pinnedConnection != null && selected.connection !== pinnedConnection) {
            gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); return false
        }
        return true
    }
    private fun moment(): OrionObservationMoment? = observationClock?.observe().also {
        if (it == null) gap(OrionRuntimeSessionGap.CLOCK_UNAVAILABLE, true)
    }
    private fun operation(marker: OrionRuntimeOperationMarker) {
        val destination = recorder; val association = fence
        if (destination != null && association != null) {
            if (!destination.trueOperation(association, marker)) gap(OrionRuntimeSessionGap.SOURCE_UNAVAILABLE, true)
        }
        else if (pending.size < OrionRuntimeEventRecorder.MAX_MARKERS) pending.add(marker)
        else gap(OrionRuntimeSessionGap.CAPACITY, true)
        memorySampler?.boundary(marker.kind, marker.at,
            marker.status?.terminal == OrionInferenceTerminal.SUCCEEDED && marker.loadedModelConfirmed == true)
    }

    /** Original local load-start value is retained only after the original call/reply proves issuance. */
    fun loadIssued(selected: OrionRuntimeCaptureFrame, service: Any, id: String, originalStart: MonotonicInstant) {
        access {
            if (!current(selected, service)) return@access
            val boot = originalStart.bootSessionId
            if (boot == null || boot != preparation?.bootSessionId) {
                gap(OrionRuntimeSessionGap.CLOCK_UNAVAILABLE, true); return@access
            }
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START,
                OrionRuntimeEventTime(boot.bootCount, originalStart.elapsedRealtimeMillis), OrionRuntimeTechnicalId(id)))
            loadIssued = true
        }
    }
    /** Actual MAIN dispatch-attempt point, distinct from local request construction and ACCEPTED. */
    fun loadDispatch(selected: OrionRuntimeCaptureFrame, service: Any, id: String) {
        access {
            if (!current(selected, service)) return@access
            val at = moment() ?: return@access
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH,
                OrionRuntimeEventTime.fromObservation(at), OrionRuntimeTechnicalId(id)))
            loadDispatched = true
        }
    }
    fun loadTerminal(selected: OrionRuntimeCaptureFrame, service: Any, id: String, terminal: OrionInferenceTerminal, loaded: Boolean) {
        access {
            if (!current(selected, service)) return@access
            val at = moment() ?: return@access
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, OrionRuntimeEventTime.fromObservation(at),
                OrionRuntimeTechnicalId(id), OrionInferenceStatus(OrionInferenceStage.TERMINAL, terminal, null), loadedModelConfirmed = loaded))
            loadTerminal = true
        }
    }
    /** Reuses the Float value of the original putFloat expression; no conversion/read of request here. */
    fun wireTemperature(selected: OrionRuntimeCaptureFrame, service: Any, value: Float) {
        access {
            if (!current(selected, service)) return@access
            if (originalWireTemperature != null) { gap(OrionRuntimeSessionGap.REUSED, true); return@access }
            originalWireTemperature = value
        }
    }
    fun inferenceDispatch(selected: OrionRuntimeCaptureFrame, service: Any, id: String, inputBytes: Long) {
        memorySampler?.terminalHint(false) // A terminal already won before this dispatch cannot create an inference window.
        access {
            if (!current(selected, service)) return@access
            val at = moment() ?: return@access
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, OrionRuntimeEventTime.fromObservation(at),
                OrionRuntimeTechnicalId(id), inputBytes = inputBytes))
        }
    }
    fun unloadDispatch(selected: OrionRuntimeCaptureFrame, service: Any) {
        terminalHint()
        access {
            if (!current(selected, service)) return@access
            val at = moment() ?: return@access
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, OrionRuntimeEventTime.fromObservation(at)))
            unloadIssued = true
        }
    }
    fun unloadCompleted(selected: OrionRuntimeCaptureFrame, service: Any, originalDeadline: MonotonicDeadline, strictReply: Boolean) {
        access {
            if (!current(selected, service)) return@access
            if (!strictReply) { gap(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED); return@access }
            val at = moment() ?: return@access
            if (at.observationalBootCount != originalDeadline.start.bootSessionId?.bootCount ||
                at.elapsedRealtimeMillis < originalDeadline.start.elapsedRealtimeMillis || at.elapsedRealtimeMillis >= originalDeadline.expiresElapsedRealtimeMillis) {
                gap(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED); return@access
            }
            operation(OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP, OrionRuntimeEventTime.fromObservation(at),
                cleanup = OrionRuntimeCleanupResult.UNLOADED))
            unloadConfirmed = true
        }
    }

    /** Original return/throw path only publishes scalar state. No Clock, lookup/copy, snapshot or wait. */
    fun retainedResult() { retainedKnown = true }
    fun clientReturnedFromLedger() { if (!ownerEntered) retainedKnown = true }
    fun runReturned(threw: Boolean) { didThrow = threw; runFinished = true }

    /** One separate manual phase; no native read occurs on the original return or turn cleanup. */
    fun readAfterRun(actualOwner: Any): OrionRuntimeSessionReceipt? {
        if (actualOwner !== owner || !runFinished) return null
        cached?.let {
            if (invalidated.get() && !it.invalidated) return null
            val selected = frame
            if (selected != null && (!selected.valid || pinnedConnection != null && selected.connection !== pinnedConnection)) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); return null
            }
            return it
        }
        if (!readClaimed.compareAndSet(false, true)) return null
        return access {
            var finalizedSampling: OrionRuntimeMemorySamplingReport? = null
            if (!ownerEntered) gap(if (retainedKnown) OrionRuntimeSessionGap.RETAINED_OR_JOINED else OrionRuntimeSessionGap.SOURCE_UNAVAILABLE)
            var summary: OrionRuntimeLifecycleImportSummary? = null
            val destination = recorder; val association = fence; val originalStart = start
            val selected = frame
            val stillCurrent = selected != null && selected.valid && selected.connection === pinnedConnection
            if (association == null) gap(OrionRuntimeSessionGap.IDENTITY_UNAVAILABLE)
            if (!stillCurrent && selected != null && pinnedConnection != null) gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true)
            val end = if (activated && association != null && stillCurrent && !invalidated.get()) moment() else null
            if (destination != null && association != null && ownerEntered && originalStart != null) {
                summary = destination.importOwnedLifecycle(association, lifecycleSource, originalStart, end)
                // Manual close never waits for a diagnostic RPC. Late results are fenced and omitted.
                memorySampler?.terminalHint(false)
                val sampling = memorySampler?.seal(end, destination, association)
                finalizedSampling = sampling
                if (end != null) destination.sessionCompleted(association, OrionRuntimeEventTime.fromObservation(end))
                if (summary?.invalidated == true || summary?.snapshotContended == true) gap(OrionRuntimeSessionGap.SOURCE_UNAVAILABLE, true)
                if (invalidated.get() || observationClock?.snapshot()?.invalidated != false)
                    destination.markCaptureUnavailable()
            }
            if (finalizedSampling == null) finalizedSampling = memorySampler?.seal(end, destination, association)
            if (destination == null && ownerEntered) summary = lifecycleSource.snapshot()?.let { OrionRuntimeLifecycleImportSummary(it) }
            if (loadDispatched && !loadTerminal) gap(OrionRuntimeSessionGap.LOAD_TERMINAL_UNAVAILABLE)
            if (unloadIssued && !unloadConfirmed) gap(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED)
            // A new turn/endpoint/close can race the separate bounded import. Never bless its stale identity.
            if (selected != null && (!selected.valid || pinnedConnection != null && selected.connection !== pinnedConnection)) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); destination?.markCaptureUnavailable()
            }
            val facts = destination?.snapshot()
            if (facts?.invalidated == true || destination != null && facts == null) gap(OrionRuntimeSessionGap.SOURCE_UNAVAILABLE, true)
            if (selected != null && (!selected.valid || pinnedConnection != null && selected.connection !== pinnedConnection)) {
                gap(OrionRuntimeSessionGap.IDENTITY_CHANGED, true); destination?.markCaptureUnavailable()
            }
            val reasons = EnumSet.noneOf(OrionRuntimeSessionGap::class.java)
            val bits = gapBits.get()
            OrionRuntimeSessionGap.entries.forEach { if (bits and (1L shl it.ordinal) != 0L) reasons.add(it) }
            OrionRuntimeSessionReceipt(if (ownerEntered) true else if (invalidated.get() && !retainedKnown) null else false,
                if (turnObserved) true else if (invalidated.get()) null else false,
                association != null, invalidated.get(), runFinished && !didThrow,
                didThrow, loadDispatched, if (loadIssued) true else null, Collections.unmodifiableList(ArrayList(pending)), facts, summary,
                Collections.unmodifiableSet(reasons), dropped.get(), failures.get(), accounting.get(), finalizedSampling).also { cached = it }
        }
    }

    /** Separate manual watchdog sample only. Uses the already-bound dedicated clock, never original Clock.
     * Does not change session/event timestamps, rebind, snapshot, query, wait or repair identity.
     */
    fun manualExportMoment(actualOwner: Any): OrionObservationMoment? = access {
        val selected = frame
        if (actualOwner !== owner || !runFinished || !activated || invalidated.get() || selected == null || !selected.valid ||
            pinnedConnection != null && selected.connection !== pinnedConnection) return@access null
        val at = moment() ?: return@access null
        if (!selected.valid || invalidated.get() || pinnedConnection != null && selected.connection !== pinnedConnection) null else at
    }

    private class ExportSnapshot(override val context: OrionRuntimeQualifiedContext,
        override val scalars: OrionRuntimeExportScalars, override val receipt: OrionRuntimeSessionReceipt) : OrionRuntimeExportSnapshot

    /** No acquisition, snapshot, wait or Clock. Requires the already finalized manual cached receipt.
     * The private implementation is minted only by this actual session, after sampler.seal/import.
     */
    fun freezeForExport(actualOwner: Any, context: OrionRuntimeQualifiedContext): OrionRuntimeExportSnapshot? = access {
        val value = cached ?: return@access null
        val selected = frame
        val association = fence
        val facts = value.facts ?: return@access null
        val sampling = value.memorySampling ?: return@access null
        if (actualOwner !== owner || !runFinished || !readClaimed.get() || !ownerEntered || !turnObserved || !activated ||
            invalidated.get() || value.invalidated || !value.identityKnown || selected == null || !selected.valid ||
            selected.connection !== pinnedConnection || association == null || context.mainPid != mainPid ||
            facts.association?.mainPid != mainPid || memorySampler == null ||
            samplingConfiguration == null || sampling.maximumSampleAgeMillis != samplingConfiguration.maximumSampleAgeMillis ||
            value.lifecycle !== facts.lifecycleImport) return@access null
        exported?.let { return@access it }
        val copied = OrionRuntimeExportScalars(candidateContext, candidateThreads, candidateMaxTokens,
            originalWireTemperature, deadlineElapsed, originalInventory)
        if (!selected.valid || selected.connection !== pinnedConnection || invalidated.get()) return@access null
        ExportSnapshot(context, copied, value).also { exported = it }
    }

    companion object {
        fun create(owner: Any, mainPid: Int, sampling: OrionRuntimeSamplingConfiguration? = null,
            probe: OrionRuntimeMainMemoryProbe? = null): OrionCortexRuntimeCaptureSession? = try {
            val clock = OrionObservationEventClock.createInactive()
            val worker = sampling != null && probe != null && OrionRuntimeMemorySamplingWorker.prepare()
            OrionCortexRuntimeCaptureSession(owner, mainPid, clock, OrionInferenceLifecycleEventSource(clock), sampling, probe, worker)
        } catch (_: Throwable) { null }
    }
}

package network.orion.app.ai

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.orion.ai.api.*
import network.orion.core.resources.OrionResourceDecision
import network.orion.core.resources.OrionRestartDecision
import network.orion.core.time.OrionClock
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.ipc.ProtocolInfo
import network.orion.core.ipc.IpcProtocolNegotiation
import network.orion.data.database.ModelRegistryEntity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No construction/wiring in production until real profiles and complete fresh telemetry exist. */
internal class OrionCortexClient(
    context: Context,
    private val clock: OrionClock,
    private val resources: (modelId: String, context: Int, threads: Int, tokens: Int) -> OrionResourceDecision,
    private val restart: () -> OrionRestartDecision,
    private val availabilityChanged: (Boolean) -> Unit = {},
    private val requestHistory: OrionInferenceRequestLedger = OrionInferenceRequestLedger(),
    private val unloadObservations: OrionCortexUnloadObservationBuffer? = null,
    private val cycleObservations: OrionCortexCycleObserver? = null,
) : AutoCloseable {
    private val app = context.applicationContext
    private val session = OrionInferenceTurnstile(clock)
    private val operation = Mutex()
    private val connectionMutex = Mutex()
    private val monitor = Any()
    @Volatile private var closed = false
    @Volatile private var remote: IOrionCortex? = null
    @Volatile private var active: OrionInferenceLifecycle? = null
    @Volatile private var activeId: String? = null
    @Volatile private var loaded: ModelRegistryEntity? = null
    private var loadedContext = 0
    private var loadedThreads = 0
    private var connection: ServiceConnection? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var pinnedAiPid: Int? = null
    private var metricsConnectionEpoch: Long? = 0
    private val metricsCorrelation = try { OrionCortexMetricsCorrelation() } catch (_: Throwable) { null }
    private val cycleClientScope = cycleObservations.isolatedCycle { Any() }
    @Volatile private var activeCycle: OrionCortexCycleToken? = null
    private var lastCycle: OrionCortexCycleToken? = null
    @Volatile private var cycleCallback: OrionCycleCallbackScope? = null
    @Volatile private var cycleEndpointSnapshot: OrionCycleEndpoint? = null
    private val unloadObservationScope: Long?
    private val unloadObservationOwner: OrionCortexUnloadObservationOwner?
    private val unloadDiagnosticDispatcher: OrionCortexUnloadDiagnosticDispatcher?
    private var unloadConnectionEpoch: Long? = 0
    private var unloadEndpointVersion: Long? = 0
    private var unloadExecutionEpoch: Long? = 0
    // Independent primitive generations: no diagnostic object allocation when capture is absent.
    private var runtimeConnectionEpoch = 0L
    private var runtimeTurnGeneration = 0L
    private var runtimeFenceExhausted = false
    private var runtimeBinder: IBinder? = null
    @Volatile private var runtimeFrame: OrionRuntimeCaptureFrame? = null

    init {
        require(Application.getProcessName() == app.packageName) { "Cortex client belongs to MAIN." }
        unloadObservationScope = if (unloadObservations == null) null else
            runCatching { newCortexUnloadObservationScope() }.getOrNull()
        unloadObservationOwner = if (unloadObservations == null) null else try {
            OrionCortexUnloadObservationOwner()
        } catch (_: Throwable) { unloadRecordingFailed(); null }
        // Starting the sole diagnostic thread is opt-in and occurs only after MAIN validation,
        // outside functional execution. A startup failure cannot prevent normal inference.
        unloadDiagnosticDispatcher = if (unloadObservationOwner == null) null else try {
            OrionCortexUnloadDiagnosticDispatcher
        } catch (_: Throwable) { unloadObservationOwner.disable(); unloadRecordingFailed(); null }
    }

    private fun nextUnloadVersion(previous: Long?): Long? = previous?.let { if (it < Long.MAX_VALUE) it + 1 else null }

    /** Diagnostic accounting itself must never prevent the original operation or cleanup. */
    private fun unloadRecordingFailed() {
        try { unloadObservations?.recordingFailed() } catch (_: Throwable) { }
    }

    /** Called only under monitor; catch covers diagnostic versions/allocations, never normative work. */
    private fun updateUnloadOwner(endpointChanged: Boolean = false, executionStarted: Boolean = false,
        connectionStarted: Boolean = false) {
        val owner = unloadObservationOwner ?: return
        if (owner.isDisabled()) return
        try {
            if (connectionStarted) unloadConnectionEpoch = nextUnloadVersion(unloadConnectionEpoch)
            if (endpointChanged) unloadEndpointVersion = nextUnloadVersion(unloadEndpointVersion)
            if (executionStarted) unloadExecutionEpoch = nextUnloadVersion(unloadExecutionEpoch)
            owner.update(OrionCortexUnloadOwnerStamp(
                unloadEndpointVersion, unloadConnectionEpoch, unloadExecutionEpoch, closed))
        } catch (_: Throwable) {
            // A primitive volatile write: no allocation/clock/RPC/guard lock while MAIN owns monitor.
            // Old pending tickets must never validate against the last successfully published stamp.
            owner.disable()
            unloadRecordingFailed()
        }
    }

    /** Called only under monitor sections already owned by the original functional path. */
    private fun publishRuntimeEndpoint() {
        val frame = runtimeFrame ?: return
        try {
            val service = remote; val owner = connection; val binder = runtimeBinder
            if (closed || runtimeFenceExhausted || service == null || owner == null || binder == null) {
                frame.connection = null; frame.pid = null; return
            }
            val before = frame.connection
            if (before == null || before.service !== service || before.connection !== owner || before.binder !== binder || before.epoch != runtimeConnectionEpoch)
                frame.connection = OrionRuntimeConnectionStamp(service, owner, binder, runtimeConnectionEpoch)
            frame.pid = pinnedAiPid
            frame.capture.endpointPublished(frame)
        } catch (_: Throwable) { frame.valid = false }
    }

    /** No new monitor acquisition, RPC or parsing; pairs one existing validated resource reply with its original endpoint. */
    internal fun runtimeHealthWindow(): OrionRuntimeHealthWindow? {
        val frame = runtimeFrame ?: return null
        return try {
            val stamp = frame.connection ?: return null
            if (!frame.valid || frame.ended || frame.resourceThread !== Thread.currentThread()) null else OrionRuntimeHealthWindow(frame, stamp)
        } catch (_: Throwable) { frame.valid = false; null }
    }
    internal fun runtimeMemoryValidated(window: OrionRuntimeHealthWindow?, pid: Int, at: network.orion.core.time.MonotonicInstant?) {
        if (window == null || at == null) return
        if (runtimeFrame !== window.frame) { window.frame.valid = false; return }
        window.frame.capture.originalMemoryWitness(window, pid, at)
    }

    /** Published scalar fence only; never locks/binds/queries or changes the functional PID pin. */
    internal fun runtimeDiagnosticCurrent(frame: OrionRuntimeCaptureFrame, stamp: OrionRuntimeConnectionStamp): Boolean =
        !closed && runtimeFrame === frame && frame.valid && frame.connection === stamp && remote === stamp.service &&
            connection === stamp.connection && runtimeBinder === stamp.binder && runtimeConnectionEpoch == stamp.epoch &&
            runtimeTurnGeneration == frame.turn

    internal fun runtimeDiagnosticEndpoint(frame: OrionRuntimeCaptureFrame): OrionRuntimeDiagnosticEndpoint? { return try {
        val stamp = frame.connection ?: return null
        if (!runtimeDiagnosticCurrent(frame, stamp)) null else
            OrionRuntimeDiagnosticEndpoint(this, frame, stamp, app.packageName, android.os.Process.myPid())
    } catch (_: Throwable) { null } }

    private class InstrumentedEndpoint(val service: IOrionCortex, val connection: ServiceConnection,
        val binder: IBinder, val epoch: Long, val turn: Long, val pid: Int?, val model: String?)

    /** Only supplementary to an original confirmed unload/no-invocation proof, never a terminal source. */
    internal fun instrumentedScalarIdle(): Boolean = synchronized(monitor) {
        !closed && activeId == null && loaded == null && !OrionRuntimeMemorySamplingWorker.outstanding()
    }

    /** Separate idle qualification observation. No acquire/bind, functional pin write or lostConnection. */
    internal fun instrumentedMemoryObservation(): OrionRuntimeAiMemoryReading? {
        if (!OrionRuntimeMemorySamplingWorker.tryManualDiagnostic()) return null
        try {
            val selected = synchronized(monitor) {
                if (closed || runtimeFenceExhausted || activeId != null) return null
                InstrumentedEndpoint(remote ?: return null, connection ?: return null, runtimeBinder ?: return null,
                    runtimeConnectionEpoch, runtimeTurnGeneration, pinnedAiPid, loaded?.modelId)
            }
            fun current(): Boolean = synchronized(monitor) {
                !closed && !runtimeFenceExhausted && activeId == null && remote === selected.service &&
                    connection === selected.connection && runtimeBinder === selected.binder &&
                    runtimeConnectionEpoch == selected.epoch && runtimeTurnGeneration == selected.turn &&
                    pinnedAiPid == selected.pid && loaded?.modelId == selected.model
            }
            if (!current()) return null
            val health = selected.service.healthCheck()
            OrionCortexWire.requireEnvelope(health, MEMORY_KEYS)
            require(health.keySet() == MEMORY_KEYS && OrionCortexWire.text(health, "status") == "OK" &&
                OrionCortexWire.text(health, "processName", 255) == "${app.packageName}:ai")
            val pid = OrionCortexWire.int(health, "pid")
            val boot = OrionCortexWire.int(health, "bootCount")
            val at = OrionCortexWire.long(health, "sampledElapsed")
            val pss = OrionCortexWire.long(health, "totalPssBytes")
            val native = OrionCortexWire.boolean(health, "nativeIncluded")
            val runtime = OrionCortexWire.boolean(health, "nativeRuntimeLoaded")
            val validated = OrionCortexWire.boolean(health, "nativeValidationCompleted")
            require(pid > 0 && pid != android.os.Process.myPid() && (selected.pid == null || selected.pid == pid) &&
                boot >= 0 && at >= 0 && pss > 0 && native && (!validated || runtime))
            if (!current()) return null
            val status = selected.service.getStatus()
            OrionCortexWire.requireEnvelope(status, setOf("schemaVersion", "status", "modelId"))
            val state = OrionRuntimeServiceState.valueOf(OrionCortexWire.text(status, "status"))
            if (state == OrionRuntimeServiceState.LOADED || state == OrionRuntimeServiceState.INFERENCING)
                require(runtime && validated && selected.model != null && OrionCortexWire.text(status, "modelId") == selected.model)
            return if (!current()) null else OrionRuntimeAiMemoryReading(pid, boot, at, pss, native, runtime, validated, state)
        } catch (_: Throwable) { return null }
        finally { OrionRuntimeMemorySamplingWorker.finishManualDiagnostic() }
    }

    fun newRequestId(): String { check(!closed); return requestHistory.newRequestId() }

    private fun restartCandidate(id: String, context: Int, threads: Int, tokens: Int,
        cycle: OrionCortexCycleToken? = null, phase: Int = CYCLE_BEFORE_LOAD): network.orion.core.resources.OrionInferenceResourcePlan {
        val scope = enterCycleCallback(cycle, CYCLE_RESTART, phase)
        val decision = try { restart() } finally { leaveCycleCallback(scope) }
        check(decision.eligibleForResourceEvaluation) { "Cortex restart guard blocked." }
        return checkNotNull(decision.candidatePlan).also { candidate ->
            check(candidate.model.modelId == id && context in 1..candidate.maxContext &&
                threads in 1..candidate.maxThreads && tokens in 1..candidate.maxTokens)
        }
    }

    private class MetricsResourceCapture {
        var callback: OrionCortexMetricsCorrelation.ResourceCallback? = null
    }

    private fun approved(id: String, context: Int, threads: Int, tokens: Int,
        metricsCapture: MetricsResourceCapture? = null, cycle: OrionCortexCycleToken? = null,
        phase: Int = CYCLE_LOAD): OrionResourceDecision {
        val candidate = restartCandidate(id, context, threads, tokens, cycle, phase)
        val resourceScope = enterCycleCallback(cycle, CYCLE_RESOURCE, phase)
        val runtimeResource = runtimeFrame
        if (runtimeResource != null) runtimeResource.resourceThread = Thread.currentThread()
        val decision = try { if (metricsCapture == null) resources(id, context, threads, tokens) else {
            // The restart callback above cannot provide this inference-resource witness.
            val callback = synchronized(monitor) { metricsCorrelation.isolatedMetrics { it.beginResourceCallback() } }
            metricsCapture.callback = callback
            try { resources(id, context, threads, tokens) }
            finally { synchronized(monitor) { callback?.let { token ->
                metricsCorrelation.isolatedMetrics { it.endResourceCallback(token) }
            } } }
        } } finally {
            if (runtimeResource != null) runtimeResource.resourceThread = null
            leaveCycleCallback(resourceScope)
        }
        check(decision.allowed && decision.plan?.model == candidate.model) { "Cortex resource policy blocked." }
        val plan = checkNotNull(decision.plan)
        check(context <= plan.maxContext && threads <= plan.maxThreads && tokens <= plan.maxTokens)
        return decision
    }

    /** Conservative residency: each request loads, infers and unloads even if a plan allows keep-alive. */
    suspend fun runInference(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionInferenceStatus =
        runInference(model, descriptor, request, contextTokens, threads, null, onChunks)

    suspend fun runInference(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        request: OrionInferenceRequest, contextTokens: Int, threads: Int, cycle: OrionCortexCycleToken?,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionInferenceStatus =
        runInference(model, descriptor, request, contextTokens, threads, cycle, null, onChunks)

    suspend fun runInference(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        request: OrionInferenceRequest, contextTokens: Int, threads: Int, cycle: OrionCortexCycleToken?,
        runtimeCapture: OrionCortexRuntimeCaptureSession?, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionInferenceStatus {
        check(!closed)
        validateOrionInferenceRequest(request, contextTokens, threads)
        require(request.modelId == model.modelId)
        val modelContextLimit = model.contextLimit
        require(modelContextLimit != null && contextTokens <= modelContextLimit)
        val fingerprint = orionInferenceRequestFingerprint(request, contextTokens, threads)
        val lifecycleFacts = if (cycle == null || cycleObservations == null || cycle.observer !== cycleObservations) null else try {
            OrionInferenceLifecycleFactBuffer(32)
        } catch (_: Throwable) { null }
        val capture = runtimeCapture?.forClient(this, request.requestId, model.sha256, model.sizeBytes)
        val lifecycle = OrionInferenceLifecycle(request, clock, lifecycleFacts, capture?.lifecycleSource)
        val cycleInvocation = cycleObservations.isolatedCycle(cycle) { if (cycle == null) null else OrionCortexCycleInvocation() }
        val result = requestHistory.runOnce(request.requestId, fingerprint, cancelledStatus = { lifecycle.cancel() }) {
            capture?.runOnceEntered(lifecycle)
            cycleInvocation?.logicalOwnerEntered = true
            // Constructor samples exist even on replay; only this real ledger owner may attach them.
            cycleObservations.isolatedCycle(cycle) { it.logicalOwner(cycle, request.requestId, lifecycleFacts) }
            val initial = lifecycle.status()
            if (initial.terminal != null) return@runOnce initial
            try {
                session.withTurn(request.priority, request.deadline) {
                    val invocationFrame = synchronized(monitor) {
                        check(!closed)
                        runtimeFrame?.valid = false
                        updateUnloadOwner(executionStarted = true); active = lifecycle
                        activeCycle = cycle; lastCycle = cycle
                        if (runtimeTurnGeneration == Long.MAX_VALUE) runtimeFenceExhausted = true else runtimeTurnGeneration++
                        val frame = capture?.turnEntered(this@OrionCortexClient, if (runtimeFenceExhausted) null else runtimeTurnGeneration)
                        runtimeFrame = frame
                        publishRuntimeEndpoint()
                        cycleObservations.isolatedCycle(cycle) {
                            cycleClientScope?.let { scope -> it.turn(cycle, scope) } ?: it.failed(cycle)
                        }
                        frame
                    }
                    lifecycle.markLoading()
                    try {
                        // Memory-exit cooldown/exhaustion blocks even a lightweight process rebind.
                        restartCandidate(model.modelId, contextTokens, threads, request.maxTokens, cycle)
                        val remaining = request.deadline.expiresElapsedRealtimeMillis - clock.monotonicNow().elapsedRealtimeMillis
                        if (remaining <= 0 || !loadModel(model, descriptor, contextTokens, threads, minOf(remaining, 120_000), cycle))
                            lifecycle.run { if (status().terminal == null) failTransport() else status() }
                        else infer(request, contextTokens, threads, onChunks, cycle)
                    } catch (cancelled: CancellationException) {
                        lifecycle.cancel(); throw cancelled
                    } catch (_: Exception) {
                        lifecycle.failTransport()
                    } finally {
                        withContext(NonCancellable) { runCatching { unload(cycle, invocationFrame) } }
                        invocationFrame?.ended = true
                        active = null
                        activeCycle = null; cycleCallback = null
                    }
                }
            } catch (expired: OrionInferenceQueueDeadlineException) {
                // A queued expiry owns no native slot and must never clean up/cancel the current owner.
                val observed = lifecycle.status()
                if (observed.terminal != null) observed
                else if (expired.status == MonotonicDeadlineStatus.EXPIRED)
                    OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.TIMED_OUT, null)
                else OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.FAILED,
                    OrionInferenceFailure.INVALID_TIME_REFERENCE)
            }
        }
        capture?.clientReturnedFromLedger()
        cycleObservations.isolatedCycle(cycle) { it.clientResult(cycle, cycleInvocation?.logicalOwnerEntered?.not()) }
        return result
    }

    /** Caller opens a freshly revalidated private READY file; service checks its digest again. */
    private suspend fun loadModel(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        contextTokens: Int, threads: Int, durationMillis: Long = 120_000, cycle: OrionCortexCycleToken? = null): Boolean = operation.withLock {
        require(model.status == "READY" && model.modelId == model.sha256 && model.sizeBytes > 0)
        require(durationMillis in 1..120_000)
        // Bind is lightweight; heavy work remains blocked until a fresh decision immediately before IPC.
        val service = withTimeout(10_000) { acquire() }
        OrionCortexWire.requireReadOnlyFile(descriptor, model.sizeBytes, 4L * 1024 * 1024 * 1024)
        val id = issueTransportRequestId(service)
        approved(model.modelId, contextTokens, threads, 1, cycle = cycle)
        val started = clock.monotonicNow()
        val deadline = started.deadlineAfter(durationMillis)
        runtimeFrame?.let { it.capture.loadPreparing(it, service, started) }
        val specification = Bundle().apply {
            putInt("schemaVersion", 1); putString("requestId", id); putString("modelId", model.modelId)
            putString("sha256", model.sha256); putLong("sizeBytes", model.sizeBytes)
            putInt("contextTokens", contextTokens); putInt("threads", threads)
            putInt("bootCount", checkNotNull(started.bootSessionId).bootCount)
            putLong("startedElapsed", started.elapsedRealtimeMillis); putLong("durationMillis", durationMillis)
        }
        synchronized(monitor) {
            check(!closed)
            if (active?.status()?.terminal != null) return@withLock false
            activeId = id; loaded = null
        }
        try {
            runtimeFrame?.let { it.capture.loadDispatch(it, service, id) }
            checkReply(service.loadModel(descriptor, specification), setOf("ACCEPTED"))
            runtimeFrame?.let { it.capture.loadIssued(it, service, id, started) }
            while (deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE && remote === service && active?.status()?.terminal == null) {
                val status = service.getRequestStatus(id)
                OrionCortexWire.requireEnvelope(status, REPLY_KEYS)
                check(OrionCortexWire.text(status, "requestId") == id)
                when (val loadState = OrionCortexWire.text(status, "state")) {
                    "SUCCEEDED" -> {
                        // The worker publishes its logical result before its finally releases the native slot.
                        val engine = service.getStatus()
                        OrionCortexWire.requireEnvelope(engine, REPLY_KEYS)
                        when (OrionCortexWire.text(engine, "status")) {
                            "LOADED" -> {
                                check(OrionCortexWire.text(engine, "modelId") == model.modelId)
                                loaded = model; loadedContext = contextTokens; loadedThreads = threads
                                runtimeFrame?.let { it.capture.loadTerminal(it, service, id, OrionInferenceTerminal.SUCCEEDED, true) }
                                return@withLock true
                            }
                            "LOADING" -> Unit
                            else -> return@withLock false
                        }
                    }
                    "FAILED", "CANCELLED", "TIMED_OUT", "PROCESS_DIED" -> {
                        runtimeFrame?.let { it.capture.loadTerminal(it, service, id, when (loadState) {
                            "CANCELLED" -> OrionInferenceTerminal.CANCELLED
                            "TIMED_OUT" -> OrionInferenceTerminal.TIMED_OUT
                            "PROCESS_DIED" -> OrionInferenceTerminal.PROCESS_DIED
                            else -> OrionInferenceTerminal.FAILED
                        }, false) }
                        return@withLock false
                    }
                    "RUNNING" -> Unit
                    else -> error("Invalid Cortex loading state.")
                }
                delay(25)
            }
            runCatching { service.cancelInference(id) }
            false
        } catch (cancelled: CancellationException) {
            runCatching { service.cancelInference(id) }; throw cancelled
        } finally { activeId = null }
    }

    /** Receives structured text only; this client never creates or invokes a Skill. */
    private suspend fun infer(request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit, cycle: OrionCortexCycleToken? = null): OrionInferenceStatus = operation.withLock {
        val model = checkNotNull(loaded) { "Cortex model is not loaded." }
        require(request.modelId == model.modelId && contextTokens == loadedContext && threads == loadedThreads)
        val service = withTimeout(10_000) { acquire() }
        val lifecycle = checkNotNull(active)
        if (lifecycle.status().terminal != null) return@withLock lifecycle.status()
        val transportId = issueTransportRequestId(service)
        val callbacks = Channel<Bundle>(16)
        val content = (request.context + "\n" + request.prompt).toByteArray(Charsets.UTF_8)
        val input = try {
            require(content.size in 1..OrionCortexWire.MAX_INPUT_BYTES)
            SharedMemory.create("orion_inference", content.size)
        }
        catch (failure: Throwable) { content.fill(0); callbacks.close(); throw failure }
        try {
            synchronized(monitor) {
                check(!closed)
                if (lifecycle.status().terminal != null) return@withLock lifecycle.status()
                active = lifecycle; activeId = transportId
            }
            val mapped = input.mapReadWrite()
            try { mapped.put(content) } finally { SharedMemory.unmap(mapped); content.fill(0) }
            check(input.setProtect(OsConstants.PROT_READ))
            val metricsCapture = synchronized(monitor) { metricsCorrelation.isolatedMetrics { MetricsResourceCapture() } }
            val resourceDecision = approved(model.modelId, contextTokens, threads, request.maxTokens, metricsCapture,
                cycle, CYCLE_INFERENCE)
            val metricsCandidate = synchronized(monitor) {
                metricsCorrelation.isolatedMetrics { it.candidate(service, connection, request.requestId, transportId, metricsCapture?.callback,
                    resourceDecision.allowed, resourceDecision.sampledAt, request.deadline, request.maxTokens, contextTokens) }
            }
            if (lifecycle.status().terminal != null) return@withLock lifecycle.status()
            val start = request.deadline.start
            var originalInputBytes = 0L
            val specification = Bundle().apply {
                putInt("schemaVersion", 1); putString("requestId", transportId); putString("modelId", model.modelId)
                putLong("inputSize", input.size.toLong().also { originalInputBytes = it }); putInt("maxTokens", request.maxTokens)
                putFloat("temperature", request.temperature.toFloat().also { value ->
                    runtimeFrame?.let { it.capture.wireTemperature(it, service, value) }
                })
                putInt("bootCount", checkNotNull(start.bootSessionId).bootCount)
                putLong("startedElapsed", start.elapsedRealtimeMillis)
                putLong("durationMillis", request.deadline.expiresElapsedRealtimeMillis - start.elapsedRealtimeMillis)
            }
            val callback = object : IOrionInferenceCallback.Stub() {
                override fun onChunk(chunk: Bundle) {
                    if (callbacks.trySend(chunk).isFailure) lifecycle.failTransport()
                }
            }
            lifecycle.markLoading()
            synchronized(monitor) { metricsCorrelation.isolatedMetrics { it.beginningInference() } }
            runtimeFrame?.let { it.capture.inferenceDispatch(it, service, transportId, originalInputBytes) }
            checkReply(service.startInference(input, specification, callback), setOf("ACCEPTED"))
            synchronized(monitor) { metricsCorrelation.isolatedMetrics { it.admitted(metricsCandidate) } }
            cycleObservations.isolatedCycle(cycle) { it.inferenceAccepted(cycle, snapshotCycleEndpoint()) }
            lifecycle.markDecoding()
            while (lifecycle.status().terminal == null) {
                val chunk = callbacks.tryReceive().getOrNull()
                if (chunk == null) { delay(10); continue }
                try {
                    OrionCortexWire.requireEnvelope(chunk, CHUNK_KEYS)
                    if (OrionCortexWire.text(chunk, "requestId") != transportId) continue
                    val final = OrionCortexWire.boolean(chunk, "isFinal")
                    val update = lifecycle.accept(OrionInferenceChunk(
                        request.requestId, OrionCortexWire.int(chunk, "schemaVersion"),
                        OrionCortexWire.long(chunk, "sequence"), OrionCortexWire.optionalText(chunk, "delta"), isFinal = final,
                        finishReason = if (final) OrionInferenceTerminal.valueOf(OrionCortexWire.text(chunk, "finishReason")) else null,
                    ))
                    runtimeFrame?.capture?.terminalHint()
                    if (update.chunks.isNotEmpty()) {
                        cycleObservations.isolatedCycle(cycle) { it.streamAttempt(cycle, update.chunks.size) }
                        onChunks(update.chunks)
                        cycleObservations.isolatedCycle(cycle) { it.streamReturned(cycle) }
                    }
                } catch (_: IllegalArgumentException) { lifecycle.failTransport(); runtimeFrame?.capture?.terminalHint() }
            }
            if (lifecycle.status().terminal != OrionInferenceTerminal.SUCCEEDED)
                runCatching { service.cancelInference(transportId) }
            lifecycle.status()
        } catch (cancelled: CancellationException) {
            lifecycle.cancel(); runCatching { service.cancelInference(transportId) }; throw cancelled
        } catch (_: android.os.RemoteException) {
            lifecycle.processDied(); lostConnection(); lifecycle.status()
        } catch (_: Exception) {
            lifecycle.failTransport(); runCatching { service.cancelInference(transportId) }; lifecycle.status()
        } finally {
            content.fill(0)
            try { input.close() } finally { callbacks.close(); activeId = null }
        }
    }

    fun cancel() = synchronized(monitor) {
        active?.cancel(); runtimeFrame?.capture?.terminalHint()
        activeId?.let { id -> runCatching { remote?.cancelInference(id) } }; Unit
    }

    private fun enterCycleCallback(token: OrionCortexCycleToken?, callback: Int,
        phase: Int): OrionCycleCallbackScope? = cycleObservations.isolatedCycle(token) {
        val scope = cycleClientScope
        if (token == null || scope == null || closed || activeCycle !== token || token.observer !== cycleObservations) null
        else OrionCycleCallbackScope(token, scope, OrionCycleCallback.entries[callback],
            OrionCyclePhase.entries[phase], Thread.currentThread()).also { cycleCallback = it }
    }

    private fun leaveCycleCallback(scope: OrionCycleCallbackScope?) {
        cycleObservations.isolatedCycle(scope?.token) {
            if (cycleCallback === scope) cycleCallback = null
        }
    }

    internal fun cycleCallbackScope(callback: OrionCycleCallback): OrionCycleCallbackScope? =
        cycleCallback?.takeIf { !closed && it.callback == callback && it.thread === Thread.currentThread() &&
            it.token === activeCycle && it.clientScope === cycleClientScope }

    internal fun cycleEndpoint(scope: OrionCycleCallbackScope): OrionCycleEndpoint? {
        if (cycleCallbackScope(scope.callback) !== scope) return null
        val endpoint = snapshotCycleEndpoint()
        return if (cycleCallbackScope(scope.callback) === scope && cycleEndpointSnapshot === endpoint) endpoint else null
    }

    private fun snapshotCycleEndpoint(): OrionCycleEndpoint? = if (closed) null else cycleEndpointSnapshot

    /** Called only while the original functional path already holds monitor. Never adds a wait. */
    private fun updateCycleEndpoint() {
        val scope = cycleClientScope ?: return
        val value = cycleObservations.isolatedCycle(activeCycle) {
            if (closed || remote == null || connection == null) null else {
                val previous = cycleEndpointSnapshot
                if (previous != null && previous.connectionEpoch == metricsConnectionEpoch && previous.aiPid == pinnedAiPid) previous
                else OrionCycleEndpoint(scope, metricsConnectionEpoch, pinnedAiPid)
            }
        }
        cycleEndpointSnapshot = value
    }

    internal fun cycleEndpointCurrent(token: OrionCortexCycleToken, scope: Any?, expected: OrionCycleEndpoint?): Boolean =
        synchronized(monitor) {
            !closed && lastCycle === token && scope != null && scope === cycleClientScope && expected != null &&
                expected.clientScope === scope && expected.connectionEpoch != null && expected.connectionEpoch > 0 &&
                metricsConnectionEpoch == expected.connectionEpoch && expected.aiPid != null && expected.aiPid > 0 &&
                pinnedAiPid == expected.aiPid && remote != null && connection != null
        }

    private suspend fun unload(cycle: OrionCortexCycleToken? = null, capture: OrionRuntimeCaptureFrame? = null) = operation.withLock {
        val capturing = capture != null
        val observing = unloadObservations != null
        val cycling = cycle != null && cycleObservations != null
        var cycleEndpoint: OrionCycleEndpoint? = null
        var selectedService: IOrionCortex? = null
        var selectedOwner: ServiceConnection? = null
        var selectedEpoch: Long? = null
        var selectedPid: Int? = null
        var initiallyClosed = false
        var observedDeadline: network.orion.core.time.MonotonicDeadline? = null
        var selectedStamp: OrionCortexUnloadOwnerStamp? = null
        var pollAt: network.orion.core.time.MonotonicInstant? = null
        var outcome = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
        var failurePhase = OrionCortexUnloadOutcome.UNLOAD_REQUEST_FAILED
        var failureOutcome: OrionCortexUnloadOutcome? = null
        var unloadedReply = false
        var validUnloadedReply = false
        var currentAtLoopExit = false
        var closedAtLoopExit = false
        fun observe(action: () -> Unit) {
            if (observing || cycling || capturing) try { action() } catch (_: Throwable) {
                outcome = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
                runCatching { unloadObservations?.recordingFailed() }
                cycleObservations.isolatedCycle(cycle) { it.failed(cycle) }
            }
        }
        fun endpointCurrent() = remote === selectedService && connection === selectedOwner && unloadConnectionEpoch == selectedEpoch &&
            (selectedPid == null || pinnedAiPid == selectedPid)
        fun pollingClock(): network.orion.core.time.MonotonicInstant {
            failurePhase = OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID
            return clock.monotonicNow().also { if (observing || cycling) pollAt = it }
        }
        try {
            val service = if (!observing) remote else synchronized(monitor) {
                selectedService = remote; selectedOwner = connection; initiallyClosed = closed
                selectedEpoch = if (connection == null) null else unloadConnectionEpoch?.takeIf { it > 0 }
                selectedPid = pinnedAiPid
                selectedStamp = unloadObservationOwner?.snapshot()
                selectedService
            }
            if (cycling) cycleObservations.isolatedCycle(cycle) {
                val view = snapshotCycleEndpoint()
                cycleEndpoint = view?.takeIf { !closed && remote === service && cycleEndpointSnapshot === view }
            }
            if (service == null) {
                observe { outcome = OrionCortexUnloadOutcome.NO_ENDPOINT }
                return@withLock
            }
            capture?.capture?.unloadDispatch(capture, service)
            service.unloadModel()
            failurePhase = OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID
            val deadline = clock.monotonicNow().deadlineAfter(5_000)
            observe { observedDeadline = deadline }
            withTimeout(5_500) {
                while (deadline.statusAt(pollingClock()) == MonotonicDeadlineStatus.ACTIVE && remote === service) {
                    failurePhase = OrionCortexUnloadOutcome.TRANSPORT_FAILURE
                    val status = service.getStatus()
                    failurePhase = OrionCortexUnloadOutcome.STATUS_INVALID
                    OrionCortexWire.requireEnvelope(status, REPLY_KEYS)
                    if (OrionCortexWire.text(status, "status") == "UNLOADED") {
                        observe {
                            unloadedReply = true
                            validUnloadedReply = status.keySet() == setOf("schemaVersion", "status")
                        }
                        capture?.capture?.unloadCompleted(capture, service, deadline, validUnloadedReply)
                        return@withTimeout
                    }
                    failurePhase = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
                    delay(25)
                }
                if (observing) observe { synchronized(monitor) {
                    currentAtLoopExit = endpointCurrent(); closedAtLoopExit = closed
                } }
                releaseConnection()
            }
        } catch (failure: Exception) {
            observe {
                failureOutcome = when (failure) {
                    is kotlinx.coroutines.TimeoutCancellationException -> OrionCortexUnloadOutcome.POLL_TIMEOUT
                    is CancellationException -> OrionCortexUnloadOutcome.CANCELLED
                    else -> failurePhase
                }
            }
            lostConnection()
            throw failure
        } finally {
            loaded = null
            if (cycling) cycleObservations.isolatedCycle(cycle) {
                it.unloaded(cycle, OrionCycleUnloadFact(cycleEndpoint, observedDeadline, pollAt,
                    outcome == OrionCortexUnloadOutcome.NO_ENDPOINT, unloadedReply, validUnloadedReply, failureOutcome))
            }
            // Facts-only submission: no extra Clock/provider call or await in cleanup/turn.
            if (observing) observe {
                val buffer = checkNotNull(unloadObservations)
                val owner = checkNotNull(unloadObservationOwner)
                val dispatcher = checkNotNull(unloadDiagnosticDispatcher)
                val stamp = checkNotNull(selectedStamp)
                dispatcher.submit(OrionCortexUnloadDiagnosticTicket(clock, owner,
                    OrionCortexUnloadFacts(unloadObservationScope, stamp, selectedPid, observedDeadline, pollAt,
                        outcome == OrionCortexUnloadOutcome.NO_ENDPOINT, failureOutcome, unloadedReply,
                        validUnloadedReply, currentAtLoopExit, initiallyClosed || closedAtLoopExit), buffer))
            }
        }
    }

    /** Fresh local-process PSS from this authenticated Binder epoch; never creates a second binding. */
    internal fun currentMemoryTelemetry(): Bundle? {
        val selected = synchronized(monitor) {
            val service = remote ?: return null
            val owner = connection ?: return null
            service to owner
        }
        return try {
            val receipt = selected.first.healthCheck()
            OrionCortexWire.requireEnvelope(receipt, MEMORY_KEYS)
            val pid = OrionCortexWire.int(receipt, "pid")
            require(pid > 0 && pid != android.os.Process.myPid() &&
                OrionCortexWire.text(receipt, "processName", 255) == "${app.packageName}:ai")
            synchronized(monitor) {
                if (closed || remote !== selected.first || connection !== selected.second) return null
                require(pinnedAiPid == null || pinnedAiPid == pid)
                if (pinnedAiPid != pid) {
                    cycleEndpointSnapshot = null
                    pinnedAiPid = pid; updateUnloadOwner(endpointChanged = true)
                    publishRuntimeEndpoint()
                }
                updateCycleEndpoint()
                // Optional correlation only. Parsing failures cannot change this health call.
                metricsCorrelation.isolatedMetrics { correlation ->
                    require(OrionCortexWire.text(receipt, "status") == "OK" &&
                        OrionCortexWire.long(receipt, "totalPssBytes") > 0 && OrionCortexWire.boolean(receipt, "nativeIncluded"))
                    val boot = OrionCortexWire.int(receipt, "bootCount").also { require(it >= 0) }
                    val at = network.orion.core.time.MonotonicInstant(network.orion.core.time.BootSessionId(boot),
                        OrionCortexWire.long(receipt, "sampledElapsed"))
                    correlation.memory(selected.first, selected.second, pid, at)
                }
            }
            Bundle(receipt)
        } catch (_: Exception) {
            lostConnection(selected.second)
            null
        }
    }

    /** Manual optional read on the current endpoint; never binds, polls or refreshes resource facts. */
    fun generationMetrics(logicalRequestId: String): OrionGenerationMetricsSnapshot? {
        val selected = synchronized(monitor) {
            if (closed) return null
            metricsCorrelation.isolatedMetrics { it.select(logicalRequestId)?.takeIf(::metricsEndpointCurrent) } ?: return null
        }
        val discovery = synchronized(monitor) {
            if (closed) return null
            metricsCorrelation.isolatedMetrics { if (metricsEndpointCurrent(selected)) it.claimDiscovery(selected) else null }
        }
        if (discovery != null) {
            val mask = try {
                OrionGenerationMetricsWire.capabilityMask((discovery.service as IOrionCortex).getGenerationMetricsCapabilities())
            } catch (_: Throwable) { null }
            synchronized(monitor) {
                if (closed) return null
                metricsCorrelation.isolatedMetrics { it.discovered(discovery, mask) }
            }
        }
        val mask = synchronized(monitor) {
            if (closed) return null
            metricsCorrelation.isolatedMetrics { if (metricsEndpointCurrent(selected)) it.mask(selected) else null } ?: return null
        }
        val snapshot = try {
            val reply = (selected.endpoint.service as IOrionCortex).getGenerationMetrics(
                OrionGenerationMetricsWire.query(selected.transportId))
            OrionGenerationMetricsWire.decodeSnapshot(reply, mask, selected.maxTokens, selected.contextTokens)
        } catch (_: Throwable) { null } ?: return null
        return synchronized(monitor) {
            if (closed || metricsCorrelation.isolatedMetrics {
                metricsEndpointCurrent(selected) && it.accepts(selected, snapshot)
            } != true) null else snapshot
        }
    }

    /** Called only under monitor, checking the actual owner fields as well as the detached mapping. */
    private fun metricsEndpointCurrent(selected: OrionCortexMetricsCorrelation.Binding): Boolean =
        remote === selected.endpoint.service && connection === selected.endpoint.owner &&
            metricsConnectionEpoch == selected.endpoint.epoch && pinnedAiPid == selected.witness.pid

    private suspend fun acquire(): IOrionCortex = connectionMutex.withLock {
        check(!closed)
        remote?.let { return@withLock it }
        releaseConnection()
        suspendCancellableCoroutine { pending ->
            val owner = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (!pending.isActive) { releaseConnection(this); return }
                    if (closed || connection !== this) {
                        releaseConnection(this)
                        if (pending.isActive) pending.resumeWithException(IllegalStateException("Cortex client closed."))
                        return
                    }
                    try {
                        val service = IOrionCortex.Stub.asInterface(binder)
                        val protocol = service.getProtocolInfo()
                        OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
                        check(OrionCortexWire.text(protocol, "status") == "OK")
                        val major = OrionCortexWire.int(protocol, "major")
                        val minor = OrionCortexWire.int(protocol, "minor")
                        check(major == OrionCortexProtocol.CURRENT.protocolMajor && minor >= 1) {
                            "Cortex capability discovery unavailable."
                        }
                        val capabilities = service.getCapabilities()
                        OrionCortexWire.requireEnvelope(capabilities, setOf("schemaVersion", "status", "capabilities"))
                        check(OrionCortexWire.text(capabilities, "status") == "OK")
                        val capability = OrionCortexWire.optionalText(capabilities, "capabilities")
                        val peer = ProtocolInfo(major, minor,
                            if (capability == OrionCortexProtocol.ISSUED_HISTORY.value)
                                setOf(OrionCortexProtocol.ISSUED_HISTORY) else emptySet())
                        val negotiated = OrionCortexProtocol.CURRENT.negotiateWith(peer)
                        check(negotiated is IpcProtocolNegotiation.Compatible && negotiated.protocolMinor >= 1 &&
                            OrionCortexProtocol.ISSUED_HISTORY in negotiated.capabilities) { "Cortex issued history unavailable." }
                        val death = IBinder.DeathRecipient { lostConnection(this) }
                        synchronized(monitor) {
                            check(!closed && connection === this)
                            binder.linkToDeath(death, 0)
                            cycleEndpointSnapshot = null
                            deathRecipient = death; remote = service; runtimeBinder = binder
                            publishRuntimeEndpoint()
                            updateCycleEndpoint()
                            updateUnloadOwner(endpointChanged = true)
                            metricsCorrelation.isolatedMetrics { it.connected(service, this, metricsConnectionEpoch, negotiated.protocolMinor) }
                        }
                        availabilityChanged(true)
                        pending.resume(service)
                    } catch (failure: Exception) {
                        releaseConnection(this)
                        if (pending.isActive) pending.resumeWithException(failure)
                    }
                }
                private fun disconnected() {
                    lostConnection(this)
                    if (pending.isActive) pending.resumeWithException(IllegalStateException("Cortex disconnected."))
                }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) {
                    releaseConnection(this)
                    if (pending.isActive) pending.resumeWithException(IllegalStateException("Cortex bind unavailable."))
                }
            }
            pending.invokeOnCancellation { releaseConnection(owner) }
            val intent = Intent().setComponent(ComponentName(app.packageName, "network.orion.ai.service.OrionCortexService"))
            val bound = synchronized(monitor) {
                check(!closed)
                if (!pending.isActive) false else {
                    cycleEndpointSnapshot = null
                    connection = owner
                    runtimeBinder = null
                    if (runtimeConnectionEpoch == Long.MAX_VALUE) { runtimeFenceExhausted = true; runtimeFrame?.valid = false }
                    else runtimeConnectionEpoch++
                    metricsConnectionEpoch = try {
                        metricsConnectionEpoch?.let { if (it < Long.MAX_VALUE) it + 1 else null }
                    } catch (_: Throwable) { null }
                    updateUnloadOwner(endpointChanged = true, connectionStarted = true)
                    app.bindService(intent, owner, Context.BIND_AUTO_CREATE)
                }
            }
            if (!bound) {
                releaseConnection(owner)
                if (pending.isActive) pending.resumeWithException(IllegalStateException("Cortex bind rejected."))
            }
        }
    }

    private fun lostConnection(expected: ServiceConnection? = null) {
        synchronized(monitor) {
            if (expected != null && connection !== expected) return
            runtimeFrame?.valid = false
            cycleEndpointSnapshot = null
            updateUnloadOwner(endpointChanged = true)
            loaded = null; active?.processDied()
        }
        releaseConnection(expected)
        availabilityChanged(false)
    }

    private fun releaseConnection(expected: ServiceConnection? = null) {
        synchronized(monitor) {
            if (expected != null && connection !== expected) return
            val service = remote
            cycleEndpointSnapshot = null
            val changed = remote != null || connection != null || pinnedAiPid != null
            // Invalidate before any potentially remote unlink/unbind work can delay release.
            if (changed) { runtimeFrame?.valid = false; updateUnloadOwner(endpointChanged = true) }
            deathRecipient?.let { death -> runCatching { service?.asBinder()?.unlinkToDeath(death, 0) } }
            remote = null; loaded = null; deathRecipient = null; pinnedAiPid = null; runtimeBinder = null
            metricsCorrelation.isolatedMetrics { it.clear() }
            cycleCallback = null
            connection?.let { runCatching { app.unbindService(it) } }; connection = null
        }
    }

    override fun close() {
        synchronized(monitor) { runtimeFrame?.valid = false; cycleEndpointSnapshot = null; closed = true; updateUnloadOwner(); cancel() }
        session.close()
        releaseConnection()
    }

    private fun checkReply(reply: Bundle, allowed: Set<String>) {
        OrionCortexWire.requireEnvelope(reply, REPLY_KEYS)
        check(OrionCortexWire.text(reply, "status") in allowed) { "Cortex request rejected." }
    }

    private fun issueTransportRequestId(service: IOrionCortex): String {
        val reply = service.issueRequestId()
        checkReply(reply, setOf("OK"))
        return OrionCortexWire.text(reply, "requestId").also {
            check(OrionIssuedRequestHistory.isIssuedIdentity(it)) { "Unissued Cortex identity." }
        }
    }

    companion object {
        private const val CYCLE_RESTART = 0
        private const val CYCLE_RESOURCE = 1
        private const val CYCLE_BEFORE_LOAD = 0
        private const val CYCLE_LOAD = 1
        private const val CYCLE_INFERENCE = 2
        private val REPLY_KEYS = setOf("schemaVersion", "status", "requestId", "state", "modelId")
        private val MEMORY_KEYS = setOf("schemaVersion", "status", "processName", "pid", "bootCount",
            "sampledElapsed", "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")
        private val CHUNK_KEYS = setOf("schemaVersion", "requestId", "sequence", "delta", "isFinal", "finishReason", "errorCode")
    }
}

/** Shared MAIN admission checks; no storage, inventory or Binder access. */
internal fun validateOrionInferenceRequest(request: OrionInferenceRequest, context: Int, threads: Int) {
    require(request.schemaVersion == 1)
    require(context in 32..OrionCortexWire.MAX_CONTEXT && threads in 1..OrionCortexWire.MAX_THREADS)
    require(request.maxTokens in 1..OrionCortexWire.MAX_TOKENS)
    val duration = request.deadline.expiresElapsedRealtimeMillis - request.deadline.start.elapsedRealtimeMillis
    require(duration in 0..120_000)
    require(request.prompt.length.toLong() + request.context.length < OrionCortexWire.MAX_INPUT_BYTES)
    // Reject malformed UTF-16 before storage preparation, admission or Binder can replace it.
    for (field in listOf(request.prompt, request.context)) strictInferenceUtf8(field).fill(0)
}

internal fun orionInferenceRequestFingerprint(request: OrionInferenceRequest, context: Int, threads: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in listOf(request.modelId, request.prompt, request.context, request.maxTokens.toString(),
        request.temperature.toString(), request.priority.name, request.deadline.start.toString(),
        request.deadline.expiresElapsedRealtimeMillis.toString(), context.toString(), threads.toString())) {
        val bytes = strictInferenceUtf8(field)
        try {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        } finally { bytes.fill(0) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** No replacement bytes: different request text must never alias a retained identity. */
private fun strictInferenceUtf8(value: String): ByteArray {
    val encoded = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Inference input requires well-formed Unicode.")
    }
    return try { ByteArray(encoded.remaining()).also { encoded.get(it) } }
        finally { if (encoded.hasArray()) encoded.array().fill(0) }
}

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
    private val unloadObservationScope = if (unloadObservations == null) null else
        runCatching { newCortexUnloadObservationScope() }.getOrNull()
    private var unloadConnectionEpoch: Long? = 0

    init { require(Application.getProcessName() == app.packageName) { "Cortex client belongs to MAIN." } }

    fun newRequestId(): String { check(!closed); return requestHistory.newRequestId() }

    private fun restartCandidate(id: String, context: Int, threads: Int, tokens: Int): network.orion.core.resources.OrionInferenceResourcePlan {
        val decision = restart()
        check(decision.eligibleForResourceEvaluation) { "Cortex restart guard blocked." }
        return checkNotNull(decision.candidatePlan).also { candidate ->
            check(candidate.model.modelId == id && context in 1..candidate.maxContext &&
                threads in 1..candidate.maxThreads && tokens in 1..candidate.maxTokens)
        }
    }

    private fun approved(id: String, context: Int, threads: Int, tokens: Int): OrionResourceDecision {
        val candidate = restartCandidate(id, context, threads, tokens)
        val decision = resources(id, context, threads, tokens)
        check(decision.allowed && decision.plan?.model == candidate.model) { "Cortex resource policy blocked." }
        val plan = checkNotNull(decision.plan)
        check(context <= plan.maxContext && threads <= plan.maxThreads && tokens <= plan.maxTokens)
        return decision
    }

    /** Conservative residency: each request loads, infers and unloads even if a plan allows keep-alive. */
    suspend fun runInference(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionInferenceStatus {
        check(!closed)
        validateOrionInferenceRequest(request, contextTokens, threads)
        require(request.modelId == model.modelId)
        val modelContextLimit = model.contextLimit
        require(modelContextLimit != null && contextTokens <= modelContextLimit)
        val fingerprint = orionInferenceRequestFingerprint(request, contextTokens, threads)
        val lifecycle = OrionInferenceLifecycle(request, clock)
        return requestHistory.runOnce(request.requestId, fingerprint, cancelledStatus = { lifecycle.cancel() }) {
            val initial = lifecycle.status()
            if (initial.terminal != null) return@runOnce initial
            try {
                session.withTurn(request.priority, request.deadline) {
                    synchronized(monitor) { check(!closed); active = lifecycle }
                    lifecycle.markLoading()
                    try {
                        // Memory-exit cooldown/exhaustion blocks even a lightweight process rebind.
                        restartCandidate(model.modelId, contextTokens, threads, request.maxTokens)
                        val remaining = request.deadline.expiresElapsedRealtimeMillis - clock.monotonicNow().elapsedRealtimeMillis
                        if (remaining <= 0 || !loadModel(model, descriptor, contextTokens, threads, minOf(remaining, 120_000)))
                            lifecycle.run { if (status().terminal == null) failTransport() else status() }
                        else infer(request, contextTokens, threads, onChunks)
                    } catch (cancelled: CancellationException) {
                        lifecycle.cancel(); throw cancelled
                    } catch (_: Exception) {
                        lifecycle.failTransport()
                    } finally {
                        withContext(NonCancellable) { runCatching { unload() } }
                        active = null
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
    }

    /** Caller opens a freshly revalidated private READY file; service checks its digest again. */
    private suspend fun loadModel(model: ModelRegistryEntity, descriptor: ParcelFileDescriptor,
        contextTokens: Int, threads: Int, durationMillis: Long = 120_000): Boolean = operation.withLock {
        require(model.status == "READY" && model.modelId == model.sha256 && model.sizeBytes > 0)
        require(durationMillis in 1..120_000)
        // Bind is lightweight; heavy work remains blocked until a fresh decision immediately before IPC.
        val service = withTimeout(10_000) { acquire() }
        OrionCortexWire.requireReadOnlyFile(descriptor, model.sizeBytes, 4L * 1024 * 1024 * 1024)
        val id = issueTransportRequestId(service)
        approved(model.modelId, contextTokens, threads, 1)
        val started = clock.monotonicNow()
        val deadline = started.deadlineAfter(durationMillis)
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
            checkReply(service.loadModel(descriptor, specification), setOf("ACCEPTED"))
            while (deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE && remote === service && active?.status()?.terminal == null) {
                val status = service.getRequestStatus(id)
                OrionCortexWire.requireEnvelope(status, REPLY_KEYS)
                check(OrionCortexWire.text(status, "requestId") == id)
                when (OrionCortexWire.text(status, "state")) {
                    "SUCCEEDED" -> {
                        // The worker publishes its logical result before its finally releases the native slot.
                        val engine = service.getStatus()
                        OrionCortexWire.requireEnvelope(engine, REPLY_KEYS)
                        when (OrionCortexWire.text(engine, "status")) {
                            "LOADED" -> {
                                check(OrionCortexWire.text(engine, "modelId") == model.modelId)
                                loaded = model; loadedContext = contextTokens; loadedThreads = threads
                                return@withLock true
                            }
                            "LOADING" -> Unit
                            else -> return@withLock false
                        }
                    }
                    "FAILED", "CANCELLED", "TIMED_OUT", "PROCESS_DIED" -> return@withLock false
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
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionInferenceStatus = operation.withLock {
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
            val resourceCallback = synchronized(monitor) { metricsCorrelation.isolatedMetrics { it.beginResourceCallback() } }
            val resourceDecision = try { approved(model.modelId, contextTokens, threads, request.maxTokens) }
                finally { synchronized(monitor) { resourceCallback?.let { token ->
                    metricsCorrelation.isolatedMetrics { it.endResourceCallback(token) }
                } } }
            val metricsCandidate = synchronized(monitor) {
                metricsCorrelation.isolatedMetrics { it.candidate(service, connection, request.requestId, transportId, resourceCallback,
                    resourceDecision.allowed, resourceDecision.sampledAt, request.deadline, request.maxTokens, contextTokens) }
            }
            if (lifecycle.status().terminal != null) return@withLock lifecycle.status()
            val start = request.deadline.start
            val specification = Bundle().apply {
                putInt("schemaVersion", 1); putString("requestId", transportId); putString("modelId", model.modelId)
                putLong("inputSize", input.size.toLong()); putInt("maxTokens", request.maxTokens)
                putFloat("temperature", request.temperature.toFloat())
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
            checkReply(service.startInference(input, specification, callback), setOf("ACCEPTED"))
            synchronized(monitor) { metricsCorrelation.isolatedMetrics { it.admitted(metricsCandidate) } }
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
                    if (update.chunks.isNotEmpty()) onChunks(update.chunks)
                } catch (_: IllegalArgumentException) { lifecycle.failTransport() }
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
        active?.cancel(); activeId?.let { id -> runCatching { remote?.cancelInference(id) } }; Unit
    }

    private suspend fun unload() = operation.withLock {
        val observing = unloadObservations != null
        var selectedService: IOrionCortex? = null
        var selectedOwner: ServiceConnection? = null
        var selectedEpoch: Long? = null
        var selectedPid: Int? = null
        var initiallyClosed = false
        var observedDeadline: network.orion.core.time.MonotonicDeadline? = null
        var checkedAt: network.orion.core.time.MonotonicInstant? = null
        var pollAt: network.orion.core.time.MonotonicInstant? = null
        var outcome = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
        var failurePhase = OrionCortexUnloadOutcome.UNLOAD_REQUEST_FAILED
        var failureOutcome: OrionCortexUnloadOutcome? = null
        var unloadedReply = false
        var validUnloadedReply = false
        var currentAtLoopExit = false
        var closedAtLoopExit = false
        fun observe(action: () -> Unit) {
            if (observing) try { action() } catch (_: Throwable) {
                outcome = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
                runCatching { unloadObservations?.recordingFailed() }
            }
        }
        fun observationClock() = try { clock.monotonicNow() } catch (_: Throwable) { null }
        fun endpointCurrent() = remote === selectedService && connection === selectedOwner && unloadConnectionEpoch == selectedEpoch &&
            (selectedPid == null || pinnedAiPid == selectedPid)
        fun classify(at: network.orion.core.time.MonotonicInstant?, current: Boolean, endedClosed: Boolean, valid: Boolean, unloaded: Boolean) =
            classifyCortexUnloadObservation(observedDeadline, at, pollAt,
                identityKnown = unloadObservationScope != null && selectedEpoch != null && selectedOwner != null,
                endpointCurrent = current, clientClosed = initiallyClosed || endedClosed, validEnvelope = valid, unloaded = unloaded)
        fun pollingClock(): network.orion.core.time.MonotonicInstant {
            failurePhase = OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID
            return clock.monotonicNow().also { if (observing) pollAt = it }
        }
        try {
            val service = if (!observing) remote else synchronized(monitor) {
                selectedService = remote; selectedOwner = connection; initiallyClosed = closed
                selectedEpoch = if (connection == null) null else unloadConnectionEpoch?.takeIf { it > 0 }
                selectedPid = pinnedAiPid
                selectedService
            }
            if (service == null) {
                observe { outcome = OrionCortexUnloadOutcome.NO_ENDPOINT }
                return@withLock
            }
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
                        return@withTimeout
                    }
                    failurePhase = OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE
                    delay(25)
                }
                observe { synchronized(monitor) {
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
            // No user callbacks. Publication cannot enter the cleanup catch or affect terminality.
            observe {
                // Extra diagnostic clock/classification run only AFTER the original timeout/catch.
                // A slow/throwing clock must not turn a successful cleanup into lostConnection().
                when {
                    outcome == OrionCortexUnloadOutcome.NO_ENDPOINT -> checkedAt = observationClock()
                    failureOutcome != null -> { checkedAt = observationClock(); outcome = checkNotNull(failureOutcome) }
                    unloadedReply -> {
                        checkedAt = observationClock()
                        outcome = synchronized(monitor) {
                            classify(checkedAt, endpointCurrent(), closed, validUnloadedReply, true)
                        }
                    }
                    else -> {
                        checkedAt = pollAt
                        outcome = classify(checkedAt, currentAtLoopExit, closedAtLoopExit, true, false)
                    }
                }
                unloadObservations?.offer(OrionCortexUnloadObservation(unloadObservationScope, selectedEpoch, selectedPid,
                    observedDeadline?.start, checkedAt, outcome))
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
                pinnedAiPid = pid
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
            metricsCorrelation.isolatedMetrics { it.select(logicalRequestId) } ?: return null
        }
        val discovery = synchronized(monitor) {
            if (closed) return null
            metricsCorrelation.isolatedMetrics { it.claimDiscovery(selected) }
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
            metricsCorrelation.isolatedMetrics { it.mask(selected) } ?: return null
        }
        val snapshot = try {
            val reply = (selected.endpoint.service as IOrionCortex).getGenerationMetrics(
                OrionGenerationMetricsWire.query(selected.transportId))
            OrionGenerationMetricsWire.decodeSnapshot(reply, mask, selected.maxTokens, selected.contextTokens)
        } catch (_: Throwable) { null } ?: return null
        return synchronized(monitor) {
            if (closed || metricsCorrelation.isolatedMetrics { it.accepts(selected, snapshot) } != true) null else snapshot
        }
    }

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
                            deathRecipient = death; remote = service
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
                    connection = owner
                    metricsConnectionEpoch = metricsConnectionEpoch?.let { if (it < Long.MAX_VALUE) it + 1 else null }
                    if (unloadObservations != null) unloadConnectionEpoch = unloadConnectionEpoch?.let {
                        if (it < Long.MAX_VALUE) it + 1 else null
                    }
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
            loaded = null; active?.processDied()
        }
        releaseConnection(expected)
        availabilityChanged(false)
    }

    private fun releaseConnection(expected: ServiceConnection? = null) {
        synchronized(monitor) {
            if (expected != null && connection !== expected) return
            val service = remote
            deathRecipient?.let { death -> runCatching { service?.asBinder()?.unlinkToDeath(death, 0) } }
            remote = null; loaded = null; deathRecipient = null; pinnedAiPid = null
            metricsCorrelation.isolatedMetrics { it.clear() }
            connection?.let { runCatching { app.unbindService(it) } }; connection = null
        }
    }

    override fun close() {
        synchronized(monitor) { closed = true; cancel() }
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
}

internal fun orionInferenceRequestFingerprint(request: OrionInferenceRequest, context: Int, threads: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in listOf(request.modelId, request.prompt, request.context, request.maxTokens.toString(),
        request.temperature.toString(), request.priority.name, request.deadline.start.toString(),
        request.deadline.expiresElapsedRealtimeMillis.toString(), context.toString(), threads.toString())) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        bytes.fill(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

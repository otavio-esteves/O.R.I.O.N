package network.orion.app.ai

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import network.orion.ai.api.OrionInferenceLifecycle
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferenceRequest
import network.orion.ai.api.OrionInferenceStatus
import network.orion.ai.api.OrionInferenceTerminal
import network.orion.app.resources.OrionAndroidResourceObserver
import network.orion.app.resources.OrionCortexMemoryTelemetry
import network.orion.app.resources.OrionCortexResourceFactBuffer
import network.orion.app.resources.recordGovernorFact
import network.orion.app.resources.recordRestartFact
import network.orion.core.resources.OrionCortexRestartGuard
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionMinimumResourceGovernor
import network.orion.core.resources.OrionResourceBlock
import network.orion.core.resources.OrionResourceObservation
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.OrionClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator

internal data class OrionCortexResourceTiming(val maximumObservationAgeMillis: Long, val memoryExitCooldownMillis: Long) {
    init { require(maximumObservationAgeMillis > 0 && memoryExitCooldownMillis > 0) }
}

internal enum class OrionCortexUnavailable {
    CLOSED, NO_QUALIFIED_PROFILE, RESOURCE_CONFIGURATION_UNAVAILABLE, CORE_NOT_READY,
    PROCESS_INVENTORY_UNAVAILABLE, MODEL_NOT_READY, MODEL_UNAVAILABLE, PROFILE_MISMATCH, REQUEST_RETIRED,
}

/** Read-only early configuration state. No entry represents a resource or restart permit. */
internal enum class OrionCortexPreResourceReadiness(val unavailable: OrionCortexUnavailable?) {
    CLOSED(OrionCortexUnavailable.CLOSED),
    NO_QUALIFIED_PROFILE(OrionCortexUnavailable.NO_QUALIFIED_PROFILE),
    RESOURCE_CONFIGURATION_UNAVAILABLE(OrionCortexUnavailable.RESOURCE_CONFIGURATION_UNAVAILABLE),
    CORE_NOT_READY(OrionCortexUnavailable.CORE_NOT_READY),
    DECISION_NOT_EVALUATED(null),
}

/** Exact shared early predicates; later sources stay lazy after an earlier refusal. */
internal inline fun evaluateCortexPreResourceReadiness(isClosed: () -> Boolean,
    hasQualifiedPlans: () -> Boolean, hasTiming: () -> Boolean, isCoreReady: () -> Boolean): OrionCortexPreResourceReadiness = when {
    isClosed() -> OrionCortexPreResourceReadiness.CLOSED
    !hasQualifiedPlans() -> OrionCortexPreResourceReadiness.NO_QUALIFIED_PROFILE
    !hasTiming() -> OrionCortexPreResourceReadiness.RESOURCE_CONFIGURATION_UNAVAILABLE
    !isCoreReady() -> OrionCortexPreResourceReadiness.CORE_NOT_READY
    else -> OrionCortexPreResourceReadiness.DECISION_NOT_EVALUATED
}

internal sealed interface OrionCortexRunResult {
    data class Unavailable(val reason: OrionCortexUnavailable) : OrionCortexRunResult
    data class Completed(val status: OrionInferenceStatus, val admissionBlock: OrionCortexAdmissionBlock? = null) : OrionCortexRunResult
}

/** MAIN technical owner. Empty trusted configuration cannot bind, probe storage or grant inference. */
internal class OrionCortexRuntimeOwner(
    context: Context,
    private val importer: OrionModelImportCoordinator,
    private val database: OrionDatabase,
    private val clock: OrionClock,
    qualifiedPlans: List<OrionInferenceResourcePlan> = emptyList(),
    private val timing: OrionCortexResourceTiming? = null,
    private val coreReady: () -> Boolean = { false },
    private val resourceFacts: OrionCortexResourceFactBuffer? = null,
    private val cycleObservations: OrionCortexCycleObserver? = null,
    private val singleAttemptTemplate: OrionCortexAttemptTemplate? = null,
    private val runtimeCaptureObservation: OrionRuntimeCaptureObservationConfiguration? = null,
    private val qualificationExitConfiguration: OrionQualificationExitConfiguration? = null,
    private val voiceMemory: network.orion.app.resources.OrionVoiceMemoryCollector? = null,
    private val cachedAiRssLimitKiB: Long? = null,
) : AutoCloseable {
    private val app = context.applicationContext
    private val plans = qualifiedPlans.toList()
    private val byId = plans.associateBy { it.model.modelId }
    private val monitor = Any()
    private val resourceMonitor = Any()
    private val recovery = Mutex()
    @Volatile private var closed = false
    @Volatile private var client: OrionCortexClient? = null
    private var observation: OrionResourceObservation? = null
    private var recoveryAfter: String? = null
    private var recoveryComplete = false
    private var copiesClean = false
    private var unregisteredAfter: String? = null
    private var unregisteredClean = false
    private val requestHistory = OrionInferenceRequestLedger()
    private val cycleScope = cycleObservations.isolatedCycle { Any() }
    fun newRequestId(): String { check(!closed); return requestHistory.newRequestId() }
    private val governor = timing?.takeIf { plans.isNotEmpty() }?.let {
        OrionMinimumResourceGovernor(clock, { observation }, plans, it.maximumObservationAgeMillis, it.memoryExitCooldownMillis)
    }
    private val restart = timing?.takeIf { plans.isNotEmpty() }?.let {
        OrionCortexRestartGuard(clock, plans, it.maximumObservationAgeMillis, it.memoryExitCooldownMillis)
    }
    private val telemetry = timing?.let { OrionCortexMemoryTelemetry(app.packageName, clock, it.maximumObservationAgeMillis) }
    private val observer = timing?.let { limits -> OrionAndroidResourceObserver(app, clock,
        auxiliaryMemory = {
            val inventory = discoverInventory() ?: return@OrionAndroidResourceObserver null
            if (voiceMemory == null) readAiMemory(inventory)
            else voiceMemory.collect { readAiMemory(setOf(app.packageName, "${app.packageName}:ai")) }
        }, maximumMemoryAgeMillis = limits.maximumObservationAgeMillis, cachedAiRssLimitKiB = cachedAiRssLimitKiB) }

    /** Start both cooldowns from the same observation without binding the service or choosing a model. */
    internal fun observePreviewExitHistory(): Boolean = synchronized(resourceMonitor) {
        if (cachedAiRssLimitKiB == null || closed || !coreReady() || discoverInventory() == null) return@synchronized false
        val snapshot = observer?.observe(null, 1, 32, 1) ?: return@synchronized false
        observation = snapshot.observation
        val resource = governor?.evaluate()?.block
        val restartBlock = restart?.evaluate(snapshot.aiExitHistory)?.block
        if (restartBlock != null) OrionS21CortexPreview.reportRestart(restartBlock)
        val governorCooling = resource == OrionResourceBlock.MEMORY_EXIT || resource == OrionResourceBlock.RESOURCE_EXIT
        if (restartBlock == null && governorCooling) OrionS21CortexPreview.reportResource(resource)
        restartBlock == OrionRestartBlock.COOLDOWN || (restartBlock == null && governorCooling)
    }

    /** Wait once, before assigning an inference deadline. Admission still takes fresh decisions. */
    internal suspend fun awaitPreviewExitCooldown() {
        if (cachedAiRssLimitKiB == null || timing == null) return
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timing.memoryExitCooldownMillis + 1_000) {
                while (observePreviewExitHistory()) delay(1_000)
            }
        }
    }

    private fun readAiMemory(inventory: Set<String>): network.orion.app.resources.OrionAppMemoryEvidence? {
        val selectedClient = client ?: return null
        val captureWindow = selectedClient.runtimeHealthWindow()
        val receipt = selectedClient.currentMemoryTelemetry() ?: return null
        val pid = receipt.getInt("pid")
        val evidence = telemetry?.read(receipt, pid, inventory, true)
        selectedClient.runtimeMemoryValidated(captureWindow, pid, evidence?.sampledAt)
        return evidence
    }

    init {
        require(Application.getProcessName() == app.packageName) { "Cortex runtime belongs to MAIN." }
        require(plans.size <= 64 && byId.size == plans.size && plans.all { validModelHash(it.model.modelId) })
    }

    /** Stops before inventory, storage, Binder, governor or restart evaluation, even when configured. */
    internal fun preResourceReadiness(): OrionCortexPreResourceReadiness = evaluateCortexPreResourceReadiness(
        { closed }, { plans.isNotEmpty() }, { timing != null }, coreReady)

    /** Read-only candidate metadata; never a permit, registry lookup, model load or resource decision. */
    internal fun chatResourcePlan(): OrionInferenceResourcePlan? =
        if (preResourceReadiness().unavailable == null) plans.firstOrNull { it.model.modelId == network.orion.app.chat.QWEN_MODEL_ID } else null

    /** Candidature only. Early refusals precede even the one selected registry lookup. */
    internal suspend fun selectSingleAttemptTemplate(): OrionCortexAttemptConfiguration = selectAttemptTemplate(true)

    internal suspend fun selectRuntimeCaptureTemplate(): OrionCortexAttemptConfiguration = selectAttemptTemplate(false)
    internal fun runtimeCaptureObservationConfiguration(): OrionRuntimeCaptureObservationConfiguration? = runtimeCaptureObservation

    internal fun instrumentedConfigurationBinding(template: OrionCortexAttemptTemplate): OrionInstrumentedConfigurationBinding? =
        if (closed || template !== singleAttemptTemplate) null else
            deriveInstrumentedConfigurationBinding(plans, timing, template, runtimeCaptureObservation, qualificationExitConfiguration)

    /** Existing endpoint only. No resources/guard evaluation or changes to functional ownership. */
    internal fun instrumentedMemoryObservation(exitSlot: OrionQualificationAiSlot? = null): OrionRuntimeAiMemoryReading? {
        val selected = synchronized(monitor) { if (closed) null else client } ?: return null
        val value = selected.instrumentedMemoryObservation(exitSlot)
        return synchronized(monitor) { if (!closed && client === selected) value else null }
    }

    internal fun drainInstrumentedResourceFacts(): network.orion.app.resources.OrionCortexResourceFactBatch? =
        if (closed) null else resourceFacts?.drain()

    internal fun newInstrumentedExitSource(): OrionQualificationExitSource? = try {
        val config = qualificationExitConfiguration
        if (closed || resourceFacts == null || config == null || app.packageName != "network.orion.qualification") null
        else OrionQualificationExitSource(app, config)
    } catch (_: Throwable) { null }

    internal fun matchesHistoricalProcessDeath(capture: OrionCortexRuntimeCaptureSession, incident: OrionQualificationExitIncident): Boolean =
        capture.matchesHistoricalProcessDeath(this, incident)

    internal fun instrumentedScalarIdle(): Boolean {
        val selected = synchronized(monitor) { if (closed) return false else client }
        return selected?.instrumentedScalarIdle() ?: !OrionRuntimeMemorySamplingWorker.outstanding()
    }

    private suspend fun selectAttemptTemplate(requireCycleObserver: Boolean): OrionCortexAttemptConfiguration = withContext(Dispatchers.IO) {
        fun refuse(reason: OrionCortexAttemptRefusal) = OrionCortexAttemptConfiguration.Refused(reason)
        preResourceReadiness().unavailable?.let { return@withContext refuse(singleAttemptRefusal(it)) }
        if (requireCycleObserver && cycleScope == null) return@withContext refuse(OrionCortexAttemptRefusal.OBSERVER_UNAVAILABLE)
        if (!requireCycleObserver && runtimeCaptureObservation == null)
            return@withContext refuse(OrionCortexAttemptRefusal.RESOURCE_CONFIGURATION_UNAVAILABLE)
        val template = singleAttemptTemplate ?: return@withContext refuse(OrionCortexAttemptRefusal.TEMPLATE_UNAVAILABLE)
        if (!template.isValid()) return@withContext refuse(OrionCortexAttemptRefusal.TEMPLATE_INVALID)
        val plan = byId[template.modelId] ?: return@withContext refuse(OrionCortexAttemptRefusal.PROFILE_MISMATCH)
        val selected = database.models().find(template.modelId)
        if (selected?.status != "READY") return@withContext refuse(OrionCortexAttemptRefusal.MODEL_NOT_READY)
        if (!profileMatchesReadyModel(plan, selected, template.contextTokens, template.threads, template.maxTokens))
            return@withContext refuse(OrionCortexAttemptRefusal.PROFILE_MISMATCH)
        OrionCortexAttemptConfiguration.Ready(template)
    }

    /** Called only after candidate consumption and observer reservation, never for evidence time. */
    internal fun prepareSingleAttemptRequest(template: OrionCortexAttemptTemplate): OrionInferenceRequest? {
        if (closed || template !== singleAttemptTemplate) return null
        val now = clock.monotonicNow()
        if (now.bootSessionId == null) return null
        val deadline = now.deadlineAfter(template.requestDurationMillis)
        val id = newRequestId()
        return OrionInferenceRequest(id, 1, template.modelId, template.workloadPrompt, template.workloadContext,
            template.maxTokens, template.temperature, template.priority, deadline, id)
    }

    internal fun discardSingleAttemptReservation(token: OrionCortexCycleToken): Boolean = cycleScope?.let { scope ->
        cycleObservations.isolatedCycle(token) { it.discardUnclaimed(scope, token) }
    } == true

    fun beginCycleObservation(): OrionCortexCycleToken? = cycleScope?.let { scope ->
        cycleObservations.isolatedCycle { it.begin(scope) }
    }

    /** The token is technical observation ownership, never request/profile/admission authority. */
    suspend fun runObserved(token: OrionCortexCycleToken?, modelId: String, request: OrionInferenceRequest,
        contextTokens: Int, threads: Int, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult =
        runObserved(token, modelId, request, contextTokens, threads, null, onChunks)

    internal suspend fun runObserved(token: OrionCortexCycleToken?, modelId: String, request: OrionInferenceRequest,
        contextTokens: Int, threads: Int, runtimeCapture: OrionCortexRuntimeCaptureSession?,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult {
        val selected = runtimeCapture?.takeIf { it.claim(this) }
        val capture = cycleScope?.let { scope -> cycleObservations.isolatedCycle(token) { it.claim(scope, token) } }
        return try {
            val result = runOwned(modelId, request, contextTokens, threads, onChunks, capture, selected)
            selected?.runReturned(false)
            cycleObservations.isolatedCycle(capture) { it.returned(capture, result) }
            result
        } catch (failure: Throwable) {
            selected?.runReturned(true)
            cycleObservations.isolatedCycle(capture) { it.threw(capture, when (failure) {
                is CancellationException -> OrionCycleThrown.CANCELLED
                is InterruptedException -> OrionCycleThrown.INTERRUPTED
                else -> OrionCycleThrown.OTHER
            }) }
            throw failure
        }
    }

    /** Explicit internal preparation only; no factory activation, workload choice or admission. */
    internal fun beginRuntimeCapture(sampling: OrionRuntimeSamplingConfiguration? = null): OrionCortexRuntimeCaptureSession? = try {
        if (closed) null else OrionCortexRuntimeCaptureSession.create(this, android.os.Process.myPid(), sampling,
            if (sampling == null) null else OrionRuntimeMainMemoryProbe(app))
    } catch (_: Throwable) { null }

    /** Same original path and original callback; normal run/runObserved remain capture-absent. */
    internal suspend fun runCaptured(capture: OrionCortexRuntimeCaptureSession?, modelId: String, request: OrionInferenceRequest,
        contextTokens: Int, threads: Int, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult {
        val selected = capture?.takeIf { it.claim(this) }
        return try {
            val original = runOwned(modelId, request, contextTokens, threads, onChunks, null, selected)
            selected?.runReturned(false)
            original
        } catch (failure: Throwable) { selected?.runReturned(true); throw failure }
    }

    internal fun readRuntimeCapture(capture: OrionCortexRuntimeCaptureSession): OrionRuntimeSessionReceipt? = capture.readAfterRun(this)

    internal fun runtimeCaptureManualMoment(capture: OrionCortexRuntimeCaptureSession): network.orion.core.time.OrionObservationMoment? =
        capture.manualExportMoment(this)

    /** Explicit manual export, after readRuntimeCapture sealed the separate sampling window. */
    internal fun freezeRuntimeCapture(capture: OrionCortexRuntimeCaptureSession,
        context: OrionRuntimeQualifiedContext): OrionRuntimeExportSnapshot? =
        if (context.matches(app.packageName, app.applicationInfo.uid)) capture.freezeForExport(this, context) else null

    suspend fun run(modelId: String, request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult =
        runOwned(modelId, request, contextTokens, threads, onChunks, null)

    private suspend fun runOwned(modelId: String, request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit, cycle: OrionCortexCycleToken?,
        runtimeCapture: OrionCortexRuntimeCaptureSession? = null): OrionCortexRunResult = withContext(Dispatchers.IO) {
        fun unavailable(reason: OrionCortexUnavailable): OrionCortexRunResult.Unavailable {
            cycleObservations.isolatedCycle(cycle) { it.noAttempt(cycle) }
            return OrionCortexRunResult.Unavailable(reason)
        }
        preResourceReadiness().unavailable?.let { return@withContext unavailable(it) }
        val plan = byId[modelId]
        if (request.modelId != modelId || plan == null) return@withContext unavailable(OrionCortexUnavailable.PROFILE_MISMATCH)
        val preparationContext = currentCoroutineContext()
        preparationContext.ensureActive()
        val fingerprint = try {
            validateOrionInferenceRequest(request, contextTokens, threads)
            orionInferenceRequestFingerprint(request, contextTokens, threads)
        } catch (_: IllegalArgumentException) { return@withContext unavailable(OrionCortexUnavailable.MODEL_UNAVAILABLE) }
        val lifecycle = OrionInferenceLifecycle(request, clock)
        fun checkRequest() {
            preparationContext.ensureActive()
            val status = lifecycle.status()
            if (status.terminal != null) throw PreparationStopped(status)
        }
        try {
            requestHistory.existingResult(request.requestId, fingerprint)?.let {
                runtimeCapture?.retainedResult()
                cycleObservations.isolatedCycle(cycle) { observer -> observer.retained(cycle) }
                return@withContext OrionCortexRunResult.Completed(it)
            }
            checkRequest()
            val originalInventory = discoverInventory()
                ?: return@withContext unavailable(OrionCortexUnavailable.PROCESS_INVENTORY_UNAVAILABLE)
            checkRequest()
            val beforeRecovery = database.models().find(modelId)
            // One registry row per request: no scan or hash work joins mandatory Core startup.
            recovery.withLock {
                checkRequest()
                // A later interrupted import may appear after the initial cursor
                // completed. Reconcile only that selected row, never reopen a scan.
                if (beforeRecovery?.status in setOf("IMPORTED", "VALIDATING")) importer.recoverSelected(modelId, ::checkRequest)
                if (!copiesClean) {
                    val copies = importer.cleanupInterruptedCopies(25, ::checkRequest)
                    copiesClean = !copies.needsContinuation && copies.blocked == 0
                }
                if (!recoveryComplete) {
                    val page = importer.recoverPage(recoveryAfter, 1, ::checkRequest)
                    recoveryAfter = page.nextAfterModelId
                    recoveryComplete = recoveryAfter == null
                }
                if (!unregisteredClean) {
                    val files = importer.cleanupUnregisteredFiles(unregisteredAfter, 1, ::checkRequest)
                    unregisteredAfter = files.nextAfterFileName
                    unregisteredClean = unregisteredAfter == null && files.blocked == 0
                }
            }
            checkRequest()
            // A pending selected row never gains authority in this same attempt merely through recovery.
            if (beforeRecovery?.status != "READY") return@withContext unavailable(OrionCortexUnavailable.MODEL_NOT_READY)
            val selected = database.models().find(modelId)
            if (!profileMatchesReadyModel(plan, selected, contextTokens, threads, request.maxTokens))
                return@withContext unavailable(OrionCortexUnavailable.PROFILE_MISMATCH)
            cycleObservations.isolatedCycle(cycle) { it.trustedModel(cycle, plan.model.modelId) }
            val descriptor = importer.openReadOnly(modelId, ::checkRequest)
                ?: return@withContext unavailable(OrionCortexUnavailable.MODEL_UNAVAILABLE)
            descriptor.use {
                checkRequest()
                if (closed) return@withContext unavailable(OrionCortexUnavailable.CLOSED)
                if (!coreReady()) return@withContext unavailable(OrionCortexUnavailable.CORE_NOT_READY)
                val ownedClient = synchronized(monitor) {
                    if (closed) null else client ?: OrionCortexClient(app, clock,
                        resources = { id, context, workerThreads, tokens -> synchronized(resourceMonitor) {
                            val cycleClient = client
                            val cycleCallback = cycleObservations.isolatedCycle {
                                cycleClient?.cycleCallbackScope(OrionCycleCallback.RESOURCE)?.takeIf { it.token.owner === cycleScope }
                            }
                            val snapshot = if (coreReady() && discoverInventory() != null) byId[id]?.let { trusted ->
                                observer?.observe(trusted, workerThreads, context, tokens)
                            } else null
                            observation = snapshot?.observation
                            val decision = checkNotNull(governor).evaluate()
                            if (cachedAiRssLimitKiB != null) OrionS21CortexPreview.reportResource(decision.block)
                            if (cycleCallback != null) cycleObservations.isolatedCycle(cycleCallback.token) {
                                it.governor(cycleCallback, cycleClient?.cycleEndpoint(cycleCallback), snapshot, decision)
                            }
                            resourceFacts.recordGovernorFact(snapshot, decision)
                        } },
                        restart = {
                            val cycleClient = client
                            val cycleCallback = cycleObservations.isolatedCycle {
                                cycleClient?.cycleCallbackScope(OrionCycleCallback.RESTART)?.takeIf { it.token.owner === cycleScope }
                            }
                            val guard = checkNotNull(restart)
                            val snapshot = if (coreReady() && discoverInventory() != null)
                                observer?.observe(null, 1, 32, 1) else null
                            val decision = guard.evaluate(snapshot?.aiExitHistory)
                            if (cachedAiRssLimitKiB != null) OrionS21CortexPreview.reportRestart(decision.block)
                            if (cycleCallback != null) cycleObservations.isolatedCycle(cycleCallback.token) {
                                it.restart(cycleCallback, cycleClient?.cycleEndpoint(cycleCallback), snapshot, decision)
                            }
                            resourceFacts.recordRestartFact(snapshot, decision)
                        },
                        requestHistory = requestHistory, cycleObservations = cycleObservations).also { client = it }
                } ?: return@withContext unavailable(OrionCortexUnavailable.CLOSED)
                if (runtimeCapture != null && selected != null) runtimeCapture.selected(this@OrionCortexRuntimeOwner,
                    selected.sha256, selected.sizeBytes, request.requestId, request.deadline, timing?.maximumObservationAgeMillis,
                    contextTokens, threads, request.maxTokens, originalInventory)
                var admissionBlock: OrionCortexAdmissionBlock? = null
                val status = ownedClient.runInference(checkNotNull(selected), it, request, contextTokens, threads,
                    cycle, runtimeCapture, onChunks) { block -> admissionBlock = block }
                OrionCortexRunResult.Completed(status, admissionBlock.takeIf { status.terminal == OrionInferenceTerminal.FAILED })
            }
        } catch (stopped: PreparationStopped) {
            val invocation = cycleObservations.isolatedCycle(cycle) { if (cycle == null) null else OrionCortexCycleInvocation() }
            val status = requestHistory.runOnce(request.requestId, fingerprint) {
                invocation?.logicalOwnerEntered = true
                cycleObservations.isolatedCycle(cycle) { it.logicalOwner(cycle, request.requestId) }
                stopped.status
            }
            cycleObservations.isolatedCycle(cycle) { it.clientResult(cycle, invocation?.logicalOwnerEntered?.not()) }
            OrionCortexRunResult.Completed(status)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: network.orion.ai.api.OrionRetiredRequestException) { unavailable(OrionCortexUnavailable.REQUEST_RETIRED) }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { unavailable(OrionCortexUnavailable.MODEL_UNAVAILABLE) }
    }

    /** Explicit manual phase after run. No port creation, bind, clock or resource refresh. */
    fun readCycleObservation(token: OrionCortexCycleToken): OrionCortexCycleReceipt? {
        val scope = cycleScope ?: return null
        val read = cycleObservations.isolatedCycle(token) { it.prepareRead(scope, token) } ?: return null
        if (!read.mayQuery) return cycleObservations.isolatedCycle(token) { it.completeRead(read, null, false) }
        val selected = synchronized(monitor) { if (closed) null else client }
        val currentBefore = cycleObservations.isolatedCycle(token) {
            selected?.cycleEndpointCurrent(token, read.clientScope, read.endpoint)
        } == true
        val metrics = if (!currentBefore || read.logicalId == null) null else try {
            selected?.generationMetrics(read.logicalId)
        } catch (_: Throwable) { null }
        val currentAfter = synchronized(monitor) { !closed && client === selected } && cycleObservations.isolatedCycle(token) {
            selected?.cycleEndpointCurrent(token, read.clientScope, read.endpoint)
        } == true
        return cycleObservations.isolatedCycle(token) { it.completeRead(read, metrics, currentBefore && currentAfter) }
    }

    fun releaseCycleObservation(token: OrionCortexCycleToken): Boolean = cycleScope?.let { scope ->
        cycleObservations.isolatedCycle(token) { it.release(scope, token) }
    } == true

    private class PreparationStopped(val status: OrionInferenceStatus) : RuntimeException("Cortex preparation stopped.")

    @Suppress("DEPRECATION")
    private fun discoverInventory(): Set<String>? = try {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.MATCH_DISABLED_COMPONENTS)
        val components = sequenceOf(info.activities?.asSequence(), info.services?.asSequence(),
            info.receivers?.asSequence(), info.providers?.asSequence()).filterNotNull().flatten()
            .take(257).map { it.processName }.toList()
        declaredCortexInventory(app.packageName, info.applicationInfo?.processName, components,
            includeVoice = voiceMemory != null)
    } catch (_: Exception) { null }

    override fun close() {
        val owned = synchronized(monitor) { closed = true; client.also { client = null } }
        owned?.close()
    }
}

internal fun validModelHash(value: String) = value.matches(Regex("[a-f0-9]{64}")) && value.any { it != '0' }

internal fun profileMatchesReadyModel(plan: OrionInferenceResourcePlan, model: ModelRegistryEntity?,
    contextTokens: Int, threads: Int, tokens: Int): Boolean {
    val contextLimit = model?.contextLimit ?: return false
    return model.status == "READY" && validModelHash(model.modelId) && model.modelId == model.sha256 &&
        model.modelId == plan.model.modelId && model.sizeBytes > 0 && plan.model.estimatedMemoryBytes >= model.sizeBytes &&
        contextTokens in 32..plan.maxContext && contextTokens <= contextLimit &&
        threads in 1..plan.maxThreads && tokens in 1..plan.maxTokens
}

/** Null component process inherits the trusted application default; unknown inventories deny. */
internal fun supportedCortexInventory(mainName: String, applicationProcess: String?, components: List<String?>): Set<String>? =
    declaredCortexInventory(mainName, applicationProcess, components, includeVoice = false)

internal fun declaredCortexInventory(mainName: String, applicationProcess: String?,
    components: List<String?>, includeVoice: Boolean): Set<String>? {
    if (mainName.isBlank() || ':' in mainName || applicationProcess != mainName) return null
    val bounded = components.take(257)
    if (bounded.size > 256) return null
    val names = bounded.map { name -> if (name == null) mainName else if (name.startsWith(':')) mainName + name else name }.toSet() + mainName
    val base = setOf(mainName, "$mainName:ai")
    val expected = if (includeVoice) base + setOf("$mainName:voice", "$mainName:voice_session") else base
    return names.takeIf { it == expected }
}

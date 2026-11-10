package network.orion.app.ai

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferenceRequest
import network.orion.ai.api.OrionInferenceStatus
import network.orion.app.resources.OrionAndroidResourceObserver
import network.orion.app.resources.OrionCortexMemoryTelemetry
import network.orion.core.resources.OrionCortexRestartGuard
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionMinimumResourceGovernor
import network.orion.core.resources.OrionResourceObservation
import network.orion.core.time.OrionClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator

internal data class OrionCortexResourceTiming(val maximumObservationAgeMillis: Long, val memoryExitCooldownMillis: Long) {
    init { require(maximumObservationAgeMillis > 0 && memoryExitCooldownMillis > 0) }
}

internal enum class OrionCortexUnavailable {
    CLOSED, NO_QUALIFIED_PROFILE, RESOURCE_CONFIGURATION_UNAVAILABLE, CORE_NOT_READY,
    PROCESS_INVENTORY_UNAVAILABLE, MODEL_NOT_READY, MODEL_UNAVAILABLE, PROFILE_MISMATCH,
}

internal sealed interface OrionCortexRunResult {
    data class Unavailable(val reason: OrionCortexUnavailable) : OrionCortexRunResult
    data class Completed(val status: OrionInferenceStatus) : OrionCortexRunResult
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
            val receipt = client?.currentMemoryTelemetry() ?: return@OrionAndroidResourceObserver null
            // currentMemoryTelemetry already authenticates this connection's process name and PID.
            telemetry?.read(receipt, receipt.getInt("pid"), inventory, true)
        }, maximumMemoryAgeMillis = limits.maximumObservationAgeMillis) }

    init {
        require(Application.getProcessName() == app.packageName) { "Cortex runtime belongs to MAIN." }
        require(plans.size <= 64 && byId.size == plans.size && plans.all { validModelHash(it.model.modelId) })
    }

    suspend fun run(modelId: String, request: OrionInferenceRequest, contextTokens: Int, threads: Int,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult = withContext(Dispatchers.IO) {
        fun unavailable(reason: OrionCortexUnavailable) = OrionCortexRunResult.Unavailable(reason)
        if (closed) return@withContext unavailable(OrionCortexUnavailable.CLOSED)
        if (plans.isEmpty()) return@withContext unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE)
        if (timing == null) return@withContext unavailable(OrionCortexUnavailable.RESOURCE_CONFIGURATION_UNAVAILABLE)
        if (!coreReady()) return@withContext unavailable(OrionCortexUnavailable.CORE_NOT_READY)
        if (discoverInventory() == null) return@withContext unavailable(OrionCortexUnavailable.PROCESS_INVENTORY_UNAVAILABLE)
        val plan = byId[modelId]
        if (request.modelId != modelId || plan == null) return@withContext unavailable(OrionCortexUnavailable.PROFILE_MISMATCH)
        currentCoroutineContext().ensureActive()
        try {
            val beforeRecovery = database.models().find(modelId)
            // One registry row per request: no scan or hash work joins mandatory Core startup.
            recovery.withLock {
                // A later interrupted import may appear after the initial cursor
                // completed. Reconcile only that selected row, never reopen a scan.
                if (beforeRecovery?.status in setOf("IMPORTED", "VALIDATING")) importer.recoverSelected(modelId)
                if (!copiesClean) {
                    val copies = importer.cleanupInterruptedCopies(25)
                    copiesClean = !copies.needsContinuation && copies.blocked == 0
                }
                if (!recoveryComplete) {
                    val page = importer.recoverPage(recoveryAfter, 1)
                    recoveryAfter = page.nextAfterModelId
                    recoveryComplete = recoveryAfter == null
                }
                if (!unregisteredClean) {
                    val files = importer.cleanupUnregisteredFiles(unregisteredAfter, 1)
                    unregisteredAfter = files.nextAfterFileName
                    unregisteredClean = unregisteredAfter == null && files.blocked == 0
                }
            }
            currentCoroutineContext().ensureActive()
            // A pending selected row never gains authority in this same attempt merely through recovery.
            if (beforeRecovery?.status != "READY") return@withContext unavailable(OrionCortexUnavailable.MODEL_NOT_READY)
            val selected = database.models().find(modelId)
            if (!profileMatchesReadyModel(plan, selected, contextTokens, threads, request.maxTokens))
                return@withContext unavailable(OrionCortexUnavailable.PROFILE_MISMATCH)
            val descriptor = importer.openReadOnly(modelId)
                ?: return@withContext unavailable(OrionCortexUnavailable.MODEL_UNAVAILABLE)
            descriptor.use {
                currentCoroutineContext().ensureActive()
                if (closed) return@withContext unavailable(OrionCortexUnavailable.CLOSED)
                if (!coreReady()) return@withContext unavailable(OrionCortexUnavailable.CORE_NOT_READY)
                val ownedClient = synchronized(monitor) {
                    if (closed) null else client ?: OrionCortexClient(app, clock,
                        resources = { id, context, workerThreads, tokens -> synchronized(resourceMonitor) {
                            observation = if (coreReady() && discoverInventory() != null) byId[id]?.let { trusted ->
                                observer?.observe(trusted, workerThreads, context, tokens)?.observation
                            } else null
                            checkNotNull(governor).evaluate()
                        } },
                        restart = { checkNotNull(restart).evaluate(if (coreReady() && discoverInventory() != null)
                            observer?.observe(null, 1, 32, 1)?.aiExitHistory else null) }).also { client = it }
                } ?: return@withContext unavailable(OrionCortexUnavailable.CLOSED)
                OrionCortexRunResult.Completed(ownedClient.runInference(checkNotNull(selected), it, request,
                    contextTokens, threads, onChunks))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) { unavailable(OrionCortexUnavailable.MODEL_UNAVAILABLE) }
    }

    @Suppress("DEPRECATION")
    private fun discoverInventory(): Set<String>? = try {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.MATCH_DISABLED_COMPONENTS)
        val components = sequenceOf(info.activities?.asSequence(), info.services?.asSequence(),
            info.receivers?.asSequence(), info.providers?.asSequence()).filterNotNull().flatten()
            .take(257).map { it.processName }.toList()
        supportedCortexInventory(app.packageName, info.applicationInfo?.processName, components)
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
internal fun supportedCortexInventory(mainName: String, applicationProcess: String?, components: List<String?>): Set<String>? {
    if (mainName.isBlank() || ':' in mainName || applicationProcess != mainName) return null
    val bounded = components.take(257)
    if (bounded.size > 256) return null
    val names = bounded.map { name -> if (name == null) mainName else if (name.startsWith(':')) mainName + name else name }.toSet() + mainName
    return names.takeIf { it == setOf(mainName, "$mainName:ai") }
}

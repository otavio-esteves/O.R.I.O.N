package network.orion.app.cortex

import android.content.Context
import java.util.UUID
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexClient
import network.orion.app.resources.AndroidOrionResourceObserver
import network.orion.core.ingress.StartupBarrier
import network.orion.core.resources.*
import network.orion.core.time.OrionClock
import network.orion.data.database.ModelRegistryStatus
import network.orion.data.modelregistry.*

/**
 * MAIN composition boundary. Registry readiness is separate from resource qualification.
 * There are deliberately no default real-model plans. A caller/LLM/model file cannot
 * invent a budget, bind an AI process or authorize an Android Skill through this port.
 * Blocking registry/observation operations are used only on Core IO threads.
 */
internal class OrionMainCortexAccess(
    context: Context,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    private val observations: AndroidOrionResourceObserver,
    val registry: OrionModelRegistry,
    qualifiedPlans: List<OrionInferenceResourcePlan> = emptyList(),
    private val onHealth: (OrionCortexHealth) -> Unit,
) : AutoCloseable {
    private val plans = qualifiedPlans.associateBy { it.model.modelId }
    val qualifiedModelPlanCount: Int get() = plans.size
    @Volatile private var registryRecovered = false
    @Volatile private var closed = false
    private val evaluating = ThreadLocal<OrionInferenceResourcePlan>()
    val governor = OrionMinimumResourceGovernor(clock, {
        val plan = evaluating.get()
        observations.observe(plan?.model, plan?.maxThreads ?: 0, plan?.maxContext ?: 0, plan?.maxTokens ?: 0)
    }, qualifiedPlans, MAXIMUM_SAMPLE_AGE_MS, MEMORY_COOLDOWN_MS)
    private val restart = OrionAiRestartGuard(clock, maximumAttemptsPerBoot = 3,
        minimumAttemptIntervalMillis = 10_000, memoryCooldownMillis = MEMORY_COOLDOWN_MS,
        maximumMemoryDeathsPerBoot = 3)
    private val client = OrionCortexClient(context, clock, OrionCortexAdmissionGuard(::approve)) { health ->
        if (health == OrionCortexHealth.UNAVAILABLE) restart.recordDeath(memoryEvidence = false)
        onHealth(health)
    }
    @Volatile var lastResourceBlock: OrionResourceBlock? = OrionResourceBlock.MODEL_UNQUALIFIED
        private set

    init { require(plans.size == qualifiedPlans.size) }

    private fun approve(modelId: String, operation: OrionCortexOperation): OrionCortexApproval? {
        if (closed || !registryRecovered || !barrier.isOpen()) return null
        val plan = plans[modelId] ?: run { lastResourceBlock = OrionResourceBlock.MODEL_UNQUALIFIED; return null }
        val model = registry.find(modelId)?.takeIf { it.status == ModelRegistryStatus.READY } ?: return null
        if (model.modelId != plan.model.modelId || plan.maxContext > (model.contextLimit ?: 0)) return null
        // The complete envelope is evaluated once, rather than mixing independently
        // resampled limits with an earlier yes/no resource decision.
        evaluating.set(plan)
        val decision = try { governor.evaluate() } finally { evaluating.remove() }
        lastResourceBlock = decision.block
        if (!decision.allowed || decision.plan != plan || !barrier.isOpen()) return null
        if (operation == OrionCortexOperation.BIND) {
            restart.observeExits(observations.recentAiExits())
            if (!restart.allowAttempt()) return null
        }
        val sampled = decision.sampledAt ?: return null
        return OrionCortexApproval(modelId, plan.maxContext, plan.maxThreads, plan.maxTokens,
            sampled, MAXIMUM_SAMPLE_AGE_MS, keepAliveMs = plan.keepAliveMs)
    }

    /** The trusted caller already holds a chosen registry identity; no arbitrary file path is accepted. */
    fun loadRegistered(modelId: String, onChunk: (OrionCortexChunk) -> Unit): UUID? {
        if (closed || !registryRecovered || !barrier.isOpen() || modelId !in plans) return null
        val opened = registry.openReady(modelId) as? OrionModelOpenResult.Ready ?: return null
        return opened.descriptor.use { descriptor ->
            client.loadModel(modelId, descriptor, opened.model.sizeBytes, onChunk = onChunk)
        }
    }
    fun infer(modelId: String, prompt: String, maxTokens: Int, onChunk: (OrionCortexChunk) -> Unit): UUID? {
        if (closed || !registryRecovered || !barrier.isOpen() || modelId !in plans) return null
        return client.startInference(modelId, prompt, maxTokens, onChunk = onChunk)
    }
    fun cancel(requestId: UUID) = client.cancelInference(requestId)
    fun status() = client.getStatus()
    fun recoverRegistry(): OrionModelRecoveryReport {
        val report = registry.recover()
        if (!closed) registryRecovered = report.blockedArtifacts == 0
        return report
    }
    override fun close() { closed = true; registryRecovered = false; client.close() }

    companion object {
        private const val MAXIMUM_SAMPLE_AGE_MS = 1_000L
        private const val MEMORY_COOLDOWN_MS = 60_000L
    }
}

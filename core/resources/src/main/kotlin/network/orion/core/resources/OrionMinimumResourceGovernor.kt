package network.orion.core.resources

import java.util.Collections
import java.util.concurrent.CancellationException
import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock

enum class OrionProcessImportance { FOREGROUND, BACKGROUND, UNKNOWN }
enum class OrionMemoryPressure { NONE, MODERATE, CRITICAL, UNKNOWN }
enum class OrionResourceBlock {
    OBSERVATION_UNAVAILABLE, OBSERVATION_INVALID, OBSERVATION_STALE, EXIT_HISTORY_UNAVAILABLE,
    MODEL_UNQUALIFIED, BACKGROUND, BATTERY, THERMAL, POWER_SAVER, REQUEST_LIMITS,
    MEMORY_PRESSURE, MEMORY_EXIT, MEMORY_BUDGET, RESOURCE_EXIT,
}

/** Supplied by trusted, qualified configuration; construction alone is not device qualification. */
data class OrionInferenceResourcePlan(
    val model: InferenceModelProfile,
    val tier: String,
    val maxThreads: Int,
    val maxContext: Int,
    val maxTokens: Int,
    val keepAliveMs: Long,
    val processBudgetBytes: Long,
    val aggregateBudgetBytes: Long,
    val systemReserveBytes: Long,
    val minimumBatteryPercent: Int,
    val maxThermalStatus: Int,
) {
    init {
        require(tier.isNotBlank() && maxThreads > 0 && maxContext > 0 && maxTokens > 0)
        require(keepAliveMs >= 0 && processBudgetBytes > 0 && aggregateBudgetBytes > 0)
        require(systemReserveBytes >= 0 && minimumBatteryPercent in 1..100)
        require(maxThermalStatus in 0..2) // Android NONE..MODERATE; never permit SEVERE+.
    }
}

/** MAIN adapter must include every app process and native/JNI usage in the aggregate. */
data class OrionResourceObservation(
    val sampledAt: MonotonicInstant,
    val model: InferenceModelProfile?,
    val batteryPercent: Int?,
    val thermalStatus: Int?,
    val foreground: Boolean?,
    val importance: OrionProcessImportance,
    val powerSave: Boolean?,
    val memoryPressure: OrionMemoryPressure,
    val processMemoryBytes: Long?,
    val aggregateAppMemoryBytes: Long?,
    val systemAvailableMemoryBytes: Long?,
    val recentExits: List<ProcessExitSignal>?,
    val requestedThreads: Int,
    val requestedContext: Int,
    val requestedTokens: Int,
)

/** Atomic resource decision only; never a Skill authorization or an indefinitely reusable permit. */
class OrionResourceDecision internal constructor(
    val block: OrionResourceBlock?,
    val plan: OrionInferenceResourcePlan?,
    val memoryRisk: MemoryRisk,
    val estimatedHeadroomBytes: Long?,
    val sampledAt: MonotonicInstant?,
    exits: List<ProcessExitSignal>,
) {
    val allowed: Boolean get() = block == null && plan != null
    val recentExits: List<ProcessExitSignal> = Collections.unmodifiableList(exits.toList())
}

/**
 * No Android probing, persistence, loading or restart side effects. The future Core
 * adapter owns observations and qualified plans. Missing input denies all work.
 * Each API call reevaluates; consumers requiring coherent limits use evaluate()
 * once immediately before a request and apply that decision's complete envelope.
 */
class OrionMinimumResourceGovernor(
    private val clock: OrionClock,
    private val observations: () -> OrionResourceObservation?,
    qualifiedPlans: List<OrionInferenceResourcePlan>,
    private val maximumObservationAgeMillis: Long,
    private val memoryExitCooldownMillis: Long,
) : ResourceGovernor {
    private val plans = qualifiedPlans.associateBy { it.model.modelId }
    private var observedBoot: BootSessionId? = null
    private val observedResourceExits = linkedSetOf<ProcessExitSignal>()
    private var resourceFenceStarted: MonotonicInstant? = null
    private var resourceFenceBlock = OrionResourceBlock.MEMORY_EXIT
    init {
        require(plans.size == qualifiedPlans.size)
        require(maximumObservationAgeMillis > 0 && memoryExitCooldownMillis > 0)
    }

    fun evaluate(): OrionResourceDecision = observe(null)

    @Synchronized private fun observe(requestedModel: InferenceModelProfile?): OrionResourceDecision = try {
        val raw = observations()
        if (raw == null) denied(OrionResourceBlock.OBSERVATION_UNAVAILABLE)
        // Bound the copy itself, not only the resulting policy state.
        else decide(raw.copy(recentExits = raw.recentExits?.take(MAX_EXIT_SIGNALS + 1)), requestedModel)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt(); throw interrupted
    } catch (_: Exception) {
        denied(OrionResourceBlock.OBSERVATION_UNAVAILABLE)
    }

    private fun decide(sample: OrionResourceObservation, requestedModel: InferenceModelProfile?): OrionResourceDecision {
        val exits = sample.recentExits ?: emptyList()
        fun block(reason: OrionResourceBlock, risk: MemoryRisk = MemoryRisk.UNKNOWN, headroom: Long? = null) =
            OrionResourceDecision(reason, null, risk, headroom, sample.sampledAt, exits)
        val now = clock.monotonicNow()
        val age = now.elapsedSince(sample.sampledAt)?.toMillis()
        if (age == null || age >= maximumObservationAgeMillis) return block(OrionResourceBlock.OBSERVATION_STALE)
        if (sample.recentExits == null) return block(OrionResourceBlock.EXIT_HISTORY_UNAVAILABLE)
        if (exits.size > MAX_EXIT_SIGNALS) return block(OrionResourceBlock.OBSERVATION_INVALID)
        if (observedBoot != now.bootSessionId) {
            observedBoot = now.bootSessionId
            observedResourceExits.clear()
            resourceFenceStarted = null
        }
        // Exit wall timestamps identify evidence; they cannot shorten an in-boot
        // cooldown. Even historical resource-death evidence first seen by this governor
        // opens one conservative fence. Duplicate snapshots never extend it.
        for (exit in exits) {
            if (exit.requiresResourceBackoff() && observedResourceExits.add(exit)) {
                resourceFenceStarted = now
                resourceFenceBlock = if (exit.isMemoryExit()) OrionResourceBlock.MEMORY_EXIT else OrionResourceBlock.RESOURCE_EXIT
                while (observedResourceExits.size > MAX_EXIT_SIGNALS)
                    observedResourceExits.remove(observedResourceExits.first())
            }
        }
        if (sample.memoryPressure == OrionMemoryPressure.CRITICAL)
            return block(OrionResourceBlock.MEMORY_PRESSURE, MemoryRisk.CRITICAL)
        if (sample.memoryPressure == OrionMemoryPressure.MODERATE)
            return block(OrionResourceBlock.MEMORY_PRESSURE, MemoryRisk.HIGH)
        resourceFenceStarted?.let { start ->
            val elapsed = now.elapsedSince(start)?.toMillis()
            if (elapsed == null || elapsed < memoryExitCooldownMillis)
                return block(resourceFenceBlock,
                    if (resourceFenceBlock == OrionResourceBlock.MEMORY_EXIT) MemoryRisk.HIGH else MemoryRisk.UNKNOWN)
        }
        if (exits.any { it.kind == ProcessExitKind.UNKNOWN && !it.requiresResourceBackoff() })
            return block(OrionResourceBlock.OBSERVATION_INVALID)
        val plan = sample.model?.let { plans[it.modelId]?.takeIf { plan -> plan.model == it } }
            ?: return block(OrionResourceBlock.MODEL_UNQUALIFIED)
        if (requestedModel != null && requestedModel != plan.model) return block(OrionResourceBlock.MODEL_UNQUALIFIED)
        val process = sample.processMemoryBytes
        val aggregate = sample.aggregateAppMemoryBytes
        val system = sample.systemAvailableMemoryBytes
        if (sample.batteryPercent == null || sample.batteryPercent !in 0..100 ||
            sample.thermalStatus == null || sample.thermalStatus !in 0..6 ||
            sample.foreground == null || sample.powerSave == null ||
            sample.importance == OrionProcessImportance.UNKNOWN || sample.memoryPressure == OrionMemoryPressure.UNKNOWN ||
            process == null || process < 0 || aggregate == null || aggregate < process || system == null || system < 0)
            return block(OrionResourceBlock.OBSERVATION_INVALID)
        // Subtraction with nonnegative operands, clamped before taking the minimum:
        // never add attacker-sized estimates to usage or overflow a budget sum.
        val headroom = minOf((plan.processBudgetBytes - process).coerceAtLeast(0),
            (plan.aggregateBudgetBytes - aggregate).coerceAtLeast(0),
            (system - plan.systemReserveBytes).coerceAtLeast(0))
        if (headroom < plan.model.estimatedMemoryBytes)
            return block(OrionResourceBlock.MEMORY_BUDGET, MemoryRisk.CRITICAL, headroom)
        if (!sample.foreground || sample.importance != OrionProcessImportance.FOREGROUND)
            return block(OrionResourceBlock.BACKGROUND, MemoryRisk.LOW, headroom)
        if (sample.batteryPercent < plan.minimumBatteryPercent) return block(OrionResourceBlock.BATTERY, MemoryRisk.LOW, headroom)
        if (sample.thermalStatus > plan.maxThermalStatus) return block(OrionResourceBlock.THERMAL, MemoryRisk.LOW, headroom)
        if (sample.powerSave) return block(OrionResourceBlock.POWER_SAVER, MemoryRisk.LOW, headroom)
        if (sample.requestedThreads !in 1..plan.maxThreads || sample.requestedContext !in 1..plan.maxContext ||
            sample.requestedTokens !in 1..plan.maxTokens) return block(OrionResourceBlock.REQUEST_LIMITS, MemoryRisk.LOW, headroom)
        return OrionResourceDecision(null, plan, MemoryRisk.LOW, headroom, sample.sampledAt, exits)
    }

    private fun denied(reason: OrionResourceBlock) = OrionResourceDecision(reason, null, MemoryRisk.UNKNOWN, null, null, emptyList())
    override fun canRunInference() = evaluate().allowed
    override fun canLoadModel(modelProfile: InferenceModelProfile) = observe(modelProfile).allowed
    override fun allowedModelTier() = evaluate().plan?.tier
    override fun maxThreads() = evaluate().plan?.maxThreads ?: 0
    override fun maxContext() = evaluate().plan?.maxContext ?: 0
    override fun maxTokens() = evaluate().plan?.maxTokens ?: 0
    override fun keepAliveMs() = evaluate().plan?.keepAliveMs ?: 0L
    override fun memoryRisk() = evaluate().memoryRisk
    override fun estimatedHeadroom() = evaluate().estimatedHeadroomBytes
    override fun recentProcessExitSignals() = evaluate().recentExits

    companion object { private const val MAX_EXIT_SIGNALS = 64 }
}

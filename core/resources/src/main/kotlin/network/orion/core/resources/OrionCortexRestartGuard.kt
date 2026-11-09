package network.orion.core.resources

import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock

/** Trusted MAIN adapter supplies only :ai exits; null history means unavailable. */
data class OrionAiExitHistory(val sampledAt: MonotonicInstant, val exits: List<ProcessExitSignal>)

enum class OrionRestartBlock { HISTORY_UNAVAILABLE, HISTORY_INVALID, HISTORY_STALE, COOLDOWN, FALLBACK_EXHAUSTED }

/** Eligibility to ask ResourceGovernor again, never permission to load or relaunch. */
data class OrionRestartDecision internal constructor(
    val block: OrionRestartBlock?,
    val candidatePlan: OrionInferenceResourcePlan?,
    val memoryDeaths: Int,
    val degraded: Boolean,
    val preloadAllowed: Boolean,
) {
    val eligibleForResourceEvaluation: Boolean get() = block == null && candidatePlan != null
}

/**
 * MAIN-owned ephemeral guard. Recovery must supply complete available AI exit
 * history again; construction is not a reset of memory evidence. No process or
 * model is started here. Consumers must also obtain a fresh governor decision.
 */
class OrionCortexRestartGuard(
    private val clock: OrionClock,
    qualifiedFallbackPlans: List<OrionInferenceResourcePlan>,
    private val maximumHistoryAgeMillis: Long,
    private val cooldownMillis: Long,
) {
    private val plans = qualifiedFallbackPlans.toList()
    private val memoryExits = linkedSetOf<ProcessExitSignal>()
    private var boot: BootSessionId? = null
    private var fence: MonotonicInstant? = null

    init {
        require(plans.isNotEmpty() && plans.size <= MAX_EXITS)
        require(plans.map { it.model.modelId }.distinct().size == plans.size)
        require(plans.map { it.tier }.distinct().size == plans.size)
        require(maximumHistoryAgeMillis > 0 && cooldownMillis > 0)
        plans.zipWithNext().forEach { (larger, smaller) ->
            require(smaller.model.estimatedMemoryBytes < larger.model.estimatedMemoryBytes &&
                smaller.tier != larger.tier && smaller.maxContext <= larger.maxContext &&
                smaller.maxThreads <= larger.maxThreads && smaller.maxTokens <= larger.maxTokens &&
                smaller.keepAliveMs <= larger.keepAliveMs) { "Fallbacks must reduce qualified resource demand." }
        }
    }

    @Synchronized fun evaluate(history: OrionAiExitHistory?): OrionRestartDecision {
        fun blocked(reason: OrionRestartBlock) = OrionRestartDecision(reason, null,
            memoryExits.size, memoryExits.isNotEmpty(), false)
        if (history == null) return blocked(OrionRestartBlock.HISTORY_UNAVAILABLE)
        val now = clock.monotonicNow()
        val age = now.elapsedSince(history.sampledAt)?.toMillis()
        if (age == null || age >= maximumHistoryAgeMillis) return blocked(OrionRestartBlock.HISTORY_STALE)
        // Bound copying even when the provider supplies a huge lazy list.
        val exits = history.exits.take(MAX_EXITS + 1)
        if (exits.size > MAX_EXITS || exits.any { it.kind == ProcessExitKind.UNKNOWN && !it.isMemoryExit() })
            return blocked(OrionRestartBlock.HISTORY_INVALID)
        if (boot != now.bootSessionId) {
            boot = now.bootSessionId
            // Preserve degradation but never interpret a previous boot's fence.
            fence = if (memoryExits.isNotEmpty()) now else null
        }
        for (exit in exits) {
            if (exit.isMemoryExit() && exit !in memoryExits) {
                // Saturation already exhausts any permitted fallback ladder.
                if (memoryExits.size < MAX_EXITS) memoryExits.add(exit)
                fence = now
            }
        }
        if (memoryExits.size >= plans.size) return blocked(OrionRestartBlock.FALLBACK_EXHAUSTED)
        fence?.let {
            val elapsed = now.elapsedSince(it)?.toMillis()
            if (elapsed == null || elapsed < cooldownMillis) return blocked(OrionRestartBlock.COOLDOWN)
        }
        val degraded = memoryExits.isNotEmpty()
        val plan = plans[memoryExits.size].let { if (degraded) it.copy(keepAliveMs = 0) else it }
        return OrionRestartDecision(null, plan, memoryExits.size, degraded, !degraded)
    }

    private fun ProcessExitSignal.isMemoryExit() = reason == 3 || memoryLimiterEvidence ||
        kind == ProcessExitKind.LOW_MEMORY || kind == ProcessExitKind.MEMORY_LIMITER

    companion object { private const val MAX_EXITS = 64 }
}

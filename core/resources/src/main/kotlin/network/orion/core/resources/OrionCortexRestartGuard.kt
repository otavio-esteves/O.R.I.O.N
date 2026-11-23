package network.orion.core.resources

import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock

/** Cached reclaims remain memory-pressure evidence. They do not consume a model tier. */
data class OrionAiExitHistory(val sampledAt: MonotonicInstant, val exits: List<ProcessExitSignal>,
    val cachedReclaims: Set<ProcessExitSignal> = emptySet())

enum class OrionRestartBlock { HISTORY_UNAVAILABLE, HISTORY_INVALID, HISTORY_STALE, COOLDOWN, FALLBACK_EXHAUSTED }

/** Eligibility to ask ResourceGovernor again, never permission to load or relaunch. */
data class OrionRestartDecision internal constructor(
    val block: OrionRestartBlock?,
    val candidatePlan: OrionInferenceResourcePlan?,
    val memoryDeaths: Int,
    val degraded: Boolean,
    val preloadAllowed: Boolean,
    val excessiveResourceDeaths: Int = 0,
) {
    val eligibleForResourceEvaluation: Boolean get() = block == null && candidatePlan != null
}

/**
 * MAIN-owned ephemeral guard. Recovery must supply complete available AI exit
 * history again; construction is not a reset of resource-death evidence. No process or
 * model is started here. Consumers must also obtain a fresh governor decision.
 */
class OrionCortexRestartGuard(
    private val clock: OrionClock,
    qualifiedFallbackPlans: List<OrionInferenceResourcePlan>,
    private val maximumHistoryAgeMillis: Long,
    private val cooldownMillis: Long,
) {
    private val plans = qualifiedFallbackPlans.toList()
    private val resourceExits = linkedSetOf<ProcessExitSignal>()
    private val cachedReclaims = linkedSetOf<ProcessExitSignal>()
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
        fun degraded() = resourceExits.isNotEmpty() || cachedReclaims.isNotEmpty()
        fun decision(block: OrionRestartBlock?, plan: OrionInferenceResourcePlan?) = OrionRestartDecision(
            block, plan, resourceExits.count { it.isMemoryExit() } + cachedReclaims.size, degraded(),
            block == null && !degraded(), resourceExits.count { !it.isMemoryExit() })
        fun blocked(reason: OrionRestartBlock) = decision(reason, null)
        if (history == null) return blocked(OrionRestartBlock.HISTORY_UNAVAILABLE)
        val now = clock.monotonicNow()
        val age = now.elapsedSince(history.sampledAt)?.toMillis()
        if (age == null || age >= maximumHistoryAgeMillis) return blocked(OrionRestartBlock.HISTORY_STALE)
        // Bound copying even when the provider supplies a huge lazy list.
        val exits = history.exits.take(MAX_EXITS + 1)
        val cached = history.cachedReclaims.take(MAX_EXITS + 1).toSet()
        if (exits.size > MAX_EXITS || exits.any { it.kind == ProcessExitKind.UNKNOWN && !it.requiresResourceBackoff() } ||
            cached.size > MAX_EXITS || cached.any { it !in exits || it.reason != 3 ||
                it.kind != ProcessExitKind.LOW_MEMORY || it.memoryLimiterEvidence })
            return blocked(OrionRestartBlock.HISTORY_INVALID)
        if (boot != now.bootSessionId) {
            boot = now.bootSessionId
            // Preserve degradation but never interpret a previous boot's fence.
            fence = if (degraded()) now else null
        }
        for (exit in exits) {
            if (exit.requiresResourceBackoff() && exit !in resourceExits) {
                if (exit in cached) {
                    if (exit in cachedReclaims) continue
                    if (cachedReclaims.size == MAX_EXITS) return blocked(OrionRestartBlock.FALLBACK_EXHAUSTED)
                    cachedReclaims.add(exit)
                } else {
                    cachedReclaims.remove(exit)
                    if (resourceExits.size < MAX_EXITS) resourceExits.add(exit)
                }
                fence = now
            }
        }
        if (resourceExits.size >= plans.size) return blocked(OrionRestartBlock.FALLBACK_EXHAUSTED)
        fence?.let {
            val elapsed = now.elapsedSince(it)?.toMillis()
            if (elapsed == null || elapsed < cooldownMillis) return blocked(OrionRestartBlock.COOLDOWN)
        }
        val plan = plans[resourceExits.size].let { if (degraded()) it.copy(keepAliveMs = 0) else it }
        return decision(null, plan)
    }

    companion object { private const val MAX_EXITS = 64 }
}

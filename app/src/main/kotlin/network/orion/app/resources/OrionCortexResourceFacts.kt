package network.orion.app.resources

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.resources.*
import network.orion.core.time.MonotonicInstant

/** Detached facts, never a reusable resource/restart permit or qualification attestation. */
internal data class OrionResourceModelFact(val sha256: String?, val estimatedMemoryBytes: Long)

internal data class OrionResourcePlanFact(
    val model: OrionResourceModelFact, val maxThreads: Int, val maxContext: Int, val maxTokens: Int,
    val keepAliveMs: Long, val processBudgetBytes: Long, val aggregateBudgetBytes: Long,
    val systemReserveBytes: Long, val minimumBatteryPercent: Int, val maxThermalStatus: Int,
)

/** Null entries mean unavailable history; truncatedByCapture is distinct from platform truncation. */
internal data class OrionResourceExitFacts(val entries: List<ProcessExitSignal>?, val truncatedByCapture: Boolean)
internal data class OrionAiExitHistoryFact(val sampledAt: MonotonicInstant, val exits: OrionResourceExitFacts)

internal data class OrionResourceSnapshotFact(
    val sampledAt: MonotonicInstant, val model: OrionResourceModelFact?,
    val batteryPercent: Int?, val thermalStatus: Int?, val foreground: Boolean?,
    val importance: OrionProcessImportance, val powerSave: Boolean?, val memoryPressure: OrionMemoryPressure,
    val mainPssBytes: Long?, val aiPssBytes: Long?, val aggregateAppMemoryBytes: Long?,
    val systemAvailableMemoryBytes: Long?, val recentExits: OrionResourceExitFacts,
    val requestedThreads: Int, val requestedContext: Int, val requestedTokens: Int,
    val aiExitHistory: OrionAiExitHistoryFact?, val limitations: Set<OrionResourceObservationLimitation>,
)

internal data class OrionGovernorDecisionFact(
    val allowedAsReported: Boolean, val block: OrionResourceBlock?, val plan: OrionResourcePlanFact?,
    val memoryRisk: MemoryRisk, val estimatedHeadroomBytes: Long?, val sampledAt: MonotonicInstant?,
    val recentExits: OrionResourceExitFacts,
)

internal data class OrionRestartDecisionFact(
    val eligibleAsReported: Boolean, val block: OrionRestartBlock?, val candidatePlan: OrionResourcePlanFact?,
    val memoryDeathsAsReported: Int, val excessiveResourceDeathsAsReported: Int,
    val degradedAsReported: Boolean, val preloadAllowedAsReported: Boolean,
)

internal sealed interface OrionCortexResourceFact {
    val snapshot: OrionResourceSnapshotFact?
    data class Governor(override val snapshot: OrionResourceSnapshotFact?, val decision: OrionGovernorDecisionFact) : OrionCortexResourceFact
    data class Restart(override val snapshot: OrionResourceSnapshotFact?, val decision: OrionRestartDecisionFact) : OrionCortexResourceFact
}

private const val MAX_EXITS = 64

private fun modelFact(model: InferenceModelProfile) = OrionResourceModelFact(
    model.modelId.takeIf { it.length == 64 && it.matches(Regex("[0-9a-f]{64}")) && it.any { c -> c != '0' } },
    model.estimatedMemoryBytes)

private fun planFact(plan: OrionInferenceResourcePlan) = OrionResourcePlanFact(modelFact(plan.model), plan.maxThreads,
    plan.maxContext, plan.maxTokens, plan.keepAliveMs, plan.processBudgetBytes, plan.aggregateBudgetBytes,
    plan.systemReserveBytes, plan.minimumBatteryPercent, plan.maxThermalStatus)

private fun exitFacts(values: List<ProcessExitSignal>?): OrionResourceExitFacts {
    if (values == null) return OrionResourceExitFacts(null, false)
    val bounded = values.take(MAX_EXITS + 1)
    return OrionResourceExitFacts(Collections.unmodifiableList(ArrayList(bounded.take(MAX_EXITS))), bounded.size > MAX_EXITS)
}

private fun snapshotFact(snapshot: OrionAndroidResourceSnapshot?): OrionResourceSnapshotFact? {
    if (snapshot == null) return null
    val observation = snapshot.observation
    val limits = snapshot.limitations.take(OrionResourceObservationLimitation.entries.size + 1)
    require(limits.size <= OrionResourceObservationLimitation.entries.size)
    return OrionResourceSnapshotFact(observation.sampledAt, observation.model?.let(::modelFact),
        observation.batteryPercent, observation.thermalStatus, observation.foreground, observation.importance,
        observation.powerSave, observation.memoryPressure, snapshot.mainPssBytes, observation.processMemoryBytes,
        observation.aggregateAppMemoryBytes, observation.systemAvailableMemoryBytes, exitFacts(observation.recentExits),
        observation.requestedThreads, observation.requestedContext, observation.requestedTokens,
        snapshot.aiExitHistory?.let { OrionAiExitHistoryFact(it.sampledAt, exitFacts(it.exits)) },
        Collections.unmodifiableSet(LinkedHashSet(limits)))
}

internal data class OrionCortexResourceFactBatch(
    val facts: List<OrionCortexResourceFact>, val droppedAtLeast: Long, val contendedAtLeast: Long,
    val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean, val drainContended: Boolean,
)

/** Final optional collector. No clock, Android/Cortex call, IO, callback or evaluation dependency. */
internal class OrionCortexResourceFactBuffer(private val capacity: Int = 16) {
    init { require(capacity in 1..64) }
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<OrionCortexResourceFact>(capacity)
    private val dropped = AtomicLong()
    private val contended = AtomicLong()
    private val failures = AtomicLong()
    private val complete = AtomicBoolean(true)

    private fun count(counter: AtomicLong) {
        val previous = counter.get()
        if (previous == Long.MAX_VALUE || !counter.compareAndSet(previous, previous + 1)) complete.set(false)
    }

    private inline fun offer(project: () -> OrionCortexResourceFact) {
        if (!lock.tryLock()) { count(dropped); count(contended); return }
        try {
            if (entries.size >= capacity) count(dropped)
            else entries.addLast(project())
        } catch (_: Throwable) {
            count(dropped); count(failures); complete.set(false)
        } finally { lock.unlock() }
    }

    internal fun governor(snapshot: OrionAndroidResourceSnapshot?, decision: OrionResourceDecision) = offer {
        OrionCortexResourceFact.Governor(snapshotFact(snapshot), OrionGovernorDecisionFact(decision.allowed,
            decision.block, decision.plan?.let(::planFact), decision.memoryRisk, decision.estimatedHeadroomBytes,
            decision.sampledAt, exitFacts(decision.recentExits)))
    }

    internal fun restart(snapshot: OrionAndroidResourceSnapshot?, decision: OrionRestartDecision) = offer {
        OrionCortexResourceFact.Restart(snapshotFact(snapshot), OrionRestartDecisionFact(decision.eligibleForResourceEvaluation,
            decision.block, decision.candidatePlan?.let(::planFact), decision.memoryDeaths, decision.excessiveResourceDeaths,
            decision.degraded, decision.preloadAllowed))
    }

    fun drain(): OrionCortexResourceFactBatch {
        if (!lock.tryLock()) return batch(emptyList(), true)
        return try {
            val copy = Collections.unmodifiableList(ArrayList(entries))
            entries.clear()
            batch(copy, false)
        } finally { lock.unlock() }
    }

    private fun batch(values: List<OrionCortexResourceFact>, busy: Boolean) = OrionCortexResourceFactBatch(
        values, dropped.get(), contended.get(), failures.get(), complete.get(), busy)
}

/** Preserve the already-computed object and exception behavior; no decision is computed here. */
internal fun OrionCortexResourceFactBuffer?.recordGovernorFact(snapshot: OrionAndroidResourceSnapshot?,
    decision: OrionResourceDecision): OrionResourceDecision {
    this?.governor(snapshot, decision)
    return decision
}

internal fun OrionCortexResourceFactBuffer?.recordRestartFact(snapshot: OrionAndroidResourceSnapshot?,
    decision: OrionRestartDecision): OrionRestartDecision {
    this?.restart(snapshot, decision)
    return decision
}

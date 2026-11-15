package network.orion.ai.service

import java.util.concurrent.atomic.AtomicLong
import network.orion.ai.api.*
import network.orion.ai.llama.OrionLlamaServiceGenerationReceipt

/** Primitive-only admission adapter. Disabled/invalid capture cannot become an inference failure. */
internal fun createServiceGenerationMetrics(enabled: Boolean, requestId: String, servicePid: Int,
    maxTokens: Int, contextTokens: Int?, terminal: String?): OrionServiceGenerationMetrics? {
    if (!enabled || !OrionIssuedRequestHistory.isIssuedIdentity(requestId)) return null
    return try {
        OrionServiceGenerationMetrics(requestId, servicePid, maxTokens, checkNotNull(contextTokens),
            terminal?.let(OrionInferenceTerminal::valueOf))
    } catch (_: Throwable) { null }
}

/** Process-local identity, never a clock, admission token or identity across process death. */
internal class OrionServiceInvocationIds(private val issued: AtomicLong = AtomicLong()) {
    fun next(): Long? {
        val previous = issued.get()
        return if (previous < Long.MAX_VALUE && issued.compareAndSet(previous, previous + 1)) previous + 1 else null
    }
}

/** One cached immutable snapshot on its original admitted Request; queries only read [snapshot]. */
internal class OrionServiceGenerationMetrics(
    private val requestId: String,
    private val servicePid: Int,
    private val maxTokens: Int,
    private val contextTokens: Int,
    terminal: OrionInferenceTerminal? = null,
    private val ids: OrionServiceInvocationIds = processInvocationIds,
) {
    @Volatile private var current: OrionGenerationMetricsSnapshot? = OrionGenerationMetricsSnapshot(
        requestId, servicePid, null, 1, terminal, OrionGenerationNativeState.NOT_STARTED,
        OrionGenerationNativeFinish.NONE, OrionGenerationSlotState.OWNED, OrionGenerationSnapshotState.UPDATING,
        OrionGenerationPartialWork.NONE_OBSERVED, 0, OrionGenerationTimeState.UNAVAILABLE)
    private var attempted = false
    private var ended = false

    init { require(maxTokens in 1..4096 && contextTokens in 32..32768) }

    /** Cached read only. In particular, no deadline check, clock or lazy source evaluation. */
    fun snapshot(): OrionGenerationMetricsSnapshot? = current

    @Synchronized fun unavailable() { current = null }

    @Synchronized fun wrapperAttempted() = isolate {
        check(!attempted && !ended)
        attempted = true
        change(nativeState = OrionGenerationNativeState.UNKNOWN, partial = OrionGenerationPartialWork.UNKNOWN)
    }

    /** A receipt proves the actual original JNI call returned; no invocation ID is issued before it. */
    @Synchronized fun returned(receipt: OrionLlamaServiceGenerationReceipt?) = isolate {
        check(attempted && !ended)
        check(current?.nativeState == OrionGenerationNativeState.UNKNOWN)
        val actual = requireNotNull(receipt)
        val values = linkedMapOf<OrionGenerationMetric, Long>()
        for ((metric, value) in actual.metrics) {
            val wire = OrionGenerationMetric.entries.single { it.key == metric.key }
            check(values.put(wire, value) == null)
        }
        val invocation = checkNotNull(ids.next())
        change(invocationId = invocation, nativeState = OrionGenerationNativeState.JNI_RETURNED,
            nativeFinish = OrionGenerationNativeFinish.valueOf(actual.finish.name),
            partial = if (actual.partialWorkPossible) OrionGenerationPartialWork.POSSIBLE else OrionGenerationPartialWork.NONE_OBSERVED,
            stages = actual.completedCallStagesMask, metrics = values)
    }

    @Synchronized fun terminal(value: OrionInferenceTerminal) = isolate {
        val previous = current ?: return@isolate
        if (previous.logicalTerminal != null) check(previous.logicalTerminal == value)
        else change(terminal = value)
    }

    /** Called only after original cleanup/watchdog completion and retained-entry identity check. */
    @Synchronized fun workerReleased() = isolate {
        check(!ended)
        ended = true
        val previous = current ?: return@isolate
        if (previous.nativeState == OrionGenerationNativeState.UNKNOWN || previous.logicalTerminal == null) {
            current = null
        } else change(nativeState = if (!attempted) OrionGenerationNativeState.NOT_EXECUTED else previous.nativeState,
            slot = OrionGenerationSlotState.RELEASED, state = OrionGenerationSnapshotState.FINAL)
    }

    private inline fun isolate(operation: () -> Unit) {
        if (current == null) return
        try { operation() } catch (_: Throwable) { current = null }
    }

    private fun change(
        invocationId: Long? = current?.invocationId,
        terminal: OrionInferenceTerminal? = current?.logicalTerminal,
        nativeState: OrionGenerationNativeState = checkNotNull(current).nativeState,
        nativeFinish: OrionGenerationNativeFinish = checkNotNull(current).nativeFinish,
        slot: OrionGenerationSlotState = checkNotNull(current).slotState,
        state: OrionGenerationSnapshotState = checkNotNull(current).snapshotState,
        partial: OrionGenerationPartialWork = checkNotNull(current).partialWorkState,
        stages: Int = checkNotNull(current).completedCallStagesMask,
        metrics: Map<OrionGenerationMetric, Long>? = null,
    ) {
        val previous = checkNotNull(current)
        check(previous.snapshotState != OrionGenerationSnapshotState.FINAL)
        val values = metrics ?: OrionGenerationMetric.entries.mapNotNull { metric -> previous.metric(metric)?.let { metric to it } }.toMap()
        if (invocationId == previous.invocationId && terminal == previous.logicalTerminal && nativeState == previous.nativeState &&
            nativeFinish == previous.nativeFinish && slot == previous.slotState && state == previous.snapshotState &&
            partial == previous.partialWorkState && stages == previous.completedCallStagesMask && metrics == null) return
        check(previous.snapshotSequence < Long.MAX_VALUE)
        val next = OrionGenerationMetricsSnapshot(requestId, servicePid, invocationId, previous.snapshotSequence + 1,
            terminal, nativeState, nativeFinish, slot, state, partial, stages, OrionGenerationTimeState.UNAVAILABLE,
            metrics = values)
        next.requireAdmittedBounds(maxTokens, contextTokens)
        current = next
    }

    companion object { private val processInvocationIds = OrionServiceInvocationIds() }
}

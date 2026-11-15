package network.orion.app.ai

import network.orion.ai.api.OrionGenerationMetricsSnapshot
import network.orion.ai.api.OrionGenerationTimeState
import network.orion.ai.api.OrionIssuedRequestHistory
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicInstant

/** Pure bounded correlation, never admission. Caller holds the existing client monitor. */
internal class OrionCortexMetricsCorrelation {
    internal class Endpoint(val service: Any, val owner: Any, val epoch: Long, val minor: Int)
    internal class Witness(val endpoint: Endpoint, val pid: Int, val at: MonotonicInstant)
    internal class ResourceCallback(val endpoint: Endpoint?, val thread: Thread) { var witness: Witness? = null }
    internal class Binding(val endpoint: Endpoint, val logicalId: String, val transportId: String,
        val witness: Witness, val maxTokens: Int, val contextTokens: Int)

    private var endpoint: Endpoint? = null
    private var witness: Witness? = null
    private var binding: Binding? = null
    private var resourceCallback: ResourceCallback? = null
    private var identityDiscontinuous = false
    private var discoveryClaimed = false
    private var discoveryComplete = false
    private var supportedMask: Long? = null

    fun connected(service: Any, owner: Any, epoch: Long?, negotiatedMinor: Int) {
        clear()
        if (epoch != null && epoch > 0 && negotiatedMinor >= 2)
            endpoint = Endpoint(service, owner, epoch, negotiatedMinor)
    }

    fun clear() {
        endpoint = null; witness = null; binding = null; resourceCallback = null
        identityDiscontinuous = false; discoveryClaimed = false; discoveryComplete = false; supportedMask = null
    }

    fun beginResourceCallback(): ResourceCallback = ResourceCallback(endpoint, Thread.currentThread()).also { resourceCallback = it }
    fun endResourceCallback(selected: ResourceCallback) { if (resourceCallback === selected) resourceCallback = null }

    fun memoryUnavailable() { witness = null; binding = null }

    /** This identity is parsed only from the existing currently owned health response. */
    fun memory(service: Any, owner: Any, pid: Int, at: MonotonicInstant) {
        val current = endpoint ?: return
        if (current.service !== service || current.owner !== owner || identityDiscontinuous) return
        if (pid <= 0 || at.bootSessionId == null) { memoryUnavailable(); return }
        witness?.let { previous ->
            if (pid != previous.pid || at.elapsedSince(previous.at) == null) {
                identityDiscontinuous = true; memoryUnavailable(); return
            }
        }
        val actual = Witness(current, pid, at)
        witness = actual
        resourceCallback?.takeIf { it.endpoint === current && it.thread === Thread.currentThread() }?.witness = actual
    }

    /** A new receipt inside this one existing allowed resource callback is mandatory. */
    fun candidate(service: Any, owner: Any?, logicalId: String, transportId: String,
        callback: ResourceCallback?, decisionAllowed: Boolean, decisionAt: MonotonicInstant?, deadline: MonotonicDeadline,
        maxTokens: Int, contextTokens: Int): Binding? {
        val current = endpoint ?: return null
        val actual = witness ?: return null
        if (identityDiscontinuous || current.service !== service || current.owner !== owner ||
            actual.endpoint !== current || callback == null || callback.endpoint !== current || callback.witness !== actual ||
            !decisionAllowed || decisionAt == null ||
            actual.at.elapsedSince(decisionAt) == null || decisionAt.elapsedSince(deadline.start) == null ||
            deadline.statusAt(actual.at) != MonotonicDeadlineStatus.ACTIVE ||
            maxTokens !in 1..4096 || contextTokens !in 32..32768 ||
            !OrionIssuedRequestHistory.isIssuedIdentity(transportId)) return null
        return Binding(current, logicalId, transportId, actual, maxTokens, contextTokens)
    }

    /** Called immediately before the actual start RPC, never for load, issue-ID or replay. */
    fun beginningInference() { binding = null }

    /** Only an ACCEPTED start reply may install this inference's mapping. */
    fun admitted(candidate: Binding?) {
        if (candidate != null && identityMatches(candidate)) binding = candidate
    }

    fun select(logicalId: String): Binding? = binding?.takeIf { it.logicalId == logicalId && identityMatches(it) }

    private fun identityMatches(selected: Binding): Boolean {
        val actual = witness ?: return false
        return !identityDiscontinuous && endpoint === selected.endpoint && actual.endpoint === selected.endpoint &&
            actual.pid == selected.witness.pid && actual.at.elapsedSince(selected.witness.at) != null
    }

    fun current(selected: Binding): Boolean = binding === selected && identityMatches(selected)

    /** One claim per connection, including unsupported/failed/contended discoveries. */
    fun claimDiscovery(selected: Binding): Endpoint? {
        if (!current(selected) || discoveryClaimed) return null
        discoveryClaimed = true
        return selected.endpoint
    }

    fun discovered(selected: Endpoint, mask: Long?) {
        if (endpoint !== selected || !discoveryClaimed || discoveryComplete) return
        supportedMask = mask?.takeIf { it >= 0 && it and 0xffffL.inv() == 0L }
        discoveryComplete = true
    }

    fun mask(selected: Binding): Long? = if (current(selected) && discoveryComplete) supportedMask else null

    fun accepts(selected: Binding, snapshot: OrionGenerationMetricsSnapshot): Boolean = current(selected) &&
        snapshot.requestId == selected.transportId && snapshot.servicePid == selected.witness.pid &&
        (snapshot.timeState != OrionGenerationTimeState.KNOWN_BOOT ||
            snapshot.bootCount == selected.witness.at.bootSessionId?.bootCount &&
            snapshot.capturedElapsedMillis?.let { it >= selected.witness.at.elapsedRealtimeMillis } == true &&
            (snapshot.wrapperStartedElapsed?.let { it >= selected.witness.at.elapsedRealtimeMillis } ?: true))
}

/** Only diagnostic operations belong here; never a governor, inference RPC or original exception. */
internal inline fun <T> OrionCortexMetricsCorrelation?.isolatedMetrics(operation: (OrionCortexMetricsCorrelation) -> T): T? {
    if (this == null) return null
    return try { operation(this) } catch (_: Throwable) { clear(); null }
}

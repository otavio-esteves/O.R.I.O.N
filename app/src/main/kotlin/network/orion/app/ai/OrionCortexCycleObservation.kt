package network.orion.app.ai

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.OrionGenerationMetricsSnapshot
import network.orion.app.resources.*
import network.orion.core.resources.OrionResourceDecision
import network.orion.core.resources.OrionRestartDecision
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicInstant

internal enum class OrionCycleSource { NOT_REACHED, NO_ATTEMPT, RETAINED_RESULT, LOGICAL_OWNER, TURN_ENTERED }
internal enum class OrionCycleState { PENDING, NO_ATTEMPT, RETAINED_RESULT, INCOMPLETE }
internal enum class OrionCycleCallback { RESTART, RESOURCE }
internal enum class OrionCyclePhase { BEFORE_LOAD, LOAD, INFERENCE }
internal enum class OrionCycleThrown { CANCELLED, INTERRUPTED, OTHER }
internal enum class OrionCycleIssue { PENDING, LOSS, NO_ACTUAL_TURN, RESOURCE_FACTS_MISSING, METRICS_UNAVAILABLE,
    UNLOAD_FACTS_MISSING, POST_REPLY_TIME_UNAVAILABLE, ENDPOINT_UNAVAILABLE }

/** No caller identifier, request, content, Context, client or callback is held by a token. */
internal class OrionCortexCycleToken internal constructor(internal val observer: OrionCortexCycleObserver,
    internal val owner: Any) {
    @Volatile internal var lost = false
    override fun toString() = "OrionCortexCycleToken(REDACTED)"
}

/** Call-local control-flow marker; absence/loss of a buffered event cannot prove replay. */
internal class OrionCortexCycleInvocation { @Volatile var logicalOwnerEntered = false }

/** Opaque client identity is for in-memory equality only; it conveys no Binder/admission authority. */
internal class OrionCycleEndpoint(val clientScope: Any, val connectionEpoch: Long?, val aiPid: Int?)
internal class OrionCycleCallbackScope(val token: OrionCortexCycleToken, val clientScope: Any,
    val callback: OrionCycleCallback, val phase: OrionCyclePhase, val thread: Thread)
internal class OrionCycleResourceFact(val callback: OrionCycleCallback, val phase: OrionCyclePhase,
    val endpoint: OrionCycleEndpoint?, val fact: OrionCortexResourceFact)
internal data class OrionCycleStreamSummary(val acceptedChunkObservationsAtLeast: Long,
    val callbackAttemptsAtLeast: Long, val callbacksReturnedNormallyAtLeast: Long)

/** These are retained facts from original polling, not a new timed/native-release confirmation. */
internal class OrionCycleUnloadFact(val endpoint: OrionCycleEndpoint?, val deadline: MonotonicDeadline?,
    val lastPollAt: MonotonicInstant?, val noEndpoint: Boolean, val unloadedReply: Boolean,
    val validUnloadedReply: Boolean, val failure: OrionCortexUnloadOutcome?)

internal class OrionCortexCycleReceipt internal constructor(
    val state: OrionCycleState, val source: OrionCycleSource, val originalResult: OrionCortexRunResult?,
    val thrown: OrionCycleThrown?, val trustedModelSha256: String?, val actualTurnEntered: Boolean?,
    val inferenceAccepted: Boolean?, val stream: OrionCycleStreamSummary?,
    val resources: List<OrionCycleResourceFact>, val unload: OrionCycleUnloadFact?,
    val nativeMetrics: OrionGenerationMetricsSnapshot?, val issues: Set<OrionCycleIssue>,
    val droppedAtLeast: Long, val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean,
) {
    val retainedSnapshot = true
    val qualification = "NOT_QUALIFIED"
    val profileQualified = false
    val productionInferenceEnabled = false
    val fullF5GateClosed = false
    val fullSoakGateClosed = false
    override fun toString() = "OrionCortexCycleReceipt(state=$state, source=$source, qualification=$qualification)"
}

/** One bounded technical observation store; never request history or admission state. */
internal class OrionCortexCycleObserver(private val capacity: Int = 4) {
    init { require(capacity in 1..16) }
    private class Entry {
        var claimed = false
        var finished = false
        var readClaimed = false
        var cached: OrionCortexCycleReceipt? = null
        var source = OrionCycleSource.NOT_REACHED
        var result: OrionCortexRunResult? = null
        var thrown: OrionCycleThrown? = null
        var model: String? = null
        var logicalId: String? = null // Only set inside the admitted original runOnce operation.
        var clientScope: Any? = null
        var endpoint: OrionCycleEndpoint? = null
        var inferenceAccepted = false
        var chunks = 0L; var attempts = 0L; var returned = 0L
        val resources = ArrayList<OrionCycleResourceFact>()
        val projector = OrionCortexResourceFactBuffer(1)
        var unload: OrionCycleUnloadFact? = null
    }
    internal class Read internal constructor(val token: OrionCortexCycleToken, internal val base: OrionCortexCycleReceipt,
        internal val logicalId: String?, internal val clientScope: Any?, internal val endpoint: OrionCycleEndpoint?,
        internal val mayQuery: Boolean, internal val ownsRead: Boolean)

    private val lock = ReentrantLock()
    private val entries = IdentityHashMap<OrionCortexCycleToken, Entry>()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)

    private fun count(counter: AtomicLong) {
        val previous = counter.get()
        if (previous == Long.MAX_VALUE || !counter.compareAndSet(previous, previous + 1)) accounting.set(false)
    }

    internal fun failed(token: OrionCortexCycleToken? = null) {
        token?.takeIf { it.observer === this }?.lost = true
        count(dropped); count(failures); accounting.set(false)
    }

    private inline fun <T> access(token: OrionCortexCycleToken?, operation: (Entry) -> T): T? {
        if (token == null || token.observer !== this) return null
        if (!lock.tryLock()) { token.lost = true; count(dropped); return null }
        return try { entries[token]?.let(operation) }
        catch (_: Throwable) { failed(token); null }
        finally { lock.unlock() }
    }

    fun begin(owner: Any): OrionCortexCycleToken? {
        if (!lock.tryLock()) { count(dropped); return null }
        return try {
            if (entries.size >= capacity) { count(dropped); null }
            else OrionCortexCycleToken(this, owner).also { entries[it] = Entry() }
        } catch (_: Throwable) { failed(); null }
        finally { lock.unlock() }
    }

    fun claim(owner: Any, token: OrionCortexCycleToken?): OrionCortexCycleToken? = access(token) {
        if (token?.owner !== owner) null
        else if (it.claimed) { token?.lost = true; null }
        else { it.claimed = true; token }
    }

    private inline fun write(token: OrionCortexCycleToken?, operation: (Entry) -> Unit) {
        access(token) { if (!it.claimed || it.finished) { token?.lost = true } else operation(it) }
    }

    fun noAttempt(token: OrionCortexCycleToken?) = write(token) {
        if (it.source == OrionCycleSource.NOT_REACHED) it.source = OrionCycleSource.NO_ATTEMPT
        // The result is recorded at the real return, never manufactured here.
    }
    fun retained(token: OrionCortexCycleToken?) = write(token) { it.source = OrionCycleSource.RETAINED_RESULT }
    fun trustedModel(token: OrionCortexCycleToken?, hash: String) = write(token) {
        if (validModelHash(hash)) it.model = hash else token?.lost = true
    }
    fun logicalOwner(token: OrionCortexCycleToken?, logicalId: String) = write(token) {
        it.source = OrionCycleSource.LOGICAL_OWNER; it.logicalId = logicalId
    }
    fun turn(token: OrionCortexCycleToken?, clientScope: Any) = write(token) {
        it.source = OrionCycleSource.TURN_ENTERED; it.clientScope = clientScope
    }
    fun clientResult(token: OrionCortexCycleToken?, retained: Boolean?) = write(token) {
        if (retained == true && it.source == OrionCycleSource.NOT_REACHED) it.source = OrionCycleSource.RETAINED_RESULT
    }
    fun inferenceAccepted(token: OrionCortexCycleToken?, endpoint: OrionCycleEndpoint?) = write(token) {
        it.inferenceAccepted = true; it.endpoint = endpoint
    }
    fun streamAttempt(token: OrionCortexCycleToken?, chunks: Int) = write(token) {
        require(chunks in 1..17)
        it.chunks = Math.addExact(it.chunks, chunks.toLong()); it.attempts = Math.addExact(it.attempts, 1)
    }
    fun streamReturned(token: OrionCortexCycleToken?) = write(token) { it.returned = Math.addExact(it.returned, 1) }

    fun governor(scope: OrionCycleCallbackScope, endpoint: OrionCycleEndpoint?, snapshot: OrionAndroidResourceSnapshot?,
        decision: OrionResourceDecision) = write(scope.token) { entry ->
        if (!validScope(entry, scope, endpoint, OrionCycleCallback.RESOURCE)) return@write
        if (entry.resources.size >= 12) { scope.token.lost = true; count(dropped); return@write }
        entry.projector.recordGovernorFact(snapshot, decision)
        val batch = entry.projector.drain()
        val fact = batch.facts.singleOrNull()
        if (fact == null || batch.droppedAtLeast != 0L || batch.recordingFailuresAtLeast != 0L) scope.token.lost = true
        if (fact != null) entry.resources.add(OrionCycleResourceFact(scope.callback, scope.phase, endpoint, fact))
    }
    fun restart(scope: OrionCycleCallbackScope, endpoint: OrionCycleEndpoint?, snapshot: OrionAndroidResourceSnapshot?,
        decision: OrionRestartDecision) = write(scope.token) { entry ->
        if (!validScope(entry, scope, endpoint, OrionCycleCallback.RESTART)) return@write
        if (entry.resources.size >= 12) { scope.token.lost = true; count(dropped); return@write }
        entry.projector.recordRestartFact(snapshot, decision)
        val batch = entry.projector.drain()
        val fact = batch.facts.singleOrNull()
        if (fact == null || batch.droppedAtLeast != 0L || batch.recordingFailuresAtLeast != 0L) scope.token.lost = true
        if (fact != null) entry.resources.add(OrionCycleResourceFact(scope.callback, scope.phase, endpoint, fact))
    }

    private fun validScope(entry: Entry, scope: OrionCycleCallbackScope, endpoint: OrionCycleEndpoint?,
        callback: OrionCycleCallback): Boolean {
        val valid = scope.thread === Thread.currentThread() && scope.callback == callback &&
            entry.source == OrionCycleSource.TURN_ENTERED && entry.clientScope === scope.clientScope &&
            (endpoint == null || endpoint.clientScope === scope.clientScope)
        if (!valid) { scope.token.lost = true; count(dropped) }
        return valid
    }
    fun unloaded(token: OrionCortexCycleToken?, fact: OrionCycleUnloadFact) = write(token) { it.unload = fact }

    fun returned(token: OrionCortexCycleToken?, result: OrionCortexRunResult) = write(token) {
        it.result = result; it.finished = true
    }
    fun threw(token: OrionCortexCycleToken?, kind: OrionCycleThrown) = write(token) {
        it.thrown = kind; it.finished = true
    }

    private fun receipt(token: OrionCortexCycleToken, entry: Entry, metrics: OrionGenerationMetricsSnapshot?,
        current: Boolean, pending: Boolean = false): OrionCortexCycleReceipt {
        val issues = linkedSetOf<OrionCycleIssue>()
        val actual = entry.source == OrionCycleSource.TURN_ENTERED
        val state = when {
            !entry.finished || pending -> { issues.add(OrionCycleIssue.PENDING); OrionCycleState.PENDING }
            token.lost -> OrionCycleState.INCOMPLETE
            entry.source == OrionCycleSource.NO_ATTEMPT -> OrionCycleState.NO_ATTEMPT
            entry.source == OrionCycleSource.RETAINED_RESULT -> OrionCycleState.RETAINED_RESULT
            else -> OrionCycleState.INCOMPLETE
        }
        if (token.lost) issues.add(OrionCycleIssue.LOSS)
        if (entry.finished && !actual && !token.lost && state !in setOf(OrionCycleState.NO_ATTEMPT, OrionCycleState.RETAINED_RESULT))
            issues.add(OrionCycleIssue.NO_ACTUAL_TURN)
        if (actual) {
            if (entry.resources.isEmpty()) issues.add(OrionCycleIssue.RESOURCE_FACTS_MISSING)
            if (metrics == null) issues.add(OrionCycleIssue.METRICS_UNAVAILABLE)
            if (entry.unload == null) issues.add(OrionCycleIssue.UNLOAD_FACTS_MISSING)
            else if (entry.unload?.unloadedReply == true) issues.add(OrionCycleIssue.POST_REPLY_TIME_UNAVAILABLE)
            if (!current) issues.add(OrionCycleIssue.ENDPOINT_UNAVAILABLE)
        }
        return OrionCortexCycleReceipt(state, entry.source, entry.result, entry.thrown, entry.model,
            if (token.lost && !actual) null else actual,
            if (token.lost && !entry.inferenceAccepted) null else entry.inferenceAccepted,
            if (actual) OrionCycleStreamSummary(entry.chunks, entry.attempts, entry.returned) else null,
            Collections.unmodifiableList(ArrayList(entry.resources)), entry.unload,
            if (actual && current) metrics else null, Collections.unmodifiableSet(issues),
            dropped.get(), failures.get(), accounting.get())
    }

    /** A pending read never claims the manual metrics phase. A completed call permits one explicit query. */
    fun prepareRead(owner: Any, token: OrionCortexCycleToken): Read? = access(token) { entry ->
        if (token.owner !== owner) return@access null
        val cached = entry.cached
        if (cached != null) return@access Read(token, cached, null, null, null, false, false)
        val first = entry.finished && !entry.readClaimed
        if (first) entry.readClaimed = true
        Read(token, receipt(token, entry, null, false, pending = entry.readClaimed && !first),
            entry.logicalId, entry.clientScope, entry.endpoint,
            first && entry.source == OrionCycleSource.TURN_ENTERED && entry.inferenceAccepted, first)
    }

    fun completeRead(read: Read, metrics: OrionGenerationMetricsSnapshot?, current: Boolean): OrionCortexCycleReceipt? = access(read.token) {
        it.cached?.let { cached -> return@access cached }
        if (!it.finished || !read.ownsRead) return@access read.base
        val value = receipt(read.token, it, metrics, current)
        it.cached = value
        value
    }

    /** Explicit technical eviction only; no cleanup, state replay or app side effect. */
    fun release(owner: Any, token: OrionCortexCycleToken): Boolean = access(token) {
        if (token.owner !== owner || !it.finished) false else { entries.remove(token); true }
    } ?: false
}

internal inline fun <T> OrionCortexCycleObserver?.isolatedCycle(token: OrionCortexCycleToken? = null,
    operation: (OrionCortexCycleObserver) -> T): T? {
    if (this == null) return null
    return try { operation(this) } catch (_: Throwable) { failed(token); null }
}

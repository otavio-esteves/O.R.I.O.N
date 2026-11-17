package network.orion.app.ai

import java.time.Instant
import network.orion.core.exitinfo.OrionProcessExitClassifier
import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.MonotonicInstant

/** Diagnostic wall bounds around one ALREADY performed diagnostic monotonic read; never a domain clock/lease. */
internal data class OrionQualificationTimePair(val bootCount: Int, val elapsedMillis: Long,
    val wallLowerMillis: Long, val wallUpperMillis: Long) {
    companion object {
        fun fromExistingRead(wallLower: Long?, original: MonotonicInstant, wallUpper: Long?): OrionQualificationTimePair? {
            val boot = original.bootSessionId?.bootCount ?: return null
            if (wallLower == null || wallUpper == null || wallLower < 0 || wallUpper < wallLower || original.elapsedRealtimeMillis < 0) return null
            return OrionQualificationTimePair(boot, original.elapsedRealtimeMillis, wallLower, wallUpper)
        }
    }
}
internal data class OrionQualificationObservationWindow(val started: OrionQualificationTimePair, val completed: OrionQualificationTimePair)
/** All observation parameters are explicit reviewed inputs; none is selected by this source. */
internal class OrionQualificationExitConfiguration(val maximumPairSpanMillis: Long, val maximumLiveGapMillis: Long,
    val maximumExitDelayMillis: Long) {
    init { require(maximumPairSpanMillis > 0 && maximumLiveGapMillis > 0 && maximumExitDelayMillis > 0) }
}

/** Opaque actual private endpoint equality only. Never invoked, hashed, logged or exported. */
internal class OrionQualificationEndpointKey(private val client: Any, private val connection: Any, private val binder: Any,
    private val epoch: Long) {
    fun matches(other: OrionQualificationEndpointKey): Boolean = epoch >= 0 && epoch == other.epoch &&
        client === other.client && connection === other.connection && binder === other.binder
    override fun toString() = "OrionQualificationEndpointKey(REDACTED)"
}
internal data class OrionQualificationProcWitness(val pid: Int, val uid: Int, val kernelStartTicks: Long)
internal enum class OrionQualificationProcState { PRESENT, ABSENT, UNAVAILABLE }
internal data class OrionQualificationProcObservation(val state: OrionQualificationProcState, val witness: OrionQualificationProcWitness? = null)
internal class OrionQualificationLiveReceipt(val endpoint: OrionQualificationEndpointKey, val originalReceipt: OrionRuntimeAiMemoryReading,
    val process: OrionQualificationProcWitness, val window: OrionQualificationObservationWindow,
    val baseline: OrionQualificationExitHistory) {
    val pid get() = originalReceipt.pid
    val aiBootCount get() = originalReceipt.boot
    val aiSampledElapsedMillis get() = originalReceipt.sampledElapsed
}
internal enum class OrionQualificationDescriptionEvidence { OTHER_ANON_SWAP_MARKER, NO_MATCH, UNAVAILABLE }

/** No raw description, trace, process-state summary or content survives this value. Exit has NO boot field. */
internal class OrionQualificationExitRecord(val pid: Int, val realUid: Int, val packageUid: Int, val processName: String,
    val timestampWallMillis: Long, val reason: Int, val signal: ProcessExitSignal,
    val descriptionEvidence: OrionQualificationDescriptionEvidence, val dedicatedReason: Int?) {
    override fun toString() = "OrionQualificationExitRecord(sanitized=true)"
}
internal object OrionQualificationExitSanitizer {
    fun record(pid: Int, realUid: Int, packageUid: Int, name: String, wall: Long, reason: Int,
        description: String?, dedicatedReason: Int?): OrionQualificationExitRecord? = try {
        if (pid <= 0 || realUid < 0 || packageUid < 0 || name.length !in 1..255 || wall < 0 || reason < 0 ||
            dedicatedReason != null && dedicatedReason <= 16) null else {
            val boundedDescription = description?.takeIf { it.length <= MAX_DESCRIPTION_UNITS }
            val signal = OrionProcessExitClassifier.classify(reason, Instant.ofEpochMilli(wall), boundedDescription, dedicatedReason)
            val evidence = when {
                boundedDescription == null -> OrionQualificationDescriptionEvidence.UNAVAILABLE
                reason == 13 && signal.kind == ProcessExitKind.MEMORY_LIMITER -> OrionQualificationDescriptionEvidence.OTHER_ANON_SWAP_MARKER
                else -> OrionQualificationDescriptionEvidence.NO_MATCH
            }
            OrionQualificationExitRecord(pid, realUid, packageUid, name, wall, reason, signal, evidence, dedicatedReason)
        }
    } catch (_: Throwable) { null }
    const val MAX_DESCRIPTION_UNITS = 4096
}
internal enum class OrionQualificationExitGap { CONFIGURATION_UNAVAILABLE, CLOCK_UNAVAILABLE, CLOCK_DISCONTINUOUS,
    BOOT_CHANGED, LIVE_UNPAIRED, LIVE_STALE, IDENTITY_MISMATCH, ENDPOINT_CHANGED, PROC_UNAVAILABLE, PID_REUSED,
    HISTORY_UNAVAILABLE, HISTORY_TRUNCATED, HISTORY_INVALID, NO_MATCH, AMBIGUOUS, CAPACITY }
internal class OrionQualificationExitIncident(val record: OrionQualificationExitRecord, val oldProcess: OrionQualificationProcWitness,
    private val endpoint: OrionQualificationEndpointKey, val mainPid: Int, val windowBootCount: Int, val lastLiveSampleElapsedMillis: Long, val lastLiveWallUpperMillis: Long,
    val observed: OrionQualificationObservationWindow, val firstObservedOrdinal: Long, val newIncident: Boolean) {
    fun matchesEndpoint(value: OrionQualificationEndpointKey): Boolean = endpoint.matches(value)
    val classification: String get() = when (record.signal.kind) {
        ProcessExitKind.MEMORY_LIMITER -> "MEMORY_LIMITER"
        ProcessExitKind.LOW_MEMORY -> "LOW_MEMORY"
        ProcessExitKind.EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        else -> "UNCLASSIFIED"
    }
    fun identitySha256(): String {
        // Identical to #160 incident_identity: sorted closed ASCII technical tuple; classification never creates a new incident.
        val text = "{\"pid\":${record.pid},\"processName\":\"network.orion.qualification:ai\",\"reason\":${record.reason},\"timestampWallMillis\":${record.timestampWallMillis}}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    val correlation = "PAIRED_LIVE_WINDOW"
    val sourceAuthenticated = false
    val memoryLimiterVerified = false
}
internal data class OrionQualificationExitResult(val incident: OrionQualificationExitIncident?, val gap: OrionQualificationExitGap?)

/** Pure single-session correlation state. Caller supplies only already observed typed data; no I/O/Clock/policy. */
internal class OrionQualificationExitCorrelation(private val packageName: String, private val uid: Int,
    private val mainPid: Int, private val configuration: OrionQualificationExitConfiguration?) {
    private var offsetLowerExclusive: Long? = null
    private var offsetUpperExclusive: Long? = null
    private var boot: Int? = null
    private var lastElapsed = -1L
    private var invalidClock = false
    private var previous: OrionQualificationLiveReceipt? = null
    private var paired: OrionQualificationLiveReceipt? = null
    private var ordinal = 0L
    private data class Key(val pid: Int, val uid: Int, val packageUid: Int, val wall: Long, val reason: Int, val startTicks: Long)
    private val seen = LinkedHashMap<Key, Long>()
    private data class BaselineKey(val pid: Int, val uid: Int, val packageUid: Int, val name: String, val wall: Long)
    private fun baselineKey(record: OrionQualificationExitRecord) = BaselineKey(record.pid, record.realUid, record.packageUid, record.processName, record.timestampWallMillis)
    private val baseline = LinkedHashSet<BaselineKey>()
    init { require(packageName == "network.orion.qualification" && uid > 0 && mainPid > 0) }

    private fun time(pair: OrionQualificationTimePair): OrionQualificationExitGap? {
        val config = configuration ?: return OrionQualificationExitGap.CONFIGURATION_UNAVAILABLE
        if (invalidClock) return OrionQualificationExitGap.CLOCK_DISCONTINUOUS
        if (pair.bootCount < 0 || pair.elapsedMillis < 0 || pair.elapsedMillis < lastElapsed || pair.wallLowerMillis < 0 || pair.wallUpperMillis < pair.wallLowerMillis ||
            pair.wallUpperMillis - pair.wallLowerMillis > config.maximumPairSpanMillis) {
            invalidClock = true; return OrionQualificationExitGap.CLOCK_DISCONTINUOUS
        }
        if (boot != null && boot != pair.bootCount) { invalidClock = true; return OrionQualificationExitGap.BOOT_CHANGED }
        // Each nonnegative integer clock read represents [n,n+1) ms, independently quantized.
        // Since the wall reads bracket the mono read, a constant offset must lie strictly inside
        // (wallLower - elapsed - 1, wallUpper - elapsed + 1). These are mathematical bounds,
        // not a calibrated tolerance, changed timestamp or permission to repair a discontinuity.
        val lower = try { Math.subtractExact(Math.subtractExact(pair.wallLowerMillis, pair.elapsedMillis), 1L) }
            catch (_: ArithmeticException) { invalidClock = true; return OrionQualificationExitGap.CLOCK_UNAVAILABLE }
        val upper = try { Math.addExact(Math.subtractExact(pair.wallUpperMillis, pair.elapsedMillis), 1L) }
            catch (_: ArithmeticException) { invalidClock = true; return OrionQualificationExitGap.CLOCK_UNAVAILABLE }
        val joinedLower = maxOf(offsetLowerExclusive ?: lower, lower)
        val joinedUpper = minOf(offsetUpperExclusive ?: upper, upper)
        // The endpoints are open: touching at one excluded value is not a feasible constant offset.
        if (joinedLower >= joinedUpper) { invalidClock = true; return OrionQualificationExitGap.CLOCK_DISCONTINUOUS }
        offsetLowerExclusive = joinedLower; offsetUpperExclusive = joinedUpper; lastElapsed = pair.elapsedMillis; boot = pair.bootCount
        return null
    }
    fun unavailableClock(): OrionQualificationExitGap {
        invalidClock = true; previous = null; paired = null
        return OrionQualificationExitGap.CLOCK_UNAVAILABLE
    }
    fun liveUnavailable(value: OrionQualificationObservationWindow, gap: OrionQualificationExitGap): OrionQualificationExitGap {
        val failed = window(value)
        previous = null; paired = null
        return failed ?: gap
    }
    fun pairedPid(): Int? = if (!invalidClock) paired?.pid else null
    private fun window(value: OrionQualificationObservationWindow): OrionQualificationExitGap? = time(value.started) ?: time(value.completed)
    fun live(value: OrionQualificationLiveReceipt): OrionQualificationExitGap? {
        window(value.window)?.let { paired = null; previous = null; return it }
        val config = checkNotNull(configuration)
        if (value.pid <= 0 || value.pid == mainPid || value.process.pid != value.pid || value.process.uid != uid || value.process.kernelStartTicks <= 0 ||
            value.aiBootCount != value.window.started.bootCount || value.aiSampledElapsedMillis !in value.window.started.elapsedMillis..value.window.completed.elapsedMillis) {
            paired = null; previous = null; return OrionQualificationExitGap.IDENTITY_MISMATCH
        }
        if (value.window.completed.elapsedMillis - value.aiSampledElapsedMillis > config.maximumLiveGapMillis) {
            previous = null; paired = null; return OrionQualificationExitGap.LIVE_STALE
        }
        // A prior PID incarnation's history cannot become this live instance's incident after a wall jump.
        // Keep the union from both actual live checkpoints; reason/classification updates do not create a new baseline identity.
        val entries = value.baseline.entries
        if (value.baseline.truncated || entries != null && entries.size > MAX_EXITS) {
            previous = null; paired = null; return OrionQualificationExitGap.HISTORY_TRUNCATED
        }
        if (entries == null) { previous = null; paired = null; return OrionQualificationExitGap.HISTORY_UNAVAILABLE }
        val old = previous
        if (old == null || !old.endpoint.matches(value.endpoint) || old.process != value.process) baseline.clear()
        val added = entries.map(::baselineKey)
        if ((baseline + added).size > MAX_EXITS) { previous = null; paired = null; return OrionQualificationExitGap.CAPACITY }
        baseline.addAll(added)
        previous = value
        if (old == null || old.originalReceipt === value.originalReceipt) { paired = null; return OrionQualificationExitGap.LIVE_UNPAIRED }
        if (!old.endpoint.matches(value.endpoint)) { paired = null; return OrionQualificationExitGap.ENDPOINT_CHANGED }
        if (old.process != value.process) { paired = null; return OrionQualificationExitGap.PID_REUSED }
        if (value.aiSampledElapsedMillis < old.aiSampledElapsedMillis ||
            value.aiSampledElapsedMillis - old.aiSampledElapsedMillis > config.maximumLiveGapMillis) { paired = null; return OrionQualificationExitGap.LIVE_STALE }
        paired = value
        return null
    }
    fun history(observed: OrionQualificationObservationWindow, entries: List<OrionQualificationExitRecord>?,
        currentProcess: OrionQualificationProcObservation, truncated: Boolean = false): OrionQualificationExitResult {
        window(observed)?.let { return OrionQualificationExitResult(null, it) }
        val live = paired ?: return OrionQualificationExitResult(null, OrionQualificationExitGap.LIVE_UNPAIRED)
        val config = checkNotNull(configuration)
        if (observed.started.elapsedMillis < live.aiSampledElapsedMillis || observed.completed.elapsedMillis - live.aiSampledElapsedMillis > config.maximumExitDelayMillis)
            return OrionQualificationExitResult(null, OrionQualificationExitGap.LIVE_STALE)
        if (currentProcess.state == OrionQualificationProcState.UNAVAILABLE)
            return OrionQualificationExitResult(null, OrionQualificationExitGap.PROC_UNAVAILABLE)
        if (currentProcess.state == OrionQualificationProcState.PRESENT)
            return OrionQualificationExitResult(null, if (currentProcess.witness == live.process) OrionQualificationExitGap.NO_MATCH else OrionQualificationExitGap.PID_REUSED)
        if (truncated) return OrionQualificationExitResult(null, OrionQualificationExitGap.HISTORY_TRUNCATED)
        if (entries == null) return OrionQualificationExitResult(null, OrionQualificationExitGap.HISTORY_UNAVAILABLE)
        val bounded = entries.take(MAX_EXITS + 1)
        if (bounded.size > MAX_EXITS) return OrionQualificationExitResult(null, OrionQualificationExitGap.HISTORY_TRUNCATED)
        // Real mono sample < sampledElapsed+1 and real offset < upperExclusive. Their sum is
        // strictly below this integer bound; requiring exit's wall floor >= bound proves ordering.
        // This derived causal bound is private metadata, never a rewritten observed timestamp.
        val lastLiveWallUpper = try { Math.addExact(Math.addExact(live.aiSampledElapsedMillis, checkNotNull(offsetUpperExclusive)), 1L) }
            catch (_: ArithmeticException) { return OrionQualificationExitResult(null, OrionQualificationExitGap.CLOCK_UNAVAILABLE) }
        val matched = bounded.filter { baselineKey(it) !in baseline && it.pid == live.pid && it.realUid == uid && it.packageUid == uid && it.processName == "$packageName:ai" &&
            it.timestampWallMillis in lastLiveWallUpper..observed.completed.wallLowerMillis }
        val groups = matched.groupBy { listOf(it.pid.toLong(), it.realUid.toLong(), it.packageUid.toLong(), it.timestampWallMillis, it.reason.toLong()) }
        if (groups.values.any { values -> values.map { Triple(it.signal.kind, it.descriptionEvidence, it.dedicatedReason) }.distinct().size > 1 })
            return OrionQualificationExitResult(null, OrionQualificationExitGap.AMBIGUOUS)
        val candidates = groups.values.map { it.first() }
        if (candidates.isEmpty()) return OrionQualificationExitResult(null, OrionQualificationExitGap.NO_MATCH)
        // Distinct records with this PID/window are ambiguous, even if only one has a limiter classification.
        if (candidates.size != 1) return OrionQualificationExitResult(null, OrionQualificationExitGap.AMBIGUOUS)
        val exit = candidates.single()
        val key = Key(exit.pid, exit.realUid, exit.packageUid, exit.timestampWallMillis, exit.reason, live.process.kernelStartTicks)
        if (seen.keys.any { it.pid == key.pid && it.startTicks == key.startTicks && it != key })
            return OrionQualificationExitResult(null, OrionQualificationExitGap.AMBIGUOUS)
        val existing = seen[key]
        if (existing == null && (seen.size >= MAX_EXITS || ordinal == Long.MAX_VALUE)) return OrionQualificationExitResult(null, OrionQualificationExitGap.CAPACITY)
        val first = existing ?: (++ordinal).also { seen[key] = it }
        return OrionQualificationExitResult(OrionQualificationExitIncident(exit, live.process, live.endpoint, mainPid, live.aiBootCount,
            live.aiSampledElapsedMillis, lastLiveWallUpper, observed, first, existing == null), null)
    }
    companion object { const val MAX_EXITS = 64 }
}

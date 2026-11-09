package network.orion.core.resources

import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

/** RSS plus swapped pages includes Java and native/JNI allocations; shared pages are conservatively counted per process. */
data class OrionProcessMemorySample(val pid: Int, val residentBytes: Long, val swapBytes: Long)

/**
 * MAIN-owned evidence from a qualified source, not a Binder/user claim. Completeness
 * must be established independently: a best-effort process list is insufficient.
 */
data class OrionAppMemoryEvidence(
    val sampledAt: MonotonicInstant,
    val completeRoster: Boolean,
    val processIds: Set<Int>,
    val inferenceProcessId: Int?,
    val samples: List<OrionProcessMemorySample>,
)

data class OrionAppMemoryUsage(val inferenceProcessBytes: Long, val aggregateAppBytes: Long)

/** Reject partial coverage, duplicate/reused PID readings, stale evidence and overflow. Missing never becomes zero. */
object OrionAppMemoryAccounting {
    const val MAX_APP_PROCESSES = 16

    fun calculate(evidence: OrionAppMemoryEvidence?, now: MonotonicInstant, maximumAgeMillis: Long): OrionAppMemoryUsage? {
        require(maximumAgeMillis > 0)
        evidence ?: return null
        val age = now.elapsedSince(evidence.sampledAt)?.toMillis() ?: return null
        if (age >= maximumAgeMillis || !evidence.completeRoster || evidence.processIds.isEmpty() ||
            evidence.processIds.size > MAX_APP_PROCESSES || evidence.samples.size != evidence.processIds.size ||
            evidence.processIds.any { it <= 0 } ||
            evidence.inferenceProcessId?.let { it !in evidence.processIds } == true) return null
        val samples = evidence.samples.associateBy { it.pid }
        if (samples.size != evidence.samples.size || samples.keys != evidence.processIds) return null
        var total = 0L
        var inference = 0L
        for (sample in samples.values) {
            // A running process with zero RSS is unavailable/incomplete, not an empty process.
            if (sample.residentBytes <= 0 || sample.swapBytes < 0 ||
                sample.residentBytes > Long.MAX_VALUE - sample.swapBytes) return null
            val bytes = sample.residentBytes + sample.swapBytes
            if (total > Long.MAX_VALUE - bytes) return null
            total += bytes
            if (sample.pid == evidence.inferenceProcessId) inference = bytes
        }
        // A null inference PID denotes absence only under an independently complete roster.
        return OrionAppMemoryUsage(inference, total)
    }
}

/** Retains recent pressure for a configured hysteresis window; current system evidence must still be available. */
class OrionMemoryPressureTracker(private val clock: MonotonicClock, private val holdMillis: Long) {
    private var last: MonotonicInstant? = null
    private var severity = OrionMemoryPressure.NONE
    init { require(holdMillis > 0) }

    @Synchronized fun record(pressure: OrionMemoryPressure) {
        require(pressure == OrionMemoryPressure.MODERATE || pressure == OrionMemoryPressure.CRITICAL)
        val now = clock.monotonicNow()
        val age = last?.let { now.elapsedSince(it)?.toMillis() }
        if (age == null || age >= holdMillis || pressure == OrionMemoryPressure.CRITICAL) severity = pressure
        last = now
    }

    @Synchronized fun current(systemLowMemory: Boolean?): OrionMemoryPressure {
        val now = clock.monotonicNow()
        if (systemLowMemory == true) {
            // Fresh system pressure is evidence too, even when Android omits a
            // trim callback. A following healthy sample cannot erase it at once.
            last = now; severity = OrionMemoryPressure.CRITICAL
            return if (now.bootSessionId == null) OrionMemoryPressure.UNKNOWN else severity
        }
        if (now.bootSessionId == null || systemLowMemory == null) return OrionMemoryPressure.UNKNOWN
        val stamp = last ?: return OrionMemoryPressure.NONE
        if (stamp.bootSessionId == null) {
            // A callback received while boot identity was unavailable cannot be
            // dated safely. Start its whole conservative hold once identity returns.
            last = now
            return severity
        }
        val age = now.elapsedSince(stamp)?.toMillis()
        if (age == null) {
            // A changed boot invalidates old pressure; a same-boot regression remains unknown.
            if (now.bootSessionId == stamp.bootSessionId) return OrionMemoryPressure.UNKNOWN
            last = null; severity = OrionMemoryPressure.NONE
            return OrionMemoryPressure.NONE
        }
        if (age >= holdMillis) { last = null; severity = OrionMemoryPressure.NONE }
        return severity
    }
}

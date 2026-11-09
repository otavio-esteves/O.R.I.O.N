package network.orion.core.resources

import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

/**
 * MAIN's bounded launch circuit. This class never schedules a restart or authorizes
 * inference. The caller supplies fresh AI-scoped exit history before every explicit
 * attempt, and still needs ResourceGovernor approval. Limits are operational
 * configuration, not measured device/model budgets.
 */
class OrionAiRestartGuard(
    private val clock: MonotonicClock,
    private val maximumAttemptsPerBoot: Int,
    private val minimumAttemptIntervalMillis: Long,
    private val memoryCooldownMillis: Long,
    private val maximumMemoryDeathsPerBoot: Int,
) {
    private var boot: BootSessionId? = null
    private var attempts = 0
    private var memoryDeaths = 0
    private var lastAttempt: MonotonicInstant? = null
    private var memoryFence: MonotonicInstant? = null
    private var historyReady = false
    private var historyInvalid = false
    private val seen = linkedSetOf<ProcessExitSignal>()

    init {
        require(maximumAttemptsPerBoot in 1..100 && maximumMemoryDeathsPerBoot in 1..64)
        require(minimumAttemptIntervalMillis > 0 && memoryCooldownMillis > 0)
    }

    /** Null/oversized/unclassified history is unavailable, never a clean history. */
    @Synchronized fun observeExits(signals: List<ProcessExitSignal>?) {
        val now = clock.monotonicNow()
        resetBoot(now)
        historyReady = false
        historyInvalid = signals == null || signals.size > MAX_EXIT_SIGNALS || now.bootSessionId == null
        if (historyInvalid) return
        val snapshot = signals!!.take(MAX_EXIT_SIGNALS)
        historyInvalid = snapshot.any { it.kind == ProcessExitKind.UNKNOWN && !it.memoryLimiterEvidence && it.reason != 3 }
        if (historyInvalid) return
        for (signal in snapshot) observeExitInternal(signal, now)
        historyReady = true
    }

    /** One permitted call consumes one launch opportunity and its history observation. */
    @Synchronized fun allowAttempt(): Boolean {
        val now = clock.monotonicNow()
        resetBoot(now)
        val observed = historyReady
        historyReady = false
        if (!observed || historyInvalid || now.bootSessionId == null || attempts >= maximumAttemptsPerBoot ||
            memoryDeaths >= maximumMemoryDeathsPerBoot) return false
        memoryFence?.let {
            if (it.bootSessionId == null) {
                memoryFence = now
                return false
            }
            val age = now.elapsedSince(it)?.toMillis() ?: return false
            if (age < memoryCooldownMillis) return false
        }
        lastAttempt?.let {
            val age = now.elapsedSince(it)?.toMillis() ?: return false
            if (age < minimumAttemptIntervalMillis) return false
        }
        attempts++
        lastAttempt = now
        return true
    }

    /** Trusted Binder/exit-monitor callback only. An ambiguous death never causes an automatic relaunch. */
    @Synchronized fun recordDeath(memoryEvidence: Boolean) {
        val now = clock.monotonicNow()
        resetBoot(now)
        historyReady = false
        if (memoryEvidence) {
            memoryDeaths = minOf(MAX_EXIT_SIGNALS, memoryDeaths + 1)
            memoryFence = now
        }
    }

    /** A stable historical event is applied once; duplicates cannot renew the cooldown indefinitely. */
    @Synchronized fun observeExit(signal: ProcessExitSignal) {
        val now = clock.monotonicNow()
        resetBoot(now)
        historyReady = false
        observeExitInternal(signal, now)
    }

    private fun observeExitInternal(signal: ProcessExitSignal, now: MonotonicInstant) {
        if (!isMemory(signal) || signal in seen) return
        // Once the fixed evidence bound is exhausted the circuit remains non-permissive.
        if (seen.size >= MAX_EXIT_SIGNALS) { historyInvalid = true; return }
        seen.add(signal)
        memoryDeaths = minOf(MAX_EXIT_SIGNALS, memoryDeaths + 1)
        memoryFence = now
    }

    private fun resetBoot(now: MonotonicInstant) {
        // Losing an optional platform reading is not evidence of a new boot and
        // must never erase an already-open circuit or its consumed launch budget.
        if (now.bootSessionId == null) { historyReady = false; return }
        if (boot == now.bootSessionId) return
        if (boot == null) { boot = now.bootSessionId; historyReady = false; return }
        boot = now.bootSessionId
        attempts = 0; memoryDeaths = 0; lastAttempt = null; memoryFence = null
        historyReady = false; historyInvalid = false; seen.clear()
    }

    private fun isMemory(signal: ProcessExitSignal) = signal.reason == 3 || signal.memoryLimiterEvidence ||
        signal.kind == ProcessExitKind.LOW_MEMORY || signal.kind == ProcessExitKind.MEMORY_LIMITER

    companion object { private const val MAX_EXIT_SIGNALS = 64 }
}

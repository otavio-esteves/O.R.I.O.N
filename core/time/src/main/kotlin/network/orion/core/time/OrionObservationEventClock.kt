package network.orion.core.time

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Capture-only value. No deadline, duration, lease, domain-clock interface or implicit conversion. */
class OrionObservationMoment internal constructor(
    val observationalBootCount: Int,
    val elapsedRealtimeMillis: Long,
    private val captureScope: Any,
) {
    fun sameObservationAs(other: OrionObservationMoment): Boolean = captureScope === other.captureScope
    override fun toString() = "OrionObservationMoment(captureOnly=true)"
}

enum class OrionObservationClockGap { WITNESS_INVALID, REBOUND, COUNTER_UNAVAILABLE, INVALID_COUNTER,
    COUNTER_REGRESSED, CONTENDED, RECORDING_FAILURE }

data class OrionObservationClockSnapshot(val bound: Boolean, val invalidated: Boolean,
    val observationalCachedBootCount: Int?, val firstGap: OrionObservationClockGap?,
    val counterReadsAtLeast: Long, val droppedAtLeast: Long, val recordingFailuresAtLeast: Long,
    val lossAccountingComplete: Boolean, val snapshotContended: Boolean)

/**
 * Final native elapsed-only time boundary. Construction/binding do not read a provider or any clock.
 * The cached boot is one capture's original witness, never fresh domain boot evidence or a lease.
 * Future genuine runOnce hooks must own binding and independently fence the actual source session.
 */
class OrionObservationEventClock private constructor(private val counter: Counter) {

    private sealed class Counter { abstract fun read(): Long? }
    private object NativeCounter : Counter() { override fun read(): Long = SystemClock.elapsedRealtime() }
    private class FiniteCounter(values: List<Long?>) : Counter() {
        private val values = values.toList()
        private var index = 0
        override fun read(): Long? = if (index < values.size) values[index++] else null
    }

    private val lock = ReentrantLock()
    private val scope = Any()
    private val bindingAttempted = AtomicBoolean()
    private val invalidated = AtomicBoolean()
    private val firstGap = AtomicReference<OrionObservationClockGap?>()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val reads = AtomicLong()
    private val accounting = AtomicBoolean(true)
    @Volatile private var boot: Int? = null
    @Volatile private var bound = false
    private var lastElapsed = -1L
    private var lastOriginalElapsed = -1L

    private fun count(value: AtomicLong) {
        val before = value.get()
        if (before == Long.MAX_VALUE || !value.compareAndSet(before, before + 1)) accounting.set(false)
    }
    private fun invalidate(reason: OrionObservationClockGap) {
        invalidated.set(true); firstGap.compareAndSet(null, reason); count(dropped)
    }
    private fun failed() { count(failures); accounting.set(false); invalidate(OrionObservationClockGap.RECORDING_FAILURE) }

    /** The samples/classification must already exist in the real original owner's execution. */
    fun bindOriginalWitness(preparation: MonotonicInstant, alreadyValidated: MonotonicInstant?,
        classification: MonotonicDeadlineStatus?): Boolean {
        if (!bindingAttempted.compareAndSet(false, true)) { invalidate(OrionObservationClockGap.REBOUND); return false }
        if (!lock.tryLock()) { invalidate(OrionObservationClockGap.CONTENDED); return false }
        return try {
            val identity = preparation.bootSessionId
            if (invalidated.get() || identity == null || alreadyValidated == null || alreadyValidated.bootSessionId != identity ||
                alreadyValidated.elapsedRealtimeMillis < preparation.elapsedRealtimeMillis ||
                classification == null || classification == MonotonicDeadlineStatus.INVALID_REFERENCE) {
                invalidate(OrionObservationClockGap.WITNESS_INVALID); false
            } else {
                boot = identity.bootCount
                lastOriginalElapsed = alreadyValidated.elapsedRealtimeMillis
                lastElapsed = lastOriginalElapsed // A lower bound only; never exported as a capture/event instant.
                bound = true
                true
            }
        } catch (_: Throwable) { failed(); false }
        finally { lock.unlock() }
    }

    /** Compare an already-performed original read; never query its source or refresh the cached boot. */
    fun confirmOriginalWitness(sample: MonotonicInstant?, classification: MonotonicDeadlineStatus?): Boolean {
        if (!bound || invalidated.get()) return false
        if (!lock.tryLock()) { invalidate(OrionObservationClockGap.CONTENDED); return false }
        return try {
            if (invalidated.get() || sample == null || sample.bootSessionId?.bootCount != boot ||
                sample.elapsedRealtimeMillis < lastOriginalElapsed || sample.elapsedRealtimeMillis < lastElapsed || classification == null ||
                classification == MonotonicDeadlineStatus.INVALID_REFERENCE) {
                invalidate(OrionObservationClockGap.WITNESS_INVALID); false
            } else { lastOriginalElapsed = sample.elapsedRealtimeMillis; true }
        } catch (_: Throwable) { failed(); false }
        finally { lock.unlock() }
    }

    /** Actual additional instrumentation read. Never borrows the binding witness as event time. */
    fun observe(): OrionObservationMoment? {
        if (!bound || invalidated.get()) return null
        if (!lock.tryLock()) { invalidate(OrionObservationClockGap.CONTENDED); return null }
        return try {
            if (invalidated.get()) return null
            count(reads)
            val now = counter.read()
            when {
                now == null -> { invalidate(OrionObservationClockGap.COUNTER_UNAVAILABLE); null }
                now < 0 -> { invalidate(OrionObservationClockGap.INVALID_COUNTER); null }
                now < lastElapsed || now < lastOriginalElapsed -> { invalidate(OrionObservationClockGap.COUNTER_REGRESSED); null }
                else -> OrionObservationMoment(checkNotNull(boot), now, scope).also { lastElapsed = now }
            }
        } catch (_: Throwable) { failed(); null }
        finally { lock.unlock() }
    }

    /** Manual scalar snapshot only; not a fresh boot identity or measurement read. */
    fun snapshot(): OrionObservationClockSnapshot? {
        val acquired = lock.tryLock()
        if (!acquired) invalidate(OrionObservationClockGap.CONTENDED)
        return try {
            OrionObservationClockSnapshot(bound, invalidated.get(), boot, firstGap.get(), reads.get(), dropped.get(),
                failures.get(), accounting.get(), !acquired)
        } catch (_: Throwable) { failed(); null }
        finally { if (acquired) lock.unlock() }
    }

    companion object {
        /** Optional construction failure is source unavailable, never a functional clock failure. */
        fun createInactive(): OrionObservationEventClock? = try { OrionObservationEventClock(NativeCounter) } catch (_: Throwable) { null }

        /** Finite synthetic scalar source stays inside core/time; never a public provider/factory. */
        internal fun finiteForTests(values: List<Long?>): OrionObservationEventClock {
            require(values.size <= 256)
            return OrionObservationEventClock(FiniteCounter(values))
        }
    }
}

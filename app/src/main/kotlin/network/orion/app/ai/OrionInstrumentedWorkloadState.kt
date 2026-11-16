package network.orion.app.ai

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** MAIN-created graph identity, never a capability, resource decision or execution lease. */
internal class OrionInstrumentedGraphIdentity(private val graph: Any, private val owner: Any) {
    val nonce: String = UUID.randomUUID().toString()
    fun matches(actualGraph: Any, actualOwner: Any) = graph === actualGraph && owner === actualOwner
    override fun toString() = "OrionInstrumentedGraphIdentity(REDACTED)"
}

internal enum class OrionInstrumentedOperation { OPEN, BOOTSTRAP, WORK, OBSERVE, CLEANUP }
internal enum class OrionInstrumentedExecutionState { IDLE, ACTIVE, UNCERTAIN }

/** One bounded run's association/phase guard. It cannot authorize anything in Core. */
internal class OrionInstrumentedWorkloadScope(val graph: OrionInstrumentedGraphIdentity,
    val runId: String, val fixtureId: String) {
    private val phase = AtomicInteger(IDLE)
    private val attempts = HashSet<String>()
    private var bootstrapAttempt: String? = null
    private var operations = 0
    init { require(validId(runId) && validId(fixtureId)) }
    fun state(): OrionInstrumentedExecutionState = when (phase.get()) {
        IDLE, CLOSED -> OrionInstrumentedExecutionState.IDLE
        UNKNOWN -> OrionInstrumentedExecutionState.UNCERTAIN
        else -> OrionInstrumentedExecutionState.ACTIVE
    }
    fun matches(run: String, fixture: String, nonce: String) = run == runId && fixture == fixtureId && nonce == graph.nonce
    fun claim(operation: OrionInstrumentedOperation, run: String, fixture: String, nonce: String, attempt: String?): Boolean {
        if (operation == OrionInstrumentedOperation.OPEN || !matches(run, fixture, nonce)) return false
        val selected = when (operation) {
            OrionInstrumentedOperation.WORK -> WORK
            OrionInstrumentedOperation.BOOTSTRAP -> BOOTSTRAP
            OrionInstrumentedOperation.OBSERVE -> OBSERVE
            OrionInstrumentedOperation.CLEANUP -> CLEANUP
            else -> return false
        }
        if (!phase.compareAndSet(IDLE, selected)) return false
        if (operations >= MAX_OPERATIONS) { phase.compareAndSet(selected, IDLE); return false }
        operations++
        if (operation == OrionInstrumentedOperation.BOOTSTRAP) {
            if (attempt == null || !validId(attempt) || bootstrapAttempt != null || attempts.isNotEmpty()) {
                phase.compareAndSet(selected, IDLE); return false
            }
            bootstrapAttempt = attempt
        } else if (operation == OrionInstrumentedOperation.WORK) {
            if (attempt == null || !validId(attempt) || attempts.size >= MAX_ATTEMPTS || attempt == bootstrapAttempt || !attempts.add(attempt)) {
                phase.compareAndSet(selected, IDLE); return false
            }
        } else if (attempt != null) { phase.compareAndSet(selected, IDLE); return false }
        return true
    }
    fun finish(operation: OrionInstrumentedOperation, uncertain: Boolean = false): Boolean {
        val selected = when (operation) {
            OrionInstrumentedOperation.WORK -> WORK
            OrionInstrumentedOperation.BOOTSTRAP -> BOOTSTRAP
            OrionInstrumentedOperation.OBSERVE -> OBSERVE
            OrionInstrumentedOperation.CLEANUP -> CLEANUP
            else -> return false
        }
        return phase.compareAndSet(selected, if (uncertain) UNKNOWN else if (operation == OrionInstrumentedOperation.CLEANUP) CLOSED else IDLE)
    }
    fun closed() = phase.get() == CLOSED
    override fun toString() = "OrionInstrumentedWorkloadScope(state=${state()}, qualified=false)"
    companion object {
        private const val IDLE = 0
        private const val WORK = 1
        private const val OBSERVE = 2
        private const val CLEANUP = 3
        private const val UNKNOWN = 4
        private const val CLOSED = 5
        private const val BOOTSTRAP = 6
        const val MAX_ATTEMPTS = 256
        const val MAX_OPERATIONS = 4096
        fun validId(value: String): Boolean = value.length == 36 && try {
            val parsed = UUID.fromString(value)
            parsed.toString() == value && parsed != UUID(0, 0)
        } catch (_: IllegalArgumentException) { false }
    }
}

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
internal enum class OrionInstrumentedExecutionState { IDLE, ACTIVE, UNCERTAIN, DEAD_AWAITING_EVIDENCE }

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
        DEAD_PENDING, OBSERVE_DEAD_PENDING -> OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE
        else -> OrionInstrumentedExecutionState.ACTIVE
    }
    fun matches(run: String, fixture: String, nonce: String) = run == runId && fixture == fixtureId && nonce == graph.nonce
    fun claim(operation: OrionInstrumentedOperation, run: String, fixture: String, nonce: String, attempt: String?): Boolean {
        if (operation == OrionInstrumentedOperation.OPEN || !matches(run, fixture, nonce)) return false
        val before = phase.get()
        val selected = when (operation) {
            OrionInstrumentedOperation.WORK -> WORK
            OrionInstrumentedOperation.BOOTSTRAP -> BOOTSTRAP
            OrionInstrumentedOperation.OBSERVE -> if (before == DEAD_PENDING) OBSERVE_DEAD_PENDING else OBSERVE
            OrionInstrumentedOperation.CLEANUP -> CLEANUP
            else -> return false
        }
        if (before != IDLE && !(before == DEAD_PENDING && operation == OrionInstrumentedOperation.OBSERVE)) return false
        if (!phase.compareAndSet(before, selected)) return false
        if (operations >= MAX_OPERATIONS) { phase.compareAndSet(selected, before); return false }
        operations++
        if (operation == OrionInstrumentedOperation.BOOTSTRAP) {
            if (attempt == null || !validId(attempt) || bootstrapAttempt != null || attempts.isNotEmpty()) {
                phase.compareAndSet(selected, before); return false
            }
            bootstrapAttempt = attempt
        } else if (operation == OrionInstrumentedOperation.WORK) {
            if (attempt == null || !validId(attempt) || attempts.size >= MAX_ATTEMPTS || attempt == bootstrapAttempt || !attempts.add(attempt)) {
                phase.compareAndSet(selected, before); return false
            }
        } else if (attempt != null) { phase.compareAndSet(selected, before); return false }
        return true
    }
    fun finish(operation: OrionInstrumentedOperation, uncertain: Boolean = false, deathEvidencePending: Boolean = false): Boolean {
        if (deathEvidencePending && (uncertain || operation !in setOf(OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.BOOTSTRAP))) return false
        val current = phase.get()
        val selected = when (operation) {
            OrionInstrumentedOperation.WORK -> WORK
            OrionInstrumentedOperation.BOOTSTRAP -> BOOTSTRAP
            OrionInstrumentedOperation.OBSERVE -> if (current == OBSERVE_DEAD_PENDING) OBSERVE_DEAD_PENDING else OBSERVE
            OrionInstrumentedOperation.CLEANUP -> CLEANUP
            else -> return false
        }
        return phase.compareAndSet(selected, if (deathEvidencePending) DEAD_PENDING else if (selected == OBSERVE_DEAD_PENDING && !uncertain) DEAD_PENDING else if (uncertain) UNKNOWN else if (operation == OrionInstrumentedOperation.CLEANUP) CLOSED else IDLE)
    }
    /** Only the already-owned read-only OBSERVE may settle native death; UNKNOWN is never accepted. */
    fun confirmOriginalProcessDeath(): Boolean = phase.compareAndSet(OBSERVE_DEAD_PENDING, OBSERVE)
    fun observationResultState(): OrionInstrumentedExecutionState = when (phase.get()) {
        IDLE, CLOSED, OBSERVE -> OrionInstrumentedExecutionState.IDLE
        UNKNOWN -> OrionInstrumentedExecutionState.UNCERTAIN
        DEAD_PENDING, OBSERVE_DEAD_PENDING -> OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE
        else -> OrionInstrumentedExecutionState.ACTIVE
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
        private const val DEAD_PENDING = 7
        private const val OBSERVE_DEAD_PENDING = 8
        const val MAX_ATTEMPTS = 256
        const val MAX_OPERATIONS = 4096
        fun validId(value: String): Boolean = value.length == 36 && try {
            val parsed = UUID.fromString(value)
            parsed.toString() == value && parsed != UUID(0, 0)
        } catch (_: IllegalArgumentException) { false }
    }
}

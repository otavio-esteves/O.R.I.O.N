package network.orion.app.ai

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import network.orion.ai.api.OrionCortexWire
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferencePriority

/** Internal reviewed configuration only. Construction is neither qualification nor admission.
 * No chosen request parameters/workload are supplied by this package or a parser/runner.
 */
internal class OrionCortexAttemptTemplate(
    internal val modelId: String,
    internal val contextTokens: Int,
    internal val threads: Int,
    internal val maxTokens: Int,
    internal val requestDurationMillis: Long,
    internal val temperature: Double,
    internal val priority: OrionInferencePriority,
    internal val workloadPrompt: String,
    internal val workloadContext: String,
) {
    init {
        require(modelId.length <= 128 && workloadPrompt.length <= OrionCortexWire.MAX_INPUT_BYTES &&
            workloadContext.length <= OrionCortexWire.MAX_INPUT_BYTES) { "Attempt template exceeds structural bounds." }
    }
    internal fun isValid(): Boolean {
        if (!validModelHash(modelId) || contextTokens !in 32..OrionCortexWire.MAX_CONTEXT ||
            threads !in 1..OrionCortexWire.MAX_THREADS || maxTokens !in 1..OrionCortexWire.MAX_TOKENS ||
            requestDurationMillis !in 0..120_000 || !temperature.isFinite() || temperature !in 0.0..2.0 ||
            workloadPrompt.isEmpty() || workloadPrompt.length.toLong() + workloadContext.length >= OrionCortexWire.MAX_INPUT_BYTES)
            return false
        return workloadPrompt.toByteArray(Charsets.UTF_8).size.toLong() +
            workloadContext.toByteArray(Charsets.UTF_8).size < OrionCortexWire.MAX_INPUT_BYTES
    }
    override fun toString() = "OrionCortexAttemptTemplate(configuration=REDACTED, qualified=false)"
}

/** Closed technical refusals. Code zero is reserved for SELECTED, which is never a permit. */
internal enum class OrionCortexAttemptRefusal(val code: Int) {
    OWNER_UNAVAILABLE(1), CLOSED(2), NO_QUALIFIED_PROFILE(3), RESOURCE_CONFIGURATION_UNAVAILABLE(4),
    CORE_NOT_READY(5), OBSERVER_UNAVAILABLE(6), TEMPLATE_UNAVAILABLE(7), TEMPLATE_INVALID(8),
    MODEL_NOT_READY(9), PROFILE_MISMATCH(10), ALREADY_CONSUMED(11), SELECTION_UNAVAILABLE(12),
}

/** One bounded CAS; foreign ownership is checked before consumption, even after use. */
internal class OrionCortexAttemptClaim(private val owner: Any, private val driver: Any) {
    private val used = AtomicBoolean()
    fun owns(actualOwner: Any, actualDriver: Any) = owner === actualOwner && driver === actualDriver
    fun consume(actualOwner: Any, actualDriver: Any): OrionCortexAttemptRefusal? = when {
        !owns(actualOwner, actualDriver) -> OrionCortexAttemptRefusal.OWNER_UNAVAILABLE
        !used.compareAndSet(false, true) -> OrionCortexAttemptRefusal.ALREADY_CONSUMED
        else -> null
    }
    override fun toString() = "OrionCortexAttemptClaim(ownership=OPAQUE)"
}

/** Owner selection result contains no registry row/label/path or resource permit. */
internal sealed interface OrionCortexAttemptConfiguration {
    class Ready(val template: OrionCortexAttemptTemplate) : OrionCortexAttemptConfiguration {
        override fun toString() = "OrionCortexAttemptConfiguration(SELECTED, qualified=false)"
    }
    class Refused(val reason: OrionCortexAttemptRefusal) : OrionCortexAttemptConfiguration {
        override fun toString() = "OrionCortexAttemptConfiguration(refusal=${reason.code})"
    }
}

/** One explicit attempt on an existing Owner. No factory, executor loop, poller or admission source. */
internal class OrionCortexSingleAttemptDriver(private val owner: OrionCortexRuntimeOwner) {
    private val scope = Any()
    private val prepared = AtomicBoolean()

    class Candidate private constructor(
        private val claim: OrionCortexAttemptClaim,
        private val template: OrionCortexAttemptTemplate,
    ) {
        @Volatile private var token: OrionCortexCycleToken? = null
        @Volatile private var runFinished: Boolean = false
        internal fun templateFor(owner: Any, scope: Any) = template.takeIf { owns(owner, scope) }
        internal fun tokenFor(owner: Any, scope: Any) = token.takeIf { owns(owner, scope) }
        internal fun finishedFor(owner: Any, scope: Any) = owns(owner, scope) && runFinished
        internal fun reserved(owner: Any, scope: Any, value: OrionCortexCycleToken) {
            if (owns(owner, scope)) token = value
        }
        internal fun finished(owner: Any, scope: Any) { if (owns(owner, scope)) runFinished = true }
        internal fun owns(owner: Any, scope: Any) = claim.owns(owner, scope)
        internal fun consume(owner: Any, scope: Any) = claim.consume(owner, scope)
        override fun toString() = "OrionCortexAttemptCandidate(ownership=OPAQUE, qualified=false)"
        companion object {
            internal fun selected(owner: Any, scope: Any, template: OrionCortexAttemptTemplate) =
                Candidate(OrionCortexAttemptClaim(owner, scope), template)
        }
    }

    sealed interface Selection {
        val code: Int
        class Selected(val candidate: Candidate) : Selection {
            override val code: Int get() = 0
            override fun toString() = "OrionCortexAttemptSelection(SELECTED, qualified=false)"
        }
        class Refused(val reason: OrionCortexAttemptRefusal) : Selection {
            override val code: Int get() = reason.code
            override fun toString() = "OrionCortexAttemptSelection(refusal=$code)"
        }
    }

    sealed interface Execution {
        /** Not a run result or cycle receipt: runObserved was never called. */
        class DriverNotAttempted(val reason: OrionCortexAttemptRefusal) : Execution {
            override fun toString() = "OrionCortexAttemptExecution(DRIVER_NOT_ATTEMPTED, refusal=${reason.code})"
        }
        /** Reserved before run; only published after storing the same original result reference. */
        class Invoked internal constructor() : Execution {
            private var value: OrionCortexRunResult? = null
            val originalResult: OrionCortexRunResult get() = checkNotNull(value)
            internal fun completed(result: OrionCortexRunResult) { value = result }
            override fun toString() = "OrionCortexAttemptExecution(INVOKED, qualified=false)"
        }
    }

    suspend fun prepare(): Selection {
        if (!prepared.compareAndSet(false, true)) return Selection.Refused(OrionCortexAttemptRefusal.ALREADY_CONSUMED)
        return try {
            when (val selected = owner.selectSingleAttemptTemplate()) {
                is OrionCortexAttemptConfiguration.Refused -> Selection.Refused(selected.reason)
                is OrionCortexAttemptConfiguration.Ready -> Selection.Selected(Candidate.selected(owner, scope, selected.template))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Throwable) { Selection.Refused(OrionCortexAttemptRefusal.SELECTION_UNAVAILABLE) }
    }

    suspend fun execute(candidate: Candidate, onChunks: (List<OrionInferenceChunk>) -> Unit): Execution {
        candidate.consume(owner, scope)?.let { return Execution.DriverNotAttempted(it) }
        // Everything below that can allocate a diagnostic envelope precedes the original invocation.
        var runIssued = false
        try {
            val early = owner.preResourceReadiness().unavailable
            if (early != null) return Execution.DriverNotAttempted(singleAttemptRefusal(early))
            val token = owner.beginCycleObservation()
                ?: return Execution.DriverNotAttempted(OrionCortexAttemptRefusal.OBSERVER_UNAVAILABLE)
            candidate.reserved(owner, scope, token)
            val template = checkNotNull(candidate.templateFor(owner, scope))
            val input = try { owner.prepareSingleAttemptRequest(template) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
                catch (_: Throwable) { null }
            if (input == null) return Execution.DriverNotAttempted(OrionCortexAttemptRefusal.SELECTION_UNAVAILABLE)
            val invoked = Execution.Invoked()
            runIssued = true
            // No wrapper around onChunks; no optional catch, allocation or read after original run.
            try {
                val result = owner.runObserved(token, template.modelId, input,
                    template.contextTokens, template.threads, onChunks)
                invoked.completed(result)
                return invoked
            } finally { candidate.finished(owner, scope) }
        } finally {
            // Pre-invocation reservation cleanup only. Never runs for an issued original invocation.
            if (!runIssued) candidate.tokenFor(owner, scope)?.let { owner.discardSingleAttemptReservation(it) }
        }
    }

    /** Separate explicit phase; retained PARTIAL data, never a current-state or qualification claim. */
    fun readPartial(candidate: Candidate): OrionCortexCyclePartialProjector.Projection? {
        if (!candidate.owns(owner, scope) || !candidate.finishedFor(owner, scope)) return null
        val token = candidate.tokenFor(owner, scope) ?: return null
        return try { OrionCortexCyclePartialProjector().project(owner.readCycleObservation(token)) }
        catch (_: Throwable) { null }
    }

    /** Removes completed observation only; no app/model cleanup, retry or resume. */
    fun release(candidate: Candidate): Boolean {
        if (!candidate.owns(owner, scope) || !candidate.finishedFor(owner, scope)) return false
        return candidate.tokenFor(owner, scope)?.let { owner.releaseCycleObservation(it) } == true
    }
}

internal fun singleAttemptRefusal(reason: OrionCortexUnavailable): OrionCortexAttemptRefusal = when (reason) {
    OrionCortexUnavailable.CLOSED -> OrionCortexAttemptRefusal.CLOSED
    OrionCortexUnavailable.NO_QUALIFIED_PROFILE -> OrionCortexAttemptRefusal.NO_QUALIFIED_PROFILE
    OrionCortexUnavailable.RESOURCE_CONFIGURATION_UNAVAILABLE -> OrionCortexAttemptRefusal.RESOURCE_CONFIGURATION_UNAVAILABLE
    OrionCortexUnavailable.CORE_NOT_READY -> OrionCortexAttemptRefusal.CORE_NOT_READY
    else -> OrionCortexAttemptRefusal.SELECTION_UNAVAILABLE
}

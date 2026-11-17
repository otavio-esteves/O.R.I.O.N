package network.orion.app.ai

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import network.orion.ai.api.OrionInferenceChunk
import network.orion.core.time.OrionObservationMoment

/** Explicit reviewed observation bounds, never workload/admission parameters or selected defaults. */
internal class OrionRuntimeCaptureObservationConfiguration(val maximumSampleAgeMillis: Long, val postReturnWindowMillis: Long) {
    init { require(maximumSampleAgeMillis in 1..1000 && postReturnWindowMillis in 1..OrionRuntimeManualExportWatchdog.MAX_MILLIS) }
}

/** Already-observed native moments only. Expiry refuses source; it cannot preempt a suspended/blocking operation. */
internal object OrionRuntimeManualExportWatchdog {
    const val MAX_MILLIS = 12000L
    fun failure(start: OrionObservationMoment?, end: OrionObservationMoment?): OrionRuntimeExportFailure? = when {
        start == null || end == null -> OrionRuntimeExportFailure.CLOCK_UNAVAILABLE
        !start.sameObservationAs(end) || end.elapsedRealtimeMillis < start.elapsedRealtimeMillis -> OrionRuntimeExportFailure.CLOCK_DISCONTINUOUS
        end.elapsedRealtimeMillis - start.elapsedRealtimeMillis > MAX_MILLIS -> OrionRuntimeExportFailure.CAPTURE_LIMIT
        else -> null
    }
}

internal sealed interface OrionRuntimeProgramWitness {
    val ownerCallInvoked: Boolean?
    val noLoadRefusal: Boolean
    val failure: OrionRuntimeExportFailure?
}
internal sealed interface OrionRuntimeCaptureCandidate

/** One manual call on the real existing owner. No factory, operating values, retry or callback wrapper. */
internal class OrionCortexRuntimeCaptureProgram(private val owner: OrionCortexRuntimeOwner) {
    private val scope = Any()
    private val prepared = AtomicBoolean()
    private class Unavailable : OrionRuntimeProgramWitness {
        override val ownerCallInvoked: Boolean? = null
        override val noLoadRefusal = false
        override val failure = OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
    }
    private class Refused(override val failure: OrionRuntimeExportFailure) : OrionRuntimeProgramWitness {
        override val ownerCallInvoked = false
        override val noLoadRefusal = true
    }
    /** Detached final program facts only; it retains no candidate/template/request/content/owner. */
    private class ObservedCall(override val ownerCallInvoked: Boolean, override val noLoadRefusal: Boolean,
        override val failure: OrionRuntimeExportFailure?) : OrionRuntimeProgramWitness
    private class Candidate(val owner: Any, val scope: Any, val template: OrionCortexAttemptTemplate,
        val configuration: OrionRuntimeCaptureObservationConfiguration) : OrionRuntimeCaptureCandidate {
        val consumed = AtomicBoolean()
        val rendered = AtomicBoolean()
        val finalized = AtomicBoolean()
        var manualStartedAt: OrionObservationMoment? = null
        @Volatile var issued = false
        @Volatile var finished = false
        @Volatile var failure: OrionRuntimeExportFailure? = null
        var capture: OrionCortexRuntimeCaptureSession? = null
        var snapshot: OrionRuntimeExportSnapshot? = null
    }
    sealed interface Selection {
        class Selected(val candidate: OrionRuntimeCaptureCandidate) : Selection
        class Refusal(val witness: OrionRuntimeProgramWitness) : Selection
    }
    suspend fun prepare(): Selection {
        if (!prepared.compareAndSet(false, true)) return Selection.Refusal(Unavailable())
        return when (val selected = owner.selectRuntimeCaptureTemplate()) {
            is OrionCortexAttemptConfiguration.Refused -> Selection.Refusal(Refused(refusal(selected.reason)))
            is OrionCortexAttemptConfiguration.Ready -> {
                val configuration = owner.runtimeCaptureObservationConfiguration()
                    ?: return Selection.Refusal(Refused(OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE))
                Selection.Selected(Candidate(owner, scope, selected.template, configuration))
            }
        }
    }
    private fun owned(value: OrionRuntimeCaptureCandidate): Candidate? =
        (value as? Candidate)?.takeIf { it.owner === owner && it.scope === scope }

    /** Returns the identical original result or throws the identical original Throwable. */
    suspend fun execute(value: OrionRuntimeCaptureCandidate, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult? {
        val selected = owned(value) ?: return null
        if (!selected.consumed.compareAndSet(false, true)) return null
        owner.preResourceReadiness().unavailable?.let {
            selected.failure = refusal(singleAttemptRefusal(it)); return null
        }
        val input = owner.prepareSingleAttemptRequest(selected.template)
        if (input == null) { selected.failure = OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE; return null }
        val capture = owner.beginRuntimeCapture(OrionRuntimeSamplingConfiguration(selected.configuration.maximumSampleAgeMillis))
        if (capture == null) { selected.failure = OrionRuntimeExportFailure.SOURCE_UNAVAILABLE; return null }
        selected.capture = capture
        // Factual program call boundary, after all preparation/allocation; no diagnostic query here.
        selected.issued = true
        try {
            return owner.runObserved(null, selected.template.modelId, input, selected.template.contextTokens,
                selected.template.threads, capture, onChunks)
        } finally { selected.finished = true }
    }
    private fun observed(selected: Candidate): OrionRuntimeProgramWitness =
        ObservedCall(selected.issued, !selected.issued && selected.failure != null, selected.failure)
    fun witness(value: OrionRuntimeCaptureCandidate): OrionRuntimeProgramWitness = owned(value)?.let(::observed) ?: Unavailable()

    /** Separate manual phase; the original return/throw never waits for the observation window. */
    suspend fun exportAfterReturn(value: OrionRuntimeCaptureCandidate, context: OrionRuntimeQualifiedContext): OrionRuntimeEncodedCapsule {
        val selected = owned(value) ?: return OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE)
        val witness = observed(selected)
        if (!selected.issued) return OrionRuntimeCapsuleEncoder.programRefusal(witness)
        if (!selected.finished || !selected.rendered.compareAndSet(false, true))
            return OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, witness = witness)
        val activeCapture = selected.capture
            ?: return OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, witness = witness)
        var closed = false
        try {
            selected.manualStartedAt = owner.runtimeCaptureManualMoment(activeCapture)
            if (selected.manualStartedAt == null) {
                val receipt = owner.readRuntimeCapture(activeCapture); closed = true
                return OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.CLOCK_UNAVAILABLE, receipt, witness)
            }
            delay(selected.configuration.postReturnWindowMillis)
            val receipt = owner.readRuntimeCapture(activeCapture); closed = true
            val snapshot = owner.freezeRuntimeCapture(activeCapture, context)
            selected.snapshot = snapshot
            val encoded = if (snapshot == null) OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, receipt, witness)
                else OrionRuntimeCapsuleEncoder.encode(snapshot, witness)
            return if (snapshot != null && owner.freezeRuntimeCapture(activeCapture, context) !== snapshot)
                OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.IDENTITY_CHANGED, receipt, witness) else encoded
        } finally {
            // Actual bounded manual close fences late sampling even if the delay/manual rendering failed.
            // No fake end moment, wait, second import, Core cleanup or replacement of the thrown failure.
            if (!closed) try { owner.readRuntimeCapture(activeCapture) } catch (_: Throwable) { }
        }
    }
    /** Final manual watchdog point after render AND native framing, then scalar fence recheck.
     * No functional Clock/provider/RPC; a late/unknown result is refused, never clamped or retimestamped.
     */
    fun finalExportFailure(value: OrionRuntimeCaptureCandidate, context: OrionRuntimeQualifiedContext): OrionRuntimeExportFailure? {
        val selected = owned(value) ?: return OrionRuntimeExportFailure.IDENTITY_CHANGED
        if (!selected.issued || !selected.finalized.compareAndSet(false, true)) return OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
        val capture = selected.capture ?: return OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
        val snapshot = selected.snapshot ?: return OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
        if (owner.freezeRuntimeCapture(capture, context) !== snapshot) return OrionRuntimeExportFailure.IDENTITY_CHANGED
        val end = owner.runtimeCaptureManualMoment(capture)
        val failed = OrionRuntimeManualExportWatchdog.failure(selected.manualStartedAt, end)
        if (failed != null) return failed
        return if (owner.freezeRuntimeCapture(capture, context) !== snapshot) OrionRuntimeExportFailure.IDENTITY_CHANGED else null
    }
    private fun refusal(value: OrionCortexAttemptRefusal): OrionRuntimeExportFailure = when (value) {
        OrionCortexAttemptRefusal.NO_QUALIFIED_PROFILE -> OrionRuntimeExportFailure.NO_QUALIFIED_PROFILE
        OrionCortexAttemptRefusal.RESOURCE_CONFIGURATION_UNAVAILABLE, OrionCortexAttemptRefusal.TEMPLATE_UNAVAILABLE,
        OrionCortexAttemptRefusal.TEMPLATE_INVALID -> OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE
        else -> OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
    }
}

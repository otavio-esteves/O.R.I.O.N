package network.orion.app.ai

import network.orion.ai.api.OrionInferenceChunk
import network.orion.core.time.MonotonicInstant

/** Finite MAIN composition on one existing Owner; this class never grants policy/resource authority. */
internal class OrionCortexInstrumentedWorkloadProgram(val owner: OrionCortexRuntimeOwner,
    val scope: OrionInstrumentedWorkloadScope, val binding: OrionInstrumentedConfigurationBinding) {
    private var program: OrionCortexRuntimeCaptureProgram? = null
    private var candidate: OrionRuntimeCaptureCandidate? = null
    private var refused: OrionRuntimeProgramWitness? = null
    private var finished = false
    private var originalAttempt: String? = null
    val exitSource: OrionQualificationExitSource? = owner.newInstrumentedExitSource()
    var deathSettlement: OrionQualificationDeathSettlement? = null
        private set
    private var safeToClean = true
    private var manualOperation = OrionInstrumentedOperation.WORK
    var settlement = OrionInstrumentedSettlement.UNPROVEN
        private set
    var originalResult: OrionCortexRunResult? = null
        private set
    var lastClaimSucceeded = false
        private set

    /** Returns the identical original result and throws the identical original Throwable. */
    suspend fun executeWork(run: String, fixture: String, nonce: String, attempt: String,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult? =
        execute(OrionInstrumentedOperation.WORK, run, fixture, nonce, attempt, onChunks)

    suspend fun executeBootstrap(run: String, fixture: String, nonce: String, attempt: String,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult? =
        execute(OrionInstrumentedOperation.BOOTSTRAP, run, fixture, nonce, attempt, onChunks)

    private suspend fun execute(operation: OrionInstrumentedOperation, run: String, fixture: String, nonce: String, attempt: String,
        onChunks: (List<OrionInferenceChunk>) -> Unit): OrionCortexRunResult? {
        lastClaimSucceeded = false
        if (!scope.claim(operation, run, fixture, nonce, attempt)) return null
        lastClaimSucceeded = true; manualOperation = operation; settlement = OrionInstrumentedSettlement.UNPROVEN
        originalAttempt = attempt; deathSettlement = null
        finished = false; safeToClean = false; originalResult = null; refused = null; candidate = null
        val actual = OrionCortexRuntimeCaptureProgram(owner)
        program = actual
        when (val selection = actual.prepare()) {
            is OrionCortexRuntimeCaptureProgram.Selection.Refusal -> {
                refused = selection.witness; safeToClean = selection.witness.ownerCallInvoked == false
                finished = true; return null
            }
            is OrionCortexRuntimeCaptureProgram.Selection.Selected -> {
                val selected = selection.candidate; candidate = selected
                try {
                    val result = actual.execute(selected, onChunks)
                    originalResult = result
                    return result
                } finally { finished = true }
            }
        }
    }

    fun witness(): OrionRuntimeProgramWitness? = refused ?: candidate?.let { program?.witness(it) }
    suspend fun export(context: OrionRuntimeQualifiedContext): OrionRuntimeEncodedCapsule {
        refused?.let { return OrionRuntimeCapsuleEncoder.programRefusal(it) }
        val actual = program; val selected = candidate
        return if (!finished || actual == null || selected == null)
            OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, witness = witness())
        else actual.exportAfterReturn(selected, context)
    }
    fun finalExportFailure(context: OrionRuntimeQualifiedContext): OrionRuntimeExportFailure? =
        candidate?.let { program?.finalExportFailure(it, context) } ?: OrionRuntimeExportFailure.SOURCE_UNAVAILABLE

    /** Logical terminal alone is insufficient to delete buffers or declare native work settled. */
    fun finishManual(encoded: OrionRuntimeEncodedCapsule): OrionInstrumentedExecutionState {
        val current = witness()
        val receipt = encoded.diagnosticReceipt
        val facts = receipt?.facts
        val noOwnerCall = current?.ownerCallInvoked == false
        val originalUnload = current?.ownerCallInvoked == true &&
            (finished && receipt?.identityKnown == true && !receipt.invalidated && facts?.invalidated == false &&
                facts.markers.any { it.kind == OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP && it.cleanup == OrionRuntimeCleanupResult.UNLOADED })
        safeToClean = noOwnerCall || originalUnload
        val settled = safeToClean && owner.instrumentedScalarIdle() && !OrionRuntimeMemorySamplingWorker.outstanding()
        settlement = if (!settled) OrionInstrumentedSettlement.UNPROVEN else if (noOwnerCall)
            OrionInstrumentedSettlement.NO_OWNER_CALL else OrionInstrumentedSettlement.ORIGINAL_UNLOAD_CONFIRMED
        val originalDeath = originalProcessDeathAwaitingEvidence(originalResult, current?.ownerCallInvoked, finished)
        scope.finish(manualOperation, uncertain = !settled && !originalDeath, deathEvidencePending = !settled && originalDeath)
        return scope.state()
    }

    /** Read-only observation may resolve ONLY the explicit original-process-death phase, never transport UNKNOWN. */
    fun observeOriginalProcessDeath(result: OrionQualificationExitResult?, at: MonotonicInstant) {
        if (scope.state() != OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE ||
            !originalProcessDeathAwaitingEvidence(originalResult, witness()?.ownerCallInvoked, finished)) return
        val incident = result?.incident ?: return
        val actual = program ?: return; val selected = candidate ?: return
        if (!actual.matchesHistoricalProcessDeath(selected, incident) || !owner.instrumentedScalarIdle() ||
            OrionRuntimeMemorySamplingWorker.outstanding()) return
        val attempt = originalAttempt ?: return
        val receipt = OrionQualificationDeathSettlement.observed(attempt, incident, at) ?: return
        if (!owner.instrumentedScalarIdle() || OrionRuntimeMemorySamplingWorker.outstanding() ||
            !scope.confirmOriginalProcessDeath()) return
        deathSettlement = receipt
        settlement = OrionInstrumentedSettlement.ORIGINAL_PROCESS_DEATH_CONFIRMED
        safeToClean = true // Technical buffers only. No Owner/client/history/guard/model state is cleared.
    }

    fun cleanup(run: String, fixture: String, nonce: String): Boolean {
        if (!scope.claim(OrionInstrumentedOperation.CLEANUP, run, fixture, nonce, null)) return false
        if (!safeToClean || !owner.instrumentedScalarIdle() || OrionRuntimeMemorySamplingWorker.outstanding()) {
            scope.finish(OrionInstrumentedOperation.CLEANUP, uncertain = true); return false
        }
        // Only this wrapper's bounded diagnostic references. Shared Owner/Core/history/model remain owned by Core.
        program = null; candidate = null; refused = null; originalResult = null; originalAttempt = null; deathSettlement = null
        scope.finish(OrionInstrumentedOperation.CLEANUP)
        return true
    }
}

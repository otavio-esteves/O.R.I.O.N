package network.orion.app.ai

import network.orion.core.time.MonotonicInstant

/** Only the original typed return may enter the observation-only death phase; failure/timeout/absence are not death. */
internal fun originalProcessDeathAwaitingEvidence(result: OrionCortexRunResult?, ownerCalled: Boolean?, returned: Boolean): Boolean =
    returned && ownerCalled == true && (result as? OrionCortexRunResult.Completed)?.status?.terminal ==
        network.orion.ai.api.OrionInferenceTerminal.PROCESS_DIED

/** A detached technical reference to an actually executed original attempt and attributed old process.
 * It is not a resource permit, retry lease, policy revision or exit timestamp.
 */
internal class OrionQualificationDeathSettlement private constructor(val originalAttemptIdSha256: String,
    val incidentIdentitySha256: String, val observedElapsedMillis: Long, val bootCount: Int) {
    /** Manual emission guard: a retained proof never accompanies an unrelated/missing current incident. */
    fun matchesObservation(result: OrionQualificationExitResult?, at: MonotonicInstant): Boolean = try {
        val incident = result?.incident
        incident != null && at.bootSessionId?.bootCount == bootCount && incident.windowBootCount == bootCount &&
            at.elapsedRealtimeMillis >= observedElapsedMillis && at.elapsedRealtimeMillis >= incident.observed.completed.elapsedMillis &&
            incident.identitySha256() == incidentIdentitySha256
    } catch (_: Throwable) { false }
    fun write(out: OrionRuntimeAsciiWriter) {
        out.raw("{").key("schemaVersion").number(1).raw(",").key("originalAttemptIdSha256").quoted(originalAttemptIdSha256)
            .raw(",").key("originalTerminal").quoted("PROCESS_DIED").raw(",").key("incidentIdentitySha256").quoted(incidentIdentitySha256)
            .raw(",").key("observedElapsedMillis").number(observedElapsedMillis).raw(",").key("bootCount").number(bootCount.toLong()).raw("}")
    }
    companion object {
        /** Caller has already matched the original result/opaque endpoint and technical quiescence. No clock call here. */
        fun observed(attempt: String, incident: OrionQualificationExitIncident, at: MonotonicInstant): OrionQualificationDeathSettlement? = try {
            if (!OrionInstrumentedWorkloadScope.validId(attempt) || at.bootSessionId?.bootCount != incident.windowBootCount ||
                at.elapsedRealtimeMillis < incident.observed.completed.elapsedMillis) null else {
                val hash = OrionRuntimeTechnicalId(attempt).sha256ForManualExport()
                if (hash == null) null else OrionQualificationDeathSettlement(hash, incident.identitySha256(), at.elapsedRealtimeMillis, incident.windowBootCount)
            }
        } catch (_: Throwable) { null }
    }
}

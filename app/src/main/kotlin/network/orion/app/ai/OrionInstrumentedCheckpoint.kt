package network.orion.app.ai

import android.os.Bundle
import android.os.Process
import java.security.MessageDigest
import network.orion.ai.api.OrionInferenceFailure
import network.orion.core.time.MonotonicInstant
import org.json.JSONObject

internal enum class OrionInstrumentedSettlement { ORIGINAL_UNLOAD_CONFIRMED, ORIGINAL_PROCESS_DEATH_CONFIRMED, NO_OWNER_CALL, UNPROVEN }
internal enum class OrionInstrumentedOutcome { OPENED, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, PROCESS_DIED, REFUSED, CLEANED, ABSENT, BUSY, UNAVAILABLE, OBSERVED }
internal enum class OrionInstrumentedRefusal { NONE, NO_QUALIFIED_PROFILE, RESOURCE_CONFIGURATION_UNAVAILABLE, CONFIGURATION_UNAVAILABLE,
    CORE_NOT_READY, OWNER_UNAVAILABLE, CLOSED, TEMPLATE_UNAVAILABLE, TEMPLATE_INVALID, MODEL_NOT_READY, PROFILE_MISMATCH,
    SELECTION_UNAVAILABLE, RESOURCE_REFUSED, IDENTITY_CHANGED, ACTIVE_EXECUTION, UNKNOWN_EXECUTION, FOREIGN_OWNERSHIP,
    ALREADY_CONSUMED, OBSERVER_UNAVAILABLE, PROCESS_INVENTORY_UNAVAILABLE, MODEL_UNAVAILABLE, REQUEST_RETIRED }

/** Closed detached technical values only; no request, model entity, content, raw error or arbitrary object. */
internal class OrionInstrumentedCheckpoint(val operation: OrionInstrumentedOperation, val runId: String, val fixtureId: String,
    val attemptId: String?, val graphNonce: String, val at: MonotonicInstant, val outcome: OrionInstrumentedOutcome,
    val refusal: OrionInstrumentedRefusal, val failure: OrionInferenceFailure?, val ownerCallInvoked: Boolean?,
    val execution: OrionInstrumentedExecutionState, val settlement: OrionInstrumentedSettlement, val binding: OrionInstrumentedConfigurationBinding?,
    val observation: OrionInstrumentedResourceObservation? = null,
    val originalPolicies: network.orion.app.resources.OrionCortexResourceFactBatch? = null,
    val exitObservation: OrionQualificationExitResult? = observation?.exitObservation,
    val deathSettlement: OrionQualificationDeathSettlement? = null) {
    init {
        require(OrionInstrumentedWorkloadScope.validId(runId) && OrionInstrumentedWorkloadScope.validId(fixtureId) &&
            OrionInstrumentedWorkloadScope.validId(graphNonce) && (attemptId == null || OrionInstrumentedWorkloadScope.validId(attemptId)))
        require(at.bootSessionId != null && at.elapsedRealtimeMillis >= 0)
        require((outcome == OrionInstrumentedOutcome.REFUSED) == (refusal != OrionInstrumentedRefusal.NONE))
        require(failure == null || outcome == OrionInstrumentedOutcome.FAILED)
    }
    fun json(sourceHash: String?): String {
        val root = JSONObject()
        root.put("schemaVersion", 2); root.put("kind", "ORION_F5_INSTRUMENTED_CHECKPOINT"); root.put("operation", operation.name)
        root.put("runId", runId); root.put("fixtureId", fixtureId); root.put("attemptId", attemptId ?: JSONObject.NULL)
        root.put("sessionId", graphNonce)
        root.put("stamp", JSONObject().put("bootCount", checkNotNull(at.bootSessionId).bootCount)
            .put("deviceElapsedMillis", at.elapsedRealtimeMillis).put("deviceElapsedUpperMillis", at.elapsedRealtimeMillis)
            .put("mainPid", Process.myPid()))
        root.put("outcome", outcome.name); root.put("refusal", refusal.name); root.put("failureCode", failure?.name ?: JSONObject.NULL)
        root.put("ownerCallInvoked", ownerCallInvoked ?: JSONObject.NULL); root.put("executionState", execution.name); root.put("settlement", settlement.name)
        root.put("modelSha256", binding?.modelSha256 ?: JSONObject.NULL)
        root.put("configurationSha256", binding?.configurationSha256 ?: JSONObject.NULL)
        root.put("runtimeSourceSha256", sourceHash ?: JSONObject.NULL)
        val exit = exitObservation?.incident?.takeIf { it.windowBootCount == at.bootSessionId?.bootCount && it.mainPid == Process.myPid() }
        root.put("resourceExit", if (exit == null) JSONObject.NULL else JSONObject()
            .put("pid", exit.record.pid).put("timestampWallMillis", exit.record.timestampWallMillis).put("reason", exit.record.reason)
            .put("classification", exit.classification).put("windowBootCount", exit.windowBootCount).put("correlation", exit.correlation))
        val policies = OrionQualificationPolicyObservation.render(originalPolicies, exitObservation, deathSettlement)
        root.put("policyFacts", if (policies == null) JSONObject.NULL else JSONObject(policies))
        val observed = observation
        if (observed == null) {
            root.put("resources", JSONObject.NULL); root.put("observation", JSONObject.NULL)
        } else {
            require(observed.mainPid == Process.myPid() && observed.bootCount == at.bootSessionId?.bootCount &&
                observed.completedElapsed <= at.elapsedRealtimeMillis)
            val ai = observed.ai
            val total = if (observed.mainPssBytes != null && ai != null && observed.mainPssBytes <= Long.MAX_VALUE - ai.pssBytes)
                observed.mainPssBytes + ai.pssBytes else null
            root.put("resources", JSONObject().put("mainPid", observed.mainPid).put("aiPid", ai?.pid ?: JSONObject.NULL)
                .put("mainPssBytes", observed.mainPssBytes ?: JSONObject.NULL).put("aiPssBytes", ai?.pssBytes ?: JSONObject.NULL)
                .put("aggregatePssBytes", total ?: JSONObject.NULL).put("thermalStatus", observed.thermalStatus ?: JSONObject.NULL)
                .put("memoryPressure", observed.memoryPressure).put("serviceState", ai?.state?.name ?: "UNAVAILABLE")
                .put("nativeRuntimeLoaded", ai?.runtimeLoaded ?: JSONObject.NULL)
                .put("actionPendingCount", observed.queues?.pending ?: JSONObject.NULL)
                .put("actionUnknownCount", observed.queues?.unknown ?: JSONObject.NULL))
            root.put("observation", JSONObject().put("startedElapsedMillis", observed.startedElapsed)
                .put("completedElapsedMillis", observed.completedElapsed).put("mainSampledElapsedMillis", observed.mainSampledElapsed)
                .put("aiSampledElapsedMillis", ai?.sampledElapsed ?: JSONObject.NULL)
                .put("maximumSampleAgeMillis", observed.maximumAgeMillis ?: JSONObject.NULL).put("bootCount", observed.bootCount)
                .put("aiBootCount", ai?.boot ?: JSONObject.NULL).put("mainNativeIncluded", true)
                .put("aiNativeIncluded", ai?.nativeIncluded ?: JSONObject.NULL).put("nativeValidationCompleted", ai?.validated ?: JSONObject.NULL))
        }
        FLAGS.forEach { root.put(it, false) }
        return root.toString().also { text -> require(text.length <= MAX_CHECKPOINT_BYTES && text.all { it.code <= 127 }) }
    }
    override fun toString() = "OrionInstrumentedCheckpoint(operation=$operation, qualified=false)"
    companion object {
        const val MAX_CHECKPOINT_BYTES = 16 * 1024
        val FLAGS = listOf("sourceAuthenticated", "physicalEvidenceVerified", "real24HourDurationProven", "autonomyProven",
            "profileQualified", "productionInferenceEnabled", "fullF4GateClosed", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")
    }
}

/** Whole final Bundle includes checkpoint AND optional status/SOURCE in the actual native layouts. */
internal object OrionInstrumentedCheckpointFraming {
    const val CHECKPOINT = "orionWorkloadCheckpoint"
    fun frame(value: OrionInstrumentedCheckpoint, encoded: OrionRuntimeEncodedCapsule?, context: OrionRuntimeInstrumentationContext): Bundle? {
        val source = encoded?.source
        if (source != null && (source.length > OrionRuntimeCapsuleEncoder.MAX_SOURCE_BYTES || source.any { it.code > 127 })) return null
        fun candidate(runtime: OrionRuntimeEncodedCapsule?): Bundle = Bundle().apply {
            val hash = runtime?.source?.let { text -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
                .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') } }
            putString(CHECKPOINT, value.json(hash)); putString("stream", "ORION_WORKLOAD_CHECKPOINT")
            runtime?.let { putString(OrionRuntimeCapsuleParcelGuard.STATUS_KEY, it.status) }
            runtime?.source?.let { putString(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY, it) }
        }
        fun valid(bundle: Bundle) = OrionRuntimeInstrumentationFraming.measure(bundle, context.component)?.withinBudget == true
        return try {
            val full = candidate(encoded)
            if (valid(full)) full else if (source != null) {
                val original = checkNotNull(encoded)
                val unavailable = OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.EXPORT_TOO_LARGE,
                    original.diagnosticReceipt, original.programWitness)
                candidate(unavailable).takeIf(::valid)
            } else null
        } catch (_: Throwable) { null }
    }
}

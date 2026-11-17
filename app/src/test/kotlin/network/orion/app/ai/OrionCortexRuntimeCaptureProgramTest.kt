package network.orion.app.ai

import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic private-witness/status controls only; actual Owner and framework proof belongs to Android controls. */
class OrionCortexRuntimeCaptureProgramTest {
    @Test fun ordinaryDebugArtifactHasRuntimeCaptureDisabled() { assertFalse(network.orion.app.BuildConfig.ORION_CORTEX_RUNTIME_CAPTURE) }
    @Test fun observationConfigurationRequiresBothExplicitFiniteBoundsWithoutSelectedDefault() {
        for (age in listOf(0L, -1, 1001, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            OrionRuntimeCaptureObservationConfiguration(age, 1)
        }
        for (window in listOf(0L, -1, 12001, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            OrionRuntimeCaptureObservationConfiguration(1, window)
        }
        assertEquals(1L, OrionRuntimeCaptureObservationConfiguration(1, 1).maximumSampleAgeMillis)
        assertEquals(12000L, OrionRuntimeCaptureObservationConfiguration(1000, 12000).postReturnWindowMillis)
    }
    private fun moments(values: List<Long?>): List<OrionObservationMoment?> {
        val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
            it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        }.apply { isAccessible = true }
        val clock = method.invoke(OrionObservationEventClock.Companion, values) as OrionObservationEventClock
        val witness = MonotonicInstant(BootSessionId(3), 100)
        check(clock.bindOriginalWitness(witness, witness, MonotonicDeadlineStatus.ACTIVE))
        return values.map { clock.observe() }
    }
    @Test fun watchdogAcceptsExact12000AndRefusesLateUnknownOrOtherClockScopeWithoutClamping() {
        val boundary = moments(listOf(100L, 12100L, 12101L))
        assertNull(OrionRuntimeManualExportWatchdog.failure(boundary[0], boundary[1]))
        assertEquals(OrionRuntimeExportFailure.CAPTURE_LIMIT, OrionRuntimeManualExportWatchdog.failure(boundary[0], boundary[2]))
        assertEquals(OrionRuntimeExportFailure.CLOCK_UNAVAILABLE, OrionRuntimeManualExportWatchdog.failure(boundary[0], null))
        val gaps = moments(listOf(100L, null, 110L))
        assertEquals(OrionRuntimeExportFailure.CLOCK_UNAVAILABLE, OrionRuntimeManualExportWatchdog.failure(gaps[0], gaps[1]))
        assertNull(gaps[2]) // Clock failure stays sticky; no restart/repair/provider is used.
        val other = moments(listOf(12100L)).single()
        assertEquals(OrionRuntimeExportFailure.CLOCK_DISCONTINUOUS, OrionRuntimeManualExportWatchdog.failure(boundary[0], other))
        assertEquals(OrionRuntimeExportFailure.CLOCK_DISCONTINUOUS, OrionRuntimeManualExportWatchdog.failure(boundary[1], boundary[0]))
        assertEquals(12101L, boundary[2]?.elapsedRealtimeMillis)
    }

    private fun refusal(): OrionRuntimeProgramWitness =
        OrionCortexRuntimeCaptureProgram::class.java.declaredClasses.single { it.simpleName == "Refused" }
            .declaredConstructors.single().apply { isAccessible = true }
            .newInstance(OrionRuntimeExportFailure.NO_QUALIFIED_PROFILE) as OrionRuntimeProgramWitness
    @Test fun actualPreInvocationRefusalShapeHasFalseOwnerNoSourceAndNoApproval() {
        val encoded = OrionRuntimeCapsuleEncoder.programRefusal(refusal())
        assertNull(encoded.source); assertEquals(OrionRuntimeExportFailure.NO_QUALIFIED_PROFILE, encoded.failure)
        assertTrue(encoded.status.contains("\"recordState\":\"NO_LOAD_REFUSAL\""))
        assertTrue(encoded.status.contains("\"ownerCallInvoked\":false"))
        for (flag in listOf("sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
            "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")) assertTrue(encoded.status.contains("\"$flag\":false"))
        assertTrue(encoded.status.length <= 1024)
    }
    @Test fun issuedWitnessStaysTrueAfterDiagnosticFailureWithoutClaimingNoLoadOrACompleteCycle() {
        val type = OrionCortexRuntimeCaptureProgram::class.java.declaredClasses.single { it.simpleName == "ObservedCall" }
        val witness = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(true, false, null) as OrionRuntimeProgramWitness
        val encoded = OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.CAPTURE_LOSS, witness = witness)
        assertTrue(encoded.status.contains("\"ownerCallInvoked\":true")); assertTrue(encoded.status.contains("\"recordState\":\"NO_V1_RECORD\""))
        assertFalse(witness.noLoadRefusal); assertNull(encoded.source)
        assertTrue(type.declaredFields.all { it.type == Boolean::class.javaPrimitiveType || it.type == OrionRuntimeExportFailure::class.java })
    }
    @Test fun missingProgramProofRemainsUnknownRatherThanInferringIssuanceFromCaptureAbsence() {
        val result = OrionRuntimeCapsuleEncoder.encode(null)
        assertTrue(result.status.contains("\"ownerCallInvoked\":null"))
        assertTrue(result.status.contains("\"recordState\":\"NO_V1_RECORD\""))
    }
}

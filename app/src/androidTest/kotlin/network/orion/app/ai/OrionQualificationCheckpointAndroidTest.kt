package network.orion.app.ai

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import network.orion.app.resources.*
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** EMULATOR JSON/technical fixture only. No real exit, process kill, profile or policy authority is asserted. */
@RunWith(AndroidJUnit4::class)
class OrionQualificationCheckpointAndroidTest {
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun checkpoint(policies: OrionCortexResourceFactBatch? = null, incident: OrionQualificationExitIncident? = null,
        death: OrionQualificationDeathSettlement? = null) = OrionInstrumentedCheckpoint(OrionInstrumentedOperation.OBSERVE,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, UUID.randomUUID().toString(),
        MonotonicInstant(BootSessionId(3), 500), OrionInstrumentedOutcome.OBSERVED, OrionInstrumentedRefusal.NONE, null, false,
        OrionInstrumentedExecutionState.IDLE, if (death == null) OrionInstrumentedSettlement.UNPROVEN else OrionInstrumentedSettlement.ORIGINAL_PROCESS_DEATH_CONFIRMED,
        null, originalPolicies = policies, exitObservation = OrionQualificationExitResult(incident, null), deathSettlement = death)
    private fun incident(): OrionQualificationExitIncident {
        val pid = Process.myPid() + 1000
        val source = OrionQualificationExitCorrelation("network.orion.qualification", Process.myUid(), Process.myPid(),
            OrionQualificationExitConfiguration(10, 1000, 10000))
        val key = OrionQualificationEndpointKey(Any(), Any(), Any(), 1)
        fun pair(at: Long) = OrionQualificationTimePair(3, at, 1000000 + at, 1000001 + at)
        for (at in listOf(100L, 200L)) source.live(OrionQualificationLiveReceipt(key,
            OrionRuntimeAiMemoryReading(pid, 3, at + 10, 100, true, true, true, OrionRuntimeServiceState.UNLOADED),
            OrionQualificationProcWitness(pid, Process.myUid(), 99), OrionQualificationObservationWindow(pair(at), pair(at + 20)), OrionQualificationExitHistory(emptyList(), false, null)))
        val record = checkNotNull(OrionQualificationExitSanitizer.record(pid, Process.myUid(), Process.myUid(),
            "network.orion.qualification:ai", 1000250, 13, "MemoryLimiter:AnonSwap SECRET_RAW_DESCRIPTION", null))
        return checkNotNull(source.history(OrionQualificationObservationWindow(pair(300), pair(320)), listOf(record),
            OrionQualificationProcObservation(OrionQualificationProcState.ABSENT)).incident)
    }
    @Test fun reservedNestedFieldsCarryActualNullableFactsWithoutChangingTopLevelKeysOrLeakingPrivateWitnesses() {
        val original = JSONObject(checkpoint().json(null))
        assertTrue(original.isNull("policyFacts"))
        val incident = incident(); val oldAttempt = UUID.randomUUID().toString()
        val death = checkNotNull(OrionQualificationDeathSettlement.observed(oldAttempt, incident, MonotonicInstant(BootSessionId(3), 500)))
        val fact = OrionCortexResourceFact.Restart(null, OrionRestartDecisionFact(false, OrionRestartBlock.COOLDOWN, null, 1, 0, true, false))
        val text = checkpoint(OrionCortexResourceFactBatch(listOf(fact), 0, 0, 0, true, false), incident, death).json(null)
        val updated = JSONObject(text)
        assertEquals(keys(original), keys(updated)); assertFalse(updated.has("deathSettlement"))
        val sidecar = updated.getJSONObject("policyFacts")
        assertEquals(setOf("schemaVersion", "kind", "state", "gap", "droppedAtLeast", "contendedAtLeast",
            "recordingFailuresAtLeast", "lossAccountingComplete", "drainContended", "facts", "exitObservation", "deathSettlement"), keys(sidecar))
        assertEquals(setOf("gap", "paired", "newIncident", "descriptionEvidence", "dedicatedReason"), keys(sidecar.getJSONObject("exitObservation")))
        assertEquals(setOf("schemaVersion", "originalAttemptIdSha256", "originalTerminal", "incidentIdentitySha256", "observedElapsedMillis", "bootCount"),
            keys(sidecar.getJSONObject("deathSettlement")))
        assertEquals("PROCESS_DIED", sidecar.getJSONObject("deathSettlement").getString("originalTerminal"))
        assertTrue(sidecar.getJSONArray("facts").getJSONObject(0).isNull("candidateKeepAliveMillis"))
        val exit = updated.getJSONObject("resourceExit")
        assertEquals(setOf("pid", "timestampWallMillis", "reason", "classification", "windowBootCount", "correlation"), keys(exit))
        assertEquals("MEMORY_LIMITER", exit.getString("classification"))
        assertFalse(text.contains(oldAttempt)); assertFalse(text.contains("SECRET_RAW_DESCRIPTION"))
        assertFalse(text.contains("kernelStartTicks")); assertFalse(text.contains("realUid")); assertFalse(text.contains("binder"))
        assertTrue(text.length <= OrionInstrumentedCheckpoint.MAX_CHECKPOINT_BYTES)
        for (flag in OrionInstrumentedCheckpoint.FLAGS) assertFalse(updated.getBoolean(flag))
    }
}

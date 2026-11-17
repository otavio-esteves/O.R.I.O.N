package network.orion.app.ai

import network.orion.app.resources.*
import network.orion.core.resources.*
import network.orion.core.time.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Original decision helpers and synthetic minimized serialization controls; not policy or physical approval. */
class OrionQualificationPolicyObservationTest {
    private class Clock : OrionClock {
        var reads = 0
        override fun monotonicNow() = MonotonicInstant(BootSessionId(3), 100).also { reads++ }
        override fun wallNow() = Instant.EPOCH
    }
    @Test fun originalGovernorDecisionIsTheSameObjectAndPassiveCaptureAddsNoClockOrEvaluation() {
        val clock = Clock(); val governor = OrionMinimumResourceGovernor(clock, { null }, emptyList(), 10, 10)
        val original = governor.evaluate(); val reads = clock.reads
        val buffer = OrionCortexResourceFactBuffer(64)
        assertSame(original, buffer.recordGovernorFact(null, original)); assertEquals(reads, clock.reads)
        val text = checkNotNull(OrionQualificationPolicyObservation.render(buffer.drain()))
        assertTrue(text.contains("OBSERVATION_UNAVAILABLE")); assertTrue(text.contains("\"allowedAsReported\":false"))
        assertTrue(text.contains("\"candidateKeepAliveMillis\":null")); assertTrue(text.contains("\"inputSample\":null"))
        assertFalse(text.contains("revision")); assertFalse(text.contains("fence")); assertFalse(text.contains("cortexState"))
        assertEquals(reads, clock.reads)
    }
    @Test fun originalRestartPlanValuesAreCopiedRatherThanInferringZeroKeepaliveOrAPermit() {
        val clock = Clock()
        val plan = OrionInferenceResourcePlan(InferenceModelProfile("a".repeat(64), 100), "SYNTHETIC", 1, 32, 1, 234,
            1000, 2000, 0, 1, 0)
        val guard = OrionCortexRestartGuard(clock, listOf(plan), 10, 10)
        val decision = guard.evaluate(OrionAiExitHistory(MonotonicInstant(BootSessionId(3), 100), emptyList()))
        val reads = clock.reads; val buffer = OrionCortexResourceFactBuffer(64)
        assertSame(decision, buffer.recordRestartFact(null, decision)); assertEquals(reads, clock.reads)
        val text = checkNotNull(OrionQualificationPolicyObservation.render(buffer.drain()))
        assertTrue(text.contains("\"candidateKeepAliveMillis\":234")); assertTrue(text.contains("\"eligibleAsReported\":true"))
        assertTrue(text.contains("\"degradedAsReported\":false")); assertFalse(text.contains("approval")); assertEquals(reads, clock.reads)
    }
    @Test fun refusalWithNoPlanPreservesNullAndLossesDoNotBecomeACompleteSuccessfulPrefix() {
        val fact = OrionCortexResourceFact.Restart(null, OrionRestartDecisionFact(false, OrionRestartBlock.COOLDOWN, null, 1, 0, true, false))
        val batch = OrionCortexResourceFactBatch(listOf(fact), 2, 1, 0, false, false)
        val text = checkNotNull(OrionQualificationPolicyObservation.render(batch))
        assertTrue(text.contains("COOLDOWN")); assertTrue(text.contains("\"candidateKeepAliveMillis\":null"))
        assertTrue(text.contains("\"lossAccountingComplete\":false")); assertTrue(text.contains("\"droppedAtLeast\":2"))
        val contended = checkNotNull(OrionQualificationPolicyObservation.render(batch.copy(drainContended = true)))
        assertTrue(contended.contains("\"facts\":null")); assertTrue(contended.contains("DRAIN_CONTENDED"))
    }
    @Test fun batchCannotBeTruncatedToFitTheTransportBudgetOrLeakAnUnrelatedString() {
        val plan = OrionResourcePlanFact(OrionResourceModelFact("a".repeat(64), 100), 1, 32, 1, 1, 1, 1, 0, 0, 0)
        val fact = OrionCortexResourceFact.Restart(null, OrionRestartDecisionFact(true, null, plan, 0, 0, false, true))
        val batch = OrionCortexResourceFactBatch(List(64) { fact }, 0, 0, 0, true, false)
        val result = checkNotNull(OrionQualificationPolicyObservation.render(batch))
        assertTrue(result.length <= OrionQualificationPolicyObservation.MAX_BYTES)
        assertTrue(result.contains("EXPORT_TOO_LARGE")); assertTrue(result.contains("\"facts\":null"))
        assertFalse(result.contains("\"batchIndex\""))
    }
    @Test fun absentBufferIsUnavailableAndNotAnEmptySuccessfulCapture() {
        assertNull(OrionQualificationPolicyObservation.render(null))
        val empty = checkNotNull(OrionQualificationPolicyObservation.render(OrionCortexResourceFactBatch(emptyList(), 0, 0, 0, true, false)))
        assertTrue(empty.contains("\"facts\":[]")); assertTrue(empty.contains("\"deathSettlement\":null"))
        assertTrue(empty.contains("CONFIGURATION_UNAVAILABLE"))
    }
}

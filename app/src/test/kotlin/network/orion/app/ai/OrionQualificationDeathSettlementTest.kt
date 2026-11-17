package network.orion.app.ai

import java.util.UUID
import network.orion.ai.api.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic correlation and technical phase controls. No native death, Core grant or ACK reconciliation claim. */
class OrionQualificationDeathSettlementTest {
    private fun id() = UUID.randomUUID().toString()
    private fun scope() = OrionInstrumentedWorkloadScope(OrionInstrumentedGraphIdentity(Any(), Any()), id(), id())
    private fun claim(scope: OrionInstrumentedWorkloadScope, op: OrionInstrumentedOperation, attempt: String? = null) =
        scope.claim(op, scope.runId, scope.fixtureId, scope.graph.nonce, attempt)
    @Test fun pendingDeathAllowsOnlyReadObservationAndRequiresConfirmationBeforeANewUniqueAttempt() {
        val scope = scope(); val old = id()
        assertTrue(claim(scope, OrionInstrumentedOperation.WORK, old))
        assertTrue(scope.finish(OrionInstrumentedOperation.WORK, deathEvidencePending = true))
        assertEquals(OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE, scope.state())
        assertFalse(claim(scope, OrionInstrumentedOperation.WORK, id()))
        assertFalse(claim(scope, OrionInstrumentedOperation.CLEANUP))
        assertFalse(scope.confirmOriginalProcessDeath()) // no owned OBSERVE phase
        assertTrue(claim(scope, OrionInstrumentedOperation.OBSERVE)); assertTrue(scope.finish(OrionInstrumentedOperation.OBSERVE))
        assertEquals(OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE, scope.state())
        assertTrue(claim(scope, OrionInstrumentedOperation.OBSERVE)); assertTrue(scope.confirmOriginalProcessDeath())
        assertTrue(scope.finish(OrionInstrumentedOperation.OBSERVE)); assertEquals(OrionInstrumentedExecutionState.IDLE, scope.state())
        assertFalse(claim(scope, OrionInstrumentedOperation.WORK, old)) // settlement never reuses the original request/attempt
        assertTrue(claim(scope, OrionInstrumentedOperation.WORK, id()))
    }
    @Test fun malformedObserveCannotClearPendingAndUnknownTransportCannotBeSettledOrRetried() {
        val pending = scope(); assertTrue(claim(pending, OrionInstrumentedOperation.WORK, id()))
        pending.finish(OrionInstrumentedOperation.WORK, deathEvidencePending = true)
        assertFalse(claim(pending, OrionInstrumentedOperation.OBSERVE, id()))
        assertEquals(OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE, pending.state())
        assertFalse(claim(pending, OrionInstrumentedOperation.WORK, id()))
        val unknown = scope(); assertTrue(claim(unknown, OrionInstrumentedOperation.WORK, id()))
        unknown.finish(OrionInstrumentedOperation.WORK, uncertain = true)
        assertFalse(unknown.confirmOriginalProcessDeath())
        assertFalse(claim(unknown, OrionInstrumentedOperation.OBSERVE))
        assertFalse(claim(unknown, OrionInstrumentedOperation.WORK, id()))
        assertEquals(OrionInstrumentedExecutionState.UNCERTAIN, unknown.state())
    }
    @Test fun originalDeathIsDistinctFromTimeoutTransportFailureRetainedAbsenceAndUnknownIssuance() {
        for (terminal in OrionInferenceTerminal.entries) {
            val original = OrionCortexRunResult.Completed(OrionInferenceStatus(OrionInferenceStage.TERMINAL, terminal,
                if (terminal == OrionInferenceTerminal.FAILED) OrionInferenceFailure.TRANSPORT_FAILURE else null))
            val expected = terminal == OrionInferenceTerminal.PROCESS_DIED
            assertEquals(expected, originalProcessDeathAwaitingEvidence(original, true, true))
            assertFalse(originalProcessDeathAwaitingEvidence(original, null, true))
            assertFalse(originalProcessDeathAwaitingEvidence(original, false, true))
            assertFalse(originalProcessDeathAwaitingEvidence(original, true, false))
            val scope = scope(); assertTrue(claim(scope, OrionInstrumentedOperation.WORK, id()))
            scope.finish(OrionInstrumentedOperation.WORK, uncertain = !expected, deathEvidencePending = expected)
            assertEquals(if (expected) OrionInstrumentedExecutionState.DEAD_AWAITING_EVIDENCE else OrionInstrumentedExecutionState.UNCERTAIN, scope.state())
            assertFalse(claim(scope, OrionInstrumentedOperation.WORK, id()))
        }
        assertFalse(originalProcessDeathAwaitingEvidence(null, true, true))
        assertFalse(originalProcessDeathAwaitingEvidence(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CORE_NOT_READY), true, true))
    }

    private fun incident(): OrionQualificationExitIncident {
        val source = OrionQualificationExitCorrelation("network.orion.qualification", 10000, 1, OrionQualificationExitConfiguration(10, 1000, 10000))
        val endpoint = OrionQualificationEndpointKey(Any(), Any(), Any(), 1)
        fun pair(at: Long) = OrionQualificationTimePair(3, at, 1000000 + at, 1000001 + at)
        for (at in listOf(100L, 200L)) source.live(OrionQualificationLiveReceipt(endpoint,
            OrionRuntimeAiMemoryReading(2, 3, at + 10, 100, true, true, true, OrionRuntimeServiceState.UNLOADED),
            OrionQualificationProcWitness(2, 10000, 99), OrionQualificationObservationWindow(pair(at), pair(at + 20)), OrionQualificationExitHistory(emptyList(), false, null)))
        val record = checkNotNull(OrionQualificationExitSanitizer.record(2, 10000, 10000, "network.orion.qualification:ai",
            1000250, 13, "MemoryLimiter:AnonSwap", null))
        return checkNotNull(source.history(OrionQualificationObservationWindow(pair(300), pair(320)), listOf(record),
            OrionQualificationProcObservation(OrionQualificationProcState.ABSENT)).incident)
    }
    @Test fun settlementReferencesTheStoredOriginalAttemptAndActualObservationStampWithoutInventingExitTime() {
        val incident = incident(); val attempt = id()
        val value = checkNotNull(OrionQualificationDeathSettlement.observed(attempt, incident, MonotonicInstant(BootSessionId(3), 500)))
        assertEquals(OrionRuntimeTechnicalId(attempt).sha256ForManualExport(), value.originalAttemptIdSha256)
        assertEquals(incident.identitySha256(), value.incidentIdentitySha256)
        assertEquals(500L, value.observedElapsedMillis); assertEquals(3, value.bootCount)
        assertTrue(value.matchesObservation(OrionQualificationExitResult(incident, null), MonotonicInstant(BootSessionId(3), 600)))
        assertFalse(value.matchesObservation(null, MonotonicInstant(BootSessionId(3), 600)))
        assertFalse(value.matchesObservation(OrionQualificationExitResult(null, OrionQualificationExitGap.HISTORY_UNAVAILABLE), MonotonicInstant(BootSessionId(3), 600)))
        assertFalse(value.matchesObservation(OrionQualificationExitResult(incident, null), MonotonicInstant(BootSessionId(4), 600)))
        assertFalse(value.matchesObservation(OrionQualificationExitResult(incident, null), MonotonicInstant(BootSessionId(3), 499)))
        val out = OrionRuntimeAsciiWriter(1024); value.write(out); val text = out.finish()
        assertTrue(text.contains("\"originalTerminal\":\"PROCESS_DIED\"")); assertFalse(text.contains(attempt))
        assertNull(OrionQualificationDeathSettlement.observed(attempt, incident, MonotonicInstant(BootSessionId(4), 500)))
        assertNull(OrionQualificationDeathSettlement.observed(attempt, incident, MonotonicInstant(BootSessionId(3), 319)))
        assertNull(OrionQualificationDeathSettlement.observed("HOST_ECHO_NOT_AN_EXECUTED_ID", incident, MonotonicInstant(BootSessionId(3), 500)))
    }
}

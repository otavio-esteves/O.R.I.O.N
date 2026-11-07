package network.orion.core.exitinfo

import org.junit.Assert.*
import org.junit.Test

class OrionForceStopStartupEvidenceTest {
    @Test fun incompleteNegativeHistoryDoesNotMaskLaterCompletedPrimaryEvidence() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(false, startupComplete = false)
        assertEquals(ForceStopStartupObservation.UNAVAILABLE, evidence.observation)
        assertFalse(evidence.needsPersistence)
        evidence.observe(true, startupComplete = true)
        assertEquals(ForceStopStartupObservation.OBSERVED, evidence.observation)
        assertTrue(evidence.needsPersistence)
    }

    @Test fun missingCurrentRecordRemainsUnavailableRegardlessOfCompletionHint() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(null, startupComplete = false)
        evidence.observe(null, startupComplete = true)
        assertEquals(ForceStopStartupObservation.UNAVAILABLE, evidence.observation)
        assertFalse(evidence.needsPersistence)
    }

    @Test fun completedNegativeMaySettleButCannotOverrideLaterPositiveCallback() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(false, startupComplete = true)
        assertEquals(ForceStopStartupObservation.NOT_OBSERVED, evidence.observation)
        evidence.observe(true, startupComplete = true)
        assertEquals(ForceStopStartupObservation.OBSERVED, evidence.observation)
        assertTrue(evidence.needsPersistence)
    }

    @Test fun positivePrimaryValueIsStickyEvenIfLaterDataIsIncompleteOrNegative() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(true, startupComplete = false)
        evidence.observe(null, startupComplete = false)
        evidence.observe(false, startupComplete = true)
        assertEquals(ForceStopStartupObservation.OBSERVED, evidence.observation)
        assertTrue(evidence.needsPersistence)
    }

    @Test fun failedDurableWriteLeavesEvidencePendingForASafeRetry() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(true, startupComplete = true)
        // A failed write must not call markPersisted; resampling retains the pending observation.
        evidence.observe(null, startupComplete = false)
        assertTrue(evidence.needsPersistence)
        evidence.markPersisted()
        assertFalse(evidence.needsPersistence)
        assertEquals(ForceStopStartupObservation.OBSERVED, evidence.observation)
    }

    @Test fun duplicateCallbackDoesNotRequestDuplicatePersistenceAfterSuccessfulWrite() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.observe(true, startupComplete = true)
        evidence.markPersisted()
        evidence.observe(true, startupComplete = true)
        assertFalse(evidence.needsPersistence)
    }

    @Test fun oldPlatformSupportIsExplicitAndNeverDerivedFromOtherSignals() {
        val evidence = OrionForceStopStartupEvidence()
        evidence.markUnsupported()
        assertEquals(ForceStopStartupObservation.UNSUPPORTED, evidence.observation)
        assertFalse(evidence.needsPersistence)
        assertThrows(IllegalStateException::class.java) { evidence.markPersisted() }
    }
}

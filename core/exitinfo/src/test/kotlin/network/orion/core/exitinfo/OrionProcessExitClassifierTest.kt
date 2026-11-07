package network.orion.core.exitinfo

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OrionProcessExitClassifierTest {
    private val at = Instant.parse("2026-11-07T12:00:00Z")

    @Test fun stablePublicReasonsClassifyWithoutDescriptionOrPlatformDependencies() {
        val expected = listOf(ProcessExitKind.UNKNOWN, ProcessExitKind.NORMAL_EXIT, ProcessExitKind.SIGNALLED,
            ProcessExitKind.LOW_MEMORY, ProcessExitKind.JVM_CRASH, ProcessExitKind.NATIVE_CRASH,
            ProcessExitKind.ANR, ProcessExitKind.INITIALIZATION_FAILURE, ProcessExitKind.PERMISSION_CHANGED,
            ProcessExitKind.EXCESSIVE_RESOURCE_USAGE, ProcessExitKind.USER_REQUESTED, ProcessExitKind.USER_STOPPED,
            ProcessExitKind.DEPENDENCY_DIED, ProcessExitKind.OTHER_SYSTEM, ProcessExitKind.FREEZER,
            ProcessExitKind.PACKAGE_CHANGED, ProcessExitKind.PACKAGE_UPDATED)
        expected.forEachIndexed { reason, kind ->
            val signal = OrionProcessExitClassifier.classify(reason, at)
            assertEquals(reason, signal.reason)
            assertEquals(at, signal.occurredAt)
            assertEquals(kind, signal.kind)
            assertFalse(signal.memoryLimiterEvidence)
        }
    }

    @Test fun dedicatedMemoryLimiterNeedsExposedPlatformConstantButNoDescription() {
        val signal = OrionProcessExitClassifier.classify(17, at, dedicatedMemoryLimiterReason = 17)
        assertEquals(ProcessExitKind.MEMORY_LIMITER, signal.kind)
        assertTrue(signal.memoryLimiterEvidence)
        assertEquals(ProcessExitKind.UNKNOWN, OrionProcessExitClassifier.classify(17, at).kind)
    }

    @Test fun dedicatedReasonDoesNotDependOnAssumingOnePreviewNumericEncoding() {
        val signal = OrionProcessExitClassifier.classify(71, at, dedicatedMemoryLimiterReason = 71)
        assertEquals(ProcessExitKind.MEMORY_LIMITER, signal.kind)
        assertTrue(signal.memoryLimiterEvidence)
        assertEquals(ProcessExitKind.UNKNOWN,
            OrionProcessExitClassifier.classify(71, at, dedicatedMemoryLimiterReason = 17).kind)
    }

    @Test fun legacyOtherPlusPreciseMarkerRecognizesMemoryLimiterAndDropsDescription() {
        val description = "private platform prefix MemoryLimiter:AnonSwap private platform suffix"
        val signal = OrionProcessExitClassifier.classify(13, at, description)
        assertEquals(ProcessExitKind.MEMORY_LIMITER, signal.kind)
        assertTrue(signal.memoryLimiterEvidence)
        assertFalse(signal.toString().contains("private"))
        assertFalse(signal.toString().contains("AnonSwap"))
        assertEquals(ProcessExitSignal(13, at, ProcessExitKind.MEMORY_LIMITER, true), signal)
    }

    @Test fun missingOrImpreciseDescriptionIsNotMemoryLimiterEvidence() {
        for (description in listOf(null, "", "MemoryLimiter", "AnonSwap", "memorylimiter:anonswap", "ordinary system update")) {
            val signal = OrionProcessExitClassifier.classify(13, at, description)
            assertEquals(ProcessExitKind.OTHER_SYSTEM, signal.kind)
            assertFalse(signal.memoryLimiterEvidence)
        }
    }

    @Test fun markerUnderUnrelatedReasonDoesNotOverrideItsMeaning() {
        for (reason in listOf(0, 1, 2, 3, 4, 5, 10, 11, 99)) {
            val signal = OrionProcessExitClassifier.classify(reason, at, "MemoryLimiter:AnonSwap")
            assertNotEquals(ProcessExitKind.MEMORY_LIMITER, signal.kind)
            assertFalse(signal.memoryLimiterEvidence)
        }
    }

    @Test fun lowMemoryAndSignalsRemainDistinctAndNeverInferForceStop() {
        assertEquals(ProcessExitKind.LOW_MEMORY, OrionProcessExitClassifier.classify(3, at).kind)
        assertEquals(ProcessExitKind.SIGNALLED, OrionProcessExitClassifier.classify(2, at).kind)
        // USER_REQUESTED can also mean dismissal or a historical package update.
        assertEquals(ProcessExitKind.USER_REQUESTED, OrionProcessExitClassifier.classify(10, at, "force stop").kind)
        assertEquals(ProcessExitKind.USER_STOPPED, OrionProcessExitClassifier.classify(11, at).kind)
        assertFalse(ProcessExitKind.entries.any { it.name == "FORCE_STOP" })
    }

    @Test fun unknownReasonsRemainExplicitAndExistingTwoArgumentSignalsStayCompatible() {
        for (reason in listOf(Int.MIN_VALUE, -1, 18, 999, Int.MAX_VALUE)) {
            val signal = OrionProcessExitClassifier.classify(reason, at)
            assertEquals(ProcessExitKind.UNKNOWN, signal.kind)
            assertFalse(signal.memoryLimiterEvidence)
            assertEquals(reason, signal.reason)
        }
        assertEquals(ProcessExitSignal(3, at, ProcessExitKind.UNKNOWN, false), ProcessExitSignal(3, at))
    }

    @Test fun dedicatedConstantCannotShadowAStablePublicReason() {
        for (reason in listOf(Int.MIN_VALUE, -1, 0, 3, 13, 16)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionProcessExitClassifier.classify(reason, at, dedicatedMemoryLimiterReason = reason)
            }
        }
    }
}

package network.orion.core.health

import org.junit.Assert.*
import org.junit.Test

class OrionPlatformDiagnosticsTest {
    @Test fun `unknown native metadata never implies qualification`() {
        for (value in listOf(null, "", "pass", "unknown", "PENDING user content"))
            assertEquals(OrionNativeQualification.UNAVAILABLE,
                OrionPlatformDiagnostics.from(value, false, null).nativeQualification)
        assertEquals(OrionNativeQualification.PENDING, OrionPlatformDiagnostics.from("PENDING", false, false).nativeQualification)
        assertEquals(OrionNativeQualification.FAIL, OrionPlatformDiagnostics.from("FAIL", true, true).nativeQualification)
        assertEquals(OrionNativeQualification.PASS, OrionPlatformDiagnostics.from("PASS", true, false).nativeQualification)
    }

    @Test fun `unavailable exit history differs from no observed limiter evidence`() {
        val missing = OrionPlatformDiagnostics.from("PENDING", false, null)
        assertEquals(OrionMemoryLimiterEvidence.UNAVAILABLE, missing.memoryLimiterEvidence)
        assertEquals(OrionMemoryLimiterClassification.DESCRIPTION_FALLBACK_ONLY, missing.memoryLimiterClassification)
        val observed = OrionPlatformDiagnostics.from("PENDING", false, true)
        assertEquals(OrionMemoryLimiterEvidence.OBSERVED, observed.memoryLimiterEvidence)
        assertEquals(OrionMemoryLimiterEvidence.NOT_OBSERVED,
            OrionPlatformDiagnostics.from("PENDING", true, false).memoryLimiterEvidence)
        assertEquals(OrionMemoryLimiterClassification.DEDICATED_REASON_AVAILABLE,
            OrionPlatformDiagnostics.from("PENDING", true, false).memoryLimiterClassification)
    }
}

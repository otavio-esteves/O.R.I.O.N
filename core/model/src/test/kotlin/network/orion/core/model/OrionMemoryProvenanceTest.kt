package network.orion.core.model

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryProvenanceTest {
    @Test fun inferenceFollowsOriginAndKeepsConfirmationIndependent() {
        for (origin in OrionMemoryOrigin.entries) {
            val provenance = OrionMemoryProvenance(origin, 0.4, false, Sensitivity.PERSONAL)
            assertEquals(origin == OrionMemoryOrigin.LLM_INFERRED, provenance.inferred)
            assertFalse(provenance.userConfirmed)
            assertNull(provenance.originEventId)
        }
        val evidence = UUID.randomUUID()
        val reviewed = OrionMemoryProvenance(OrionMemoryOrigin.LLM_INFERRED, 0.5, true, Sensitivity.SENSITIVE, evidence)
        assertTrue(reviewed.inferred)
        assertTrue(reviewed.userConfirmed)
        assertEquals(evidence, reviewed.originEventId)
        assertFalse(reviewed.toString().contains(evidence.toString()))
    }

    @Test fun confidenceIsFiniteAndBounded() {
        for (confidence in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionMemoryProvenance(OrionMemoryOrigin.USER_DECLARED, confidence, false, Sensitivity.PERSONAL)
            }
        }
        for (confidence in listOf(0.0, 1.0)) {
            assertEquals(confidence,
                OrionMemoryProvenance(OrionMemoryOrigin.USER_DECLARED, confidence, false, Sensitivity.PERSONAL).confidence,
                0.0)
        }
    }
}

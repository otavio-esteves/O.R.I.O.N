package network.orion.app.ai

import org.junit.Assert.*
import org.junit.Test

class OrionCortexPreResourceReadinessTest {
    @Test fun `early predicates preserve order and stop before later sources`() {
        val cases = listOf(
            Triple(listOf(true, false, false, false), listOf("closed"), OrionCortexPreResourceReadiness.CLOSED),
            Triple(listOf(false, false, false, false), listOf("closed", "plans"), OrionCortexPreResourceReadiness.NO_QUALIFIED_PROFILE),
            Triple(listOf(false, true, false, false), listOf("closed", "plans", "timing"), OrionCortexPreResourceReadiness.RESOURCE_CONFIGURATION_UNAVAILABLE),
            Triple(listOf(false, true, true, false), listOf("closed", "plans", "timing", "core"), OrionCortexPreResourceReadiness.CORE_NOT_READY),
            Triple(listOf(false, true, true, true), listOf("closed", "plans", "timing", "core"), OrionCortexPreResourceReadiness.DECISION_NOT_EVALUATED),
        )
        for ((values, expectedCalls, expected) in cases) {
            val calls = mutableListOf<String>()
            fun source(index: Int, name: String): Boolean {
                check(name in expectedCalls) { "A later source was evaluated after refusal." }
                calls.add(name)
                return values[index]
            }
            assertEquals(expected, evaluateCortexPreResourceReadiness(
                { source(0, "closed") }, { source(1, "plans") }, { source(2, "timing") }, { source(3, "core") }))
            assertEquals(expectedCalls, calls)
        }
    }

    @Test fun `configured state is not an admission decision or permit`() {
        val result = evaluateCortexPreResourceReadiness({ false }, { true }, { true }, { true })
        assertEquals(OrionCortexPreResourceReadiness.DECISION_NOT_EVALUATED, result)
        assertNull(result.unavailable)
        assertEquals(setOf("CLOSED", "NO_QUALIFIED_PROFILE", "RESOURCE_CONFIGURATION_UNAVAILABLE", "CORE_NOT_READY",
            "DECISION_NOT_EVALUATED"), OrionCortexPreResourceReadiness.entries.map { it.name }.toSet())
    }

    @Test fun `early refusal maps to existing run result without changing reason`() {
        assertEquals(OrionCortexUnavailable.CLOSED, OrionCortexPreResourceReadiness.CLOSED.unavailable)
        assertEquals(OrionCortexUnavailable.NO_QUALIFIED_PROFILE, OrionCortexPreResourceReadiness.NO_QUALIFIED_PROFILE.unavailable)
        assertEquals(OrionCortexUnavailable.RESOURCE_CONFIGURATION_UNAVAILABLE,
            OrionCortexPreResourceReadiness.RESOURCE_CONFIGURATION_UNAVAILABLE.unavailable)
        assertEquals(OrionCortexUnavailable.CORE_NOT_READY, OrionCortexPreResourceReadiness.CORE_NOT_READY.unavailable)
    }

    @Test(expected = IllegalStateException::class) fun `required Core callback failures propagate as before`() {
        evaluateCortexPreResourceReadiness({ false }, { true }, { true }, { error("Unavailable Core source") })
    }
}

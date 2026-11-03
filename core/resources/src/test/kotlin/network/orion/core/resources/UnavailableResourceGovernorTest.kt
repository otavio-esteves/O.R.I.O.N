package network.orion.core.resources

import org.junit.Assert.*
import org.junit.Test

class UnavailableResourceGovernorTest {
    @Test fun `unknown resources never authorize loading or inference`() {
        val governor = UnavailableResourceGovernor()
        assertFalse(governor.canRunInference())
        assertFalse(governor.canLoadModel(InferenceModelProfile("orion_model", 1)))
        assertNull(governor.allowedModelTier())
        assertNull(governor.estimatedHeadroom())
        assertEquals(MemoryRisk.UNKNOWN, governor.memoryRisk())
        assertEquals(0, governor.maxThreads())
        assertEquals(0, governor.maxContext())
        assertEquals(0, governor.maxTokens())
        assertEquals(0L, governor.keepAliveMs())
    }
}

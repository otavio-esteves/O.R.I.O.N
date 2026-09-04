package network.orion.core.common.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrionProcessRoleTest {
    @Test
    fun `maps every owned Android process explicitly`() {
        val applicationId = "network.orion"

        assertEquals(OrionProcessRole.MAIN, OrionProcessRole.fromProcessName(applicationId, applicationId))
        assertEquals(OrionProcessRole.AI, OrionProcessRole.fromProcessName(applicationId, "$applicationId:ai"))
        assertEquals(
            OrionProcessRole.VOICE_CONTROL,
            OrionProcessRole.fromProcessName(applicationId, "$applicationId:voice"),
        )
        assertEquals(
            OrionProcessRole.VOICE_SESSION,
            OrionProcessRole.fromProcessName(applicationId, "$applicationId:voice_session"),
        )
    }

    @Test
    fun `rejects an unknown process instead of treating it as main`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionProcessRole.fromProcessName("network.orion", "network.orion:unknown")
        }
    }
}

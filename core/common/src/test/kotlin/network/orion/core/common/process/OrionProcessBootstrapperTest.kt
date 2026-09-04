package network.orion.core.common.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrionProcessBootstrapperTest {
    @Test
    fun `initializes only the selected process boundary`() {
        val initialized = mutableListOf<OrionProcessRole>()
        val initializers = OrionProcessRole.entries.associateWith { role ->
            OrionProcessInitializer { initialized += role }
        }

        OrionProcessBootstrapper(initializers).bootstrap(OrionProcessRole.VOICE_SESSION)

        assertEquals(listOf(OrionProcessRole.VOICE_SESSION), initialized)
    }

    @Test
    fun `requires an initializer for every process role`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionProcessBootstrapper(
                mapOf(OrionProcessRole.MAIN to OrionProcessInitializer { }),
            )
        }
    }
}

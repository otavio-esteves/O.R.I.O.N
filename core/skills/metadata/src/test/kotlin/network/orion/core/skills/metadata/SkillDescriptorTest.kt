package network.orion.core.skills.metadata

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SkillDescriptorTest {
    private fun descriptor(auth: AuthorizationLevel = AuthorizationLevel.SAFE_READ,
        effect: SideEffectClass = SideEffectClass.READ_ONLY,
        confirmation: ConfirmationPolicy = ConfirmationPolicy.POLICY_DEPENDENT,
        version: Int = 1, name: String = "orion_time") = SkillDescriptor(
        name, auth, RecoveryStrategy.NO_RETRY, effect, IdempotencyMode.NATURAL,
        setOf(Capability("orion_clock")), emptySet(), false, confirmation, true, version,
    )

    @Test fun `destructive classification cannot remove mandatory confirmation`() {
        assertThrows(IllegalArgumentException::class.java) { descriptor(auth = AuthorizationLevel.DESTRUCTIVE) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(effect = SideEffectClass.DESTRUCTIVE) }
        assertEquals(ConfirmationPolicy.ALWAYS, descriptor(auth = AuthorizationLevel.DESTRUCTIVE,
            effect = SideEffectClass.DESTRUCTIVE, confirmation = ConfirmationPolicy.ALWAYS).confirmationPolicy)
    }
    @Test fun `metadata requires identity and positive argument schema`() {
        assertThrows(IllegalArgumentException::class.java) { descriptor(version = 0) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { Capability(" ") }
        assertThrows(IllegalArgumentException::class.java) { Capability(" orion_clock") }
        assertEquals("orion_clock", descriptor().requiredCapabilities.single().id)
    }
}

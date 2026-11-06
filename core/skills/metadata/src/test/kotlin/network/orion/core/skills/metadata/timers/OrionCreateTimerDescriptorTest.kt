package network.orion.core.skills.metadata.timers

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionCreateTimerDescriptorTest {
    @Test fun `create timer metadata is static local and conservative until replay is implemented`() {
        val descriptor = OrionCreateTimerDescriptor.value
        assertSame(descriptor, OrionCreateTimerDescriptor.value)
        assertEquals("timer.create", descriptor.name)
        assertEquals(1, descriptor.argumentSchemaVersion)
        assertEquals(AuthorizationLevel.SAFE_WRITE, descriptor.authorizationLevel)
        assertEquals(SideEffectClass.LOCAL_MUTATION, descriptor.sideEffectClass)
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, descriptor.recoveryStrategy)
        assertEquals(IdempotencyMode.KEYED, descriptor.idempotencyMode)
        assertEquals(ConfirmationPolicy.POLICY_DEPENDENT, descriptor.confirmationPolicy)
        assertTrue(descriptor.requiresUserPresence)
        assertFalse(descriptor.supportsDryRun)
    }

    @Test fun `private storage needs a trusted functional capability without Android permission grants`() {
        val descriptor = OrionCreateTimerDescriptor.value
        assertEquals(setOf(Capability("orion_timers_write")), descriptor.requiredCapabilities)
        assertEquals(OrionCreateTimerDescriptor.WRITE_CAPABILITY, descriptor.requiredCapabilities.single())
        assertTrue(descriptor.requiredPermissions.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (descriptor.requiredCapabilities as MutableSet<Capability>).clear()
        }
        assertEquals(setOf(Capability("orion_timers_write")), OrionCreateTimerDescriptor.value.requiredCapabilities)
    }
    @Test fun timerCancellationRequiresConfirmationAndDoesNotSelectUnknownSkills() {
        val d=OrionTimerStatusDescriptors.cancel
        assertEquals("timer.cancel",d.name)
        assertEquals(ConfirmationPolicy.ALWAYS,d.confirmationPolicy)
        assertEquals(RecoveryStrategy.MANUAL_REVIEW,d.recoveryStrategy)
        assertTrue(d.requiresUserPresence)
        assertEquals(setOf(OrionCreateTimerDescriptor.WRITE_CAPABILITY),d.requiredCapabilities)
        for(name in listOf("timer.complete","timer.create","TIMER.CANCEL","timer.cancel ")) assertNull(OrionTimerStatusDescriptors.forName(name))
    }
}

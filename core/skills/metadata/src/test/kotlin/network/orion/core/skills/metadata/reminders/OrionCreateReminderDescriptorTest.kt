package network.orion.core.skills.metadata.reminders

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionCreateReminderDescriptorTest {
    @Test fun `reminder creation metadata is static local and conservative`() {
        val descriptor = OrionCreateReminderDescriptor.value
        assertSame(descriptor, OrionCreateReminderDescriptor.value)
        assertEquals("reminder.create", descriptor.name)
        assertEquals(1, descriptor.argumentSchemaVersion)
        assertEquals(AuthorizationLevel.SAFE_WRITE, descriptor.authorizationLevel)
        assertEquals(SideEffectClass.LOCAL_MUTATION, descriptor.sideEffectClass)
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, descriptor.recoveryStrategy)
        assertEquals(IdempotencyMode.KEYED, descriptor.idempotencyMode)
        assertEquals(ConfirmationPolicy.POLICY_DEPENDENT, descriptor.confirmationPolicy)
        assertTrue(descriptor.requiresUserPresence)
        assertFalse(descriptor.supportsDryRun)
    }

    @Test fun `reminder preparation declares a functional capability without scheduling permission`() {
        val descriptor = OrionCreateReminderDescriptor.value
        assertEquals(setOf(Capability("orion_reminders_write")), descriptor.requiredCapabilities)
        assertEquals(OrionCreateReminderDescriptor.WRITE_CAPABILITY, descriptor.requiredCapabilities.single())
        assertTrue(descriptor.requiredPermissions.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (descriptor.requiredCapabilities as MutableSet<Capability>).clear()
        }
        assertEquals(setOf(Capability("orion_reminders_write")), OrionCreateReminderDescriptor.value.requiredCapabilities)
    }
}

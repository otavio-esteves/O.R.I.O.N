package network.orion.core.skills.metadata.tasks

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionCreateTaskDescriptorTest {
    @Test fun `create task metadata is static local and conservative until replay is implemented`() {
        val descriptor = OrionCreateTaskDescriptor.value
        assertSame(descriptor, OrionCreateTaskDescriptor.value)
        assertEquals("task.create", descriptor.name)
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
        val descriptor = OrionCreateTaskDescriptor.value
        assertEquals(setOf(Capability("orion_tasks_write")), descriptor.requiredCapabilities)
        assertEquals(OrionCreateTaskDescriptor.WRITE_CAPABILITY, descriptor.requiredCapabilities.single())
        assertTrue(descriptor.requiredPermissions.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (descriptor.requiredCapabilities as MutableSet<Capability>).clear()
        }
        assertEquals(setOf(Capability("orion_tasks_write")), OrionCreateTaskDescriptor.value.requiredCapabilities)
    }
}

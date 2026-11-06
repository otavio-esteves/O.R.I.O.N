package network.orion.core.skills.metadata.memories

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryDescriptorsTest {
    @Test fun userCreationRequiresOwnedLocalKeyedMutationAndFreshPresence() {
        val descriptor=OrionCreateMemoryDescriptor.value
        assertEquals("memory.create",descriptor.name); assertEquals(AuthorizationLevel.SAFE_WRITE,descriptor.authorizationLevel)
        assertEquals(SideEffectClass.LOCAL_MUTATION,descriptor.sideEffectClass); assertEquals(IdempotencyMode.KEYED,descriptor.idempotencyMode)
        assertEquals(RecoveryStrategy.MANUAL_REVIEW,descriptor.recoveryStrategy); assertTrue(descriptor.requiresUserPresence)
        assertEquals(setOf(Capability("orion_memories_write")),descriptor.requiredCapabilities)
        assertTrue(descriptor.requiredPermissions.isEmpty()); assertFalse(descriptor.supportsDryRun)
    }
    @Test fun forgettingIsDestructiveAndCannotLoseMandatoryConfirmation() {
        val descriptor=OrionMemoryStatusDescriptors.delete
        assertSame(descriptor,OrionMemoryStatusDescriptors.forName("memory.delete"))
        assertNull(OrionMemoryStatusDescriptors.forName("memory.confirmInferred"))
        assertEquals(AuthorizationLevel.DESTRUCTIVE,descriptor.authorizationLevel)
        assertEquals(SideEffectClass.DESTRUCTIVE,descriptor.sideEffectClass)
        assertEquals(ConfirmationPolicy.ALWAYS,descriptor.confirmationPolicy)
        assertTrue(descriptor.requiresUserPresence); assertEquals(IdempotencyMode.KEYED,descriptor.idempotencyMode)
    }
}

package network.orion.core.skills.metadata.device

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionDeviceDescriptorsTest {
    @Test fun descriptorsAreStaticReadOnlyWithoutMutationAuthorityOrAndroidPermissions() {
        val descriptors=listOf(OrionDeviceDescriptors.battery,OrionDeviceDescriptors.time,
            OrionDeviceDescriptors.status,OrionDeviceDescriptors.activity)
        assertEquals(listOf("device.battery","device.time","device.status","activity.history"),descriptors.map { it.name })
        for (descriptor in descriptors) {
            assertEquals(AuthorizationLevel.SAFE_READ,descriptor.authorizationLevel)
            assertEquals(SideEffectClass.READ_ONLY,descriptor.sideEffectClass)
            assertEquals(RecoveryStrategy.RETRY_SAFE,descriptor.recoveryStrategy)
            assertEquals(IdempotencyMode.NATURAL,descriptor.idempotencyMode)
            assertEquals(ConfirmationPolicy.POLICY_DEPENDENT,descriptor.confirmationPolicy)
            assertTrue(descriptor.requiredPermissions.isEmpty()); assertTrue(descriptor.requiredCapabilities.isEmpty())
            assertFalse(descriptor.requiresUserPresence); assertFalse(descriptor.supportsDryRun)
            assertEquals(1,descriptor.argumentSchemaVersion)
        }
        assertSame(OrionDeviceDescriptors.battery,OrionDeviceDescriptors.battery)
    }
}

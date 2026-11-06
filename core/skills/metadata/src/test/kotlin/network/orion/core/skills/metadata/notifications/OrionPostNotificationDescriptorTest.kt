package network.orion.core.skills.metadata.notifications
import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test
class OrionPostNotificationDescriptorTest {
    @Test fun externalPostRequiresPresenceExactConfirmationPermissionAndConservativeRecovery() {
        val d=OrionPostNotificationDescriptor.value
        assertEquals("notification.post",d.name);assertEquals(1,d.argumentSchemaVersion)
        assertEquals(AuthorizationLevel.SAFE_WRITE,d.authorizationLevel)
        assertEquals(SideEffectClass.EXTERNAL_REVERSIBLE,d.sideEffectClass)
        assertEquals(RecoveryStrategy.MANUAL_REVIEW,d.recoveryStrategy);assertEquals(IdempotencyMode.KEYED,d.idempotencyMode)
        assertEquals(ConfirmationPolicy.ALWAYS,d.confirmationPolicy);assertTrue(d.requiresUserPresence);assertFalse(d.supportsDryRun)
        assertEquals(setOf("android.permission.POST_NOTIFICATIONS"),d.requiredPermissions)
        assertEquals(setOf(OrionPostNotificationDescriptor.WRITE_CAPABILITY),d.requiredCapabilities)
        assertThrows(UnsupportedOperationException::class.java) { (d.requiredPermissions as MutableSet<String>).clear() }
    }
}

package network.orion.core.skills.metadata.tasks

import network.orion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrionTaskStatusDescriptorsTest {
    @Test fun bothStatusOperationsRequireConfirmedPresentLocalKeyedWrites() {
        for (name in listOf("task.complete", "task.cancel")) {
            val descriptor = checkNotNull(OrionTaskStatusDescriptors.forName(name))
            assertEquals(name, descriptor.name)
            assertEquals(AuthorizationLevel.SAFE_WRITE, descriptor.authorizationLevel)
            assertEquals(SideEffectClass.LOCAL_MUTATION, descriptor.sideEffectClass)
            assertEquals(IdempotencyMode.KEYED, descriptor.idempotencyMode)
            assertEquals(RecoveryStrategy.MANUAL_REVIEW, descriptor.recoveryStrategy)
            assertEquals(ConfirmationPolicy.ALWAYS, descriptor.confirmationPolicy)
            assertEquals(setOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY), descriptor.requiredCapabilities)
            assertTrue(descriptor.requiredPermissions.isEmpty())
            assertTrue(descriptor.requiresUserPresence)
            assertFalse(descriptor.supportsDryRun)
        }
    }

    @Test fun unknownNamesCannotSelectAReadOnlyOrLowerAuthorizationFallback() {
        for (name in listOf("task.create", "TASK.COMPLETE", "task.delete", "", "task.cancel "))
            assertNull(OrionTaskStatusDescriptors.forName(name))
    }
}

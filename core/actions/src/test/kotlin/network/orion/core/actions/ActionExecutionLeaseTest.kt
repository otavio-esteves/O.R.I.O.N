package network.orion.core.actions

import java.util.UUID
import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class ActionExecutionLeaseTest {
    private val lease = ActionExecutionLease(UUID.randomUUID(), "main-test", BootSessionId(4), 100, 200)

    @Test fun `only the creating boot can validate the lease and equality is expired`() {
        assertEquals(ActionLeaseStatus.ACTIVE, lease.statusAt(MonotonicInstant(BootSessionId(4), 100)))
        assertEquals(ActionLeaseStatus.ACTIVE, lease.statusAt(MonotonicInstant(BootSessionId(4), 199)))
        assertEquals(ActionLeaseStatus.EXPIRED, lease.statusAt(MonotonicInstant(BootSessionId(4), 200)))
        assertEquals(ActionLeaseStatus.INVALID, lease.statusAt(MonotonicInstant(BootSessionId(5), 150)))
        assertEquals(ActionLeaseStatus.INVALID, lease.statusAt(MonotonicInstant(null, 150)))
        assertEquals(ActionLeaseStatus.INVALID, lease.statusAt(MonotonicInstant(BootSessionId(4), 99)))
    }

    @Test fun `missing ownership or restart never authorizes automatic replay for any recovery strategy`() {
        for (strategy in RecoveryStrategy.entries) for (effect in SideEffectClass.entries) {
            val resolution = AbandonedActionRecovery.resolve(null, MonotonicInstant(BootSessionId(5), 1), strategy, effect)
            val external = effect in setOf(SideEffectClass.EXTERNAL_REVERSIBLE, SideEffectClass.EXTERNAL_IRREVERSIBLE, SideEffectClass.DESTRUCTIVE)
            assertEquals(if (external && strategy != RecoveryStrategy.MANUAL_REVIEW) AbandonedActionResolution.UNKNOWN
                else AbandonedActionResolution.MANUAL_REVIEW, resolution)
            assertEquals(AbandonedActionResolution.KEEP_RUNNING,
                AbandonedActionRecovery.resolve(lease, MonotonicInstant(BootSessionId(4), 150), strategy, effect))
        }
    }

    @Test fun `invalid owner and duration are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { lease.copy(claimedBy = "") }
        assertThrows(IllegalArgumentException::class.java) { lease.copy(expiresElapsedRealtime = 100) }
        assertThrows(IllegalArgumentException::class.java) { lease.copy(startedElapsedRealtime = -1) }
        assertEquals("ActionExecutionLease(<redacted>)", lease.toString())
    }

    private val capability = Capability("test.write")
    private val request = NormalizedActionRequest(UUID.randomUUID(), "test.write", "{}", "target",
        AuthorizationLevel.SAFE_WRITE, 1, SideEffectClass.LOCAL_MUTATION, RecoveryStrategy.RETRY_SAFE, IdempotencyMode.KEYED, "1")
    private val descriptor = SkillDescriptor("test.write", AuthorizationLevel.SAFE_WRITE, RecoveryStrategy.RETRY_SAFE,
        SideEffectClass.LOCAL_MUTATION, IdempotencyMode.KEYED, setOf(capability), setOf("test.PERMISSION"), true,
        ConfirmationPolicy.ALWAYS, false, 1)

    private inner class Authority : ActionExecutionAuthority {
        var open = true
        var policy = "1"
        var allowed = true
        var capabilities = true
        var permissions = true
        var presence = true
        var currentDescriptor: SkillDescriptor? = descriptor
        override fun startupBarrierOpen() = open
        override fun descriptorFor(skill: String) = currentDescriptor
        override fun currentPolicyVersion() = policy
        override fun policyAllows(request: NormalizedActionRequest) = allowed
        override fun capabilityAvailable(capability: Capability) = capabilities
        override fun permissionGranted(permission: String) = permissions
        override fun userPresent() = presence
    }

    @Test fun `claim checks every independent authority boundary and exact descriptor`() {
        val cases = listOf<Pair<ActionExecutionDenial, Authority.() -> Unit>>(
            ActionExecutionDenial.STARTUP_GATED to { open = false },
            ActionExecutionDenial.DESCRIPTOR_MISMATCH to { currentDescriptor = null },
            ActionExecutionDenial.DESCRIPTOR_MISMATCH to { currentDescriptor = descriptor.copy(argumentSchemaVersion = 2) },
            ActionExecutionDenial.POLICY_CHANGED to { policy = "2" },
            ActionExecutionDenial.POLICY_DENIED to { allowed = false },
            ActionExecutionDenial.CAPABILITY_UNAVAILABLE to { capabilities = false },
            ActionExecutionDenial.PERMISSION_DENIED to { permissions = false },
            ActionExecutionDenial.USER_PRESENCE_REQUIRED to { presence = false },
        )
        for ((expected, mutate) in cases) {
            val authority = Authority().apply(mutate)
            assertEquals(expected, ActionExecutionValidation.denyReason(request, true, authority))
        }
        assertNull(ActionExecutionValidation.denyReason(request, true, Authority()))
        assertEquals(ActionExecutionDenial.CONFIRMATION_REQUIRED, ActionExecutionValidation.denyReason(request, false, Authority()))
    }

    @Test fun `policy update or barrier close during permission checks blocks claim`() {
        val delegate = Authority()
        val changing = object : ActionExecutionAuthority by delegate {
            override fun permissionGranted(permission: String): Boolean { delegate.policy = "2"; return true }
        }
        assertEquals(ActionExecutionDenial.POLICY_CHANGED, ActionExecutionValidation.denyReason(request, true, changing))
        delegate.policy = "1"
        val closing = object : ActionExecutionAuthority by delegate {
            override fun userPresent(): Boolean { delegate.open = false; return true }
        }
        assertEquals(ActionExecutionDenial.STARTUP_GATED, ActionExecutionValidation.denyReason(request, true, closing))
    }
}

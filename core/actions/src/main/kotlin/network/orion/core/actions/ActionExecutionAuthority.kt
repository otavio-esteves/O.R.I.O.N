package network.orion.core.actions

import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.skills.metadata.SkillDescriptor

/**
 * Main-process trusted ports, supplied by actual policy/capability/permission code.
 * Never deserialize an implementation or its decisions from IPC, UI or LLM output.
 * An implementation must obtain fresh state on every call. No default grants exist.
 */
interface ActionExecutionAuthority {
    fun startupBarrierOpen(): Boolean
    fun descriptorFor(skill: String): SkillDescriptor?
    fun currentPolicyVersion(): String
    fun policyAllows(request: NormalizedActionRequest): Boolean
    fun capabilityAvailable(capability: Capability): Boolean
    fun permissionGranted(permission: String): Boolean
    fun userPresent(): Boolean
}

enum class ActionExecutionDenial {
    STARTUP_GATED, DESCRIPTOR_MISMATCH, POLICY_CHANGED, POLICY_DENIED,
    CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, USER_PRESENCE_REQUIRED, CONFIRMATION_REQUIRED,
}

/** Re-evaluated immediately before a transactional claim of the exact persisted request. */
object ActionExecutionValidation {
    fun denyReason(request: NormalizedActionRequest, requiresConfirmation: Boolean,
        authority: ActionExecutionAuthority): ActionExecutionDenial? {
        if (!authority.startupBarrierOpen()) return ActionExecutionDenial.STARTUP_GATED
        val descriptor = authority.descriptorFor(request.skill) ?: return ActionExecutionDenial.DESCRIPTOR_MISMATCH
        if (descriptor.name != request.skill || descriptor.argumentSchemaVersion != request.argumentSchemaVersion ||
            descriptor.authorizationLevel != request.authorizationLevel || descriptor.sideEffectClass != request.sideEffectClass ||
            descriptor.recoveryStrategy != request.recoveryStrategy || descriptor.idempotencyMode != request.idempotencyMode) {
            return ActionExecutionDenial.DESCRIPTOR_MISMATCH
        }
        if (authority.currentPolicyVersion() != request.policyVersion) return ActionExecutionDenial.POLICY_CHANGED
        if (!requiresConfirmation && descriptor.confirmationPolicy == ConfirmationPolicy.ALWAYS) {
            return ActionExecutionDenial.CONFIRMATION_REQUIRED
        }
        if (!authority.policyAllows(request)) return ActionExecutionDenial.POLICY_DENIED
        if (descriptor.requiredCapabilities.any { !authority.capabilityAvailable(it) }) return ActionExecutionDenial.CAPABILITY_UNAVAILABLE
        if (descriptor.requiredPermissions.any { !authority.permissionGranted(it) }) return ActionExecutionDenial.PERMISSION_DENIED
        if (descriptor.requiresUserPresence && !authority.userPresent()) return ActionExecutionDenial.USER_PRESENCE_REQUIRED
        // A policy update or barrier closing during checks cannot authorize a claim.
        if (authority.currentPolicyVersion() != request.policyVersion) return ActionExecutionDenial.POLICY_CHANGED
        if (!authority.startupBarrierOpen()) return ActionExecutionDenial.STARTUP_GATED
        return null
    }
}

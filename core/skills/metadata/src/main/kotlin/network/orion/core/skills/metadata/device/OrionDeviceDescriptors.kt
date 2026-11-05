package network.orion.core.skills.metadata.device

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Permission-free read declarations. Metadata never authorizes a mutation. */
object OrionDeviceDescriptors {
    private fun read(name: String) = SkillDescriptor(name, AuthorizationLevel.SAFE_READ,
        RecoveryStrategy.RETRY_SAFE, SideEffectClass.READ_ONLY, IdempotencyMode.NATURAL,
        emptySet(), emptySet(), false, ConfirmationPolicy.POLICY_DEPENDENT, false, 1)
    val battery = read("device.battery")
    val time = read("device.time")
    val status = read("device.status")
    val activity = read("activity.history")
}

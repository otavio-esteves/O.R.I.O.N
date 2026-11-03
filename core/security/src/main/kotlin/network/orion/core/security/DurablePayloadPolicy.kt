package network.orion.core.security

import network.orion.core.model.Sensitivity

/** Requirements, not proof that a payload was minimized, encrypted or safely persisted. */
data class DurablePayloadRequirements(
    val referenceOnly: Boolean,
    val requiresVersionedEncryption: Boolean,
)

/** Applies equally to domain events, ingress and action payloads (Architecture §13). */
object DurablePayloadPolicy {
    fun requirementsFor(sensitivity: Sensitivity, referenceSufficient: Boolean): DurablePayloadRequirements =
        DurablePayloadRequirements(
            referenceOnly = referenceSufficient,
            requiresVersionedEncryption = sensitivity == Sensitivity.SENSITIVE || sensitivity == Sensitivity.SECRET,
        )
}

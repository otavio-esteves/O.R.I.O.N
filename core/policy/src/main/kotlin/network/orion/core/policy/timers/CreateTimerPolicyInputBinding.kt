package network.orion.core.policy.timers

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import network.orion.core.commands.timers.ValidatedCreateTimer

/**
 * Equality check for an admission's exact validated input, not proof of origin or
 * consent. The Core must obtain origin from its authenticated admission boundary.
 * This in-memory domain is distinct from ActionRequest/confirmation fingerprints.
 * Correlation binds this admission's trace; it remains outside material action JSON.
 */
class CreateTimerPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CreateTimerPolicyInputBinding &&
        MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode()
    override fun toString(): String = "CreateTimerPolicyInputBinding(<redacted>)"

    companion object {
        fun from(timer: ValidatedCreateTimer): CreateTimerPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_timer_policy_admission_v1", "timer.create",
                    timer.argumentSchemaVersion.toString(), timer.timerId.toString(),
                    timer.idempotencyKey.toString(), timer.correlationId.toString(),
                    timer.normalizedTarget, timer.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try {
                CreateTimerPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical))
            } finally { canonical.fill(0) }
        }
    }
}

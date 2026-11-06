package network.orion.core.policy.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import network.orion.core.commands.memories.ValidatedCreateMemory

/**
 * Equality check for an admission's exact validated input, not proof of origin or
 * consent. The Core must obtain origin from its authenticated admission boundary.
 * This in-memory domain is distinct from ActionRequest/confirmation fingerprints.
 * Correlation binds this admission's trace; it remains outside material action JSON.
 */
class CreateMemoryPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CreateMemoryPolicyInputBinding &&
        MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode()
    override fun toString(): String = "CreateMemoryPolicyInputBinding(<redacted>)"

    companion object {
        fun from(memory: ValidatedCreateMemory): CreateMemoryPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_memory_policy_admission_v1", "memory.create",
                    memory.argumentSchemaVersion.toString(), memory.memoryId.toString(),
                    memory.idempotencyKey.toString(), memory.correlationId.toString(),
                    memory.normalizedTarget, memory.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try {
                CreateMemoryPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical))
            } finally { canonical.fill(0) }
        }
    }
}

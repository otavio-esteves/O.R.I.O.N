package network.orion.core.policy.tasks

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import network.orion.core.commands.tasks.ValidatedCreateTask

/**
 * Equality check for an admission's exact validated input, not proof of origin or
 * consent. The Core must obtain origin from its authenticated admission boundary.
 * This in-memory domain is distinct from ActionRequest/confirmation fingerprints.
 * Correlation binds this admission's trace; it remains outside material action JSON.
 */
class CreateTaskPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CreateTaskPolicyInputBinding &&
        MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode()
    override fun toString(): String = "CreateTaskPolicyInputBinding(<redacted>)"

    companion object {
        fun from(task: ValidatedCreateTask): CreateTaskPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_task_policy_admission_v1", "task.create",
                    task.argumentSchemaVersion.toString(), task.taskId.toString(),
                    task.idempotencyKey.toString(), task.correlationId.toString(),
                    task.normalizedTarget, task.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try {
                CreateTaskPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical))
            } finally { canonical.fill(0) }
        }
    }
}

package network.orion.core.policy.reminders

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import network.orion.core.commands.reminders.ValidatedCreateReminder

/**
 * Equality check for an admission's exact validated input, not proof of origin or
 * consent. The Core must obtain origin from its authenticated admission boundary.
 * This in-memory domain is distinct from ActionRequest/confirmation fingerprints.
 * Correlation binds this admission's trace; it remains outside material action JSON.
 */
class CreateReminderPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CreateReminderPolicyInputBinding &&
        MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode()
    override fun toString(): String = "CreateReminderPolicyInputBinding(<redacted>)"

    companion object {
        fun from(reminder: ValidatedCreateReminder): CreateReminderPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_reminder_policy_admission_v1", "reminder.create",
                    reminder.argumentSchemaVersion.toString(), reminder.reminderId.toString(),
                    reminder.idempotencyKey.toString(), reminder.correlationId.toString(),
                    reminder.normalizedTarget, reminder.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try {
                CreateReminderPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical))
            } finally { canonical.fill(0) }
        }
    }
}

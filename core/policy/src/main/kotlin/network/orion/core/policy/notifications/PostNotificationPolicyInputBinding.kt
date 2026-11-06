package network.orion.core.policy.notifications

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import network.orion.core.commands.notifications.ValidatedPostNotification

/**
 * Equality check for an admission's exact validated input, not proof of origin or
 * consent. The Core must obtain origin from its authenticated admission boundary.
 * This in-memory domain is distinct from ActionRequest/confirmation fingerprints.
 * Correlation binds this admission's trace; it remains outside material action JSON.
 */
class PostNotificationPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PostNotificationPolicyInputBinding &&
        MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode()
    override fun toString(): String = "PostNotificationPolicyInputBinding(<redacted>)"

    companion object {
        fun from(notification: ValidatedPostNotification): PostNotificationPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_notification_policy_admission_v1", "notification.post",
                    notification.argumentSchemaVersion.toString(), notification.requestId.toString(),
                    notification.idempotencyKey.toString(), notification.correlationId.toString(),
                    notification.normalizedTarget, notification.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try {
                PostNotificationPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical))
            } finally { canonical.fill(0) }
        }
    }
}

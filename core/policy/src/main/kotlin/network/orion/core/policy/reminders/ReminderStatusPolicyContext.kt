package network.orion.core.policy.reminders

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import network.orion.core.commands.reminders.ValidatedReminderStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability

enum class ReminderStatusAdmissionOrigin { EXPLICIT_USER_REQUEST, UNTRUSTED_CONTENT, AUTOMATION, UNKNOWN }

/** Exact equality binding, never an authentication credential or confirmation fingerprint. */
class ReminderStatusPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?) = other is ReminderStatusPolicyInputBinding && MessageDigest.isEqual(digest, other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "ReminderStatusPolicyInputBinding(<redacted>)"
    companion object {
        fun from(change: ValidatedReminderStatusChange): ReminderStatusPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_reminder_status_admission_v1", change.skillName, change.argumentSchemaVersion.toString(),
                    change.reminderId.toString(), change.idempotencyKey.toString(), change.correlationId.toString(),
                    change.normalizedTarget, change.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try { ReminderStatusPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical)) }
            finally { canonical.fill(0) }
        }
    }
}

/** MAIN's coherent current observation for ONE authenticated admission; no producer authority flags. */
class ReminderStatusPolicyContext(
    val admissionId: UUID, val inputBinding: ReminderStatusPolicyInputBinding, val revision: Long,
    val processRole: OrionProcessRole? = null,
    val origin: ReminderStatusAdmissionOrigin = ReminderStatusAdmissionOrigin.UNKNOWN,
    val startupBarrierOpen: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    val userPresence: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    capabilities: Map<Capability, OrionPolicyEvidence> = emptyMap(),
    permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
) {
    init { require(revision >= 0) { "Reminder policy revision must be nonnegative." } }
    private val capabilities = Collections.unmodifiableMap(HashMap(capabilities))
    private val permissions = Collections.unmodifiableMap(HashMap(permissions))
    internal fun capability(id: Capability) = capabilities[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun permission(id: String) = permissions[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun sameSnapshot(other: ReminderStatusPolicyContext) = admissionId == other.admissionId &&
        inputBinding == other.inputBinding && revision == other.revision && processRole == other.processRole &&
        origin == other.origin && startupBarrierOpen == other.startupBarrierOpen && userPresence == other.userPresence &&
        capabilities == other.capabilities && permissions == other.permissions
    override fun toString() = "ReminderStatusPolicyContext(<redacted>)"
}

sealed interface ReminderStatusPolicyContextRead {
    class Available(val context: ReminderStatusPolicyContext) : ReminderStatusPolicyContextRead {
        override fun toString() = "ReminderStatusPolicyContextRead.Available(<redacted>)"
    }
    data object Unavailable : ReminderStatusPolicyContextRead
}

/** Capture origin/input at the actual MAIN boundary. Reads must be fresh; observed ABA advances revision. */
fun interface ReminderStatusPolicyContextSource { fun read(): ReminderStatusPolicyContextRead }

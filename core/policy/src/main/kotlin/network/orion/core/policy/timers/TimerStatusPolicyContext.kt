package network.orion.core.policy.timers

import network.orion.core.policy.tasks.OrionPolicyEvidence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import network.orion.core.commands.timers.ValidatedTimerStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability

enum class TimerStatusAdmissionOrigin { EXPLICIT_USER_REQUEST, UNTRUSTED_CONTENT, AUTOMATION, UNKNOWN }

/** Exact equality binding, never an authentication credential or confirmation fingerprint. */
class TimerStatusPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?) = other is TimerStatusPolicyInputBinding && MessageDigest.isEqual(digest, other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "TimerStatusPolicyInputBinding(<redacted>)"
    companion object {
        fun from(change: ValidatedTimerStatusChange): TimerStatusPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_timer_status_admission_v1", change.skillName, change.argumentSchemaVersion.toString(),
                    change.timerId.toString(), change.idempotencyKey.toString(), change.correlationId.toString(),
                    change.normalizedTarget, change.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try { TimerStatusPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical)) }
            finally { canonical.fill(0) }
        }
    }
}

/** MAIN's coherent current observation for ONE authenticated admission; no producer authority flags. */
class TimerStatusPolicyContext(
    val admissionId: UUID, val inputBinding: TimerStatusPolicyInputBinding, val revision: Long,
    val processRole: OrionProcessRole? = null,
    val origin: TimerStatusAdmissionOrigin = TimerStatusAdmissionOrigin.UNKNOWN,
    val startupBarrierOpen: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    val userPresence: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    capabilities: Map<Capability, OrionPolicyEvidence> = emptyMap(),
    permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
) {
    init { require(revision >= 0) { "Timer policy revision must be nonnegative." } }
    private val capabilities = Collections.unmodifiableMap(HashMap(capabilities))
    private val permissions = Collections.unmodifiableMap(HashMap(permissions))
    internal fun capability(id: Capability) = capabilities[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun permission(id: String) = permissions[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun sameSnapshot(other: TimerStatusPolicyContext) = admissionId == other.admissionId &&
        inputBinding == other.inputBinding && revision == other.revision && processRole == other.processRole &&
        origin == other.origin && startupBarrierOpen == other.startupBarrierOpen && userPresence == other.userPresence &&
        capabilities == other.capabilities && permissions == other.permissions
    override fun toString() = "TimerStatusPolicyContext(<redacted>)"
}

sealed interface TimerStatusPolicyContextRead {
    class Available(val context: TimerStatusPolicyContext) : TimerStatusPolicyContextRead {
        override fun toString() = "TimerStatusPolicyContextRead.Available(<redacted>)"
    }
    data object Unavailable : TimerStatusPolicyContextRead
}

/** Capture origin/input at the actual MAIN boundary. Reads must be fresh; observed ABA advances revision. */
fun interface TimerStatusPolicyContextSource { fun read(): TimerStatusPolicyContextRead }

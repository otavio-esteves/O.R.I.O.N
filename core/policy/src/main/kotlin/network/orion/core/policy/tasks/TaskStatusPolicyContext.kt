package network.orion.core.policy.tasks

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import network.orion.core.commands.tasks.ValidatedTaskStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability

enum class TaskStatusAdmissionOrigin { EXPLICIT_USER_REQUEST, UNTRUSTED_CONTENT, AUTOMATION, UNKNOWN }

/** Exact equality binding, never an authentication credential or confirmation fingerprint. */
class TaskStatusPolicyInputBinding private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?) = other is TaskStatusPolicyInputBinding && MessageDigest.isEqual(digest, other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "TaskStatusPolicyInputBinding(<redacted>)"
    companion object {
        fun from(change: ValidatedTaskStatusChange): TaskStatusPolicyInputBinding {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                for (field in listOf("orion_task_status_admission_v1", change.skillName, change.argumentSchemaVersion.toString(),
                    change.taskId.toString(), change.idempotencyKey.toString(), change.correlationId.toString(),
                    change.normalizedTarget, change.canonicalNormalizedArguments)) {
                    val encoded = field.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
                }
            }
            val canonical = bytes.toByteArray()
            return try { TaskStatusPolicyInputBinding(MessageDigest.getInstance("SHA-256").digest(canonical)) }
            finally { canonical.fill(0) }
        }
    }
}

/** MAIN's coherent current observation for ONE authenticated admission; no producer authority flags. */
class TaskStatusPolicyContext(
    val admissionId: UUID, val inputBinding: TaskStatusPolicyInputBinding, val revision: Long,
    val processRole: OrionProcessRole? = null,
    val origin: TaskStatusAdmissionOrigin = TaskStatusAdmissionOrigin.UNKNOWN,
    val startupBarrierOpen: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    val userPresence: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    capabilities: Map<Capability, OrionPolicyEvidence> = emptyMap(),
    permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
) {
    init { require(revision >= 0) { "Task policy revision must be nonnegative." } }
    private val capabilities = Collections.unmodifiableMap(HashMap(capabilities))
    private val permissions = Collections.unmodifiableMap(HashMap(permissions))
    internal fun capability(id: Capability) = capabilities[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun permission(id: String) = permissions[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun sameSnapshot(other: TaskStatusPolicyContext) = admissionId == other.admissionId &&
        inputBinding == other.inputBinding && revision == other.revision && processRole == other.processRole &&
        origin == other.origin && startupBarrierOpen == other.startupBarrierOpen && userPresence == other.userPresence &&
        capabilities == other.capabilities && permissions == other.permissions
    override fun toString() = "TaskStatusPolicyContext(<redacted>)"
}

sealed interface TaskStatusPolicyContextRead {
    class Available(val context: TaskStatusPolicyContext) : TaskStatusPolicyContextRead {
        override fun toString() = "TaskStatusPolicyContextRead.Available(<redacted>)"
    }
    data object Unavailable : TaskStatusPolicyContextRead
}

/** Capture origin/input at the actual MAIN boundary. Reads must be fresh; observed ABA advances revision. */
fun interface TaskStatusPolicyContextSource { fun read(): TaskStatusPolicyContextRead }

package network.orion.core.policy.notifications

import network.orion.core.policy.tasks.OrionPolicyEvidence

import java.util.Collections
import java.util.UUID
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability

enum class PostNotificationAdmissionOrigin { EXPLICIT_USER_REQUEST, UNTRUSTED_CONTENT, AUTOMATION, UNKNOWN }

/**
 * Immutable observation supplied by trusted MAIN code, never a UI/IPC/LLM DTO.
 * An admission ID/hash is not authentication: the source must own and verify the
 * admission and its origin before constructing this observation. Foreground UI,
 * command UUIDs, OrionIntent.source and presence alone cannot establish origin.
 *
 * revision is an ephemeral state generation, not a rules/config policy version.
 * The owner must advance it for relevant transitions, including ABA, and not
 * reuse it within this source/admission lifetime. Matching observations do not
 * prove that no unobserved transition occurred or create an atomic OS snapshot.
 * Read the actual operational barrier, not the eligibility of a health snapshot.
 * Unknown evidence stays unavailable. Provide only bounded technical metadata.
 */
class PostNotificationPolicyContext(
    val admissionId: UUID,
    val inputBinding: PostNotificationPolicyInputBinding,
    val revision: Long,
    val processRole: OrionProcessRole? = null,
    val origin: PostNotificationAdmissionOrigin = PostNotificationAdmissionOrigin.UNKNOWN,
    val startupBarrierOpen: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    val userPresence: OrionPolicyEvidence = OrionPolicyEvidence.UNAVAILABLE,
    capabilities: Map<Capability, OrionPolicyEvidence> = emptyMap(),
    permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
) {
    init { require(revision >= 0) { "Policy state revision must be nonnegative." } }
    private val capabilities = Collections.unmodifiableMap(HashMap(capabilities))
    private val permissions = Collections.unmodifiableMap(HashMap(permissions))

    internal fun capability(id: Capability): OrionPolicyEvidence = capabilities[id] ?: OrionPolicyEvidence.UNAVAILABLE
    internal fun permission(id: String): OrionPolicyEvidence = permissions[id] ?: OrionPolicyEvidence.UNAVAILABLE

    internal fun sameSnapshot(other: PostNotificationPolicyContext): Boolean = admissionId == other.admissionId &&
        inputBinding == other.inputBinding && revision == other.revision && processRole == other.processRole &&
        origin == other.origin && startupBarrierOpen == other.startupBarrierOpen && userPresence == other.userPresence &&
        capabilities == other.capabilities && permissions == other.permissions

    override fun toString(): String = "PostNotificationPolicyContext(<redacted>)"
}

sealed interface PostNotificationPolicyContextRead {
    class Available(val context: PostNotificationPolicyContext) : PostNotificationPolicyContextRead {
        override fun toString(): String = "PostNotificationPolicyContextRead.Available(<redacted>)"
    }
    data object Unavailable : PostNotificationPolicyContextRead
}

/**
 * Trusted MAIN port scoped to ONE authenticated admission, not a global 'current
 * UI' context or lookup whose UUIDs supplied by a command are treated as proof.
 * Capture the normalized input binding at that real admission boundary, then
 * return a fresh bounded, coherent observation of Core-owned state on every read.
 * Advance its generation for relevant observed state transitions. No business effects,
 * network, client authority flags or arbitrary error text cross this port.
 *
 * There is intentionally no production adapter in this increment. Future wiring
 * must revalidate execution conditions inside writer admission, preserve lock
 * order (barrier snapshots must not acquire its admission monitor), and never use
 * this preparation decision as an execution grant or a confirmation receipt.
 */
fun interface PostNotificationPolicyContextSource {
    fun read(): PostNotificationPolicyContextRead
}

object UnavailablePostNotificationPolicyContextSource : PostNotificationPolicyContextSource {
    override fun read(): PostNotificationPolicyContextRead = PostNotificationPolicyContextRead.Unavailable
}

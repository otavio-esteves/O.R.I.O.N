package network.orion.core.policy.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.concurrent.CancellationException
import network.orion.core.commands.memories.ValidatedMemoryStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.*
import network.orion.core.skills.metadata.memories.OrionMemoryStatusDescriptors

enum class MemoryStatusPolicyReason {
    CONTEXT_UNAVAILABLE, SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE, PROCESS_NOT_MAIN,
    INPUT_MISMATCH, ORIGIN_UNAVAILABLE, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    STATE_UNAVAILABLE, USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

sealed interface MemoryStatusPolicyDecision {
    data class Denied(val reason: MemoryStatusPolicyReason) : MemoryStatusPolicyDecision
    data class Unavailable(val reason: MemoryStatusPolicyReason) : MemoryStatusPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : MemoryStatusPolicyDecision {
        override fun toString() = "MemoryStatusPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific complete/cancel preparation policy, not execution authority or consent.
 * A negative STARTUP_GATED observation must preserve durable ingress behind its gate.
 * Two matching observations are advisory, not an atomic OS snapshot; execution
 * revalidates authority/consent inside writer admission. No preferences waive v1 confirmation.
 */
class OrionMemoryStatusPolicy(private val source: MemoryStatusPolicyContextSource = MemoryStatusPolicyContextSource {
    MemoryStatusPolicyContextRead.Unavailable
}) {
    fun evaluate(change: ValidatedMemoryStatusChange): MemoryStatusPolicyDecision {
        val first = observe()
        if (first is Observation.Failed) return MemoryStatusPolicyDecision.Unavailable(first.reason)
        val context = (first as Observation.Ready).context
        if (context.processRole == null) return unavailable(MemoryStatusPolicyReason.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(MemoryStatusPolicyReason.PROCESS_NOT_MAIN)
        if (context.inputBinding != MemoryStatusPolicyInputBinding.from(change)) return denied(MemoryStatusPolicyReason.INPUT_MISMATCH)
        val descriptor = OrionMemoryStatusDescriptors.forName(change.skillName) ?: return denied(MemoryStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        if (descriptor.authorizationLevel != AuthorizationLevel.DESTRUCTIVE || descriptor.sideEffectClass != SideEffectClass.DESTRUCTIVE ||
            descriptor.argumentSchemaVersion != change.argumentSchemaVersion || descriptor.confirmationPolicy != ConfirmationPolicy.ALWAYS ||
            !descriptor.requiresUserPresence) return denied(MemoryStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        when (context.origin) {
            MemoryStatusAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            MemoryStatusAdmissionOrigin.UNKNOWN -> return unavailable(MemoryStatusPolicyReason.ORIGIN_UNAVAILABLE)
            else -> return denied(MemoryStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, MemoryStatusPolicyReason.STARTUP_GATED)?.let { return it }
        for (id in descriptor.requiredCapabilities)
            requirement(context.capability(id), MemoryStatusPolicyReason.CAPABILITY_UNAVAILABLE)?.let { return it }
        for (id in descriptor.requiredPermissions)
            requirement(context.permission(id), MemoryStatusPolicyReason.PERMISSION_DENIED)?.let { return it }
        requirement(context.userPresence, MemoryStatusPolicyReason.USER_NOT_PRESENT)?.let { return it }
        val second = observe()
        if (second is Observation.Failed) return unavailable(second.reason)
        if (!context.sameSnapshot((second as Observation.Ready).context)) return unavailable(MemoryStatusPolicyReason.CONTEXT_CHANGED)
        return MemoryStatusPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, reason: MemoryStatusPolicyReason): MemoryStatusPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(reason)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(MemoryStatusPolicyReason.STATE_UNAVAILABLE)
    }
    private sealed interface Observation {
        class Ready(val context: MemoryStatusPolicyContext) : Observation
        data class Failed(val reason: MemoryStatusPolicyReason) : Observation
    }
    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is MemoryStatusPolicyContextRead.Available -> Observation.Ready(read.context)
            MemoryStatusPolicyContextRead.Unavailable -> Observation.Failed(MemoryStatusPolicyReason.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (_: Exception) { Observation.Failed(MemoryStatusPolicyReason.SOURCE_FAILURE) }
    private fun denied(reason: MemoryStatusPolicyReason) = MemoryStatusPolicyDecision.Denied(reason)
    private fun unavailable(reason: MemoryStatusPolicyReason) = MemoryStatusPolicyDecision.Unavailable(reason)
    companion object { const val POLICY_VERSION = "orion_memory_status_policy_v1" }
}

package network.orion.core.policy.reminders

import java.util.concurrent.CancellationException
import network.orion.core.commands.reminders.ValidatedReminderStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.*
import network.orion.core.skills.metadata.reminders.OrionReminderStatusDescriptors

enum class ReminderStatusPolicyReason {
    CONTEXT_UNAVAILABLE, SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE, PROCESS_NOT_MAIN,
    INPUT_MISMATCH, ORIGIN_UNAVAILABLE, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    STATE_UNAVAILABLE, USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

sealed interface ReminderStatusPolicyDecision {
    data class Denied(val reason: ReminderStatusPolicyReason) : ReminderStatusPolicyDecision
    data class Unavailable(val reason: ReminderStatusPolicyReason) : ReminderStatusPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : ReminderStatusPolicyDecision {
        override fun toString() = "ReminderStatusPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific snooze/cancel preparation policy, not execution authority or consent.
 * A negative STARTUP_GATED observation must preserve durable ingress behind its gate.
 * Two matching observations are advisory, not an atomic OS snapshot; execution
 * revalidates authority/consent inside writer admission. No preferences waive v1 confirmation.
 */
class OrionReminderStatusPolicy(private val source: ReminderStatusPolicyContextSource = ReminderStatusPolicyContextSource {
    ReminderStatusPolicyContextRead.Unavailable
}) {
    fun evaluate(change: ValidatedReminderStatusChange): ReminderStatusPolicyDecision {
        val first = observe()
        if (first is Observation.Failed) return ReminderStatusPolicyDecision.Unavailable(first.reason)
        val context = (first as Observation.Ready).context
        if (context.processRole == null) return unavailable(ReminderStatusPolicyReason.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(ReminderStatusPolicyReason.PROCESS_NOT_MAIN)
        if (context.inputBinding != ReminderStatusPolicyInputBinding.from(change)) return denied(ReminderStatusPolicyReason.INPUT_MISMATCH)
        val descriptor = OrionReminderStatusDescriptors.forName(change.skillName) ?: return denied(ReminderStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        if (descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION ||
            descriptor.argumentSchemaVersion != change.argumentSchemaVersion || descriptor.confirmationPolicy != ConfirmationPolicy.ALWAYS ||
            !descriptor.requiresUserPresence) return denied(ReminderStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        when (context.origin) {
            ReminderStatusAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            ReminderStatusAdmissionOrigin.UNKNOWN -> return unavailable(ReminderStatusPolicyReason.ORIGIN_UNAVAILABLE)
            else -> return denied(ReminderStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, ReminderStatusPolicyReason.STARTUP_GATED)?.let { return it }
        for (id in descriptor.requiredCapabilities)
            requirement(context.capability(id), ReminderStatusPolicyReason.CAPABILITY_UNAVAILABLE)?.let { return it }
        for (id in descriptor.requiredPermissions)
            requirement(context.permission(id), ReminderStatusPolicyReason.PERMISSION_DENIED)?.let { return it }
        requirement(context.userPresence, ReminderStatusPolicyReason.USER_NOT_PRESENT)?.let { return it }
        val second = observe()
        if (second is Observation.Failed) return unavailable(second.reason)
        if (!context.sameSnapshot((second as Observation.Ready).context)) return unavailable(ReminderStatusPolicyReason.CONTEXT_CHANGED)
        return ReminderStatusPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, reason: ReminderStatusPolicyReason): ReminderStatusPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(reason)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(ReminderStatusPolicyReason.STATE_UNAVAILABLE)
    }
    private sealed interface Observation {
        class Ready(val context: ReminderStatusPolicyContext) : Observation
        data class Failed(val reason: ReminderStatusPolicyReason) : Observation
    }
    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is ReminderStatusPolicyContextRead.Available -> Observation.Ready(read.context)
            ReminderStatusPolicyContextRead.Unavailable -> Observation.Failed(ReminderStatusPolicyReason.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (_: Exception) { Observation.Failed(ReminderStatusPolicyReason.SOURCE_FAILURE) }
    private fun denied(reason: ReminderStatusPolicyReason) = ReminderStatusPolicyDecision.Denied(reason)
    private fun unavailable(reason: ReminderStatusPolicyReason) = ReminderStatusPolicyDecision.Unavailable(reason)
    companion object { const val POLICY_VERSION = "orion_reminder_status_policy_v1" }
}

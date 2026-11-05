package network.orion.core.policy.timers

import network.orion.core.policy.tasks.OrionPolicyEvidence

import java.util.concurrent.CancellationException
import network.orion.core.commands.timers.ValidatedTimerStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.*
import network.orion.core.skills.metadata.timers.OrionTimerStatusDescriptors

enum class TimerStatusPolicyReason {
    CONTEXT_UNAVAILABLE, SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE, PROCESS_NOT_MAIN,
    INPUT_MISMATCH, ORIGIN_UNAVAILABLE, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    STATE_UNAVAILABLE, USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

sealed interface TimerStatusPolicyDecision {
    data class Denied(val reason: TimerStatusPolicyReason) : TimerStatusPolicyDecision
    data class Unavailable(val reason: TimerStatusPolicyReason) : TimerStatusPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : TimerStatusPolicyDecision {
        override fun toString() = "TimerStatusPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific complete/cancel preparation policy, not execution authority or consent.
 * A negative STARTUP_GATED observation must preserve durable ingress behind its gate.
 * Two matching observations are advisory, not an atomic OS snapshot; execution
 * revalidates authority/consent inside writer admission. No preferences waive v1 confirmation.
 */
class OrionTimerStatusPolicy(private val source: TimerStatusPolicyContextSource = TimerStatusPolicyContextSource {
    TimerStatusPolicyContextRead.Unavailable
}) {
    fun evaluate(change: ValidatedTimerStatusChange): TimerStatusPolicyDecision {
        val first = observe()
        if (first is Observation.Failed) return TimerStatusPolicyDecision.Unavailable(first.reason)
        val context = (first as Observation.Ready).context
        if (context.processRole == null) return unavailable(TimerStatusPolicyReason.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(TimerStatusPolicyReason.PROCESS_NOT_MAIN)
        if (context.inputBinding != TimerStatusPolicyInputBinding.from(change)) return denied(TimerStatusPolicyReason.INPUT_MISMATCH)
        val descriptor = OrionTimerStatusDescriptors.forName(change.skillName) ?: return denied(TimerStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        if (descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION ||
            descriptor.argumentSchemaVersion != change.argumentSchemaVersion || descriptor.confirmationPolicy != ConfirmationPolicy.ALWAYS ||
            !descriptor.requiresUserPresence) return denied(TimerStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        when (context.origin) {
            TimerStatusAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            TimerStatusAdmissionOrigin.UNKNOWN -> return unavailable(TimerStatusPolicyReason.ORIGIN_UNAVAILABLE)
            else -> return denied(TimerStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, TimerStatusPolicyReason.STARTUP_GATED)?.let { return it }
        for (id in descriptor.requiredCapabilities)
            requirement(context.capability(id), TimerStatusPolicyReason.CAPABILITY_UNAVAILABLE)?.let { return it }
        for (id in descriptor.requiredPermissions)
            requirement(context.permission(id), TimerStatusPolicyReason.PERMISSION_DENIED)?.let { return it }
        requirement(context.userPresence, TimerStatusPolicyReason.USER_NOT_PRESENT)?.let { return it }
        val second = observe()
        if (second is Observation.Failed) return unavailable(second.reason)
        if (!context.sameSnapshot((second as Observation.Ready).context)) return unavailable(TimerStatusPolicyReason.CONTEXT_CHANGED)
        return TimerStatusPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, reason: TimerStatusPolicyReason): TimerStatusPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(reason)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(TimerStatusPolicyReason.STATE_UNAVAILABLE)
    }
    private sealed interface Observation {
        class Ready(val context: TimerStatusPolicyContext) : Observation
        data class Failed(val reason: TimerStatusPolicyReason) : Observation
    }
    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is TimerStatusPolicyContextRead.Available -> Observation.Ready(read.context)
            TimerStatusPolicyContextRead.Unavailable -> Observation.Failed(TimerStatusPolicyReason.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (_: Exception) { Observation.Failed(TimerStatusPolicyReason.SOURCE_FAILURE) }
    private fun denied(reason: TimerStatusPolicyReason) = TimerStatusPolicyDecision.Denied(reason)
    private fun unavailable(reason: TimerStatusPolicyReason) = TimerStatusPolicyDecision.Unavailable(reason)
    companion object { const val POLICY_VERSION = "orion_timer_status_policy_v1" }
}

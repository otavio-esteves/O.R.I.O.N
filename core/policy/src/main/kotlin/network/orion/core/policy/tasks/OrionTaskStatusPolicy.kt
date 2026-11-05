package network.orion.core.policy.tasks

import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.ValidatedTaskStatusChange
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.*
import network.orion.core.skills.metadata.tasks.OrionTaskStatusDescriptors

enum class TaskStatusPolicyReason {
    CONTEXT_UNAVAILABLE, SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE, PROCESS_NOT_MAIN,
    INPUT_MISMATCH, ORIGIN_UNAVAILABLE, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    STATE_UNAVAILABLE, USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

sealed interface TaskStatusPolicyDecision {
    data class Denied(val reason: TaskStatusPolicyReason) : TaskStatusPolicyDecision
    data class Unavailable(val reason: TaskStatusPolicyReason) : TaskStatusPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : TaskStatusPolicyDecision {
        override fun toString() = "TaskStatusPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific complete/cancel preparation policy, not execution authority or consent.
 * A negative STARTUP_GATED observation must preserve durable ingress behind its gate.
 * Two matching observations are advisory, not an atomic OS snapshot; execution
 * revalidates authority/consent inside writer admission. No preferences waive v1 confirmation.
 */
class OrionTaskStatusPolicy(private val source: TaskStatusPolicyContextSource = TaskStatusPolicyContextSource {
    TaskStatusPolicyContextRead.Unavailable
}) {
    fun evaluate(change: ValidatedTaskStatusChange): TaskStatusPolicyDecision {
        val first = observe()
        if (first is Observation.Failed) return TaskStatusPolicyDecision.Unavailable(first.reason)
        val context = (first as Observation.Ready).context
        if (context.processRole == null) return unavailable(TaskStatusPolicyReason.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(TaskStatusPolicyReason.PROCESS_NOT_MAIN)
        if (context.inputBinding != TaskStatusPolicyInputBinding.from(change)) return denied(TaskStatusPolicyReason.INPUT_MISMATCH)
        val descriptor = OrionTaskStatusDescriptors.forName(change.skillName) ?: return denied(TaskStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        if (descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION ||
            descriptor.argumentSchemaVersion != change.argumentSchemaVersion || descriptor.confirmationPolicy != ConfirmationPolicy.ALWAYS ||
            !descriptor.requiresUserPresence) return denied(TaskStatusPolicyReason.UNSUPPORTED_DESCRIPTOR)
        when (context.origin) {
            TaskStatusAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            TaskStatusAdmissionOrigin.UNKNOWN -> return unavailable(TaskStatusPolicyReason.ORIGIN_UNAVAILABLE)
            else -> return denied(TaskStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, TaskStatusPolicyReason.STARTUP_GATED)?.let { return it }
        for (id in descriptor.requiredCapabilities)
            requirement(context.capability(id), TaskStatusPolicyReason.CAPABILITY_UNAVAILABLE)?.let { return it }
        for (id in descriptor.requiredPermissions)
            requirement(context.permission(id), TaskStatusPolicyReason.PERMISSION_DENIED)?.let { return it }
        requirement(context.userPresence, TaskStatusPolicyReason.USER_NOT_PRESENT)?.let { return it }
        val second = observe()
        if (second is Observation.Failed) return unavailable(second.reason)
        if (!context.sameSnapshot((second as Observation.Ready).context)) return unavailable(TaskStatusPolicyReason.CONTEXT_CHANGED)
        return TaskStatusPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, reason: TaskStatusPolicyReason): TaskStatusPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(reason)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(TaskStatusPolicyReason.STATE_UNAVAILABLE)
    }
    private sealed interface Observation {
        class Ready(val context: TaskStatusPolicyContext) : Observation
        data class Failed(val reason: TaskStatusPolicyReason) : Observation
    }
    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is TaskStatusPolicyContextRead.Available -> Observation.Ready(read.context)
            TaskStatusPolicyContextRead.Unavailable -> Observation.Failed(TaskStatusPolicyReason.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (_: Exception) { Observation.Failed(TaskStatusPolicyReason.SOURCE_FAILURE) }
    private fun denied(reason: TaskStatusPolicyReason) = TaskStatusPolicyDecision.Denied(reason)
    private fun unavailable(reason: TaskStatusPolicyReason) = TaskStatusPolicyDecision.Unavailable(reason)
    companion object { const val POLICY_VERSION = "orion_task_status_policy_v1" }
}

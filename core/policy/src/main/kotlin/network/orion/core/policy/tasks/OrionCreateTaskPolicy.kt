package network.orion.core.policy.tasks

import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor

enum class CreateTaskPolicyDenial {
    PROCESS_NOT_MAIN, ADMISSION_INPUT_MISMATCH, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

enum class CreateTaskPolicyUnavailable {
    CONTEXT_UNAVAILABLE, CONTEXT_SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE,
    ORIGIN_UNAVAILABLE, STARTUP_UNAVAILABLE, PRESENCE_UNAVAILABLE,
    CAPABILITY_EVIDENCE_UNAVAILABLE, PERMISSION_EVIDENCE_UNAVAILABLE,
}

/**
 * Advisory preparation results, never execution authority or proof of consent.
 * Negative results are observations, not terminal Action/Ingress states: in
 * particular STARTUP_GATED must leave durable ingress behind its barrier.
 */
sealed interface CreateTaskPolicyDecision {
    data class Denied(val reason: CreateTaskPolicyDenial) : CreateTaskPolicyDecision
    data class Unavailable(val reason: CreateTaskPolicyUnavailable) : CreateTaskPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : CreateTaskPolicyDecision {
        override fun toString(): String = "CreateTaskPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific deterministic policy for task.create schema v1. MAIN supplies a source
 * bound to the real authenticated admission; the command supplies no authority.
 * Both successful observations must agree. Differing snapshots/generations are
 * rejected; two reads are not atomic and cannot detect ABA if the source reuses
 * its generation. Execution must still revalidate inside writer admission.
 *
 * v1 always requires a later confirmation bound to an actual ActionRequest,
 * including its actionId, normalized payload and policy version. This method does
 * not construct that request/receipt, grant permissions, activate capabilities,
 * open the barrier, persist, dispatch or execute. No confirmation preference is
 * implemented. Any relevant future rule/config change must use a new rules version.
 */
class OrionCreateTaskPolicy(
    private val source: CreateTaskPolicyContextSource = UnavailableCreateTaskPolicyContextSource,
) {
    fun evaluate(task: ValidatedCreateTask): CreateTaskPolicyDecision {
        val initial = observe()
        if (initial is Observation.Failed) return CreateTaskPolicyDecision.Unavailable(initial.reason)
        val context = (initial as Observation.Ready).context
        if (context.processRole == null) return unavailable(CreateTaskPolicyUnavailable.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(CreateTaskPolicyDenial.PROCESS_NOT_MAIN)
        if (context.inputBinding != CreateTaskPolicyInputBinding.from(task))
            return denied(CreateTaskPolicyDenial.ADMISSION_INPUT_MISMATCH)

        val descriptor = OrionCreateTaskDescriptor.value
        if (descriptor.name != "task.create" || descriptor.argumentSchemaVersion != task.argumentSchemaVersion ||
            descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION)
            return denied(CreateTaskPolicyDenial.UNSUPPORTED_DESCRIPTOR)

        when (context.origin) {
            CreateTaskAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            CreateTaskAdmissionOrigin.UNKNOWN -> return unavailable(CreateTaskPolicyUnavailable.ORIGIN_UNAVAILABLE)
            else -> return denied(CreateTaskPolicyDenial.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, CreateTaskPolicyDenial.STARTUP_GATED,
            CreateTaskPolicyUnavailable.STARTUP_UNAVAILABLE)?.let { return it }
        for (capability in descriptor.requiredCapabilities) {
            requirement(context.capability(capability), CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE,
                CreateTaskPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // v1 requires no Android permissions. Empty metadata grants none, and never skips capabilities.
        for (permission in descriptor.requiredPermissions) {
            requirement(context.permission(permission), CreateTaskPolicyDenial.PERMISSION_DENIED,
                CreateTaskPolicyUnavailable.PERMISSION_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // This first user-driven policy is deliberately stricter than optional descriptor presence.
        requirement(context.userPresence, CreateTaskPolicyDenial.USER_NOT_PRESENT,
            CreateTaskPolicyUnavailable.PRESENCE_UNAVAILABLE)?.let { return it }

        val latest = observe()
        if (latest is Observation.Failed) return unavailable(latest.reason)
        if (!context.sameSnapshot((latest as Observation.Ready).context))
            return unavailable(CreateTaskPolicyUnavailable.CONTEXT_CHANGED)
        return CreateTaskPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, denial: CreateTaskPolicyDenial,
        missing: CreateTaskPolicyUnavailable): CreateTaskPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(denial)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(missing)
    }

    private sealed interface Observation {
        class Ready(val context: CreateTaskPolicyContext) : Observation
        class Failed(val reason: CreateTaskPolicyUnavailable) : Observation
    }

    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is CreateTaskPolicyContextRead.Available -> Observation.Ready(read.context)
            CreateTaskPolicyContextRead.Unavailable -> Observation.Failed(CreateTaskPolicyUnavailable.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        // Source exceptions can contain user data. No exception text crosses the policy boundary.
        Observation.Failed(CreateTaskPolicyUnavailable.CONTEXT_SOURCE_FAILURE)
    }

    private fun denied(reason: CreateTaskPolicyDenial) = CreateTaskPolicyDecision.Denied(reason)
    private fun unavailable(reason: CreateTaskPolicyUnavailable) = CreateTaskPolicyDecision.Unavailable(reason)

    companion object {
        /** Fixed rules, not a client/config field or an ephemeral state revision. */
        const val POLICY_VERSION = "orion_task_create_policy_v1"
    }
}

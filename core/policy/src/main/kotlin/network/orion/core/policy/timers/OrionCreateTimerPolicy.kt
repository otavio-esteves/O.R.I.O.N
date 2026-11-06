package network.orion.core.policy.timers

import network.orion.core.policy.tasks.OrionPolicyEvidence

import java.util.concurrent.CancellationException
import network.orion.core.commands.timers.ValidatedCreateTimer
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor

enum class CreateTimerPolicyDenial {
    PROCESS_NOT_MAIN, ADMISSION_INPUT_MISMATCH, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

enum class CreateTimerPolicyUnavailable {
    CONTEXT_UNAVAILABLE, CONTEXT_SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE,
    ORIGIN_UNAVAILABLE, STARTUP_UNAVAILABLE, PRESENCE_UNAVAILABLE,
    CAPABILITY_EVIDENCE_UNAVAILABLE, PERMISSION_EVIDENCE_UNAVAILABLE,
}

/**
 * Advisory preparation results, never execution authority or proof of consent.
 * Negative results are observations, not terminal Action/Ingress states: in
 * particular STARTUP_GATED must leave durable ingress behind its barrier.
 */
sealed interface CreateTimerPolicyDecision {
    data class Denied(val reason: CreateTimerPolicyDenial) : CreateTimerPolicyDecision
    data class Unavailable(val reason: CreateTimerPolicyUnavailable) : CreateTimerPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : CreateTimerPolicyDecision {
        override fun toString(): String = "CreateTimerPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific deterministic policy for timer.create schema v1. MAIN supplies a source
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
class OrionCreateTimerPolicy(
    private val source: CreateTimerPolicyContextSource = UnavailableCreateTimerPolicyContextSource,
) {
    fun evaluate(timer: ValidatedCreateTimer): CreateTimerPolicyDecision {
        val initial = observe()
        if (initial is Observation.Failed) return CreateTimerPolicyDecision.Unavailable(initial.reason)
        val context = (initial as Observation.Ready).context
        if (context.processRole == null) return unavailable(CreateTimerPolicyUnavailable.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(CreateTimerPolicyDenial.PROCESS_NOT_MAIN)
        if (context.inputBinding != CreateTimerPolicyInputBinding.from(timer))
            return denied(CreateTimerPolicyDenial.ADMISSION_INPUT_MISMATCH)

        val descriptor = OrionCreateTimerDescriptor.value
        if (descriptor.name != "timer.create" || descriptor.argumentSchemaVersion != timer.argumentSchemaVersion ||
            descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION)
            return denied(CreateTimerPolicyDenial.UNSUPPORTED_DESCRIPTOR)

        when (context.origin) {
            CreateTimerAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            CreateTimerAdmissionOrigin.UNKNOWN -> return unavailable(CreateTimerPolicyUnavailable.ORIGIN_UNAVAILABLE)
            else -> return denied(CreateTimerPolicyDenial.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, CreateTimerPolicyDenial.STARTUP_GATED,
            CreateTimerPolicyUnavailable.STARTUP_UNAVAILABLE)?.let { return it }
        for (capability in descriptor.requiredCapabilities) {
            requirement(context.capability(capability), CreateTimerPolicyDenial.CAPABILITY_UNAVAILABLE,
                CreateTimerPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // v1 requires no Android permissions. Empty metadata grants none, and never skips capabilities.
        for (permission in descriptor.requiredPermissions) {
            requirement(context.permission(permission), CreateTimerPolicyDenial.PERMISSION_DENIED,
                CreateTimerPolicyUnavailable.PERMISSION_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // This first user-driven policy is deliberately stricter than optional descriptor presence.
        requirement(context.userPresence, CreateTimerPolicyDenial.USER_NOT_PRESENT,
            CreateTimerPolicyUnavailable.PRESENCE_UNAVAILABLE)?.let { return it }

        val latest = observe()
        if (latest is Observation.Failed) return unavailable(latest.reason)
        if (!context.sameSnapshot((latest as Observation.Ready).context))
            return unavailable(CreateTimerPolicyUnavailable.CONTEXT_CHANGED)
        return CreateTimerPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, denial: CreateTimerPolicyDenial,
        missing: CreateTimerPolicyUnavailable): CreateTimerPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(denial)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(missing)
    }

    private sealed interface Observation {
        class Ready(val context: CreateTimerPolicyContext) : Observation
        class Failed(val reason: CreateTimerPolicyUnavailable) : Observation
    }

    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is CreateTimerPolicyContextRead.Available -> Observation.Ready(read.context)
            CreateTimerPolicyContextRead.Unavailable -> Observation.Failed(CreateTimerPolicyUnavailable.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        // Source exceptions can contain user data. No exception text crosses the policy boundary.
        Observation.Failed(CreateTimerPolicyUnavailable.CONTEXT_SOURCE_FAILURE)
    }

    private fun denied(reason: CreateTimerPolicyDenial) = CreateTimerPolicyDecision.Denied(reason)
    private fun unavailable(reason: CreateTimerPolicyUnavailable) = CreateTimerPolicyDecision.Unavailable(reason)

    companion object {
        /** Fixed rules, not a client/config field or an ephemeral state revision. */
        const val POLICY_VERSION = "orion_timer_create_policy_v1"
    }
}

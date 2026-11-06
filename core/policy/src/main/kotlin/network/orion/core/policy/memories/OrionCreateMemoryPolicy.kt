package network.orion.core.policy.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.concurrent.CancellationException
import network.orion.core.commands.memories.ValidatedCreateMemory
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor

enum class CreateMemoryPolicyDenial {
    PROCESS_NOT_MAIN, ADMISSION_INPUT_MISMATCH, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

enum class CreateMemoryPolicyUnavailable {
    CONTEXT_UNAVAILABLE, CONTEXT_SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE,
    ORIGIN_UNAVAILABLE, STARTUP_UNAVAILABLE, PRESENCE_UNAVAILABLE,
    CAPABILITY_EVIDENCE_UNAVAILABLE, PERMISSION_EVIDENCE_UNAVAILABLE,
}

/**
 * Advisory preparation results, never execution authority or proof of consent.
 * Negative results are observations, not terminal Action/Ingress states: in
 * particular STARTUP_GATED must leave durable ingress behind its barrier.
 */
sealed interface CreateMemoryPolicyDecision {
    data class Denied(val reason: CreateMemoryPolicyDenial) : CreateMemoryPolicyDecision
    data class Unavailable(val reason: CreateMemoryPolicyUnavailable) : CreateMemoryPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : CreateMemoryPolicyDecision {
        override fun toString(): String = "CreateMemoryPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific deterministic policy for memory.create schema v1. MAIN supplies a source
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
class OrionCreateMemoryPolicy(
    private val source: CreateMemoryPolicyContextSource = UnavailableCreateMemoryPolicyContextSource,
) {
    fun evaluate(memory: ValidatedCreateMemory): CreateMemoryPolicyDecision {
        val initial = observe()
        if (initial is Observation.Failed) return CreateMemoryPolicyDecision.Unavailable(initial.reason)
        val context = (initial as Observation.Ready).context
        if (context.processRole == null) return unavailable(CreateMemoryPolicyUnavailable.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(CreateMemoryPolicyDenial.PROCESS_NOT_MAIN)
        if (context.inputBinding != CreateMemoryPolicyInputBinding.from(memory))
            return denied(CreateMemoryPolicyDenial.ADMISSION_INPUT_MISMATCH)

        val descriptor = OrionCreateMemoryDescriptor.value
        if (descriptor.name != "memory.create" || descriptor.argumentSchemaVersion != memory.argumentSchemaVersion ||
            descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION)
            return denied(CreateMemoryPolicyDenial.UNSUPPORTED_DESCRIPTOR)

        when (context.origin) {
            CreateMemoryAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            CreateMemoryAdmissionOrigin.UNKNOWN -> return unavailable(CreateMemoryPolicyUnavailable.ORIGIN_UNAVAILABLE)
            else -> return denied(CreateMemoryPolicyDenial.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, CreateMemoryPolicyDenial.STARTUP_GATED,
            CreateMemoryPolicyUnavailable.STARTUP_UNAVAILABLE)?.let { return it }
        for (capability in descriptor.requiredCapabilities) {
            requirement(context.capability(capability), CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE,
                CreateMemoryPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // v1 requires no Android permissions. Empty metadata grants none, and never skips capabilities.
        for (permission in descriptor.requiredPermissions) {
            requirement(context.permission(permission), CreateMemoryPolicyDenial.PERMISSION_DENIED,
                CreateMemoryPolicyUnavailable.PERMISSION_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // This first user-driven policy is deliberately stricter than optional descriptor presence.
        requirement(context.userPresence, CreateMemoryPolicyDenial.USER_NOT_PRESENT,
            CreateMemoryPolicyUnavailable.PRESENCE_UNAVAILABLE)?.let { return it }

        val latest = observe()
        if (latest is Observation.Failed) return unavailable(latest.reason)
        if (!context.sameSnapshot((latest as Observation.Ready).context))
            return unavailable(CreateMemoryPolicyUnavailable.CONTEXT_CHANGED)
        return CreateMemoryPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, denial: CreateMemoryPolicyDenial,
        missing: CreateMemoryPolicyUnavailable): CreateMemoryPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(denial)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(missing)
    }

    private sealed interface Observation {
        class Ready(val context: CreateMemoryPolicyContext) : Observation
        class Failed(val reason: CreateMemoryPolicyUnavailable) : Observation
    }

    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is CreateMemoryPolicyContextRead.Available -> Observation.Ready(read.context)
            CreateMemoryPolicyContextRead.Unavailable -> Observation.Failed(CreateMemoryPolicyUnavailable.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        // Source exceptions can contain user data. No exception text crosses the policy boundary.
        Observation.Failed(CreateMemoryPolicyUnavailable.CONTEXT_SOURCE_FAILURE)
    }

    private fun denied(reason: CreateMemoryPolicyDenial) = CreateMemoryPolicyDecision.Denied(reason)
    private fun unavailable(reason: CreateMemoryPolicyUnavailable) = CreateMemoryPolicyDecision.Unavailable(reason)

    companion object {
        /** Fixed rules, not a client/config field or an ephemeral state revision. */
        const val POLICY_VERSION = "orion_memory_create_policy_v1"
    }
}

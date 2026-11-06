package network.orion.core.policy.reminders

import java.util.concurrent.CancellationException
import network.orion.core.commands.reminders.ValidatedCreateReminder
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor

enum class CreateReminderPolicyDenial {
    PROCESS_NOT_MAIN, ADMISSION_INPUT_MISMATCH, ORIGIN_NOT_USER_REQUEST, STARTUP_GATED,
    USER_NOT_PRESENT, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED_DESCRIPTOR,
}

enum class CreateReminderPolicyUnavailable {
    CONTEXT_UNAVAILABLE, CONTEXT_SOURCE_FAILURE, CONTEXT_CHANGED, PROCESS_UNAVAILABLE,
    ORIGIN_UNAVAILABLE, STARTUP_UNAVAILABLE, PRESENCE_UNAVAILABLE,
    CAPABILITY_EVIDENCE_UNAVAILABLE, PERMISSION_EVIDENCE_UNAVAILABLE,
}

/**
 * Advisory preparation results, never execution authority or proof of consent.
 * Negative results are observations, not terminal Action/Ingress states: in
 * particular STARTUP_GATED must leave durable ingress behind its barrier.
 */
sealed interface CreateReminderPolicyDecision {
    data class Denied(val reason: CreateReminderPolicyDenial) : CreateReminderPolicyDecision
    data class Unavailable(val reason: CreateReminderPolicyUnavailable) : CreateReminderPolicyDecision
    class NeedsConfirmation internal constructor(val policyVersion: String) : CreateReminderPolicyDecision {
        override fun toString(): String = "CreateReminderPolicyDecision.NeedsConfirmation(<redacted>)"
    }
}

/**
 * Specific deterministic policy for reminder.create schema v1. MAIN supplies a source
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
class OrionCreateReminderPolicy(
    private val source: CreateReminderPolicyContextSource = UnavailableCreateReminderPolicyContextSource,
) {
    fun evaluate(reminder: ValidatedCreateReminder): CreateReminderPolicyDecision {
        val initial = observe()
        if (initial is Observation.Failed) return CreateReminderPolicyDecision.Unavailable(initial.reason)
        val context = (initial as Observation.Ready).context
        if (context.processRole == null) return unavailable(CreateReminderPolicyUnavailable.PROCESS_UNAVAILABLE)
        if (context.processRole != OrionProcessRole.MAIN) return denied(CreateReminderPolicyDenial.PROCESS_NOT_MAIN)
        if (context.inputBinding != CreateReminderPolicyInputBinding.from(reminder))
            return denied(CreateReminderPolicyDenial.ADMISSION_INPUT_MISMATCH)

        val descriptor = OrionCreateReminderDescriptor.value
        if (descriptor.name != "reminder.create" || descriptor.argumentSchemaVersion != reminder.argumentSchemaVersion ||
            descriptor.authorizationLevel != AuthorizationLevel.SAFE_WRITE || descriptor.sideEffectClass != SideEffectClass.LOCAL_MUTATION)
            return denied(CreateReminderPolicyDenial.UNSUPPORTED_DESCRIPTOR)

        when (context.origin) {
            CreateReminderAdmissionOrigin.EXPLICIT_USER_REQUEST -> Unit
            CreateReminderAdmissionOrigin.UNKNOWN -> return unavailable(CreateReminderPolicyUnavailable.ORIGIN_UNAVAILABLE)
            else -> return denied(CreateReminderPolicyDenial.ORIGIN_NOT_USER_REQUEST)
        }
        requirement(context.startupBarrierOpen, CreateReminderPolicyDenial.STARTUP_GATED,
            CreateReminderPolicyUnavailable.STARTUP_UNAVAILABLE)?.let { return it }
        for (capability in descriptor.requiredCapabilities) {
            requirement(context.capability(capability), CreateReminderPolicyDenial.CAPABILITY_UNAVAILABLE,
                CreateReminderPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // v1 requires no Android permissions. Empty metadata grants none, and never skips capabilities.
        for (permission in descriptor.requiredPermissions) {
            requirement(context.permission(permission), CreateReminderPolicyDenial.PERMISSION_DENIED,
                CreateReminderPolicyUnavailable.PERMISSION_EVIDENCE_UNAVAILABLE)?.let { return it }
        }
        // This first user-driven policy is deliberately stricter than optional descriptor presence.
        requirement(context.userPresence, CreateReminderPolicyDenial.USER_NOT_PRESENT,
            CreateReminderPolicyUnavailable.PRESENCE_UNAVAILABLE)?.let { return it }

        val latest = observe()
        if (latest is Observation.Failed) return unavailable(latest.reason)
        if (!context.sameSnapshot((latest as Observation.Ready).context))
            return unavailable(CreateReminderPolicyUnavailable.CONTEXT_CHANGED)
        return CreateReminderPolicyDecision.NeedsConfirmation(POLICY_VERSION)
    }

    private fun requirement(evidence: OrionPolicyEvidence, denial: CreateReminderPolicyDenial,
        missing: CreateReminderPolicyUnavailable): CreateReminderPolicyDecision? = when (evidence) {
        OrionPolicyEvidence.SATISFIED -> null
        OrionPolicyEvidence.NOT_SATISFIED -> denied(denial)
        OrionPolicyEvidence.UNAVAILABLE -> unavailable(missing)
    }

    private sealed interface Observation {
        class Ready(val context: CreateReminderPolicyContext) : Observation
        class Failed(val reason: CreateReminderPolicyUnavailable) : Observation
    }

    private fun observe(): Observation = try {
        when (val read = source.read()) {
            is CreateReminderPolicyContextRead.Available -> Observation.Ready(read.context)
            CreateReminderPolicyContextRead.Unavailable -> Observation.Failed(CreateReminderPolicyUnavailable.CONTEXT_UNAVAILABLE)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        // Source exceptions can contain user data. No exception text crosses the policy boundary.
        Observation.Failed(CreateReminderPolicyUnavailable.CONTEXT_SOURCE_FAILURE)
    }

    private fun denied(reason: CreateReminderPolicyDenial) = CreateReminderPolicyDecision.Denied(reason)
    private fun unavailable(reason: CreateReminderPolicyUnavailable) = CreateReminderPolicyDecision.Unavailable(reason)

    companion object {
        /** Fixed rules, not a client/config field or an ephemeral state revision. */
        const val POLICY_VERSION = "orion_reminder_create_policy_v1"
    }
}

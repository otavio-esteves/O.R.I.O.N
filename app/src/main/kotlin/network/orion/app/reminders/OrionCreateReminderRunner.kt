package network.orion.app.reminders

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.policy.reminders.*
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.reminders.OrionOwnedReminderStore
import network.orion.skills.api.*
import network.orion.skills.reminders.OrionCreateReminderSkill

internal enum class OrionReminderExecutionFailure {
    STARTUP_GATED, POLICY_DENIED, CONFLICT, INVALID_STATE, UNREADABLE,
    CONFIRMATION_EXPIRED, OWNERSHIP_UNAVAILABLE, REMINDER_ID_CONFLICT, STORAGE_UNAVAILABLE,
}

internal sealed interface OrionReminderExecutionResult {
    data class Completed(val actionId: UUID, val reminderId: UUID, val deduplicated: Boolean) : OrionReminderExecutionResult {
        override fun toString() = "OrionReminderExecutionResult.Completed(<redacted>)"
    }
    data class Blocked(val reason: OrionReminderExecutionFailure) : OrionReminderExecutionResult
}

/**
 * MAIN's bounded LOCAL_MUTATION runner, off the UI thread. The exact final Skill
 * implementation does not suspend: its normative suspend entry delegates to the
 * same executeInTransaction body. No monitor is held across coroutine suspension.
 * Claim, local effect/event and finish share one admitted writer transaction.
 * No generic Skill registry, external-effect execution or automatic retry exists.
 */
internal class OrionCreateReminderRunner(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val ownedReminders: OrionOwnedReminderStore,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
) {
    private val skill = OrionCreateReminderSkill(ownedReminders)

    fun execute(admission: OrionCreateReminderAdmission, displayed: OrionReminderActionPreview): OrionReminderExecutionResult =
        orionReminderTransaction(database, barrier, OrionReminderTransactionBlocked.Execution(OrionReminderExecutionFailure.STARTUP_GATED),
            blocked = { if (it is OrionReminderTransactionBlocked.Execution) OrionReminderExecutionResult.Blocked(it.reason) else throw it },
            block = { executeInTransaction(admission, displayed) })

    private fun executeInTransaction(admission: OrionCreateReminderAdmission, displayed: OrionReminderActionPreview): OrionReminderExecutionResult {
        fun freshPolicy() = admission.evaluate() is CreateReminderPolicyDecision.NeedsConfirmation
        if (!freshPolicy()) abort(OrionReminderExecutionFailure.POLICY_DENIED)
        val reminder = admission.reminder
        val stored = when (val read = actions.read(displayed.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> abort(OrionReminderExecutionFailure.UNREADABLE)
        }
        val expected = OrionCreateReminderActionStore.normalized(displayed.actionId, reminder, OrionCreateReminderPolicy.POLICY_VERSION)
        val row = database.actions().find(displayed.actionId.toString()) ?: abort(OrionReminderExecutionFailure.UNREADABLE)
        if (stored.request != expected || !stored.requiresConfirmation ||
            row.idempotencyScope != "reminder.create" || row.idempotencyKey != reminder.idempotencyKey.toString() ||
            displayed.reminder.canonicalNormalizedArguments != reminder.canonicalNormalizedArguments ||
            displayed.reminder.idempotencyKey != reminder.idempotencyKey || displayed.policyVersion != expected.policyVersion ||
            displayed.confirmationFingerprint != ActionConfirmationBinding(clock).prepare(expected).confirmationFingerprint)
            abort(OrionReminderExecutionFailure.UNREADABLE)
        if (stored.status == ActionStatus.SUCCEEDED) {
            if (!ownedReminders.matchesExisting(reminder)) abort(OrionReminderExecutionFailure.UNREADABLE)
            return OrionReminderExecutionResult.Completed(displayed.actionId, reminder.reminderId, true)
        }
        if (stored.status != ActionStatus.CONFIRMED) abort(OrionReminderExecutionFailure.INVALID_STATE)
        if (stored.version != displayed.version) abort(OrionReminderExecutionFailure.CONFLICT)
        val authority = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = barrier.isOpen()
            override fun descriptorFor(skill: String) = OrionCreateReminderDescriptor.value.takeIf { it.name == skill }
            override fun currentPolicyVersion() = OrionCreateReminderPolicy.POLICY_VERSION
            override fun policyAllows(request: NormalizedActionRequest) = request == expected && freshPolicy()
            override fun capabilityAvailable(capability: Capability) = capability == OrionCreateReminderDescriptor.WRITE_CAPABILITY && freshPolicy()
            override fun permissionGranted(permission: String) = false
            override fun userPresent() = freshPolicy()
        }
        val execution = OrionActionExecutionStore(database, actions, clock, authority)
        val claim = when (val result = execution.claim(displayed.actionId, stored.version, "orion_reminder_main", Duration.ofSeconds(30))) {
            is ActionClaimResult.Claimed -> result
            is ActionClaimResult.Blocked -> abort(when (result.reason) {
                ActionClaimFailure.CONFIRMATION_EXPIRED -> OrionReminderExecutionFailure.CONFIRMATION_EXPIRED
                ActionClaimFailure.CONFLICT -> OrionReminderExecutionFailure.CONFLICT
                ActionClaimFailure.POLICY_DENIED, ActionClaimFailure.CAPABILITY_UNAVAILABLE,
                ActionClaimFailure.PERMISSION_DENIED, ActionClaimFailure.USER_PRESENCE_REQUIRED -> OrionReminderExecutionFailure.POLICY_DENIED
                else -> OrionReminderExecutionFailure.OWNERSHIP_UNAVAILABLE
            })
        }
        val intent = OrionIntent("reminder.create", 1.0, reminder, "MAIN_UI", false, reminder.correlationId)
        val result = skill.executeInTransaction(intent, SkillContext(displayed.actionId, claim.lease.executionId, claim.version))
        if (result is SkillResult.Rejected) abort(when (result.reason) {
            SkillFailure.STATE_CONFLICT -> OrionReminderExecutionFailure.REMINDER_ID_CONFLICT
            SkillFailure.STORAGE_UNAVAILABLE -> OrionReminderExecutionFailure.STORAGE_UNAVAILABLE
            SkillFailure.EXECUTION_NOT_OWNED -> OrionReminderExecutionFailure.OWNERSHIP_UNAVAILABLE
            else -> OrionReminderExecutionFailure.UNREADABLE
        })
        if (!freshPolicy()) abort(OrionReminderExecutionFailure.POLICY_DENIED)
        val receipt = stored.confirmation ?: abort(OrionReminderExecutionFailure.UNREADABLE)
        if (ActionConfirmationBinding(clock).validate(expected, receipt) != ConfirmationBindingResult.VALID_BINDING)
            abort(OrionReminderExecutionFailure.CONFIRMATION_EXPIRED)
        if (execution.finish(displayed.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) != ActionMutation.APPLIED)
            abort(OrionReminderExecutionFailure.OWNERSHIP_UNAVAILABLE)
        return OrionReminderExecutionResult.Completed(displayed.actionId, reminder.reminderId, (result as SkillResult.Completed).deduplicated)
    }

    private fun abort(reason: OrionReminderExecutionFailure): Nothing = throw OrionReminderTransactionBlocked.Execution(reason)
}

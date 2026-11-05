package network.orion.app.timers

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.policy.timers.*
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.timers.OrionOwnedTimerStore
import network.orion.skills.api.*
import network.orion.skills.timers.OrionCreateTimerSkill

internal enum class OrionTimerExecutionFailure {
    STARTUP_GATED, POLICY_DENIED, CONFLICT, INVALID_STATE, UNREADABLE,
    CONFIRMATION_EXPIRED, OWNERSHIP_UNAVAILABLE, TIMER_ID_CONFLICT, STORAGE_UNAVAILABLE,
}

internal sealed interface OrionTimerExecutionResult {
    data class Completed(val actionId: UUID, val timerId: UUID, val deduplicated: Boolean) : OrionTimerExecutionResult {
        override fun toString() = "OrionTimerExecutionResult.Completed(<redacted>)"
    }
    data class Blocked(val reason: OrionTimerExecutionFailure) : OrionTimerExecutionResult
}

/**
 * MAIN's bounded LOCAL_MUTATION runner, off the UI thread. The exact final Skill
 * implementation does not suspend: its normative suspend entry delegates to the
 * same executeInTransaction body. No monitor is held across coroutine suspension.
 * Claim, local effect/event and finish share one admitted writer transaction.
 * No generic Skill registry, external-effect execution or automatic retry exists.
 */
internal class OrionCreateTimerRunner(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val ownedTimers: OrionOwnedTimerStore,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
) {
    private val skill = OrionCreateTimerSkill(ownedTimers)

    fun execute(admission: OrionCreateTimerAdmission, displayed: OrionTimerActionPreview): OrionTimerExecutionResult =
        orionTimerTransaction(database, barrier, OrionTimerTransactionBlocked.Execution(OrionTimerExecutionFailure.STARTUP_GATED),
            blocked = { if (it is OrionTimerTransactionBlocked.Execution) OrionTimerExecutionResult.Blocked(it.reason) else throw it },
            block = { executeInTransaction(admission, displayed) })

    private fun executeInTransaction(admission: OrionCreateTimerAdmission, displayed: OrionTimerActionPreview): OrionTimerExecutionResult {
        fun freshPolicy() = admission.evaluate() is CreateTimerPolicyDecision.NeedsConfirmation
        if (!freshPolicy()) abort(OrionTimerExecutionFailure.POLICY_DENIED)
        val timer = admission.timer
        val stored = when (val read = actions.read(displayed.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> abort(OrionTimerExecutionFailure.UNREADABLE)
        }
        val expected = OrionCreateTimerActionStore.normalized(displayed.actionId, timer, OrionCreateTimerPolicy.POLICY_VERSION)
        val row = database.actions().find(displayed.actionId.toString()) ?: abort(OrionTimerExecutionFailure.UNREADABLE)
        if (stored.request != expected || !stored.requiresConfirmation ||
            row.idempotencyScope != "timer.create" || row.idempotencyKey != timer.idempotencyKey.toString() ||
            displayed.timer.canonicalNormalizedArguments != timer.canonicalNormalizedArguments ||
            displayed.timer.idempotencyKey != timer.idempotencyKey || displayed.policyVersion != expected.policyVersion ||
            displayed.confirmationFingerprint != ActionConfirmationBinding(clock).prepare(expected).confirmationFingerprint)
            abort(OrionTimerExecutionFailure.UNREADABLE)
        if (stored.status == ActionStatus.SUCCEEDED) {
            if (!ownedTimers.matchesExisting(timer)) abort(OrionTimerExecutionFailure.UNREADABLE)
            return OrionTimerExecutionResult.Completed(displayed.actionId, timer.timerId, true)
        }
        if (stored.status != ActionStatus.CONFIRMED) abort(OrionTimerExecutionFailure.INVALID_STATE)
        if (stored.version != displayed.version) abort(OrionTimerExecutionFailure.CONFLICT)
        val authority = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = barrier.isOpen()
            override fun descriptorFor(skill: String) = OrionCreateTimerDescriptor.value.takeIf { it.name == skill }
            override fun currentPolicyVersion() = OrionCreateTimerPolicy.POLICY_VERSION
            override fun policyAllows(request: NormalizedActionRequest) = request == expected && freshPolicy()
            override fun capabilityAvailable(capability: Capability) = capability == OrionCreateTimerDescriptor.WRITE_CAPABILITY && freshPolicy()
            override fun permissionGranted(permission: String) = false
            override fun userPresent() = freshPolicy()
        }
        val execution = OrionActionExecutionStore(database, actions, clock, authority)
        val claim = when (val result = execution.claim(displayed.actionId, stored.version, "orion_timer_main", Duration.ofSeconds(30))) {
            is ActionClaimResult.Claimed -> result
            is ActionClaimResult.Blocked -> abort(when (result.reason) {
                ActionClaimFailure.CONFIRMATION_EXPIRED -> OrionTimerExecutionFailure.CONFIRMATION_EXPIRED
                ActionClaimFailure.CONFLICT -> OrionTimerExecutionFailure.CONFLICT
                ActionClaimFailure.POLICY_DENIED, ActionClaimFailure.CAPABILITY_UNAVAILABLE,
                ActionClaimFailure.PERMISSION_DENIED, ActionClaimFailure.USER_PRESENCE_REQUIRED -> OrionTimerExecutionFailure.POLICY_DENIED
                else -> OrionTimerExecutionFailure.OWNERSHIP_UNAVAILABLE
            })
        }
        val intent = OrionIntent("timer.create", 1.0, timer, "MAIN_UI", false, timer.correlationId)
        val result = skill.executeInTransaction(intent, SkillContext(displayed.actionId, claim.lease.executionId, claim.version))
        if (result is SkillResult.Rejected) abort(when (result.reason) {
            SkillFailure.STATE_CONFLICT -> OrionTimerExecutionFailure.TIMER_ID_CONFLICT
            SkillFailure.STORAGE_UNAVAILABLE -> OrionTimerExecutionFailure.STORAGE_UNAVAILABLE
            SkillFailure.EXECUTION_NOT_OWNED -> OrionTimerExecutionFailure.OWNERSHIP_UNAVAILABLE
            else -> OrionTimerExecutionFailure.UNREADABLE
        })
        if (!freshPolicy()) abort(OrionTimerExecutionFailure.POLICY_DENIED)
        val receipt = stored.confirmation ?: abort(OrionTimerExecutionFailure.UNREADABLE)
        if (ActionConfirmationBinding(clock).validate(expected, receipt) != ConfirmationBindingResult.VALID_BINDING)
            abort(OrionTimerExecutionFailure.CONFIRMATION_EXPIRED)
        if (execution.finish(displayed.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) != ActionMutation.APPLIED)
            abort(OrionTimerExecutionFailure.OWNERSHIP_UNAVAILABLE)
        return OrionTimerExecutionResult.Completed(displayed.actionId, timer.timerId, (result as SkillResult.Completed).deduplicated)
    }

    private fun abort(reason: OrionTimerExecutionFailure): Nothing = throw OrionTimerTransactionBlocked.Execution(reason)
}

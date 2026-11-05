package network.orion.app.tasks

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.policy.tasks.*
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.tasks.OrionOwnedTaskStore
import network.orion.skills.api.*
import network.orion.skills.tasks.OrionCreateTaskSkill

internal enum class OrionTaskExecutionFailure {
    STARTUP_GATED, POLICY_DENIED, CONFLICT, INVALID_STATE, UNREADABLE,
    CONFIRMATION_EXPIRED, OWNERSHIP_UNAVAILABLE, TASK_ID_CONFLICT, STORAGE_UNAVAILABLE,
}

internal sealed interface OrionTaskExecutionResult {
    data class Completed(val actionId: UUID, val taskId: UUID, val deduplicated: Boolean) : OrionTaskExecutionResult {
        override fun toString() = "OrionTaskExecutionResult.Completed(<redacted>)"
    }
    data class Blocked(val reason: OrionTaskExecutionFailure) : OrionTaskExecutionResult
}

/**
 * MAIN's bounded LOCAL_MUTATION runner, off the UI thread. The exact final Skill
 * implementation does not suspend: its normative suspend entry delegates to the
 * same executeInTransaction body. No monitor is held across coroutine suspension.
 * Claim, local effect/event and finish share one admitted writer transaction.
 * No generic Skill registry, external-effect execution or automatic retry exists.
 */
internal class OrionCreateTaskRunner(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val ownedTasks: OrionOwnedTaskStore,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
) {
    private val skill = OrionCreateTaskSkill(ownedTasks)

    fun execute(admission: OrionCreateTaskAdmission, displayed: OrionTaskActionPreview): OrionTaskExecutionResult =
        orionTaskTransaction(database, barrier, OrionTaskTransactionBlocked.Execution(OrionTaskExecutionFailure.STARTUP_GATED),
            blocked = { if (it is OrionTaskTransactionBlocked.Execution) OrionTaskExecutionResult.Blocked(it.reason) else throw it },
            block = { executeInTransaction(admission, displayed) })

    private fun executeInTransaction(admission: OrionCreateTaskAdmission, displayed: OrionTaskActionPreview): OrionTaskExecutionResult {
        fun freshPolicy() = admission.evaluate() is CreateTaskPolicyDecision.NeedsConfirmation
        if (!freshPolicy()) abort(OrionTaskExecutionFailure.POLICY_DENIED)
        val task = admission.task
        val stored = when (val read = actions.read(displayed.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> abort(OrionTaskExecutionFailure.UNREADABLE)
        }
        val expected = OrionCreateTaskActionStore.normalized(displayed.actionId, task, OrionCreateTaskPolicy.POLICY_VERSION)
        val row = database.actions().find(displayed.actionId.toString()) ?: abort(OrionTaskExecutionFailure.UNREADABLE)
        if (stored.request != expected || !stored.requiresConfirmation ||
            row.idempotencyScope != "task.create" || row.idempotencyKey != task.idempotencyKey.toString() ||
            displayed.task.canonicalNormalizedArguments != task.canonicalNormalizedArguments ||
            displayed.task.idempotencyKey != task.idempotencyKey || displayed.policyVersion != expected.policyVersion ||
            displayed.confirmationFingerprint != ActionConfirmationBinding(clock).prepare(expected).confirmationFingerprint)
            abort(OrionTaskExecutionFailure.UNREADABLE)
        if (stored.status == ActionStatus.SUCCEEDED) {
            if (!ownedTasks.matchesExisting(task)) abort(OrionTaskExecutionFailure.UNREADABLE)
            return OrionTaskExecutionResult.Completed(displayed.actionId, task.taskId, true)
        }
        if (stored.status != ActionStatus.CONFIRMED) abort(OrionTaskExecutionFailure.INVALID_STATE)
        if (stored.version != displayed.version) abort(OrionTaskExecutionFailure.CONFLICT)
        val authority = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = barrier.isOpen()
            override fun descriptorFor(skill: String) = OrionCreateTaskDescriptor.value.takeIf { it.name == skill }
            override fun currentPolicyVersion() = OrionCreateTaskPolicy.POLICY_VERSION
            override fun policyAllows(request: NormalizedActionRequest) = request == expected && freshPolicy()
            override fun capabilityAvailable(capability: Capability) = capability == OrionCreateTaskDescriptor.WRITE_CAPABILITY && freshPolicy()
            override fun permissionGranted(permission: String) = false
            override fun userPresent() = freshPolicy()
        }
        val execution = OrionActionExecutionStore(database, actions, clock, authority)
        val claim = when (val result = execution.claim(displayed.actionId, stored.version, "orion_task_main", Duration.ofSeconds(30))) {
            is ActionClaimResult.Claimed -> result
            is ActionClaimResult.Blocked -> abort(when (result.reason) {
                ActionClaimFailure.CONFIRMATION_EXPIRED -> OrionTaskExecutionFailure.CONFIRMATION_EXPIRED
                ActionClaimFailure.CONFLICT -> OrionTaskExecutionFailure.CONFLICT
                ActionClaimFailure.POLICY_DENIED, ActionClaimFailure.CAPABILITY_UNAVAILABLE,
                ActionClaimFailure.PERMISSION_DENIED, ActionClaimFailure.USER_PRESENCE_REQUIRED -> OrionTaskExecutionFailure.POLICY_DENIED
                else -> OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE
            })
        }
        val intent = OrionIntent("task.create", 1.0, task, "MAIN_UI", false, task.correlationId)
        val result = skill.executeInTransaction(intent, SkillContext(displayed.actionId, claim.lease.executionId, claim.version))
        if (result is SkillResult.Rejected) abort(when (result.reason) {
            SkillFailure.STATE_CONFLICT -> OrionTaskExecutionFailure.TASK_ID_CONFLICT
            SkillFailure.STORAGE_UNAVAILABLE -> OrionTaskExecutionFailure.STORAGE_UNAVAILABLE
            SkillFailure.EXECUTION_NOT_OWNED -> OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE
            else -> OrionTaskExecutionFailure.UNREADABLE
        })
        if (!freshPolicy()) abort(OrionTaskExecutionFailure.POLICY_DENIED)
        val receipt = stored.confirmation ?: abort(OrionTaskExecutionFailure.UNREADABLE)
        if (ActionConfirmationBinding(clock).validate(expected, receipt) != ConfirmationBindingResult.VALID_BINDING)
            abort(OrionTaskExecutionFailure.CONFIRMATION_EXPIRED)
        if (execution.finish(displayed.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) != ActionMutation.APPLIED)
            abort(OrionTaskExecutionFailure.OWNERSHIP_UNAVAILABLE)
        return OrionTaskExecutionResult.Completed(displayed.actionId, task.taskId, (result as SkillResult.Completed).deduplicated)
    }

    private fun abort(reason: OrionTaskExecutionFailure): Nothing = throw OrionTaskTransactionBlocked.Execution(reason)
}

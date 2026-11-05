package network.orion.app.memories

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.policy.memories.*
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.memory.OrionOwnedMemoryStore
import network.orion.skills.api.*
import network.orion.skills.memory.OrionCreateMemorySkill

internal enum class OrionMemoryExecutionFailure {
    STARTUP_GATED, POLICY_DENIED, CONFLICT, INVALID_STATE, UNREADABLE,
    CONFIRMATION_EXPIRED, OWNERSHIP_UNAVAILABLE, MEMORY_ID_CONFLICT, STORAGE_UNAVAILABLE,
}

internal sealed interface OrionMemoryExecutionResult {
    data class Completed(val actionId: UUID, val memoryId: UUID, val deduplicated: Boolean) : OrionMemoryExecutionResult {
        override fun toString() = "OrionMemoryExecutionResult.Completed(<redacted>)"
    }
    data class Blocked(val reason: OrionMemoryExecutionFailure) : OrionMemoryExecutionResult
}

/**
 * MAIN's bounded LOCAL_MUTATION runner, off the UI thread. The exact final Skill
 * implementation does not suspend: its normative suspend entry delegates to the
 * same executeInTransaction body. No monitor is held across coroutine suspension.
 * Claim, local effect/event and finish share one admitted writer transaction.
 * No generic Skill registry, external-effect execution or automatic retry exists.
 */
internal class OrionCreateMemoryRunner(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val ownedMemories: OrionOwnedMemoryStore,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
) {
    private val skill = OrionCreateMemorySkill(ownedMemories)

    fun execute(admission: OrionCreateMemoryAdmission, displayed: OrionMemoryActionPreview): OrionMemoryExecutionResult =
        orionMemoryTransaction(database, barrier, OrionMemoryTransactionBlocked.Execution(OrionMemoryExecutionFailure.STARTUP_GATED),
            blocked = { if (it is OrionMemoryTransactionBlocked.Execution) OrionMemoryExecutionResult.Blocked(it.reason) else throw it },
            block = { executeInTransaction(admission, displayed) })

    private fun executeInTransaction(admission: OrionCreateMemoryAdmission, displayed: OrionMemoryActionPreview): OrionMemoryExecutionResult {
        fun freshPolicy() = admission.evaluate() is CreateMemoryPolicyDecision.NeedsConfirmation
        if (!freshPolicy()) abort(OrionMemoryExecutionFailure.POLICY_DENIED)
        val memory = admission.memory
        val stored = when (val read = actions.read(displayed.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> abort(OrionMemoryExecutionFailure.UNREADABLE)
        }
        val expected = OrionCreateMemoryActionStore.normalized(displayed.actionId, memory, OrionCreateMemoryPolicy.POLICY_VERSION)
        val row = database.actions().find(displayed.actionId.toString()) ?: abort(OrionMemoryExecutionFailure.UNREADABLE)
        if (stored.request != expected || !stored.requiresConfirmation ||
            row.idempotencyScope != "memory.create" || row.idempotencyKey != memory.idempotencyKey.toString() ||
            displayed.memory.canonicalNormalizedArguments != memory.canonicalNormalizedArguments ||
            displayed.memory.idempotencyKey != memory.idempotencyKey || displayed.policyVersion != expected.policyVersion ||
            displayed.confirmationFingerprint != ActionConfirmationBinding(clock).prepare(expected).confirmationFingerprint)
            abort(OrionMemoryExecutionFailure.UNREADABLE)
        if (stored.status == ActionStatus.SUCCEEDED) {
            if (!ownedMemories.matchesExisting(memory)) abort(OrionMemoryExecutionFailure.UNREADABLE)
            return OrionMemoryExecutionResult.Completed(displayed.actionId, memory.memoryId, true)
        }
        if (stored.status != ActionStatus.CONFIRMED) abort(OrionMemoryExecutionFailure.INVALID_STATE)
        if (stored.version != displayed.version) abort(OrionMemoryExecutionFailure.CONFLICT)
        val authority = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = barrier.isOpen()
            override fun descriptorFor(skill: String) = OrionCreateMemoryDescriptor.value.takeIf { it.name == skill }
            override fun currentPolicyVersion() = OrionCreateMemoryPolicy.POLICY_VERSION
            override fun policyAllows(request: NormalizedActionRequest) = request == expected && freshPolicy()
            override fun capabilityAvailable(capability: Capability) = capability == OrionCreateMemoryDescriptor.WRITE_CAPABILITY && freshPolicy()
            override fun permissionGranted(permission: String) = false
            override fun userPresent() = freshPolicy()
        }
        val execution = OrionActionExecutionStore(database, actions, clock, authority)
        val claim = when (val result = execution.claim(displayed.actionId, stored.version, "orion_memory_main", Duration.ofSeconds(30))) {
            is ActionClaimResult.Claimed -> result
            is ActionClaimResult.Blocked -> abort(when (result.reason) {
                ActionClaimFailure.CONFIRMATION_EXPIRED -> OrionMemoryExecutionFailure.CONFIRMATION_EXPIRED
                ActionClaimFailure.CONFLICT -> OrionMemoryExecutionFailure.CONFLICT
                ActionClaimFailure.POLICY_DENIED, ActionClaimFailure.CAPABILITY_UNAVAILABLE,
                ActionClaimFailure.PERMISSION_DENIED, ActionClaimFailure.USER_PRESENCE_REQUIRED -> OrionMemoryExecutionFailure.POLICY_DENIED
                else -> OrionMemoryExecutionFailure.OWNERSHIP_UNAVAILABLE
            })
        }
        val intent = OrionIntent("memory.create", 1.0, memory, "MAIN_UI", false, memory.correlationId)
        val result = skill.executeInTransaction(intent, SkillContext(displayed.actionId, claim.lease.executionId, claim.version))
        if (result is SkillResult.Rejected) abort(when (result.reason) {
            SkillFailure.STATE_CONFLICT -> OrionMemoryExecutionFailure.MEMORY_ID_CONFLICT
            SkillFailure.STORAGE_UNAVAILABLE -> OrionMemoryExecutionFailure.STORAGE_UNAVAILABLE
            SkillFailure.EXECUTION_NOT_OWNED -> OrionMemoryExecutionFailure.OWNERSHIP_UNAVAILABLE
            else -> OrionMemoryExecutionFailure.UNREADABLE
        })
        if (!freshPolicy()) abort(OrionMemoryExecutionFailure.POLICY_DENIED)
        val receipt = stored.confirmation ?: abort(OrionMemoryExecutionFailure.UNREADABLE)
        if (ActionConfirmationBinding(clock).validate(expected, receipt) != ConfirmationBindingResult.VALID_BINDING)
            abort(OrionMemoryExecutionFailure.CONFIRMATION_EXPIRED)
        if (execution.finish(displayed.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) != ActionMutation.APPLIED)
            abort(OrionMemoryExecutionFailure.OWNERSHIP_UNAVAILABLE)
        return OrionMemoryExecutionResult.Completed(displayed.actionId, memory.memoryId, (result as SkillResult.Completed).deduplicated)
    }

    private fun abort(reason: OrionMemoryExecutionFailure): Nothing = throw OrionMemoryTransactionBlocked.Execution(reason)
}

package network.orion.app.tasks

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.tasks.*
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

internal enum class OrionTaskActionFailure {
    STARTUP_GATED, POLICY_DENIED, POLICY_UNAVAILABLE, POLICY_CHANGED,
    IDEMPOTENCY_CONFLICT, CONFLICT, NOT_FOUND, INVALID_STATE, UNREADABLE,
    KEY_UNAVAILABLE, CONFIRMATION_MISMATCH, CONFIRMATION_STALE, CONFIRMATION_EXPIRED,
}

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionTaskActionPreview(
    val task: ValidatedCreateTask,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionTaskActionPreview(<redacted>)"
}

internal sealed interface OrionTaskActionResult {
    data class Ready(val preview: OrionTaskActionPreview) : OrionTaskActionResult {
        override fun toString() = "OrionTaskActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionTaskActionResult {
        override fun toString() = "OrionTaskActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionTaskActionFailure) : OrionTaskActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread. Product wiring must still pass
 * through durable ingress; this package installs no command/Skill dispatcher.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no task/event is written here.
 */
internal class OrionCreateTaskActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private val idempotency = IdempotencyKeyPolicy("MAIN_UI", "task.create", Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionCreateTaskAdmission): OrionTaskActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val task = admission.task
        val incoming = normalized(UUID.randomUUID(), task, version)
        val registration = actions.register(incoming, idempotency, task.idempotencyKey.toString(),
            task.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        var stored = read(id)
        requireExact(stored, task, version)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionTaskActionResult.Ready(preview(task, stored))
            ActionStatus.SUCCEEDED -> OrionTaskActionResult.AlreadySucceeded(id)
            else -> abort(OrionTaskActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionCreateTaskAdmission, displayed: OrionTaskActionPreview): OrionTaskActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionTaskActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionTaskActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionTaskActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.task, version)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.task.canonicalNormalizedArguments != admission.task.canonicalNormalizedArguments ||
            displayed.task.normalizedTarget != admission.task.normalizedTarget ||
            displayed.task.idempotencyKey != admission.task.idempotencyKey)
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionTaskActionFailure.CONFIRMATION_STALE)
        }
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionTaskActionResult.Ready(preview(admission.task, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionCreateTaskAdmission): String = when (val decision = admission.evaluate()) {
        is CreateTaskPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is CreateTaskPolicyDecision.Unavailable -> abort(OrionTaskActionFailure.POLICY_UNAVAILABLE)
        is CreateTaskPolicyDecision.Denied -> abort(if (decision.reason == CreateTaskPolicyDenial.STARTUP_GATED)
            OrionTaskActionFailure.STARTUP_GATED else OrionTaskActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, task: ValidatedCreateTask, version: String) {
        if (stored.request.policyVersion != version) abort(OrionTaskActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, task, version))
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionTaskActionFailure.NOT_FOUND)
        if (row.idempotencyScope != idempotency.scope || row.idempotencyKey != task.idempotencyKey.toString())
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(task: ValidatedCreateTask, stored: StoredAction) = OrionTaskActionPreview(task,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionTaskActionResult) = orionTaskTransaction(database, barrier,
        OrionTaskTransactionBlocked.Action(OrionTaskActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionTaskTransactionBlocked.Action) OrionTaskActionResult.Blocked(it.reason) else throw it
        }, block = block)

    private fun abort(reason: OrionTaskActionFailure): Nothing = throw OrionTaskTransactionBlocked.Action(reason)

    private fun failure(outcome: ActionStoreOutcome) = when (outcome) {
        ActionStoreOutcome.IDEMPOTENCY_CONFLICT -> OrionTaskActionFailure.IDEMPOTENCY_CONFLICT
        ActionStoreOutcome.CONFLICT -> OrionTaskActionFailure.CONFLICT
        ActionStoreOutcome.NOT_FOUND -> OrionTaskActionFailure.NOT_FOUND
        ActionStoreOutcome.INVALID_STATE -> OrionTaskActionFailure.INVALID_STATE
        ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED -> OrionTaskActionFailure.KEY_UNAVAILABLE
        ActionStoreOutcome.CONFIRMATION_STALE -> OrionTaskActionFailure.CONFIRMATION_STALE
        ActionStoreOutcome.CONFIRMATION_MISMATCH -> OrionTaskActionFailure.CONFIRMATION_MISMATCH
        ActionStoreOutcome.CONFIRMATION_EXPIRED -> OrionTaskActionFailure.CONFIRMATION_EXPIRED
        ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED -> OrionTaskActionFailure.POLICY_CHANGED
        else -> OrionTaskActionFailure.UNREADABLE
    }

    companion object {
        internal fun normalized(id: UUID, task: ValidatedCreateTask, policyVersion: String): NormalizedActionRequest {
            val descriptor = OrionCreateTaskDescriptor.value
            return NormalizedActionRequest(id, descriptor.name, task.canonicalNormalizedArguments, task.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

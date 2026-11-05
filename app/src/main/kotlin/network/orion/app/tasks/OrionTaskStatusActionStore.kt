package network.orion.app.tasks

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.tasks.ValidatedTaskStatusChange
import network.orion.core.commands.tasks.TaskStatusOperation
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.tasks.*
import network.orion.core.skills.metadata.tasks.OrionTaskStatusDescriptors
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import network.orion.data.database.TaskStatus

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionTaskStatusActionPreview(
    val change: ValidatedTaskStatusChange,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionTaskStatusActionPreview(<redacted>)"
}

internal sealed interface OrionTaskStatusActionResult {
    data class Ready(val preview: OrionTaskStatusActionPreview) : OrionTaskStatusActionResult {
        override fun toString() = "OrionTaskStatusActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionTaskStatusActionResult {
        override fun toString() = "OrionTaskStatusActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionTaskActionFailure) : OrionTaskStatusActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread. Product wiring must still pass
 * through durable ingress; this package installs no command/Skill dispatcher.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no task status/event is written here.
 */
internal class OrionTaskStatusActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private fun idempotency(change: ValidatedTaskStatusChange) = IdempotencyKeyPolicy("MAIN_UI", change.skillName, Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionTaskStatusAdmission): OrionTaskStatusActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val change = admission.change
        val incoming = normalized(UUID.randomUUID(), change, version)
        val registration = actions.register(incoming, idempotency(change), change.idempotencyKey.toString(),
            change.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        var stored = read(id)
        requireExact(stored, change, version)
        if (stored.status == ActionStatus.SUCCEEDED) {
            val target = database.tasks().find(change.taskId.toString()) ?: abort(OrionTaskActionFailure.UNREADABLE)
            val desired = if (change.operation == TaskStatusOperation.COMPLETE) TaskStatus.COMPLETED else TaskStatus.CANCELLED
            if (target.version != change.expectedVersion + 1 || target.status != desired) abort(OrionTaskActionFailure.UNREADABLE)
            return@admittedTransaction OrionTaskStatusActionResult.AlreadySucceeded(id)
        }
        requireOpen(change)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionTaskStatusActionResult.Ready(preview(change, stored))
            ActionStatus.SUCCEEDED -> OrionTaskStatusActionResult.AlreadySucceeded(id)
            else -> abort(OrionTaskActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionTaskStatusAdmission, displayed: OrionTaskStatusActionPreview): OrionTaskStatusActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionTaskActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionTaskActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionTaskActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.change, version)
        requireOpen(admission.change)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.change.canonicalNormalizedArguments != admission.change.canonicalNormalizedArguments ||
            displayed.change.normalizedTarget != admission.change.normalizedTarget ||
            displayed.change.idempotencyKey != admission.change.idempotencyKey)
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionTaskActionFailure.CONFIRMATION_STALE)
        }
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionTaskStatusActionResult.Ready(preview(admission.change, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionTaskStatusAdmission): String = when (val decision = admission.evaluate()) {
        is TaskStatusPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is TaskStatusPolicyDecision.Unavailable -> abort(OrionTaskActionFailure.POLICY_UNAVAILABLE)
        is TaskStatusPolicyDecision.Denied -> abort(if (decision.reason == TaskStatusPolicyReason.STARTUP_GATED)
            OrionTaskActionFailure.STARTUP_GATED else OrionTaskActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, change: ValidatedTaskStatusChange, version: String) {
        if (stored.request.policyVersion != version) abort(OrionTaskActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, change, version))
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionTaskActionFailure.NOT_FOUND)
        if (row.idempotencyScope != change.skillName || row.idempotencyKey != change.idempotencyKey.toString())
            abort(OrionTaskActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun requireOpen(change: ValidatedTaskStatusChange) {
        val target = database.tasks().find(change.taskId.toString()) ?: abort(OrionTaskActionFailure.NOT_FOUND)
        if (target.version != change.expectedVersion || target.status != TaskStatus.OPEN) abort(OrionTaskActionFailure.CONFLICT)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(change: ValidatedTaskStatusChange, stored: StoredAction) = OrionTaskStatusActionPreview(change,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionTaskStatusActionResult) = orionTaskTransaction(database, barrier,
        OrionTaskTransactionBlocked.Action(OrionTaskActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionTaskTransactionBlocked.Action) OrionTaskStatusActionResult.Blocked(it.reason) else throw it
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
        internal fun normalized(id: UUID, change: ValidatedTaskStatusChange, policyVersion: String): NormalizedActionRequest {
            val descriptor = checkNotNull(OrionTaskStatusDescriptors.forName(change.skillName))
            return NormalizedActionRequest(id, descriptor.name, change.canonicalNormalizedArguments, change.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

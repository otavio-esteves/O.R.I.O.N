package network.orion.app.timers

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.timers.ValidatedTimerStatusChange
import network.orion.core.commands.timers.TimerStatusOperation
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.timers.*
import network.orion.core.skills.metadata.timers.OrionTimerStatusDescriptors
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import network.orion.data.database.TimerStatus

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionTimerStatusActionPreview(
    val change: ValidatedTimerStatusChange,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionTimerStatusActionPreview(<redacted>)"
}

internal sealed interface OrionTimerStatusActionResult {
    data class Ready(val preview: OrionTimerStatusActionPreview) : OrionTimerStatusActionResult {
        override fun toString() = "OrionTimerStatusActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionTimerStatusActionResult {
        override fun toString() = "OrionTimerStatusActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionTimerActionFailure) : OrionTimerStatusActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the timer kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no timer status/event is written here.
 */
internal class OrionTimerStatusActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private fun idempotency(change: ValidatedTimerStatusChange) = IdempotencyKeyPolicy("MAIN_UI", change.skillName, Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionTimerStatusAdmission): OrionTimerStatusActionResult = admittedTransaction {
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
            val target = database.timers().find(change.timerId.toString()) ?: abort(OrionTimerActionFailure.UNREADABLE)
            val desired = TimerStatus.CANCELLED
            if (target.version != change.expectedVersion + 1 || target.status != desired) abort(OrionTimerActionFailure.UNREADABLE)
            return@admittedTransaction OrionTimerStatusActionResult.AlreadySucceeded(id)
        }
        requireOpen(change)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionTimerStatusActionResult.Ready(preview(change, stored))
            ActionStatus.SUCCEEDED -> OrionTimerStatusActionResult.AlreadySucceeded(id)
            else -> abort(OrionTimerActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionTimerStatusAdmission, displayed: OrionTimerStatusActionPreview): OrionTimerStatusActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionTimerStatusAdmission, displayed: OrionTimerStatusActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionTimerStatusActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionTimerActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionTimerActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionTimerActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.change, version)
        requireOpen(admission.change)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.change.canonicalNormalizedArguments != admission.change.canonicalNormalizedArguments ||
            displayed.change.normalizedTarget != admission.change.normalizedTarget ||
            displayed.change.idempotencyKey != admission.change.idempotencyKey)
            abort(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            originalReceipt ?: binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionTimerActionFailure.CONFIRMATION_STALE)
        }
        val receiptExpiry = receipt.confirmationExpiresAt
        if (originalReceipt != null && (receiptExpiry == null ||
            !receiptExpiry.isAfter(receipt.confirmedAt) || receiptExpiry.isAfter(receipt.confirmedAt.plusSeconds(60))))
            abort(OrionTimerActionFailure.CONFIRMATION_STALE)
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionTimerStatusActionResult.Ready(preview(admission.change, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionTimerStatusAdmission): String = when (val decision = admission.evaluate()) {
        is TimerStatusPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is TimerStatusPolicyDecision.Unavailable -> abort(OrionTimerActionFailure.POLICY_UNAVAILABLE)
        is TimerStatusPolicyDecision.Denied -> abort(if (decision.reason == TimerStatusPolicyReason.STARTUP_GATED)
            OrionTimerActionFailure.STARTUP_GATED else OrionTimerActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, change: ValidatedTimerStatusChange, version: String) {
        if (stored.request.policyVersion != version) abort(OrionTimerActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, change, version))
            abort(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionTimerActionFailure.NOT_FOUND)
        if (row.idempotencyScope != change.skillName || row.idempotencyKey != change.idempotencyKey.toString())
            abort(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun requireOpen(change: ValidatedTimerStatusChange) {
        val target = database.timers().find(change.timerId.toString()) ?: abort(OrionTimerActionFailure.NOT_FOUND)
        if (target.version != change.expectedVersion || target.status != TimerStatus.ACTIVE) abort(OrionTimerActionFailure.CONFLICT)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(change: ValidatedTimerStatusChange, stored: StoredAction) = OrionTimerStatusActionPreview(change,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionTimerStatusActionResult) = orionTimerTransaction(database, barrier,
        OrionTimerTransactionBlocked.Action(OrionTimerActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionTimerTransactionBlocked.Action) OrionTimerStatusActionResult.Blocked(it.reason) else throw it
        }, block = block)

    private fun abort(reason: OrionTimerActionFailure): Nothing = throw OrionTimerTransactionBlocked.Action(reason)

    private fun failure(outcome: ActionStoreOutcome) = when (outcome) {
        ActionStoreOutcome.IDEMPOTENCY_CONFLICT -> OrionTimerActionFailure.IDEMPOTENCY_CONFLICT
        ActionStoreOutcome.CONFLICT -> OrionTimerActionFailure.CONFLICT
        ActionStoreOutcome.NOT_FOUND -> OrionTimerActionFailure.NOT_FOUND
        ActionStoreOutcome.INVALID_STATE -> OrionTimerActionFailure.INVALID_STATE
        ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED -> OrionTimerActionFailure.KEY_UNAVAILABLE
        ActionStoreOutcome.CONFIRMATION_STALE -> OrionTimerActionFailure.CONFIRMATION_STALE
        ActionStoreOutcome.CONFIRMATION_MISMATCH -> OrionTimerActionFailure.CONFIRMATION_MISMATCH
        ActionStoreOutcome.CONFIRMATION_EXPIRED -> OrionTimerActionFailure.CONFIRMATION_EXPIRED
        ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED -> OrionTimerActionFailure.POLICY_CHANGED
        else -> OrionTimerActionFailure.UNREADABLE
    }

    companion object {
        internal fun normalized(id: UUID, change: ValidatedTimerStatusChange, policyVersion: String): NormalizedActionRequest {
            val descriptor = checkNotNull(OrionTimerStatusDescriptors.forName(change.skillName))
            return NormalizedActionRequest(id, descriptor.name, change.canonicalNormalizedArguments, change.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

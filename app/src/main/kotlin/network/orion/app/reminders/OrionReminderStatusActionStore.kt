package network.orion.app.reminders

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.reminders.ValidatedReminderStatusChange
import network.orion.core.commands.reminders.ReminderStatusOperation
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.reminders.*
import network.orion.core.skills.metadata.reminders.OrionReminderStatusDescriptors
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import network.orion.data.database.ReminderStatus

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionReminderStatusActionPreview(
    val change: ValidatedReminderStatusChange,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionReminderStatusActionPreview(<redacted>)"
}

internal sealed interface OrionReminderStatusActionResult {
    data class Ready(val preview: OrionReminderStatusActionPreview) : OrionReminderStatusActionResult {
        override fun toString() = "OrionReminderStatusActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionReminderStatusActionResult {
        override fun toString() = "OrionReminderStatusActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionReminderActionFailure) : OrionReminderStatusActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the reminder kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no reminder status/event is written here.
 */
internal class OrionReminderStatusActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private fun idempotency(change: ValidatedReminderStatusChange) = IdempotencyKeyPolicy("MAIN_UI", change.skillName, Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionReminderStatusAdmission): OrionReminderStatusActionResult = admittedTransaction {
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
            val target = database.reminders().find(change.reminderId.toString()) ?: abort(OrionReminderActionFailure.UNREADABLE)
            val desired = if (change.operation == ReminderStatusOperation.SNOOZE) ReminderStatus.PENDING else ReminderStatus.CANCELLED
            if (target.version != change.expectedVersion + 1 || target.status != desired ||
                change.operation == ReminderStatusOperation.SNOOZE && (target.scheduledAt != change.scheduledAt?.toEpochMilli() || target.zoneId != change.zoneId?.id)) abort(OrionReminderActionFailure.UNREADABLE)
            return@admittedTransaction OrionReminderStatusActionResult.AlreadySucceeded(id)
        }
        requireOpen(change)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionReminderStatusActionResult.Ready(preview(change, stored))
            ActionStatus.SUCCEEDED -> OrionReminderStatusActionResult.AlreadySucceeded(id)
            else -> abort(OrionReminderActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionReminderStatusAdmission, displayed: OrionReminderStatusActionPreview): OrionReminderStatusActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionReminderStatusAdmission, displayed: OrionReminderStatusActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionReminderStatusActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionReminderActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionReminderActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionReminderActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.change, version)
        requireOpen(admission.change)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.change.canonicalNormalizedArguments != admission.change.canonicalNormalizedArguments ||
            displayed.change.normalizedTarget != admission.change.normalizedTarget ||
            displayed.change.idempotencyKey != admission.change.idempotencyKey)
            abort(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            originalReceipt ?: binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionReminderActionFailure.CONFIRMATION_STALE)
        }
        val receiptExpiry = receipt.confirmationExpiresAt
        if (originalReceipt != null && (receiptExpiry == null ||
            !receiptExpiry.isAfter(receipt.confirmedAt) || receiptExpiry.isAfter(receipt.confirmedAt.plusSeconds(60))))
            abort(OrionReminderActionFailure.CONFIRMATION_STALE)
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionReminderStatusActionResult.Ready(preview(admission.change, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionReminderStatusAdmission): String = when (val decision = admission.evaluate()) {
        is ReminderStatusPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is ReminderStatusPolicyDecision.Unavailable -> abort(OrionReminderActionFailure.POLICY_UNAVAILABLE)
        is ReminderStatusPolicyDecision.Denied -> abort(if (decision.reason == ReminderStatusPolicyReason.STARTUP_GATED)
            OrionReminderActionFailure.STARTUP_GATED else OrionReminderActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, change: ValidatedReminderStatusChange, version: String) {
        if (stored.request.policyVersion != version) abort(OrionReminderActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, change, version))
            abort(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionReminderActionFailure.NOT_FOUND)
        if (row.idempotencyScope != change.skillName || row.idempotencyKey != change.idempotencyKey.toString())
            abort(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun requireOpen(change: ValidatedReminderStatusChange) {
        val target = database.reminders().find(change.reminderId.toString()) ?: abort(OrionReminderActionFailure.NOT_FOUND)
        if (target.version != change.expectedVersion || target.status != ReminderStatus.PENDING ||
            change.operation == ReminderStatusOperation.SNOOZE && checkNotNull(change.scheduledAt).toEpochMilli() <= target.scheduledAt)
            abort(OrionReminderActionFailure.CONFLICT)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(change: ValidatedReminderStatusChange, stored: StoredAction) = OrionReminderStatusActionPreview(change,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionReminderStatusActionResult) = orionReminderTransaction(database, barrier,
        OrionReminderTransactionBlocked.Action(OrionReminderActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionReminderTransactionBlocked.Action) OrionReminderStatusActionResult.Blocked(it.reason) else throw it
        }, block = block)

    private fun abort(reason: OrionReminderActionFailure): Nothing = throw OrionReminderTransactionBlocked.Action(reason)

    private fun failure(outcome: ActionStoreOutcome) = when (outcome) {
        ActionStoreOutcome.IDEMPOTENCY_CONFLICT -> OrionReminderActionFailure.IDEMPOTENCY_CONFLICT
        ActionStoreOutcome.CONFLICT -> OrionReminderActionFailure.CONFLICT
        ActionStoreOutcome.NOT_FOUND -> OrionReminderActionFailure.NOT_FOUND
        ActionStoreOutcome.INVALID_STATE -> OrionReminderActionFailure.INVALID_STATE
        ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED -> OrionReminderActionFailure.KEY_UNAVAILABLE
        ActionStoreOutcome.CONFIRMATION_STALE -> OrionReminderActionFailure.CONFIRMATION_STALE
        ActionStoreOutcome.CONFIRMATION_MISMATCH -> OrionReminderActionFailure.CONFIRMATION_MISMATCH
        ActionStoreOutcome.CONFIRMATION_EXPIRED -> OrionReminderActionFailure.CONFIRMATION_EXPIRED
        ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED -> OrionReminderActionFailure.POLICY_CHANGED
        else -> OrionReminderActionFailure.UNREADABLE
    }

    companion object {
        internal fun normalized(id: UUID, change: ValidatedReminderStatusChange, policyVersion: String): NormalizedActionRequest {
            val descriptor = checkNotNull(OrionReminderStatusDescriptors.forName(change.skillName))
            return NormalizedActionRequest(id, descriptor.name, change.canonicalNormalizedArguments, change.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

package network.orion.app.reminders

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.reminders.ValidatedCreateReminder
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.reminders.*
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

internal enum class OrionReminderActionFailure {
    STARTUP_GATED, POLICY_DENIED, POLICY_UNAVAILABLE, POLICY_CHANGED,
    IDEMPOTENCY_CONFLICT, CONFLICT, NOT_FOUND, INVALID_STATE, UNREADABLE,
    KEY_UNAVAILABLE, CONFIRMATION_MISMATCH, CONFIRMATION_STALE, CONFIRMATION_EXPIRED,
}

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionReminderActionPreview(
    val reminder: ValidatedCreateReminder,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionReminderActionPreview(<redacted>)"
}

internal sealed interface OrionReminderActionResult {
    data class Ready(val preview: OrionReminderActionPreview) : OrionReminderActionResult {
        override fun toString() = "OrionReminderActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionReminderActionResult {
        override fun toString() = "OrionReminderActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionReminderActionFailure) : OrionReminderActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the reminder kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no reminder/event is written here.
 */
internal class OrionCreateReminderActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private val idempotency = IdempotencyKeyPolicy("MAIN_UI", "reminder.create", Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionCreateReminderAdmission): OrionReminderActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val reminder = admission.reminder
        val incoming = normalized(UUID.randomUUID(), reminder, version)
        val registration = actions.register(incoming, idempotency, reminder.idempotencyKey.toString(),
            reminder.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        var stored = read(id)
        requireExact(stored, reminder, version)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionReminderActionResult.Ready(preview(reminder, stored))
            ActionStatus.SUCCEEDED -> OrionReminderActionResult.AlreadySucceeded(id)
            else -> abort(OrionReminderActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionCreateReminderAdmission, displayed: OrionReminderActionPreview): OrionReminderActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionCreateReminderAdmission, displayed: OrionReminderActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionReminderActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionReminderActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionReminderActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionReminderActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.reminder, version)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.reminder.canonicalNormalizedArguments != admission.reminder.canonicalNormalizedArguments ||
            displayed.reminder.normalizedTarget != admission.reminder.normalizedTarget ||
            displayed.reminder.idempotencyKey != admission.reminder.idempotencyKey)
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
        OrionReminderActionResult.Ready(preview(admission.reminder, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionCreateReminderAdmission): String = when (val decision = admission.evaluate()) {
        is CreateReminderPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is CreateReminderPolicyDecision.Unavailable -> abort(OrionReminderActionFailure.POLICY_UNAVAILABLE)
        is CreateReminderPolicyDecision.Denied -> abort(if (decision.reason == CreateReminderPolicyDenial.STARTUP_GATED)
            OrionReminderActionFailure.STARTUP_GATED else OrionReminderActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, reminder: ValidatedCreateReminder, version: String) {
        if (stored.request.policyVersion != version) abort(OrionReminderActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, reminder, version))
            abort(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionReminderActionFailure.NOT_FOUND)
        if (row.idempotencyScope != idempotency.scope || row.idempotencyKey != reminder.idempotencyKey.toString())
            abort(OrionReminderActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(reminder: ValidatedCreateReminder, stored: StoredAction) = OrionReminderActionPreview(reminder,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionReminderActionResult) = orionReminderTransaction(database, barrier,
        OrionReminderTransactionBlocked.Action(OrionReminderActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionReminderTransactionBlocked.Action) OrionReminderActionResult.Blocked(it.reason) else throw it
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
        internal fun normalized(id: UUID, reminder: ValidatedCreateReminder, policyVersion: String): NormalizedActionRequest {
            val descriptor = OrionCreateReminderDescriptor.value
            return NormalizedActionRequest(id, descriptor.name, reminder.canonicalNormalizedArguments, reminder.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

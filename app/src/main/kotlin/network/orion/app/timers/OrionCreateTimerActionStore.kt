package network.orion.app.timers

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.timers.ValidatedCreateTimer
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.timers.*
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

internal enum class OrionTimerActionFailure {
    STARTUP_GATED, POLICY_DENIED, POLICY_UNAVAILABLE, POLICY_CHANGED,
    IDEMPOTENCY_CONFLICT, CONFLICT, NOT_FOUND, INVALID_STATE, UNREADABLE,
    KEY_UNAVAILABLE, CONFIRMATION_MISMATCH, CONFIRMATION_STALE, CONFIRMATION_EXPIRED,
}

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionTimerActionPreview(
    val timer: ValidatedCreateTimer,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionTimerActionPreview(<redacted>)"
}

internal sealed interface OrionTimerActionResult {
    data class Ready(val preview: OrionTimerActionPreview) : OrionTimerActionResult {
        override fun toString() = "OrionTimerActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionTimerActionResult {
        override fun toString() = "OrionTimerActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionTimerActionFailure) : OrionTimerActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the timer kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no timer/event is written here.
 */
internal class OrionCreateTimerActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private val idempotency = IdempotencyKeyPolicy("MAIN_UI", "timer.create", Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionCreateTimerAdmission): OrionTimerActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val timer = admission.timer
        val incoming = normalized(UUID.randomUUID(), timer, version)
        val registration = actions.register(incoming, idempotency, timer.idempotencyKey.toString(),
            timer.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        var stored = read(id)
        requireExact(stored, timer, version)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionTimerActionResult.Ready(preview(timer, stored))
            ActionStatus.SUCCEEDED -> OrionTimerActionResult.AlreadySucceeded(id)
            else -> abort(OrionTimerActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionCreateTimerAdmission, displayed: OrionTimerActionPreview): OrionTimerActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionCreateTimerAdmission, displayed: OrionTimerActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionTimerActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionTimerActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionTimerActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionTimerActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.timer, version)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.timer.canonicalNormalizedArguments != admission.timer.canonicalNormalizedArguments ||
            displayed.timer.normalizedTarget != admission.timer.normalizedTarget ||
            displayed.timer.idempotencyKey != admission.timer.idempotencyKey)
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
        OrionTimerActionResult.Ready(preview(admission.timer, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionCreateTimerAdmission): String = when (val decision = admission.evaluate()) {
        is CreateTimerPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is CreateTimerPolicyDecision.Unavailable -> abort(OrionTimerActionFailure.POLICY_UNAVAILABLE)
        is CreateTimerPolicyDecision.Denied -> abort(if (decision.reason == CreateTimerPolicyDenial.STARTUP_GATED)
            OrionTimerActionFailure.STARTUP_GATED else OrionTimerActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, timer: ValidatedCreateTimer, version: String) {
        if (stored.request.policyVersion != version) abort(OrionTimerActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, timer, version))
            abort(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionTimerActionFailure.NOT_FOUND)
        if (row.idempotencyScope != idempotency.scope || row.idempotencyKey != timer.idempotencyKey.toString())
            abort(OrionTimerActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(timer: ValidatedCreateTimer, stored: StoredAction) = OrionTimerActionPreview(timer,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionTimerActionResult) = orionTimerTransaction(database, barrier,
        OrionTimerTransactionBlocked.Action(OrionTimerActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionTimerTransactionBlocked.Action) OrionTimerActionResult.Blocked(it.reason) else throw it
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
        internal fun normalized(id: UUID, timer: ValidatedCreateTimer, policyVersion: String): NormalizedActionRequest {
            val descriptor = OrionCreateTimerDescriptor.value
            return NormalizedActionRequest(id, descriptor.name, timer.canonicalNormalizedArguments, timer.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

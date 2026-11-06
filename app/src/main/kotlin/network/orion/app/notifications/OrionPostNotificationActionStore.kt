package network.orion.app.notifications

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.notifications.*
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

internal enum class OrionNotificationActionFailure {
    STARTUP_GATED, POLICY_DENIED, POLICY_UNAVAILABLE, POLICY_CHANGED,
    IDEMPOTENCY_CONFLICT, CONFLICT, NOT_FOUND, INVALID_STATE, UNREADABLE,
    KEY_UNAVAILABLE, CONFIRMATION_MISMATCH, CONFIRMATION_STALE, CONFIRMATION_EXPIRED,
}

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionNotificationActionPreview(
    val notification: ValidatedPostNotification,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionNotificationActionPreview(<redacted>)"
}

internal sealed interface OrionNotificationActionResult {
    data class Ready(val preview: OrionNotificationActionPreview) : OrionNotificationActionResult {
        override fun toString() = "OrionNotificationActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionNotificationActionResult {
        override fun toString() = "OrionNotificationActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionNotificationActionFailure) : OrionNotificationActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the notification kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no notification/event is written here.
 */
internal class OrionPostNotificationActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private val idempotency = IdempotencyKeyPolicy("MAIN_UI", "notification.post", Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionPostNotificationAdmission): OrionNotificationActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val notification = admission.notification
        val incoming = normalized(UUID.randomUUID(), notification, version)
        val registration = actions.register(incoming, idempotency, notification.idempotencyKey.toString(),
            notification.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        var stored = read(id)
        requireExact(stored, notification, version)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionNotificationActionResult.Ready(preview(notification, stored))
            ActionStatus.SUCCEEDED -> OrionNotificationActionResult.AlreadySucceeded(id)
            else -> abort(OrionNotificationActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionPostNotificationAdmission, displayed: OrionNotificationActionPreview): OrionNotificationActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionPostNotificationAdmission, displayed: OrionNotificationActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionNotificationActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionNotificationActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionNotificationActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionNotificationActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.notification, version)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.notification.canonicalNormalizedArguments != admission.notification.canonicalNormalizedArguments ||
            displayed.notification.normalizedTarget != admission.notification.normalizedTarget ||
            displayed.notification.idempotencyKey != admission.notification.idempotencyKey)
            abort(OrionNotificationActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            originalReceipt ?: binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionNotificationActionFailure.CONFIRMATION_STALE)
        }
        val receiptExpiry = receipt.confirmationExpiresAt
        if (originalReceipt != null && (receiptExpiry == null ||
            !receiptExpiry.isAfter(receipt.confirmedAt) || receiptExpiry.isAfter(receipt.confirmedAt.plusSeconds(60))))
            abort(OrionNotificationActionFailure.CONFIRMATION_STALE)
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionNotificationActionResult.Ready(preview(admission.notification, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionPostNotificationAdmission): String = when (val decision = admission.evaluate()) {
        is PostNotificationPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is PostNotificationPolicyDecision.Unavailable -> abort(OrionNotificationActionFailure.POLICY_UNAVAILABLE)
        is PostNotificationPolicyDecision.Denied -> abort(if (decision.reason == PostNotificationPolicyDenial.STARTUP_GATED)
            OrionNotificationActionFailure.STARTUP_GATED else OrionNotificationActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, notification: ValidatedPostNotification, version: String) {
        if (stored.request.policyVersion != version) abort(OrionNotificationActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, notification, version))
            abort(OrionNotificationActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionNotificationActionFailure.NOT_FOUND)
        if (row.idempotencyScope != idempotency.scope || row.idempotencyKey != notification.idempotencyKey.toString())
            abort(OrionNotificationActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(notification: ValidatedPostNotification, stored: StoredAction) = OrionNotificationActionPreview(notification,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionNotificationActionResult) = orionNotificationTransaction(database, barrier,
        OrionNotificationTransactionBlocked.Action(OrionNotificationActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionNotificationTransactionBlocked.Action) OrionNotificationActionResult.Blocked(it.reason) else throw it
        }, block = block)

    private fun abort(reason: OrionNotificationActionFailure): Nothing = throw OrionNotificationTransactionBlocked.Action(reason)

    private fun failure(outcome: ActionStoreOutcome) = when (outcome) {
        ActionStoreOutcome.IDEMPOTENCY_CONFLICT -> OrionNotificationActionFailure.IDEMPOTENCY_CONFLICT
        ActionStoreOutcome.CONFLICT -> OrionNotificationActionFailure.CONFLICT
        ActionStoreOutcome.NOT_FOUND -> OrionNotificationActionFailure.NOT_FOUND
        ActionStoreOutcome.INVALID_STATE -> OrionNotificationActionFailure.INVALID_STATE
        ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED -> OrionNotificationActionFailure.KEY_UNAVAILABLE
        ActionStoreOutcome.CONFIRMATION_STALE -> OrionNotificationActionFailure.CONFIRMATION_STALE
        ActionStoreOutcome.CONFIRMATION_MISMATCH -> OrionNotificationActionFailure.CONFIRMATION_MISMATCH
        ActionStoreOutcome.CONFIRMATION_EXPIRED -> OrionNotificationActionFailure.CONFIRMATION_EXPIRED
        ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED -> OrionNotificationActionFailure.POLICY_CHANGED
        else -> OrionNotificationActionFailure.UNREADABLE
    }

    companion object {
        internal fun normalized(id: UUID, notification: ValidatedPostNotification, policyVersion: String): NormalizedActionRequest {
            val descriptor = OrionPostNotificationDescriptor.value
            return NormalizedActionRequest(id, descriptor.name, notification.canonicalNormalizedArguments, notification.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

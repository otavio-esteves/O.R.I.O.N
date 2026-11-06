package network.orion.app.memories

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.memories.ValidatedMemoryStatusChange
import network.orion.core.commands.memories.MemoryStatusOperation
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.memories.*
import network.orion.core.skills.metadata.memories.OrionMemoryStatusDescriptors
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import network.orion.data.database.MemoryStatus

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionMemoryStatusActionPreview(
    val change: ValidatedMemoryStatusChange,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionMemoryStatusActionPreview(<redacted>)"
}

internal sealed interface OrionMemoryStatusActionResult {
    data class Ready(val preview: OrionMemoryStatusActionPreview) : OrionMemoryStatusActionResult {
        override fun toString() = "OrionMemoryStatusActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionMemoryStatusActionResult {
        override fun toString() = "OrionMemoryStatusActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Blocked(val reason: OrionMemoryActionFailure) : OrionMemoryStatusActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the memory kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no memory status/event is written here.
 */
internal class OrionMemoryStatusActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private fun idempotency(change: ValidatedMemoryStatusChange) = IdempotencyKeyPolicy("MAIN_UI", change.skillName, Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionMemoryStatusAdmission): OrionMemoryStatusActionResult = admittedTransaction {
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
            val target = database.memories().find(change.memoryId.toString()) ?: abort(OrionMemoryActionFailure.UNREADABLE)
            val desired = if (change.operation == MemoryStatusOperation.DELETE) MemoryStatus.DELETED else MemoryStatus.DELETED
            if (target.version != change.expectedVersion + 1 || target.status != desired) abort(OrionMemoryActionFailure.UNREADABLE)
            return@admittedTransaction OrionMemoryStatusActionResult.AlreadySucceeded(id)
        }
        requireOpen(change)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionMemoryStatusActionResult.Ready(preview(change, stored))
            ActionStatus.SUCCEEDED -> OrionMemoryStatusActionResult.AlreadySucceeded(id)
            else -> abort(OrionMemoryActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionMemoryStatusAdmission, displayed: OrionMemoryStatusActionPreview): OrionMemoryStatusActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionMemoryStatusAdmission, displayed: OrionMemoryStatusActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionMemoryStatusActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionMemoryActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionMemoryActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionMemoryActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.change, version)
        requireOpen(admission.change)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.change.canonicalNormalizedArguments != admission.change.canonicalNormalizedArguments ||
            displayed.change.normalizedTarget != admission.change.normalizedTarget ||
            displayed.change.idempotencyKey != admission.change.idempotencyKey)
            abort(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
        val receipt = try {
            originalReceipt ?: binding.recordConfirmation(stored.request, clock.wallNow().plusSeconds(60))
        } catch (_: IllegalArgumentException) {
            abort(OrionMemoryActionFailure.CONFIRMATION_STALE)
        }
        val receiptExpiry = receipt.confirmationExpiresAt
        if (originalReceipt != null && (receiptExpiry == null ||
            !receiptExpiry.isAfter(receipt.confirmedAt) || receiptExpiry.isAfter(receipt.confirmedAt.plusSeconds(60))))
            abort(OrionMemoryActionFailure.CONFIRMATION_STALE)
        val confirmed = actions.confirm(displayed.actionId, stored.version, receipt, version)
        if (confirmed.outcome != ActionStoreOutcome.APPLIED) abort(failure(confirmed.outcome))
        OrionMemoryStatusActionResult.Ready(preview(admission.change, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionMemoryStatusAdmission): String = when (val decision = admission.evaluate()) {
        is MemoryStatusPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is MemoryStatusPolicyDecision.Unavailable -> abort(OrionMemoryActionFailure.POLICY_UNAVAILABLE)
        is MemoryStatusPolicyDecision.Denied -> abort(if (decision.reason == MemoryStatusPolicyReason.STARTUP_GATED)
            OrionMemoryActionFailure.STARTUP_GATED else OrionMemoryActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, change: ValidatedMemoryStatusChange, version: String) {
        if (stored.request.policyVersion != version) abort(OrionMemoryActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, change, version))
            abort(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionMemoryActionFailure.NOT_FOUND)
        if (row.idempotencyScope != change.skillName || row.idempotencyKey != change.idempotencyKey.toString())
            abort(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun requireOpen(change: ValidatedMemoryStatusChange) {
        val target = database.memories().find(change.memoryId.toString()) ?: abort(OrionMemoryActionFailure.NOT_FOUND)
        if (target.version != change.expectedVersion || target.status != MemoryStatus.ACTIVE) abort(OrionMemoryActionFailure.CONFLICT)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(change: ValidatedMemoryStatusChange, stored: StoredAction) = OrionMemoryStatusActionPreview(change,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionMemoryStatusActionResult) = orionMemoryTransaction(database, barrier,
        OrionMemoryTransactionBlocked.Action(OrionMemoryActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionMemoryTransactionBlocked.Action) OrionMemoryStatusActionResult.Blocked(it.reason) else throw it
        }, block = block)

    private fun abort(reason: OrionMemoryActionFailure): Nothing = throw OrionMemoryTransactionBlocked.Action(reason)

    private fun failure(outcome: ActionStoreOutcome) = when (outcome) {
        ActionStoreOutcome.IDEMPOTENCY_CONFLICT -> OrionMemoryActionFailure.IDEMPOTENCY_CONFLICT
        ActionStoreOutcome.CONFLICT -> OrionMemoryActionFailure.CONFLICT
        ActionStoreOutcome.NOT_FOUND -> OrionMemoryActionFailure.NOT_FOUND
        ActionStoreOutcome.INVALID_STATE -> OrionMemoryActionFailure.INVALID_STATE
        ActionStoreOutcome.KEY_UNAVAILABLE, ActionStoreOutcome.KEY_INVALIDATED -> OrionMemoryActionFailure.KEY_UNAVAILABLE
        ActionStoreOutcome.CONFIRMATION_STALE -> OrionMemoryActionFailure.CONFIRMATION_STALE
        ActionStoreOutcome.CONFIRMATION_MISMATCH -> OrionMemoryActionFailure.CONFIRMATION_MISMATCH
        ActionStoreOutcome.CONFIRMATION_EXPIRED -> OrionMemoryActionFailure.CONFIRMATION_EXPIRED
        ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED -> OrionMemoryActionFailure.POLICY_CHANGED
        else -> OrionMemoryActionFailure.UNREADABLE
    }

    companion object {
        internal fun normalized(id: UUID, change: ValidatedMemoryStatusChange, policyVersion: String): NormalizedActionRequest {
            val descriptor = checkNotNull(OrionMemoryStatusDescriptors.forName(change.skillName))
            return NormalizedActionRequest(id, descriptor.name, change.canonicalNormalizedArguments, change.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

package network.orion.app.memories

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.memories.ValidatedCreateMemory
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.policy.memories.*
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

internal enum class OrionMemoryActionFailure {
    STARTUP_GATED, POLICY_DENIED, POLICY_UNAVAILABLE, POLICY_CHANGED,
    IDEMPOTENCY_CONFLICT, CONFLICT, NOT_FOUND, INVALID_STATE, UNREADABLE,
    KEY_UNAVAILABLE, CONFIRMATION_MISMATCH, CONFIRMATION_STALE, CONFIRMATION_EXPIRED,
}

/** Selected display content and exact binding, never proof of a gesture or authority. */
internal data class OrionMemoryActionPreview(
    val memory: ValidatedCreateMemory,
    val actionId: UUID,
    val version: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
    val status: ActionStatus,
) {
    override fun toString() = "OrionMemoryActionPreview(<redacted>)"
}

internal sealed interface OrionMemoryActionResult {
    data class Ready(val preview: OrionMemoryActionPreview) : OrionMemoryActionResult {
        override fun toString() = "OrionMemoryActionResult.Ready(<redacted>)"
    }
    data class AlreadySucceeded(val actionId: UUID) : OrionMemoryActionResult {
        override fun toString() = "OrionMemoryActionResult.AlreadySucceeded(<redacted>)"
    }
    data class Forgotten(val actionId: UUID) : OrionMemoryActionResult {
        override fun toString() = "OrionMemoryActionResult.Forgotten(<redacted>)"
    }
    data class Blocked(val reason: OrionMemoryActionFailure) : OrionMemoryActionResult
}

/**
 * MAIN-owned blocking adapter, off the UI thread, used by the memory kernel through
 * encrypted durable ingress and the actual recovery barrier.
 * The caller captures a NEW actual UI admission when the user confirms a preview.
 * Prepare and confirm are separate from execution; no memory/event is written here.
 */
internal class OrionCreateMemoryActionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
) {
    private val idempotency = IdempotencyKeyPolicy("MAIN_UI", "memory.create", Duration.ofDays(90), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun prepare(admission: OrionCreateMemoryAdmission): OrionMemoryActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val memory = admission.memory
        val incoming = normalized(UUID.randomUUID(), memory, version)
        val registration = actions.register(incoming, idempotency, memory.idempotencyKey.toString(),
            memory.correlationId, Sensitivity.SENSITIVE, true)
        if (registration.outcome != ActionStoreOutcome.REGISTERED && registration.outcome != ActionStoreOutcome.DEDUP)
            abort(failure(registration.outcome))
        val id = checkNotNull(registration.actionId)
        if (orionMemoryWasForgotten(database, clock, memory)) return@admittedTransaction OrionMemoryActionResult.Forgotten(id)
        var stored = read(id)
        requireExact(stored, memory, version)
        if (stored.status == ActionStatus.PENDING) {
            val waiting = actions.requireConfirmation(id, stored.version)
            if (waiting.outcome != ActionStoreOutcome.APPLIED) abort(failure(waiting.outcome))
            stored = read(id)
        }
        when (stored.status) {
            ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED -> OrionMemoryActionResult.Ready(preview(memory, stored))
            ActionStatus.SUCCEEDED -> OrionMemoryActionResult.AlreadySucceeded(id)
            else -> abort(OrionMemoryActionFailure.INVALID_STATE)
        }
    }

    fun confirm(admission: OrionCreateMemoryAdmission, displayed: OrionMemoryActionPreview): OrionMemoryActionResult =
        confirmWithReceipt(admission, displayed, null)

    /** Non-null receipt was recorded at the original human gesture, never during replay. */
    fun confirmWithReceipt(admission: OrionCreateMemoryAdmission, displayed: OrionMemoryActionPreview,
        originalReceipt: ActionConfirmationSnapshot?): OrionMemoryActionResult = admittedTransaction {
        val version = policyVersion(admission)
        val stored = read(displayed.actionId)
        if (stored.version != displayed.version) abort(OrionMemoryActionFailure.CONFLICT)
        if (stored.status != ActionStatus.NEEDS_CONFIRMATION || displayed.status != ActionStatus.NEEDS_CONFIRMATION)
            abort(OrionMemoryActionFailure.INVALID_STATE)
        if (version != displayed.policyVersion || stored.request.policyVersion != version)
            abort(OrionMemoryActionFailure.POLICY_CHANGED)
        requireExact(stored, admission.memory, version)
        val binding = ActionConfirmationBinding(clock)
        if (binding.prepare(stored.request).confirmationFingerprint != displayed.confirmationFingerprint ||
            displayed.memory.canonicalNormalizedArguments != admission.memory.canonicalNormalizedArguments ||
            displayed.memory.normalizedTarget != admission.memory.normalizedTarget ||
            displayed.memory.idempotencyKey != admission.memory.idempotencyKey)
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
        OrionMemoryActionResult.Ready(preview(admission.memory, read(displayed.actionId)))
    }

    private fun policyVersion(admission: OrionCreateMemoryAdmission): String = when (val decision = admission.evaluate()) {
        is CreateMemoryPolicyDecision.NeedsConfirmation -> decision.policyVersion
        is CreateMemoryPolicyDecision.Unavailable -> abort(OrionMemoryActionFailure.POLICY_UNAVAILABLE)
        is CreateMemoryPolicyDecision.Denied -> abort(if (decision.reason == CreateMemoryPolicyDenial.STARTUP_GATED)
            OrionMemoryActionFailure.STARTUP_GATED else OrionMemoryActionFailure.POLICY_DENIED)
    }

    private fun requireExact(stored: StoredAction, memory: ValidatedCreateMemory, version: String) {
        if (stored.request.policyVersion != version) abort(OrionMemoryActionFailure.POLICY_CHANGED)
        if (!stored.requiresConfirmation || stored.request != normalized(stored.request.actionId, memory, version))
            abort(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
        val row = database.actions().find(stored.request.actionId.toString()) ?: abort(OrionMemoryActionFailure.NOT_FOUND)
        if (row.idempotencyScope != idempotency.scope || row.idempotencyKey != memory.idempotencyKey.toString())
            abort(OrionMemoryActionFailure.CONFIRMATION_MISMATCH)
    }

    private fun read(id: UUID): StoredAction = when (val result = actions.read(id)) {
        is ActionReadResult.Ready -> result.action
        is ActionReadResult.Blocked -> abort(failure(result.reason))
    }

    private fun preview(memory: ValidatedCreateMemory, stored: StoredAction) = OrionMemoryActionPreview(memory,
        stored.request.actionId, stored.version, ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,
        stored.request.policyVersion, stored.status)

    private fun admittedTransaction(block: () -> OrionMemoryActionResult) = orionMemoryTransaction(database, barrier,
        OrionMemoryTransactionBlocked.Action(OrionMemoryActionFailure.STARTUP_GATED), blocked = {
            if (it is OrionMemoryTransactionBlocked.Action) OrionMemoryActionResult.Blocked(it.reason) else throw it
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
        internal fun normalized(id: UUID, memory: ValidatedCreateMemory, policyVersion: String): NormalizedActionRequest {
            val descriptor = OrionCreateMemoryDescriptor.value
            return NormalizedActionRequest(id, descriptor.name, memory.canonicalNormalizedArguments, memory.normalizedTarget,
                descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
                descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion)
        }
    }
}

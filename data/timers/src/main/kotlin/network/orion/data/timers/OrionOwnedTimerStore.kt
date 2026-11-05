package network.orion.data.timers

import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.timers.ValidatedCreateTimer
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

data class TimerExecutionOwner(val actionId: UUID, val executionId: UUID, val version: Long) {
    override fun toString() = "TimerExecutionOwner(<redacted>)"
}

enum class OwnedTimerWriteResult { CREATED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, TIMER_ID_CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

/** MAIN provides the real implementation. A test port or IDs are not authorization credentials. */
fun interface OrionOwnedTimerWriter {
    fun create(timer: ValidatedCreateTimer, owner: TimerExecutionOwner): OwnedTimerWriteResult
}

/**
 * Called only inside the Action Engine's admitted writer transaction. Neither
 * this adapter nor the Skill starts/finishes an execution or grants authority.
 * A direct call without that transaction and active exact ownership cannot write.
 */
class OrionOwnedTimerStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedTimerWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val timers = OrionTimerStore(database, encryption, clock)

    /** Read-only verification before returning a previously committed action result. */
    fun matchesExisting(timer: ValidatedCreateTimer): Boolean {
        if (database.timers().find(timer.timerId.toString())?.durationMillis != timer.durationMillis) return false
        val read = timers.readContent(timer.timerId) as? FieldDecryptionResult.Plaintext ?: return false
        val clear = read.copyBytes()
        val expected = timer.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        return try { clear.contentEquals(expected) } finally { clear.fill(0); expected.fill(0) }
    }

    override fun create(timer: ValidatedCreateTimer, owner: TimerExecutionOwner): OwnedTimerWriteResult {
        if (!database.inTransaction()) return OwnedTimerWriteResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedTimerWriteResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedTimerWriteResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedTimerWriteResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedTimerWriteResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedTimerWriteResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedTimerWriteResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedTimerWriteResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedTimerWriteResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedTimerWriteResult.KEY_UNAVAILABLE else OwnedTimerWriteResult.UNREADABLE
        }
        val descriptor = OrionCreateTimerDescriptor.value
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, timer.canonicalNormalizedArguments,
            timer.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != "timer.create" ||
            row.idempotencyKey != timer.idempotencyKey.toString()) return OwnedTimerWriteResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedTimerWriteResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(stored.request, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedTimerWriteResult.NOT_OWNED
        val content = timer.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        try {
            val previous = timers.readContent(timer.timerId)
            if (previous != null && database.timers().find(timer.timerId.toString())?.durationMillis != timer.durationMillis)
                return OwnedTimerWriteResult.TIMER_ID_CONFLICT
            if (previous != null) {
                if (previous is FieldDecryptionResult.Failure) return if (previous.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    previous.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedTimerWriteResult.KEY_UNAVAILABLE else OwnedTimerWriteResult.UNREADABLE
                val clear = (previous as FieldDecryptionResult.Plaintext).copyBytes()
                return try {
                    if (clear.contentEquals(content)) OwnedTimerWriteResult.DEDUP else OwnedTimerWriteResult.TIMER_ID_CONFLICT
                } finally { clear.fill(0) }
            }
            return when (val created = timers.create(timer.timerId, content, timer.durationMillis, Sensitivity.SENSITIVE, timer.correlationId)) {
                is TimerCreationResult.Created -> OwnedTimerWriteResult.CREATED
                is TimerCreationResult.Blocked -> if (created.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    created.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedTimerWriteResult.KEY_UNAVAILABLE else OwnedTimerWriteResult.UNREADABLE
            }
        } finally { content.fill(0) }
    }
}

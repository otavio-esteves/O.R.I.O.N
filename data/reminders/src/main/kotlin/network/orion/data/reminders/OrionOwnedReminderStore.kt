package network.orion.data.reminders

import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.reminders.ValidatedCreateReminder
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

data class ReminderExecutionOwner(val actionId: UUID, val executionId: UUID, val version: Long) {
    override fun toString() = "ReminderExecutionOwner(<redacted>)"
}

enum class OwnedReminderWriteResult { CREATED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, REMINDER_ID_CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

/** MAIN provides the real implementation. A test port or IDs are not authorization credentials. */
fun interface OrionOwnedReminderWriter {
    fun create(reminder: ValidatedCreateReminder, owner: ReminderExecutionOwner): OwnedReminderWriteResult
}

/**
 * Called only inside the Action Engine's admitted writer transaction. Neither
 * this adapter nor the Skill starts/finishes an execution or grants authority.
 * A direct call without that transaction and active exact ownership cannot write.
 */
class OrionOwnedReminderStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedReminderWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val reminders = OrionReminderStore(database, encryption, clock)

    /** Read-only verification before returning a previously committed action result. */
    fun matchesExisting(reminder: ValidatedCreateReminder): Boolean {
        val read = reminders.readContent(reminder.reminderId) as? FieldDecryptionResult.Plaintext ?: return false
        val clear = read.copyBytes()
        val expected = reminder.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        return try { clear.contentEquals(expected) } finally { clear.fill(0); expected.fill(0) }
    }

    override fun create(reminder: ValidatedCreateReminder, owner: ReminderExecutionOwner): OwnedReminderWriteResult {
        if (!database.inTransaction()) return OwnedReminderWriteResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedReminderWriteResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedReminderWriteResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedReminderWriteResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedReminderWriteResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedReminderWriteResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedReminderWriteResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedReminderWriteResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedReminderWriteResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedReminderWriteResult.KEY_UNAVAILABLE else OwnedReminderWriteResult.UNREADABLE
        }
        val descriptor = OrionCreateReminderDescriptor.value
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, reminder.canonicalNormalizedArguments,
            reminder.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != "reminder.create" ||
            row.idempotencyKey != reminder.idempotencyKey.toString()) return OwnedReminderWriteResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedReminderWriteResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(stored.request, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedReminderWriteResult.NOT_OWNED
        val content = reminder.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        try {
            val previous = reminders.readContent(reminder.reminderId)
            if (previous != null) {
                if (previous is FieldDecryptionResult.Failure) return if (previous.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    previous.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedReminderWriteResult.KEY_UNAVAILABLE else OwnedReminderWriteResult.UNREADABLE
                val clear = (previous as FieldDecryptionResult.Plaintext).copyBytes()
                return try {
                    if (clear.contentEquals(content)) OwnedReminderWriteResult.DEDUP else OwnedReminderWriteResult.REMINDER_ID_CONFLICT
                } finally { clear.fill(0) }
            }
            return when (val created = reminders.create(reminder.reminderId, content, Sensitivity.SENSITIVE, reminder.scheduledAt, reminder.zoneId, reminder.correlationId)) {
                is ReminderWriteResult.Stored -> OwnedReminderWriteResult.CREATED
                is ReminderWriteResult.Blocked -> if (created.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    created.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedReminderWriteResult.KEY_UNAVAILABLE else OwnedReminderWriteResult.UNREADABLE
                is ReminderWriteResult.Rejected -> OwnedReminderWriteResult.REMINDER_ID_CONFLICT
            }
        } finally { content.fill(0) }
    }
}

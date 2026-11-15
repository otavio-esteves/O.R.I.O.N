package network.orion.data.reminders

import network.orion.core.actions.*
import network.orion.core.commands.reminders.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.reminders.OrionReminderStatusDescriptors
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*

enum class OwnedReminderStatusResult { APPLIED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

fun interface OrionOwnedReminderStatusWriter {
    fun change(change: ValidatedReminderStatusChange, owner: ReminderExecutionOwner): OwnedReminderStatusResult
}

/** Verifies actual RUNNING ownership inside the engine's admitted transaction; IDs alone cannot write. */
class OrionOwnedReminderStatusStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedReminderStatusWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val reminders = OrionReminderStore(database, encryption, clock)

    /** Verify committed action and success audit, independently of later mutations and outbox pruning. */
    fun matchesExisting(change: ValidatedReminderStatusChange, actionId: java.util.UUID): Boolean {
        val action = database.actions().find(actionId.toString()) ?: return false
        if (action.status != ActionStatus.SUCCEEDED || action.idempotencyScope != change.skillName ||
            action.idempotencyKey != change.idempotencyKey.toString()) return false
        val stored = (actions.read(actionId) as? ActionReadResult.Ready)?.action ?: return false
        val descriptor = OrionReminderStatusDescriptors.forName(change.skillName) ?: return false
        val expected = NormalizedActionRequest(actionId, descriptor.name, change.canonicalNormalizedArguments,
            change.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation) return false
        val confirmation = stored.confirmation ?: return false
        val binding = ActionConfirmationBinding(clock).prepare(expected)
        // Historical success does not expire; only its original consent/material identity is checked here.
        if (confirmation.actionId != actionId || confirmation.confirmationFingerprint != binding.confirmationFingerprint ||
            confirmation.normalizedPayloadHash != binding.normalizedPayloadHash ||
            confirmation.confirmedPayloadHash != binding.normalizedPayloadHash ||
            confirmation.policyVersionAtConfirmation != expected.policyVersion) return false
        val audit = database.actions().auditFor(actionId.toString()).lastOrNull() ?: return false
        if (audit.fromStatus != ActionStatus.RUNNING || audit.toStatus != ActionStatus.SUCCEEDED ||
            audit.actionVersion != action.version || audit.occurredAt != action.executedAt ||
            audit.correlationId != action.correlationId) return false
        val target = database.reminders().find(change.reminderId.toString()) ?: return false
        if (target.version < change.expectedVersion + 1) return false
        if (target.version == change.expectedVersion + 1 && !matches(target, change)) return false
        // Action success, its audit and the local effect committed in one writer transaction.
        // The audit is retained with the ActionRequest; the publication outbox may be pruned earlier.
        return true
    }

    override fun change(change: ValidatedReminderStatusChange, owner: ReminderExecutionOwner): OwnedReminderStatusResult {
        if (!database.inTransaction()) return OwnedReminderStatusResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedReminderStatusResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedReminderStatusResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedReminderStatusResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedReminderStatusResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedReminderStatusResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedReminderStatusResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedReminderStatusResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedReminderStatusResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedReminderStatusResult.KEY_UNAVAILABLE else OwnedReminderStatusResult.UNREADABLE
        }
        val descriptor = OrionReminderStatusDescriptors.forName(change.skillName) ?: return OwnedReminderStatusResult.PAYLOAD_MISMATCH
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, change.canonicalNormalizedArguments,
            change.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != change.skillName ||
            row.idempotencyKey != change.idempotencyKey.toString()) return OwnedReminderStatusResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedReminderStatusResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(expected, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedReminderStatusResult.NOT_OWNED
        val target = database.reminders().find(change.reminderId.toString()) ?: return OwnedReminderStatusResult.CONFLICT
        if (target.version == change.expectedVersion + 1 && matches(target, change)) return OwnedReminderStatusResult.DEDUP
        if (target.version != change.expectedVersion || target.status != ReminderStatus.PENDING ||
            change.operation == ReminderStatusOperation.SNOOZE && checkNotNull(change.scheduledAt).toEpochMilli() <= target.scheduledAt)
            return OwnedReminderStatusResult.CONFLICT
        val result = if (change.operation == ReminderStatusOperation.CANCEL)
            reminders.cancel(change.reminderId, change.expectedVersion, change.correlationId)
        else {
            val now = clock.wallNow().toEpochMilli()
            database.reminders().snoozeWithEvent(change.reminderId.toString(), change.expectedVersion,
                checkNotNull(change.scheduledAt).toEpochMilli(), checkNotNull(change.zoneId).id, now,
                DomainEventOutboxEntity(java.util.UUID.randomUUID().toString(), change.reminderId.toString(),
                    change.expectedVersion + 1, "REMINDER_SNOOZED", 1, target.sensitivity, now, change.correlationId.toString()))
        }
        return if (result == ActionMutation.APPLIED) OwnedReminderStatusResult.APPLIED else OwnedReminderStatusResult.CONFLICT
    }

    private fun matches(row: ReminderEntity, change: ValidatedReminderStatusChange) = row.status == desired(change) &&
        (change.operation != ReminderStatusOperation.SNOOZE || row.scheduledAt == change.scheduledAt?.toEpochMilli() && row.zoneId == change.zoneId?.id)

    private fun desired(change: ValidatedReminderStatusChange) = when (change.operation) {
        ReminderStatusOperation.SNOOZE -> ReminderStatus.PENDING
        ReminderStatusOperation.CANCEL -> ReminderStatus.CANCELLED
    }
}

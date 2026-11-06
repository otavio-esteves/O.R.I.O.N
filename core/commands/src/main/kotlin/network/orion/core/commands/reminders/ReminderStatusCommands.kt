package network.orion.core.commands.reminders

import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import network.orion.core.commands.Command

enum class ReminderStatusOperation { SNOOZE, CANCEL }
enum class ReminderStatusResult { QUEUED, REJECTED, UNAVAILABLE }

/** Expected version is domain concurrency data, never authorization or a confirmation flag. */
sealed interface ReminderStatusCommand : Command<ReminderStatusResult> {
    val reminderId: UUID
    val expectedVersion: Long
    val idempotencyKey: UUID
    val correlationId: UUID
    val scheduledAt: Instant? get() = null
    val zoneId: ZoneId? get() = null
    val operation: ReminderStatusOperation
}

data class SnoozeReminder(override val reminderId: UUID, override val expectedVersion: Long,
    override val scheduledAt: Instant, override val zoneId: ZoneId,
    override val idempotencyKey: UUID, override val correlationId: UUID) : ReminderStatusCommand {
    override val operation get() = ReminderStatusOperation.SNOOZE
    override fun toString() = "SnoozeReminder(<redacted>)"
}

data class CancelReminder(override val reminderId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : ReminderStatusCommand {
    override val operation get() = ReminderStatusOperation.CANCEL
    override fun toString() = "CancelReminder(<redacted>)"
}

enum class ReminderStatusRejection { INVALID_VERSION, INVALID_TIME }

sealed interface ReminderStatusPreparation {
    class Ready internal constructor(val change: ValidatedReminderStatusChange) : ReminderStatusPreparation {
        override fun toString() = "ReminderStatusPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: ReminderStatusRejection) : ReminderStatusPreparation
}

/** Fixed schema-v1 material: reminderId then expectedVersion. Operation is bound by static Skill name. */
class ValidatedReminderStatusChange internal constructor(
    val reminderId: UUID, val expectedVersion: Long, val idempotencyKey: UUID,
    val correlationId: UUID, val operation: ReminderStatusOperation,
    val scheduledAt: Instant?, val zoneId: ZoneId?,
) {
    val skillName get() = when (operation) {
        ReminderStatusOperation.SNOOZE -> "reminder.snooze"
        ReminderStatusOperation.CANCEL -> "reminder.cancel"
    }
    val argumentSchemaVersion get() = 1
    val normalizedTarget get() = "reminder/$reminderId"
    val canonicalNormalizedArguments get() = "{\"reminderId\":\"$reminderId\",\"expectedVersion\":$expectedVersion" +
        (if (operation == ReminderStatusOperation.SNOOZE) ",\"scheduledAtEpochMillis\":${checkNotNull(scheduledAt).toEpochMilli()},\"zoneId\":\"${checkNotNull(zoneId).id}\"" else "") + "}"
    override fun toString() = "ValidatedReminderStatusChange(<redacted>)"
}

object OrionReminderStatusPreparer {
    fun prepare(command: ReminderStatusCommand): ReminderStatusPreparation {
        if (command.expectedVersion < 0 || command.expectedVersion == Long.MAX_VALUE)
            return ReminderStatusPreparation.Rejected(ReminderStatusRejection.INVALID_VERSION)
        if (command.operation == ReminderStatusOperation.SNOOZE) {
            val at = command.scheduledAt ?: return ReminderStatusPreparation.Rejected(ReminderStatusRejection.INVALID_TIME)
            val zone = command.zoneId ?: return ReminderStatusPreparation.Rejected(ReminderStatusRejection.INVALID_TIME)
            if (OrionCreateReminderPreparer.prepare(CreateReminder(command.reminderId, "snooze", at, zone,
                command.idempotencyKey, command.correlationId)) !is CreateReminderPreparation.Ready)
                return ReminderStatusPreparation.Rejected(ReminderStatusRejection.INVALID_TIME)
        }
        return ReminderStatusPreparation.Ready(ValidatedReminderStatusChange(command.reminderId, command.expectedVersion,
            command.idempotencyKey, command.correlationId, command.operation, command.scheduledAt, command.zoneId))
    }
}

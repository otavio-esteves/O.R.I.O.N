package network.orion.core.commands.reminders

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import network.orion.core.commands.Command

/**
 * Request for a one-shot reminder at an already-resolved civil instant.
 *
 * Keep reminderId/idempotencyKey on replay. No authority, confirmation, delivery
 * precision or OS scheduling method is supplied by the producer. Local-time/DST
 * resolution and recurrence are separate contracts, not device-default behavior.
 * This contract installs no handler and creates no reminder or ActionRequest.
 */
data class CreateReminder(
    val reminderId: UUID,
    val title: String,
    val scheduledAt: Instant,
    val zoneId: ZoneId,
    val idempotencyKey: UUID,
    val correlationId: UUID,
) : Command<CreateReminderResult> {
    override fun toString() = "CreateReminder(<redacted>)"
}

/** Operational execution remains unavailable in this preparation-only increment. */
enum class CreateReminderResult { QUEUED, REJECTED, UNAVAILABLE }

enum class CreateReminderRejection {
    BLANK_TITLE, TITLE_TOO_LARGE, INVALID_UNICODE, INVALID_TITLE_CHARACTER,
    UNSUPPORTED_TIME_PRECISION, TIME_OUT_OF_RANGE,
}

sealed interface CreateReminderPreparation {
    class Ready internal constructor(val reminder: ValidatedCreateReminder) : CreateReminderPreparation {
        override fun toString() = "CreateReminderPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: CreateReminderRejection) : CreateReminderPreparation {
        override fun toString() = "CreateReminderPreparation.Rejected(reason=$reason)"
    }
}

/**
 * Immutable validated input to policy, not execution authority or a dry run.
 * Schema-v1 material binds ID, normalized title, exact epoch milliseconds and
 * explicit ZoneId.id. Replay/trace metadata remains outside canonical arguments.
 * Aliases/offset zone IDs remain distinct; no system default zone is consulted.
 */
class ValidatedCreateReminder internal constructor(
    val reminderId: UUID,
    val title: String,
    val scheduledAt: Instant,
    val zoneId: ZoneId,
    val idempotencyKey: UUID,
    val correlationId: UUID,
    val scheduledAtEpochMillis: Long,
    val canonicalNormalizedArguments: String,
) {
    val argumentSchemaVersion: Int get() = 1
    val normalizedTarget: String get() = "reminder/$reminderId"
    override fun toString() = "ValidatedCreateReminder(<redacted>)"
}

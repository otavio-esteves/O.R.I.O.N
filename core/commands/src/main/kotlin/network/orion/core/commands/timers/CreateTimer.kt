package network.orion.core.commands.timers

import java.util.UUID
import network.orion.core.commands.Command

/**
 * A request to create one timer through the Core mutation pipeline.
 *
 * The issuer retains the same timer ID and idempotency key across retries; a new
 * correlation ID may trace a replay. These IDs are neither action ownership nor
 * authorization. No client policy, permission, capability or confirmation field
 * is accepted. This contract does not install a dispatcher or create an action.
 *
 * QUEUED reports durable admission only. The resulting ActionRequest still needs
 * exact human confirmation and successful execution before the timer exists.
 */
data class CreateTimer(
    val timerId: UUID,
    val title: String,
    val durationMillis: Long,
    val idempotencyKey: UUID,
    val correlationId: UUID,
) : Command<CreateTimerResult> {
    override fun toString(): String = "CreateTimer(<redacted>)"
}

/** Admission outcome only: QUEUED never claims that a timer was created. */
enum class CreateTimerResult { QUEUED, REJECTED, UNAVAILABLE }

enum class CreateTimerRejection { BLANK_TITLE, TITLE_TOO_LARGE, INVALID_UNICODE, INVALID_TITLE_CHARACTER, INVALID_DURATION }

sealed interface CreateTimerPreparation {
    class Ready internal constructor(val timer: ValidatedCreateTimer) : CreateTimerPreparation {
        override fun toString(): String = "CreateTimerPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: CreateTimerRejection) : CreateTimerPreparation {
        override fun toString(): String = "CreateTimerPreparation.Rejected(reason=$reason)"
    }
}

/**
 * Immutable validated input to future policy, never an ActionRequest or authority.
 * User content is accessible to pipeline code and must be excluded from diagnostics.
 *
 * Schema v1 canonical arguments are UTF-8 JSON with exactly this member order:
 * {"timerId":"lower-case UUID","title":"NFC and edge-trimmed title","durationMillis":positiveLong}.
 * Quote and reverse solidus are escaped; other permitted Unicode is literal.
 * The timer ID is material; replay/correlation IDs stay outside canonical arguments.
 * Policy will supply authority/version and construct the actual confirmation binding.
 */
class ValidatedCreateTimer internal constructor(
    val timerId: UUID,
    val title: String,
    val durationMillis: Long,
    val idempotencyKey: UUID,
    val correlationId: UUID,
    val canonicalNormalizedArguments: String,
) {
    val argumentSchemaVersion: Int get() = 1
    val normalizedTarget: String get() = "timer/$timerId"
    override fun toString(): String = "ValidatedCreateTimer(<redacted>)"
}

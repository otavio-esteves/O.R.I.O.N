package network.orion.core.commands.tasks

import java.util.UUID
import network.orion.core.commands.Command

/**
 * A request to create one task through the future Core mutation pipeline.
 *
 * The issuer retains the same task ID and idempotency key across retries; a new
 * correlation ID may trace a replay. These IDs are neither action ownership nor
 * authorization. No client policy, permission, capability or confirmation field
 * is accepted. This contract does not install a dispatcher or create an action.
 *
 * This initial increment declares only operational unavailability. Preparation
 * has its own result type and cannot be mistaken for a completed mutation.
 */
data class CreateTask(
    val taskId: UUID,
    val title: String,
    val idempotencyKey: UUID,
    val correlationId: UUID,
) : Command<CreateTaskResult> {
    override fun toString(): String = "CreateTask(<redacted>)"
}

/** Actual creation results require the later authorized execution/receipt contract. */
enum class CreateTaskResult { UNAVAILABLE }

enum class CreateTaskRejection { BLANK_TITLE, TITLE_TOO_LARGE, INVALID_UNICODE, INVALID_TITLE_CHARACTER }

sealed interface CreateTaskPreparation {
    class Ready internal constructor(val task: ValidatedCreateTask) : CreateTaskPreparation {
        override fun toString(): String = "CreateTaskPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: CreateTaskRejection) : CreateTaskPreparation {
        override fun toString(): String = "CreateTaskPreparation.Rejected(reason=$reason)"
    }
}

/**
 * Immutable validated input to future policy, never an ActionRequest or authority.
 * User content is accessible to pipeline code and must be excluded from diagnostics.
 *
 * Schema v1 canonical arguments are UTF-8 JSON with exactly this member order:
 * {"taskId":"lower-case UUID","title":"NFC and edge-trimmed title"}.
 * Quote and reverse solidus are escaped; other permitted Unicode is literal.
 * The task ID is material; replay/correlation IDs stay outside canonical arguments.
 * Policy will supply authority/version and construct the actual confirmation binding.
 */
class ValidatedCreateTask internal constructor(
    val taskId: UUID,
    val title: String,
    val idempotencyKey: UUID,
    val correlationId: UUID,
    val canonicalNormalizedArguments: String,
) {
    val argumentSchemaVersion: Int get() = 1
    val normalizedTarget: String get() = "task/$taskId"
    override fun toString(): String = "ValidatedCreateTask(<redacted>)"
}

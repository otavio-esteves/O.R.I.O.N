package network.orion.core.commands.memories

import java.util.UUID
import network.orion.core.commands.Command

/**
 * A request to remember one user-declared personal statement through the Core pipeline.
 * Origin/classification are fixed by schema-v1; callers cannot promote inferred data.
 *
 * The issuer retains the same memory ID and idempotency key across retries; a new
 * correlation ID may trace a replay. These IDs are neither action ownership nor
 * authorization. No client policy, permission, capability or confirmation field
 * is accepted. This contract does not install a dispatcher or create an action.
 *
 * QUEUED reports durable admission only. The resulting ActionRequest still needs
 * exact human confirmation and successful execution before the memory exists.
 */
data class CreateMemory(
    val memoryId: UUID,
    val content: String,
    val idempotencyKey: UUID,
    val correlationId: UUID,
) : Command<CreateMemoryResult> {
    override fun toString(): String = "CreateMemory(<redacted>)"
}

/** Admission outcome only: QUEUED never claims that a memory was created. */
enum class CreateMemoryResult { QUEUED, REJECTED, UNAVAILABLE, FORGOTTEN }

enum class CreateMemoryRejection { BLANK_CONTENT, CONTENT_TOO_LARGE, INVALID_UNICODE, INVALID_CONTENT_CHARACTER }

sealed interface CreateMemoryPreparation {
    class Ready internal constructor(val memory: ValidatedCreateMemory) : CreateMemoryPreparation {
        override fun toString(): String = "CreateMemoryPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: CreateMemoryRejection) : CreateMemoryPreparation {
        override fun toString(): String = "CreateMemoryPreparation.Rejected(reason=$reason)"
    }
}

/**
 * Immutable validated input to future policy, never an ActionRequest or authority.
 * User content is accessible to pipeline code and must be excluded from diagnostics.
 *
 * Schema v1 canonical arguments are UTF-8 JSON with exactly this member order:
 * {"memoryId":"lower-case UUID","content":"NFC and edge-trimmed content"}.
 * Quote and reverse solidus are escaped; other permitted Unicode is literal.
 * The memory ID is material; replay/correlation IDs stay outside canonical arguments.
 * Policy will supply authority/version and construct the actual confirmation binding.
 */
class ValidatedCreateMemory internal constructor(
    val memoryId: UUID,
    val content: String,
    val idempotencyKey: UUID,
    val correlationId: UUID,
    val canonicalNormalizedArguments: String,
) {
    val argumentSchemaVersion: Int get() = 1
    val normalizedTarget: String get() = "memory/$memoryId"
    override fun toString(): String = "ValidatedCreateMemory(<redacted>)"
}

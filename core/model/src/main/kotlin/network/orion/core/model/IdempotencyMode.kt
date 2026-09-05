package network.orion.core.model

/**
 * Declared idempotency mechanism for Skill metadata (Architecture V3.2 §102).
 *
 * A mode is not proof of replay safety and does not implement key retention,
 * canonical payload binding, conflict handling or external verification.
 */
enum class IdempotencyMode {
    NATURAL,
    KEYED,
    VERIFY_THEN_RETRY,
    NONE,
}

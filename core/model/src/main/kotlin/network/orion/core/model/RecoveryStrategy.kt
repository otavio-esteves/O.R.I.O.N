package network.orion.core.model

/**
 * Skill-declared recovery strategy (Architecture V3.2 §53).
 *
 * Metadata only: selecting a value does not execute or schedule recovery.
 * Abandoned execution ownership never proves that an external effect did not occur.
 */
enum class RecoveryStrategy {
    /** Only for operations whose duplication is demonstrably safe. */
    RETRY_SAFE,
    /** Verify external state before considering repetition. */
    VERIFY_THEN_RETRY,
    /** Do not retry; preserve failure or uncertainty according to the operation contract. */
    NO_RETRY,
    /** Requires a later review decision. */
    MANUAL_REVIEW,
}

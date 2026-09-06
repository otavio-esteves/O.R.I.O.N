package network.orion.core.events

/**
 * Intended retention/delivery classification (Architecture V3.2 §12).
 *
 * This value alone provides no storage, delivery or retention guarantee.
 * Never serialize ordinals as a durable or IPC contract.
 */
enum class EventPersistence {
    /** Temporary fact that may be lost. */
    EPHEMERAL,
    /** Must survive process death through the transactional outbox. */
    DURABLE,
    /** Audit fact with the longer retention required by its audit policy. */
    AUDIT,
}

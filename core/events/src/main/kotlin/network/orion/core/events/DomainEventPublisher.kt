package network.orion.core.events

/**
 * Publication port for facts that have already occurred (Architecture V3.2 §§9–13).
 *
 * Publication is not an instruction to execute a command. For durable changes,
 * commit state and the outbox event atomically before the outbox dispatcher calls
 * this port. Consumers must tolerate duplicate delivery of the same eventId.
 *
 * Implementations propagate failures/cancellation. Returning does not imply a
 * distributed exactly-once guarantee. Retry bounds and retention belong to the
 * outbox contract, not this interface. No global event ordering is promised.
 */
interface DomainEventPublisher {
    suspend fun publish(event: OrionEvent<*>)
}

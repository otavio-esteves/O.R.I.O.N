package network.orion.core.events

import java.time.Instant
import java.util.UUID
import network.orion.core.model.Sensitivity

/**
 * In-memory envelope for an already-occurred fact (Architecture V3.2 §§10–13).
 *
 * The producer supplies a globally unique [eventId] and wall-clock [occurredAt]
 * from its injected clock. Replay must preserve both; this type generates neither.
 * [correlationId] follows a logical flow; nullable [causationId] identifies the
 * command/event that caused this fact, absent for a root fact. No global order is
 * implied by identity or timestamp.
 *
 * [payload] must be an immutable domain value, preferably containing stable
 * references. Generic payload immutability cannot be enforced by this envelope.
 * [persistence] and [sensitivity] classify the event without authorizing storage.
 * DurablePayloadPolicy must minimize/encrypt persisted content before any outbox
 * write. This object is not a serialized DB/Binder format or an encrypted envelope.
 */
class OrionEvent<out P : Any>(
    val eventId: UUID,
    val type: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val correlationId: UUID,
    val causationId: UUID?,
    val source: String,
    val payload: P,
    val persistence: EventPersistence,
    val sensitivity: Sensitivity,
) {
    init {
        require(type.isNotBlank()) { "Event type must not be blank" }
        require(schemaVersion > 0) { "Event schema version must be positive" }
        require(source.isNotBlank()) { "Event source must not be blank" }
    }

    // Do not render payloads or arbitrary producer-supplied metadata in diagnostics.
    override fun toString(): String = "OrionEvent(payload=<redacted>)"
}

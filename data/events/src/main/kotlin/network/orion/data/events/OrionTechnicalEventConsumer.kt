package network.orion.data.events

import java.util.UUID
import network.orion.core.time.WallClock
import network.orion.data.database.DomainEventOutboxEntity
import network.orion.data.database.EventConsumption
import network.orion.data.database.OrionEventDao
import network.orion.data.database.TechnicalEventEntity

/**
 * MAIN-owned durable publication to a technical projection. No user content is
 * read, copied or logged. The DAO commits the projection and dedup receipt in one
 * transaction; a crash before dispatcher acknowledgement is safe to redeliver.
 * This consumer never dispatches commands or invokes Android side effects.
 * Registration in the production recovery graph remains subject to the F4 entry gate.
 */
class OrionTechnicalEventConsumer internal constructor(
    private val clock: WallClock,
    private val project: (String, String, TechnicalEventEntity) -> EventConsumption,
) : DurableEventConsumer {
    constructor(events: OrionEventDao, clock: WallClock) : this(clock, events::consumeTechnical)

    override fun consume(event: DomainEventOutboxEntity): DurableDeliveryResult {
        if (event.schemaVersion != 1 || event.eventType !in supportedEventTypes) {
            return DurableDeliveryResult.PERMANENT_FAILURE
        }
        val projectionId = UUID.nameUUIDFromBytes("$CONSUMER_ID:${event.eventId}".toByteArray(Charsets.UTF_8))
        project(event.eventId, CONSUMER_ID, TechnicalEventEntity(
            technicalEventId = projectionId.toString(),
            component = "DOMAIN_EVENT",
            eventCode = event.eventType,
            occurredAt = clock.wallNow().toEpochMilli(),
            correlationId = event.correlationId,
        ))
        // APPLIED and DEDUP both acknowledge the same committed fact.
        return DurableDeliveryResult.ACCEPTED
    }

    companion object {
        const val CONSUMER_ID = "orion_technical_projection_v1"

        /** Explicit routes: an unknown type/version requires review, never silent acknowledgement. */
        val supportedEventTypes: Set<String> = setOf(
            "TASK_CREATED", "TASK_COMPLETED", "TASK_CANCELLED",
            "REMINDER_CREATED", "REMINDER_UPDATED", "REMINDER_CANCELLED", "REMINDER_SNOOZED",
            "REMINDER_DELIVERY_CONFIRMED", "REMINDER_SCHEDULE_PLANNED", "REMINDER_SCHEDULED", "REMINDER_FIRED",
            "REMINDER_SERIES_ATTACHED", "REMINDER_OCCURRENCE_ADVANCED", "REMINDER_SERIES_COMPLETED", "REMINDER_SERIES_CANCELLED",
            "TIMER_CREATED", "TIMER_CANCELLED",
            "MEMORY_CREATED", "MEMORY_DELETED", "MEMORY_SUPERSEDED", "MEMORY_FORGOTTEN",
            "NOTIFICATION_POSTED",
        )

        fun routes(events: OrionEventDao, clock: WallClock): Map<String, List<DurableEventConsumer>> {
            val consumer = OrionTechnicalEventConsumer(events, clock)
            return supportedEventTypes.associateWith { listOf(consumer) }
        }
    }
}

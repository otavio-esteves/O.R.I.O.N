package network.orion.data.events

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.model.Sensitivity
import network.orion.core.time.WallClock
import network.orion.data.database.*
import org.junit.Assert.*
import org.junit.Test

class OrionTechnicalEventConsumerTest {
    private val clock = WallClock { Instant.ofEpochMilli(900) }
    private fun event(type: String = "TASK_CREATED", schema: Int = 1) = DomainEventOutboxEntity(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, type, schema,
        Sensitivity.SECRET, 100, UUID.randomUUID().toString(),
    )

    @Test fun allKnownFactsProjectOnlyAllowlistedTechnicalMetadata() {
        for (type in OrionTechnicalEventConsumer.supportedEventTypes) {
            val event = event(type)
            var calls = 0
            val consumer = OrionTechnicalEventConsumer(clock) { id, consumerId, projection ->
                calls++
                assertEquals(event.eventId, id)
                assertEquals(OrionTechnicalEventConsumer.CONSUMER_ID, consumerId)
                assertEquals("DOMAIN_EVENT", projection.component)
                assertEquals(type, projection.eventCode)
                assertEquals(event.correlationId, projection.correlationId)
                assertEquals(900L, projection.occurredAt)
                UUID.fromString(projection.technicalEventId)
                EventConsumption.APPLIED
            }
            assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(event))
            assertEquals(1, calls)
        }
    }

    @Test fun unknownTypeAndSchemaNeverReachProjectionWriter() {
        val consumer = OrionTechnicalEventConsumer(clock) { _, _, _ -> error("Must not project") }
        assertEquals(DurableDeliveryResult.PERMANENT_FAILURE, consumer.consume(event("UNRECOGNIZED")))
        assertEquals(DurableDeliveryResult.PERMANENT_FAILURE, consumer.consume(event(schema = 2)))
        assertEquals(DurableDeliveryResult.PERMANENT_FAILURE, consumer.consume(event(schema = 0)))
    }

    @Test fun duplicateReceiptIsAcknowledgedAndProjectionIdentityIsStable() {
        val ids = mutableListOf<String>()
        val consumer = OrionTechnicalEventConsumer(clock) { _, _, projection ->
            ids += projection.technicalEventId
            EventConsumption.DEDUP
        }
        val event = event()
        assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(event))
        assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(event.copy(attemptCount = 2, version = 3)))
        assertEquals(ids.first(), ids.last())
    }

    @Test fun failedTransactionAndCancellationPropagateWithoutAcknowledgement() {
        val failure = IllegalStateException("synthetic")
        val consumer = OrionTechnicalEventConsumer(clock) { _, _, _ -> throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { consumer.consume(event()) })
        val cancelled = OrionTechnicalEventConsumer(clock) { _, _, _ -> throw CancellationException() }
        assertThrows(CancellationException::class.java) { cancelled.consume(event()) }
    }
}

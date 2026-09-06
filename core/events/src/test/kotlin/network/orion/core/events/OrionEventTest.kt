package network.orion.core.events

import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import network.orion.core.model.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OrionEventTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val cause = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val instant = Instant.parse("2026-09-01T12:00:00Z")
    private data class Payload(val reference: String)

    private fun event(
        type: String = "TaskCreated",
        source: String = "tasks",
        schemaVersion: Int = 1,
        causationId: UUID? = cause,
    ) = OrionEvent(
        eventId = id,
        type = type,
        schemaVersion = schemaVersion,
        occurredAt = instant,
        correlationId = correlation,
        causationId = causationId,
        source = source,
        payload = Payload("task-reference"),
        persistence = EventPersistence.DURABLE,
        sensitivity = Sensitivity.PERSONAL,
    )

    @Test
    fun `preserves supplied identity version time causation and typed payload`() {
        val event = event(schemaVersion = 2)
        assertEquals(id, event.eventId)
        assertEquals("TaskCreated", event.type)
        assertEquals(2, event.schemaVersion)
        assertEquals(instant, event.occurredAt)
        assertEquals(correlation, event.correlationId)
        assertEquals(cause, event.causationId)
        assertEquals("tasks", event.source)
        val payload: Payload = event.payload
        assertEquals(Payload("task-reference"), payload)
        assertEquals(EventPersistence.DURABLE, event.persistence)
        assertEquals(Sensitivity.PERSONAL, event.sensitivity)
    }

    @Test
    fun `allows a root event without a cause`() {
        assertNull(event(causationId = null).causationId)
    }

    @Test
    fun `rejects missing type source and nonpositive schema versions`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { event(type = blank) }
            assertThrows(IllegalArgumentException::class.java) { event(source = blank) }
        }
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { event(schemaVersion = invalid) }
        }
    }

    @Test
    fun `validation failure does not include producer metadata`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            event(type = "private-type", source = "private-source", schemaVersion = 0)
        }
        assertFalse(failure.message.orEmpty().contains("private-type"))
        assertFalse(failure.message.orEmpty().contains("private-source"))
    }

    @Test
    fun `diagnostics never evaluate payload or expose metadata`() {
        val payload = object {
            override fun toString(): String = error("Payload must not be rendered")
        }
        for (sensitivity in Sensitivity.entries) {
            val event = OrionEvent(
                id, "private-type", 1, instant, correlation, cause, "private-source",
                payload, EventPersistence.AUDIT, sensitivity,
            )
            assertEquals("OrionEvent(payload=<redacted>)", event.toString())
        }
    }

    @Test
    fun `persistence classes match architecture section 12`() {
        assertEquals(setOf("EPHEMERAL", "DURABLE", "AUDIT"), EventPersistence.entries.map { it.name }.toSet())
    }

    @Test
    fun `publisher accepts the original event without changing replay identity`() {
        val original = event()
        val received = mutableListOf<OrionEvent<*>>()
        val publisher = object : DomainEventPublisher {
            override suspend fun publish(event: OrionEvent<*>) { received.add(event) }
        }
        var outcome: Result<Unit>? = null
        val publish: suspend () -> Unit = {
            publisher.publish(original)
            publisher.publish(original)
        }
        publish.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        checkNotNull(outcome).getOrThrow()
        assertEquals(2, received.size)
        assertSame(original, received[0])
        assertSame(original, received[1])
    }
}

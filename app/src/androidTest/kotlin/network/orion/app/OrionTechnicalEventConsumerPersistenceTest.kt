package network.orion.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.events.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite reopen evidence only; this does not claim physical process-death qualification. */
@RunWith(AndroidJUnit4::class)
class OrionTechnicalEventConsumerPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_event_projection_${UUID.randomUUID()}.db"
    private lateinit var database: OrionDatabase
    private var now = 100L
    private val clock = WallClock { Instant.ofEpochMilli(now) }

    @Before fun open() { database = openDatabase() }
    @After fun close() { database.close(); context.deleteDatabase(name) }
    private fun openDatabase() = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
    private fun event(type: String = "TASK_CREATED", schema: Int = 1) = DomainEventOutboxEntity(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, type, schema,
        Sensitivity.SECRET, now, UUID.randomUUID().toString(),
    )

    @Test fun committedProjectionSurvivesMissingAcknowledgementAndReopenWithoutDuplicateMutation() {
        val event = event()
        database.events().append(event)
        val consumer = OrionTechnicalEventConsumer(database.events(), clock)
        val interrupted = OrionEventDispatcher(database.events(), clock, mapOf(event.eventType to listOf(
            DurableEventConsumer {
                assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(it))
                throw IllegalStateException("simulated interruption after consumer commit")
            },
        )), EventRetryPolicy { 0.0 })
        assertThrows(IllegalStateException::class.java) { interrupted.dispatchBatch() }
        val pending = checkNotNull(database.events().find(event.eventId))
        assertEquals(DomainEventDeliveryStatus.PENDING, pending.status)
        assertEquals(1, pending.attemptCount)
        assertEquals(1, database.events().consumed(event.eventId, OrionTechnicalEventConsumer.CONSUMER_ID))
        database.close()
        database = openDatabase()
        now = pending.nextAttemptAt
        val resumed = OrionEventDispatcher(database.events(), clock,
            OrionTechnicalEventConsumer.routes(database.events(), clock), EventRetryPolicy { 0.0 })
        assertEquals(1, resumed.dispatchBatch().delivered)
        assertEquals(1, database.events().consumed(event.eventId, OrionTechnicalEventConsumer.CONSUMER_ID))
        database.openHelper.readableDatabase.query("SELECT count(*) FROM orion_technical_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        assertEquals(0, resumed.dispatchBatch().examined)
    }

    @Test fun unknownTypeAndSchemaRemainUnconsumedAndBecomeDeadLetter() {
        val unknownType = event("FUTURE_EVENT")
        val unknownSchema = event(schema = 2)
        database.events().append(unknownType)
        database.events().append(unknownSchema)
        val dispatcher = OrionEventDispatcher(database.events(), clock,
            OrionTechnicalEventConsumer.routes(database.events(), clock))
        assertEquals(2, dispatcher.dispatchBatch().deadLettered)
        for (event in listOf(unknownType, unknownSchema)) {
            assertEquals(DomainEventDeliveryStatus.DEAD_LETTER, database.events().find(event.eventId)?.status)
            assertEquals(0, database.events().consumed(event.eventId, OrionTechnicalEventConsumer.CONSUMER_ID))
        }
    }

    @Test fun receiptInsertionFailureRollsBackProjectionAndAllowsSafeRetry() {
        val event = event()
        database.events().append(event)
        val consumer = OrionTechnicalEventConsumer(database.events(), clock)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER orion_test_reject_receipt BEFORE INSERT ON orion_event_consumer_receipts
            BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END""")
        try {
            assertThrows(SQLiteConstraintException::class.java) { consumer.consume(event) }
            assertEquals(0, database.events().consumed(event.eventId, OrionTechnicalEventConsumer.CONSUMER_ID))
            sql.query("SELECT count(*) FROM orion_technical_events").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertEquals(DomainEventDeliveryStatus.PENDING, database.events().find(event.eventId)?.status)
        } finally {
            sql.execSQL("DROP TRIGGER orion_test_reject_receipt")
        }
        assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(event))
        assertEquals(1, database.events().consumed(event.eventId, OrionTechnicalEventConsumer.CONSUMER_ID))
        sql.query("SELECT count(*) FROM orion_technical_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        assertEquals(DurableDeliveryResult.ACCEPTED, consumer.consume(event))
        sql.query("SELECT count(*) FROM orion_technical_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }
}

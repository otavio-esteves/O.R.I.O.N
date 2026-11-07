package network.orion.app.scheduling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.ZoneOffset
import java.time.Duration
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.IdempotencyKeyPolicy
import network.orion.core.actions.NormalizedActionRequest
import network.orion.core.model.*
import network.orion.core.security.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.actions.OrionActionStore
import network.orion.data.actions.ActionStoreOutcome
import network.orion.data.reminders.OrionReminderStore
import network.orion.data.reminders.ReminderWriteResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionSoakFixtureCleanupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 29 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })

    @Test fun interruptedPreparationCleansOnlyOwnedReminderAndIsIdempotent() {
        val fixture = UUID.randomUUID()
        val id = UUID.nameUUIDFromBytes("$fixture/runtime-reminder".toByteArray(Charsets.UTF_8))
        val marker = File(context.filesDir, "orion_scheduled_runtime_$fixture.json")
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        try {
            val clock = AndroidOrionClock(context.contentResolver)
            val store = OrionReminderStore(db, crypto, clock)
            val unrelated = UUID.randomUUID()
            assertTrue(store.create(unrelated, byteArrayOf(2), Sensitivity.SENSITIVE, clock.wallNow(), ZoneOffset.UTC, unrelated) is ReminderWriteResult.Stored)
            val original = checkNotNull(db.reminders().find(unrelated.toString())).contentEnvelope.copyOf()
            writeRuntimeFixtureMetadata(marker, JSONObject().put("fixtureId", fixture.toString()).put("reminderId", id.toString()))
            try {
                assertTrue(store.create(id, byteArrayOf(1), Sensitivity.SENSITIVE, clock.wallNow(), ZoneOffset.UTC, fixture) is ReminderWriteResult.Stored)
                // Preparation can die after persisting a proposal but before attaching confirmed delivery authority.
                val actionId = UUID.randomUUID()
                val action = NormalizedActionRequest(actionId, "reminder.deliver", "{}", id.toString(),
                    AuthorizationLevel.EXTERNAL_ACTION, 1, SideEffectClass.EXTERNAL_REVERSIBLE,
                    RecoveryStrategy.NO_RETRY, IdempotencyMode.KEYED, "qualification.cleanup")
                val policy = IdempotencyKeyPolicy("MAIN_UI", "reminder.deliver", Duration.ofDays(1), 1,
                    OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
                assertEquals(ActionStoreOutcome.REGISTERED, OrionActionStore(db, crypto, clock).register(
                    action, policy, actionId.toString(), id, Sensitivity.SENSITIVE, true).outcome)
                assertNull(db.reminders().find(id.toString())?.deliveryActionId)
                cleanupSoakRuntimeReminder(context, db, fixture)
                cleanupSoakRuntimeReminder(context, db, fixture)
                assertNull(db.reminders().find(id.toString()))
                assertNull(db.actions().find(actionId.toString()))
                assertFalse(marker.exists())
                assertArrayEquals(original, db.reminders().find(unrelated.toString())?.contentEnvelope)
                db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_domain_event_outbox WHERE aggregateId = ?", arrayOf(id.toString())).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            } finally { android.util.AtomicFile(marker).delete() }
        } finally { db.close() }
    }

    @Test fun missingOrMismatchedOwnershipNeverDeletesARemainingRow() {
        val fixture = UUID.randomUUID()
        val id = UUID.nameUUIDFromBytes("$fixture/runtime-reminder".toByteArray(Charsets.UTF_8))
        val marker = File(context.filesDir, "orion_scheduled_runtime_$fixture.json")
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        try {
            val clock = AndroidOrionClock(context.contentResolver)
            assertTrue(OrionReminderStore(db, crypto, clock).create(id, byteArrayOf(1), Sensitivity.SENSITIVE,
                clock.wallNow(), ZoneOffset.UTC, fixture) is ReminderWriteResult.Stored)
            try {
                assertThrows(IllegalStateException::class.java) { cleanupSoakRuntimeReminder(context, db, fixture) }
                writeRuntimeFixtureMetadata(marker, JSONObject().put("fixtureId", fixture.toString()).put("reminderId", UUID.randomUUID().toString()))
                assertThrows(IllegalArgumentException::class.java) { cleanupSoakRuntimeReminder(context, db, fixture) }
                assertNotNull(db.reminders().find(id.toString()))
                assertTrue(marker.exists())
            } finally { android.util.AtomicFile(marker).delete() }
        } finally { db.close() }
    }
}

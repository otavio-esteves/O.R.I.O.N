package network.orion.app.reminders

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.commands.reminders.*
import network.orion.core.model.Sensitivity
import network.orion.core.queries.reminders.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.reminders.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionReminderQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var unavailable = false
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = if (unavailable) FieldKeyResult.Unavailable
            else FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 27 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val clock = WallClock { Instant.parse("2026-11-05T04:00:00Z") }
    private fun fixture(block: (OrionDatabase, OrionReminderStore, OrionReminderQuery) -> Unit) {
        val name = "orion_query_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        try { block(db, OrionReminderStore(db, crypto, clock), OrionReminderQuery(db, crypto, clock)) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private fun create(store: OrionReminderStore, id: UUID = UUID.randomUUID(), title: String = "Revisar relatório"): UUID {
        val reminder = (OrionCreateReminderPreparer.prepare(CreateReminder(id, title, Instant.EPOCH, java.time.ZoneId.of("UTC"), id, id)) as CreateReminderPreparation.Ready).reminder
        assertTrue(store.create(id, reminder.canonicalNormalizedArguments.toByteArray(), Sensitivity.SENSITIVE, Instant.EPOCH, java.time.ZoneId.of("UTC"), id) is ReminderWriteResult.Stored)
        return id
    }
    @Test fun paginationIsStableBoundedAndReadOnly() = fixture { db, store, query ->
        val ids = (1..5).map { create(store) }.sortedBy { it.toString() }
        val before = count(db, "orion_domain_event_outbox")
        val first = query.get(GetReminders(limit = 2))
        assertEquals(ids.take(2), first.items.map { it.reminderId }); assertEquals(ids[1], first.next)
        val second = query.get(GetReminders(first.next, 2))
        assertEquals(ids.drop(2).take(2), second.items.map { it.reminderId })
        val last = query.get(GetReminders(second.next, 2))
        assertEquals(listOf(ids.last()), last.items.map { it.reminderId }); assertNull(last.next)
        assertTrue(first.items.all { it.title == "Revisar relatório" && it.contentState == ReminderContentState.READABLE })
        assertEquals(before, count(db, "orion_domain_event_outbox")); assertEquals(0, count(db, "orion_action_requests"))
        assertTrue(db.reminders().page(null, 100).all { it.version == 0L })
    }
    @Test fun legacyAndNonCanonicalAndCorruptContentNeverFallsBackToPlaintext() = fixture { db, store, query ->
        val legacy = UUID.randomUUID()
        store.create(legacy, "legacy opaque".toByteArray(), Sensitivity.SENSITIVE, Instant.EPOCH, java.time.ZoneId.of("UTC"), legacy)
        val nonCanonical = UUID.randomUUID()
        store.create(nonCanonical, "{\"title\":\"x\",\"reminderId\":\"$nonCanonical\"}".toByteArray(), Sensitivity.SENSITIVE, Instant.EPOCH, java.time.ZoneId.of("UTC"), nonCanonical)
        val corrupt = create(store)
        db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET contentEnvelope = X'010203' WHERE reminderId = ?", arrayOf(corrupt.toString()))
        val rows = query.get(GetReminders()).items
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.title == null && it.contentState == ReminderContentState.UNREADABLE })
    }
    @Test fun keyFailurePreservesStoredBytesAndMetadata() = fixture { db, store, query ->
        val id = create(store)
        val before = db.reminders().find(id.toString())!!.contentEnvelope.copyOf()
        unavailable = true
        val row = query.get(GetReminders()).items.single()
        assertEquals(ReminderContentState.KEY_UNAVAILABLE, row.contentState); assertNull(row.title)
        assertEquals(ReminderContentState.KEY_UNAVAILABLE, query.find(id)!!.contentState)
        assertArrayEquals(before, db.reminders().find(id.toString())!!.contentEnvelope)
        unavailable = false
        assertEquals(ReminderContentState.READABLE, query.get(GetReminders()).items.single().contentState)
    }
    private fun count(db: OrionDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
}

package network.orion.app.tasks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.commands.tasks.*
import network.orion.core.model.Sensitivity
import network.orion.core.queries.tasks.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionTaskQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var unavailable = false
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = if (unavailable) FieldKeyResult.Unavailable
            else FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 27 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val clock = WallClock { Instant.parse("2026-11-05T04:00:00Z") }
    private fun fixture(block: (OrionDatabase, OrionTaskStore, OrionTaskQuery) -> Unit) {
        val name = "orion_query_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        try { block(db, OrionTaskStore(db, crypto, clock), OrionTaskQuery(db, crypto)) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private fun create(store: OrionTaskStore, id: UUID = UUID.randomUUID(), title: String = "Revisar relatório"): UUID {
        val task = (OrionCreateTaskPreparer.prepare(CreateTask(id, title, id, id)) as CreateTaskPreparation.Ready).task
        assertTrue(store.create(id, task.canonicalNormalizedArguments.toByteArray(), Sensitivity.SENSITIVE, id) is TaskCreationResult.Created)
        return id
    }
    @Test fun paginationIsStableBoundedAndReadOnly() = fixture { db, store, query ->
        val ids = (1..5).map { create(store) }.sortedBy { it.toString() }
        val before = count(db, "orion_domain_event_outbox")
        val first = query.get(GetTasks(limit = 2))
        assertEquals(ids.take(2), first.items.map { it.taskId }); assertEquals(ids[1], first.next)
        val second = query.get(GetTasks(first.next, 2))
        assertEquals(ids.drop(2).take(2), second.items.map { it.taskId })
        val last = query.get(GetTasks(second.next, 2))
        assertEquals(listOf(ids.last()), last.items.map { it.taskId }); assertNull(last.next)
        assertTrue(first.items.all { it.title == "Revisar relatório" && it.contentState == TaskContentState.READABLE })
        assertEquals(before, count(db, "orion_domain_event_outbox")); assertEquals(0, count(db, "orion_action_requests"))
        assertTrue(db.tasks().page(null, 100).all { it.version == 0L })
    }
    @Test fun legacyAndNonCanonicalAndCorruptContentNeverFallsBackToPlaintext() = fixture { db, store, query ->
        val legacy = UUID.randomUUID()
        store.create(legacy, "legacy opaque".toByteArray(), Sensitivity.SENSITIVE, legacy)
        val nonCanonical = UUID.randomUUID()
        store.create(nonCanonical, "{\"title\":\"x\",\"taskId\":\"$nonCanonical\"}".toByteArray(), Sensitivity.SENSITIVE, nonCanonical)
        val corrupt = create(store)
        db.openHelper.writableDatabase.execSQL("UPDATE orion_tasks SET contentEnvelope = X'010203' WHERE taskId = ?", arrayOf(corrupt.toString()))
        val rows = query.get(GetTasks()).items
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.title == null && it.contentState == TaskContentState.UNREADABLE })
    }
    @Test fun keyFailurePreservesStoredBytesAndMetadata() = fixture { db, store, query ->
        val id = create(store)
        val before = db.tasks().find(id.toString())!!.contentEnvelope.copyOf()
        unavailable = true
        val row = query.get(GetTasks()).items.single()
        assertEquals(TaskContentState.KEY_UNAVAILABLE, row.contentState); assertNull(row.title)
        assertArrayEquals(before, db.tasks().find(id.toString())!!.contentEnvelope)
        unavailable = false
        assertEquals(TaskContentState.READABLE, query.get(GetTasks()).items.single().contentState)
    }
    private fun count(db: OrionDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
}

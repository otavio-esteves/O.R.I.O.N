package network.orion.app.scheduling

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.tasks.OrionTaskStore
import network.orion.data.tasks.TaskCreationResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite/heap work in bounded disposable files; never fills the device or edits orion.db. */
@RunWith(AndroidJUnit4::class)
class OrionF4ResourceQualificationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 47 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })

    private fun scalar(sql: SupportSQLiteDatabase, statement: String): Long =
        sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }

    @Test fun fullDatabaseDuringEventInsertRollsBackTaskAndAllowsSafeLocalRetry() {
        val name = "orion_f4_storage_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        try {
            val sql = db.openHelper.writableDatabase
            val store = OrionTaskStore(db, crypto, AndroidOrionClock(context.contentResolver))
            val existing = UUID.randomUUID()
            assertTrue(store.create(existing, byteArrayOf(1), Sensitivity.SENSITIVE, existing) is TaskCreationResult.Created)
            val original = checkNotNull(db.tasks().find(existing.toString())).contentEnvelope.copyOf()
            val eventsBefore = db.events().count()
            sql.execSQL("CREATE TABLE orion_qualification_pressure (id INTEGER PRIMARY KEY, payload BLOB)")
            val originalMaximum = scalar(sql, "PRAGMA max_page_count")
            val pages = scalar(sql, "PRAGMA page_count")
            db.runInTransaction {
                // Pin to the writer connection; only this disposable database is constrained.
                assertEquals(pages + 8, scalar(sql, "PRAGMA max_page_count = ${pages + 8}"))
            }
            sql.execSQL("""CREATE TRIGGER orion_qualification_full BEFORE INSERT ON orion_domain_event_outbox
                BEGIN INSERT INTO orion_qualification_pressure(payload) VALUES(zeroblob(2097152)); END""")
            val attempted = UUID.randomUUID()
            try {
                assertThrows(SQLiteFullException::class.java) {
                    store.create(attempted, byteArrayOf(2), Sensitivity.SENSITIVE, attempted)
                }
                assertNull(db.tasks().find(attempted.toString()))
                assertEquals(eventsBefore, db.events().count())
                assertArrayEquals(original, db.tasks().find(existing.toString())!!.contentEnvelope)
                assertEquals(0, scalar(sql, "SELECT count(*) FROM orion_qualification_pressure"))
            } finally {
                db.runInTransaction { scalar(sql, "PRAGMA max_page_count = $originalMaximum") }
                sql.execSQL("DROP TRIGGER IF EXISTS orion_qualification_full")
            }
            assertTrue(store.create(attempted, byteArrayOf(2), Sensitivity.SENSITIVE, attempted) is TaskCreationResult.Created)
            assertNotNull(db.tasks().find(attempted.toString()))
            assertEquals(eventsBefore + 1, db.events().count())
            sql.query("PRAGMA quick_check(1)").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun boundedHeapAndWriteLoadKeepsAtomicEventsAndRejectsDuplicateRows() {
        val name = "orion_f4_resource_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        val held = ArrayList<ByteArray>()
        try {
            val store = OrionTaskStore(db, crypto, AndroidOrionClock(context.contentResolver))
            // 16 MiB is a bounded load, not a claim of LMK or MemoryLimiter qualification.
            repeat(8) { chunk -> held += ByteArray(2 * 1024 * 1024) { (it + chunk).toByte() } }
            val ids = List(250) { UUID.randomUUID() }
            ids.forEach { id ->
                assertTrue(store.create(id, byteArrayOf(3), Sensitivity.SENSITIVE, id) is TaskCreationResult.Created)
            }
            ids.take(10).forEach { id ->
                assertThrows(SQLiteConstraintException::class.java) {
                    store.create(id, byteArrayOf(4), Sensitivity.SENSITIVE, id)
                }
            }
            assertEquals(250L, scalar(db.openHelper.readableDatabase, "SELECT count(*) FROM orion_tasks"))
            assertEquals(250, db.events().count())
            ids.forEach { assertNotNull(db.tasks().find(it.toString())) }
            assertEquals(8, held.size)
            db.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use {
                assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
            }
        } finally {
            held.forEach { it.fill(0) }; held.clear()
            db.close(); context.deleteDatabase(name)
        }
    }
}

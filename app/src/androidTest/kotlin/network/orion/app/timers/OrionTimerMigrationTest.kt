package network.orion.app.timers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite migration evidence only; retained blobs are synthetic encrypted-envelope fixtures. */
@RunWith(AndroidJUnit4::class)
class OrionTimerMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun additiveThreeToFourPreservesMetadataTaskReminderAndUnknownActionBytes() {
        val name="orion_timer_migration_${UUID.randomUUID()}.db"
        val task=UUID.randomUUID().toString();val reminder=UUID.randomUUID().toString();val action=UUID.randomUUID().toString()
        try {
            migrations.createDatabase(name,3).use { old ->
                old.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                old.execSQL("INSERT INTO orion_tasks(taskId,contentEnvelope,sensitivity,createdAt,updatedAt,status,version) VALUES(?,X'010203','SENSITIVE',100,100,'OPEN',4)", arrayOf(task))
                old.execSQL("INSERT INTO orion_reminders(reminderId,contentEnvelope,sensitivity,scheduledAt,zoneId,createdAt,updatedAt,status,precision,version) VALUES(?,X'040506','SECRET',900,'UTC',100,100,'PENDING','UNSCHEDULED',5)",arrayOf(reminder))
                seedUnknown(old,action)
            }
            migrations.runMigrationsAndValidate(name,4,true,OrionDatabaseMigrations.MIGRATION_3_4).use { upgraded ->
                upgraded.query("SELECT contentEnvelope,version FROM orion_tasks WHERE taskId=?",arrayOf(task)).use {
                    assertTrue(it.moveToFirst());assertArrayEquals(byteArrayOf(1,2,3),it.getBlob(0));assertEquals(4L,it.getLong(1))
                }
                upgraded.query("SELECT contentEnvelope,version,precision FROM orion_reminders WHERE reminderId=?",arrayOf(reminder)).use {
                    assertTrue(it.moveToFirst());assertArrayEquals(byteArrayOf(4,5,6),it.getBlob(0));assertEquals(5L,it.getLong(1));assertEquals("UNSCHEDULED",it.getString(2))
                }
                upgraded.query("SELECT status,version,argumentsEnvelope FROM orion_action_requests WHERE actionId=?",arrayOf(action)).use {
                    assertTrue(it.moveToFirst());assertEquals("UNKNOWN",it.getString(0));assertEquals(7L,it.getLong(1));assertArrayEquals(byteArrayOf(7,8,9),it.getBlob(2))
                }
                upgraded.query("SELECT formatVersion FROM orion_storage_metadata WHERE id=1").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
                upgraded.query("SELECT count(*) FROM orion_timers").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            }
            val reopened=Room.databaseBuilder(context,OrionDatabase::class.java,name)
                .addMigrations(OrionDatabaseMigrations.MIGRATION_4_5).build()
            try { reopened.verifyStructure() } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun timerRowsPreventFreshFieldKeyProvisioningAndFailedEventLeavesNoTimer() {
        val name="orion_timer_storage_${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
        try {
            assertFalse(db.records().hasEncryptedRecords())
            val id=UUID.randomUUID().toString()
            val timer=TimerEntity(id,byteArrayOf(1),Sensitivity.SENSITIVE,1000,100,1100,7,20,100,100)
            fun event()=DomainEventOutboxEntity(UUID.randomUUID().toString(),id,0,"TIMER_CREATED",1,Sensitivity.SENSITIVE,100,UUID.randomUUID().toString())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER orion_fail_timer_event BEFORE INSERT ON orion_domain_event_outbox BEGIN SELECT RAISE(ABORT,'orion_injected_failure'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java) { db.timers().createWithEvent(timer,event()) }
            assertNull(db.timers().find(id));assertFalse(db.records().hasEncryptedRecords())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_fail_timer_event")
            db.timers().createWithEvent(timer,event())
            assertTrue(db.records().hasEncryptedRecords())
        } finally { db.close();context.deleteDatabase(name) }
    }

    private fun seedUnknown(db:SupportSQLiteDatabase,id:String) {
        val values=ContentValues()
        db.query("PRAGMA table_info(orion_action_requests)").use { columns ->
            while(columns.moveToNext()) {
                if(columns.getInt(3)==0) continue
                val name=columns.getString(1)
                when(columns.getString(2)) {
                    "INTEGER" -> values.put(name,0L)
                    "BLOB" -> values.put(name,byteArrayOf(7,8,9))
                    else -> values.put(name,"synthetic")
                }
            }
        }
        values.put("actionId",id);values.put("status","UNKNOWN");values.put("version",7L)
        db.insert("orion_action_requests",SQLiteDatabase.CONFLICT_ABORT,values)
    }
}

package network.orion.app.reminders

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
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseMigrations
import network.orion.data.database.ReminderScheduleMethod
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic opaque bytes prove additive SQLite migration; no hardware/Keystore qualification. */
@RunWith(AndroidJUnit4::class)
class OrionReminderScheduleMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun fourToFivePreservesLegacyReminderStatesAndNeverCreatesDeliveryConsent() {
        val name = "orion_reminder_migration_${UUID.randomUUID()}.db"
        val ids = listOf("PENDING", "FIRED", "CANCELLED").associateWith { UUID.randomUUID().toString() }
        val unknown = UUID.randomUUID().toString()
        try {
            migrations.createDatabase(name, 4).use { old ->
                old.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                for ((status, id) in ids) {
                    old.execSQL("""INSERT INTO orion_reminders(reminderId,contentEnvelope,sensitivity,scheduledAt,zoneId,
                        createdAt,updatedAt,status,precision,version) VALUES(?,X'010203','SECRET',1000,'UTC',100,200,?,'UNSCHEDULED',7)""",
                        arrayOf(id, status))
                }
                seedUnknown(old, unknown)
            }
            migrations.runMigrationsAndValidate(name, 5, true, OrionDatabaseMigrations.MIGRATION_4_5).use { upgraded ->
                for (table in listOf("orion_reminder_series", "orion_reminder_occurrences")) {
                    upgraded.query("SELECT count(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
                for ((status, id) in ids) {
                    upgraded.query("""SELECT contentEnvelope,sensitivity,scheduledAt,zoneId,status,precision,version,
                        fireGeneration,scheduleMethod,requestCode,deliveryActionId,lastScheduledAt,lastScheduledGeneration,
                        lastFiredAt,localDateTime,recurrenceRule,resolutionDecision,resolvedOffsetSeconds,registrationConfirmed
                        FROM orion_reminders WHERE reminderId=?""", arrayOf(id)).use { row ->
                        assertTrue(row.moveToFirst())
                        assertArrayEquals(byteArrayOf(1, 2, 3), row.getBlob(0))
                        assertEquals("SECRET", row.getString(1)); assertEquals(1_000L, row.getLong(2))
                        assertEquals("UTC", row.getString(3)); assertEquals(status, row.getString(4))
                        assertEquals("UNSCHEDULED", row.getString(5)); assertEquals(7L, row.getLong(6))
                        assertEquals(0L, row.getLong(7)); assertEquals("UNSCHEDULED", row.getString(8))
                        assertEquals(0, row.getInt(9))
                        for (column in 10..17) assertTrue("Unexpected automatic metadata at column $column", row.isNull(column))
                        assertEquals(0, row.getInt(18))
                    }
                }
                upgraded.query("SELECT status,version,argumentsEnvelope FROM orion_action_requests WHERE actionId=?",
                    arrayOf(unknown)).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals("UNKNOWN", row.getString(0))
                    assertEquals(9L, row.getLong(1)); assertArrayEquals(byteArrayOf(7, 8, 9), row.getBlob(2))
                }
            }
            val reopened = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
            try {
                reopened.verifyStructure()
                assertTrue(reopened.reminderSchedules().schedulingPage().isEmpty())
                for (id in ids.values) {
                    val row = checkNotNull(reopened.reminders().find(id))
                    assertNull(row.deliveryActionId)
                    assertEquals(ReminderScheduleMethod.UNSCHEDULED, row.scheduleMethod)
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun originalSchemaUpgradesThroughEveryExplicitMigrationWithoutDestructiveFallback() {
        val name = "orion_reminder_migration_chain_${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 1).use { old ->
                old.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
            }
            migrations.runMigrationsAndValidate(name, 5, true, OrionDatabaseMigrations.MIGRATION_1_2,
                OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4,
                OrionDatabaseMigrations.MIGRATION_4_5).use { upgraded ->
                upgraded.query("SELECT formatVersion FROM orion_storage_metadata WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                upgraded.query("SELECT count(*) FROM orion_reminders").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun seedUnknown(db: SupportSQLiteDatabase, id: String) {
        val values = ContentValues()
        db.query("PRAGMA table_info(orion_action_requests)").use { columns ->
            while (columns.moveToNext()) {
                if (columns.getInt(3) == 0) continue
                val name = columns.getString(1)
                when (columns.getString(2)) {
                    "INTEGER" -> values.put(name, 0L)
                    "BLOB" -> values.put(name, byteArrayOf(7, 8, 9))
                    else -> values.put(name, "synthetic")
                }
            }
        }
        values.put("actionId", id); values.put("status", "UNKNOWN"); values.put("version", 9L)
        db.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
    }
}

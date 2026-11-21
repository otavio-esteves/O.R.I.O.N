package network.orion.app.memories

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseMigrations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionMemoryProvenanceMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java,
    )
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun upgradesEveryPriorSchemaWithoutFabricatingProvenanceOrRestoringDeletedRows() {
        val inferred = UUID.randomUUID().toString()
        val deleted = UUID.randomUUID().toString()
        val claim = UUID.randomUUID().toString()
        val envelope = byteArrayOf(5, 17, 21)
        val originEventId = UUID.randomUUID().toString()
        for (previous in 1..7) {
            val name = "orion_memory_provenance_${UUID.randomUUID()}.db"
            try {
                migrations.createDatabase(name, previous).use { legacy ->
                    legacy.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES (1, 1)")
                    if (previous >= 3) {
                        val insert = """INSERT INTO orion_memories (memoryId,contentEnvelope,sensitivity,
                            origin,confidence,userConfirmed,createdAt,updatedAt,status,supersedesId,version)
                            VALUES (?,?,'PERSONAL',?,?,?,1000,2000,?,NULL,?)"""
                        legacy.execSQL(insert, arrayOf(inferred, envelope, "LLM_INFERRED", 0.25, 0, "ACTIVE", 5))
                        legacy.execSQL(insert, arrayOf(deleted, envelope, "USER_DECLARED", 1.0, 1, "DELETED", 8))
                        if (previous == 7) legacy.execSQL("UPDATE orion_memories SET originEventId = ? WHERE memoryId = ?",
                            arrayOf(originEventId, inferred))
                        legacy.execSQL("""INSERT INTO orion_memory_claims (claimId,memoryId,statementEnvelope,
                            sensitivity,origin,confidence,userConfirmed,status,validFrom,validUntil,version)
                            VALUES (?,?,?,'PERSONAL','LLM_INFERRED',0.25,0,'ACTIVE',1000,3000,2)""",
                            arrayOf(claim, inferred, envelope))
                    }
                }
                migrations.runMigrationsAndValidate(name, 8, true, *OrionDatabaseMigrations.ALL).use { upgraded ->
                    if (previous < 3) {
                        upgraded.query("SELECT count(*) FROM orion_memories").use { rows ->
                            assertTrue(rows.moveToFirst())
                            assertEquals(0, rows.getInt(0))
                        }
                    } else {
                        val select = """SELECT contentEnvelope,origin,confidence,userConfirmed,status,version,originEventId,
                            type,importance,validFrom,validUntil,expiresAt,detailsEnvelope,lastAccessedAt,accessCount
                            FROM orion_memories WHERE memoryId = ?"""
                        upgraded.query(select, arrayOf(inferred)).use { row ->
                            assertTrue(row.moveToFirst())
                            assertArrayEquals(envelope, row.getBlob(0))
                            assertEquals("LLM_INFERRED", row.getString(1))
                            assertEquals(0.25, row.getDouble(2), 0.0)
                            assertEquals(0, row.getInt(3))
                            assertEquals("ACTIVE", row.getString(4))
                            assertEquals(5L, row.getLong(5))
                            if (previous == 7) assertEquals(originEventId, row.getString(6)) else assertTrue(row.isNull(6))
                            for (column in 7..13) assertTrue(row.isNull(column))
                            assertEquals(0L, row.getLong(14))
                        }
                        upgraded.query(select, arrayOf(deleted)).use { row ->
                            assertTrue(row.moveToFirst())
                            assertEquals(1, row.getInt(3))
                            assertEquals("DELETED", row.getString(4))
                            assertEquals(8L, row.getLong(5))
                            assertTrue(row.isNull(6))
                        }
                        upgraded.query("""SELECT statementEnvelope,userConfirmed,validFrom,validUntil,version
                            FROM orion_memory_claims WHERE claimId = ?""", arrayOf(claim)).use { row ->
                            assertTrue(row.moveToFirst())
                            assertArrayEquals(envelope, row.getBlob(0))
                            assertEquals(0, row.getInt(1))
                            assertEquals(1000L, row.getLong(2))
                            assertEquals(3000L, row.getLong(3))
                            assertEquals(2L, row.getLong(4))
                        }
                    }
                    upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }
}

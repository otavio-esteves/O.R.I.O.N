package network.orion.app.models

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import network.orion.data.database.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionModelRegistryTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun fiveToSixPreservesUnknownEncryptedActionAndCreatesEmptyRegistry() {
        val name = "orion_model_upgrade_${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 5).use { old ->
                old.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                val values = ContentValues()
                old.query("PRAGMA table_info(orion_action_requests)").use { columns ->
                    while (columns.moveToNext()) {
                        if (columns.getInt(3) == 0) continue
                        val column = columns.getString(1)
                        when (columns.getString(2)) {
                            "INTEGER" -> values.put(column, 0L)
                            "BLOB" -> values.put(column, byteArrayOf(1, 2, 3))
                            else -> values.put(column, "synthetic")
                        }
                    }
                }
                values.put("actionId", "legacy-action"); values.put("status", "UNKNOWN"); values.put("version", 7L)
                old.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
            }
            migrations.runMigrationsAndValidate(name, 6, true, OrionDatabaseMigrations.MIGRATION_5_6).use { db ->
                db.query("SELECT count(*) FROM orion_model_registry").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.query("SELECT status,version,argumentsEnvelope FROM orion_action_requests WHERE actionId='legacy-action'").use {
                    assertTrue(it.moveToFirst()); assertEquals("UNKNOWN", it.getString(0)); assertEquals(7L, it.getLong(1))
                    assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(2))
                }
            }
            val reopened = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
            try { reopened.verifyStructure() } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun originalSchemaReachesSixWithoutDestructiveFallback() {
        val name = "orion_model_chain_${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 1).use { it.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)") }
            migrations.runMigrationsAndValidate(name, 6, true, OrionDatabaseMigrations.MIGRATION_1_2,
                OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4,
                OrionDatabaseMigrations.MIGRATION_4_5, OrionDatabaseMigrations.MIGRATION_5_6).use { db ->
                db.query("SELECT formatVersion FROM orion_storage_metadata WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun readyRequiresValidationExactIdentitySizeAndOptimisticOwnership() = withDatabase { db ->
        val models = db.models(); val row = imported()
        models.registerImported(row)
        assertEquals(0, models.completeValidation(row.modelId, 0, row.sha256, row.sizeBytes, "llama", "Q4", 2048, 200))
        assertEquals(0, models.beginValidation(row.modelId, 1))
        assertEquals(1, models.beginValidation(row.modelId, 0))
        assertEquals(0, models.beginValidation(row.modelId, 0))
        assertEquals(0, models.completeValidation(row.modelId, 1, row.sha256, row.sizeBytes + 1, "llama", "Q4", 2048, 200))
        assertEquals(1, models.completeValidation(row.modelId, 1, row.sha256, row.sizeBytes, "llama", "Q4", 2048, 200))
        assertEquals(0, models.completeValidation(row.modelId, 1, row.sha256, row.sizeBytes, "llama", "Q4", 2048, 200))
        assertEquals("READY", models.find(row.modelId)?.status)
        assertEquals(2L, models.find(row.modelId)?.version)
        assertEquals(1, models.invalidate(row.modelId, 2, true))
        assertEquals("QUARANTINED", models.find(row.modelId)?.status)
        assertEquals(0, models.beginValidation(row.modelId, 3))
        assertEquals(0, models.completeValidation(row.modelId, 3, row.sha256, row.sizeBytes, "llama", "Q4", 2048, 300))
    }

    @Test fun immutableIdentityAndPrivatePathCannotBeReplacedAndListingIsBounded() = withDatabase { db ->
        val models = db.models(); val first = imported()
        models.registerImported(first)
        try { models.registerImported(first.copy(displayName = "replacement")); fail("Duplicate identity accepted") }
        catch (_: SQLiteConstraintException) { }
        val next = imported("b".repeat(64)).copy(filePath = first.filePath)
        try { models.registerImported(next); fail("Duplicate private path accepted") }
        catch (_: SQLiteConstraintException) { }
        models.registerImported(next.copy(filePath = "models/${next.sha256}.gguf"))
        assertEquals(1, models.listPage(limit = 1).size)
        assertEquals(next.modelId, models.listPage(after = first.modelId, limit = 1).single().modelId)
        assertEquals(first, models.find(first.modelId))
        assertFalse(first.toString().contains(first.filePath))
    }

    @Test fun invalidImportedMetadataAndValidationBoundsAreRejected() = withDatabase { db ->
        val models = db.models(); val first = imported()
        for (invalid in listOf(first.copy(status = "READY"), first.copy(sizeBytes = 0),
            first.copy(modelId = "unbound"), first.copy(lastValidatedAt = 1), first.copy(contextLimit = 2048),
            first.copy(modelId = "0".repeat(64), sha256 = "0".repeat(64)))) {
            try { models.registerImported(invalid); fail("Invalid metadata accepted") } catch (_: IllegalArgumentException) { }
        }
        models.registerImported(first)
        assertEquals(1, models.invalidate(first.modelId, 0, false))
        assertEquals("INVALID", models.find(first.modelId)?.status)
        assertEquals(0, models.beginValidation(first.modelId, 1))
    }

    private fun imported(hash: String = "a".repeat(64)) = ModelRegistryEntity(hash, "Fixture", "models/$hash.gguf",
        hash, 1024, null, null, null, "IMPORTED", 100, null)

    private fun withDatabase(block: (OrionDatabase) -> Unit) {
        val name = "orion_model_registry_${UUID.randomUUID()}.db"
        try {
            val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
            try { block(db) } finally { db.close() }
        }
        finally { context.deleteDatabase(name) }
    }
}

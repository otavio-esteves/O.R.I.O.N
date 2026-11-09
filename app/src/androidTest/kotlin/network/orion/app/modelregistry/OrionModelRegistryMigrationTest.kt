package network.orion.app.modelregistry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.model.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.ModelRegistryStatus
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseMigrations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic plaintext/test-only AES keys. SQLite evidence only when executed, with no Keystore claim. */
@RunWith(AndroidJUnit4::class)
class OrionModelRegistryMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = WallClock { Instant.ofEpochMilli(1_000) }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        private val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun currentKey() = key
        override fun keyForVersion(version: Int): FieldKeyResult = if (version == 1) key else FieldKeyResult.Unavailable
    })

    @Test fun fiveToSixPreservesEveryExistingTableAndEncryptedContentWithoutGrantingModelReadiness() {
        val name = "orion_model_migration_${UUID.randomUUID()}.db"
        val task = UUID.randomUUID().toString()
        val actions = listOf(ActionStatus.UNKNOWN, ActionStatus.CONFIRMED, ActionStatus.RUNNING)
            .map { status -> ActionFixture(UUID.randomUUID(), status) }
        val before = linkedMapOf<String, List<List<Cell>>>()
        try {
            migrations.createDatabase(name, 5).use { old ->
                old.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                old.execSQL("INSERT INTO orion_tasks(taskId,contentEnvelope,sensitivity,createdAt,updatedAt,status,version) VALUES(?,X'010203','SENSITIVE',100,100,'OPEN',8)", arrayOf(task))
                actions.forEach { seedAction(old, it) }
                seedQueuesAndReceipt(old, task)
                for (table in tables(old)) before[table] = snapshot(old, table)
            }
            migrations.runMigrationsAndValidate(name, 6, true, OrionDatabaseMigrations.MIGRATION_5_6).use { upgraded ->
                // Every old cell is compared with its SQLite type, including complete BLOB
                // bytes, NULLs, retries, confirmation bindings and boot/monotonic ownership.
                for ((table, rows) in before) assertEquals("Changed migration cells in $table", rows, snapshot(upgraded, table))
                upgraded.query("SELECT contentEnvelope,sensitivity,status,version FROM orion_tasks WHERE taskId=?", arrayOf(task)).use {
                    assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(1,2,3), it.getBlob(0))
                    assertEquals("SENSITIVE", it.getString(1)); assertEquals("OPEN", it.getString(2)); assertEquals(8L, it.getLong(3))
                }
                for (action in actions) upgraded.query("""SELECT status,version,normalizedPayloadHash,confirmationFingerprint,
                    confirmedPayloadHash,leaseBootSessionId,leaseStartedElapsedRealtime,leaseExpiresElapsedRealtime,
                    attemptCount,executionId FROM orion_action_requests WHERE actionId=?""", arrayOf(action.id.toString())).use {
                    val binding = ActionConfirmationBinding(clock).prepare(action.request)
                    assertTrue(it.moveToFirst()); assertEquals(action.status.name, it.getString(0)); assertEquals(action.version, it.getLong(1))
                    assertEquals(binding.normalizedPayloadHash, it.getString(2)); assertEquals(binding.confirmationFingerprint, it.getString(3))
                    assertEquals(binding.normalizedPayloadHash, it.getString(4))
                    if (action.status == ActionStatus.CONFIRMED) {
                        for (column in 5..7) assertTrue(it.isNull(column))
                        assertEquals(0, it.getInt(8)); assertTrue(it.isNull(9))
                    } else {
                        assertEquals(7, it.getInt(5)); assertEquals(1_234L, it.getLong(6)); assertEquals(31_234L, it.getLong(7))
                        assertEquals(3, it.getInt(8)); assertEquals(action.executionId.toString(), it.getString(9))
                    }
                }
                upgraded.query("SELECT count(*) FROM orion_model_registry").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
            val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
            try {
                db.verifyStructure(); assertTrue(db.modelRegistry().list(10).isEmpty())
                val store = OrionActionStore(db, encryption, clock)
                for (action in actions) {
                    val restored = (store.read(action.id) as ActionReadResult.Ready).action
                    assertEquals(action.request, restored.request); assertEquals(action.status, restored.status)
                    assertEquals(action.version, restored.version)
                    assertEquals(ConfirmationBindingResult.VALID_BINDING,
                        ActionConfirmationBinding(clock).validate(restored.request, checkNotNull(restored.confirmation)))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun originalSchemaUpgradesThroughEveryExplicitMigrationToSix() {
        val name = "orion_model_migration_chain_${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 1).use { it.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)") }
            migrations.runMigrationsAndValidate(name, 6, true, OrionDatabaseMigrations.MIGRATION_1_2,
                OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4,
                OrionDatabaseMigrations.MIGRATION_4_5, OrionDatabaseMigrations.MIGRATION_5_6).use { upgraded ->
                upgraded.query("SELECT formatVersion FROM orion_storage_metadata WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                upgraded.query("SELECT count(*) FROM orion_model_registry").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun modelRegistryUsesPrimaryIdentityAndOptimisticTransitions() {
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        try {
            val hash = "a".repeat(64)
            val row = ModelRegistryEntity("sha256:$hash", "GGUF", "$hash.gguf", hash, 256, null, null, null,
                ModelRegistryStatus.IMPORTED, UUID.randomUUID().toString(), 100, 100, null, 0, null)
            db.modelRegistry().insert(row)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { db.modelRegistry().insert(row) }
            assertEquals(1, db.modelRegistry().transition(row.modelId, 0, ModelRegistryStatus.IMPORTED,
                ModelRegistryStatus.VALIDATING, 200, null, null, null, null, null))
            assertEquals(0, db.modelRegistry().transition(row.modelId, 0, ModelRegistryStatus.IMPORTED,
                ModelRegistryStatus.READY, 200, "llama", "0", 128, 200, null))
            val validating = checkNotNull(db.modelRegistry().find(row.modelId))
            assertEquals(1L, validating.version); assertEquals(ModelRegistryStatus.VALIDATING, validating.status)
        } finally { db.close() }
    }

    private fun tables(db: SupportSQLiteDatabase): List<String> = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'orion_%' ORDER BY name").use { rows ->
        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
    }
    private data class Cell(val type: Int, val value: Any?)
    private fun snapshot(db: SupportSQLiteDatabase, table: String): List<List<Cell>> =
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { rows ->
            buildList {
                while (rows.moveToNext()) add(buildList {
                    for (column in 0 until rows.columnCount) {
                        val type = rows.getType(column)
                        add(Cell(type, when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> rows.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> rows.getDouble(column).toRawBits()
                            Cursor.FIELD_TYPE_STRING -> rows.getString(column)
                            Cursor.FIELD_TYPE_BLOB -> rows.getBlob(column).toList()
                            else -> error("Unknown SQLite fixture type.")
                        }))
                    }
                })
            }
        }
    private data class ActionFixture(val id: UUID, val status: ActionStatus, val executionId: UUID = UUID.randomUUID()) {
        val version = when (status) { ActionStatus.CONFIRMED -> 7L; ActionStatus.RUNNING -> 13L; else -> 11L }
        val request = NormalizedActionRequest(id, "notification.post", "{\"body\":\"synthetic\",\"requestId\":\"$id\",\"title\":\"migration\"}",
            "orion_user_notifications", AuthorizationLevel.EXTERNAL_ACTION, 1, SideEffectClass.EXTERNAL_REVERSIBLE,
            RecoveryStrategy.NO_RETRY, IdempotencyMode.KEYED, "orion_migration_v1")
    }
    private fun seedAction(db: SupportSQLiteDatabase, fixture: ActionFixture) {
        val values = ContentValues()
        db.query("PRAGMA table_info(orion_action_requests)").use { columns ->
            while (columns.moveToNext()) {
                if (columns.getInt(3) == 0) continue
                val name = columns.getString(1)
                when (columns.getString(2)) {
                    "INTEGER" -> values.put(name, 0L)
                    "BLOB" -> values.put(name, byteArrayOf(7,8,9))
                    else -> values.put(name, "synthetic")
                }
            }
        }
        val request = fixture.request
        val confirmation = ActionConfirmationBinding(clock).recordConfirmation(request, Instant.ofEpochMilli(61_000))
        values.put("actionId", fixture.id.toString()); values.put("status", fixture.status.name); values.put("version", fixture.version)
        values.put("skill", request.skill); values.put("argumentSchemaVersion", 1)
        values.put("argumentsEnvelope", encrypt(request.canonicalNormalizedArguments.toByteArray(), FieldEncryptionContext("action/${fixture.id}", "arguments", 1)))
        values.put("targetEnvelope", encrypt(request.normalizedTarget.toByteArray(), FieldEncryptionContext("action/${fixture.id}", "target", 1)))
        values.put("payloadSensitivity", "SECRET"); values.put("authorizationLevel", request.authorizationLevel.name)
        values.put("sideEffectClass", request.sideEffectClass.name); values.put("recoveryStrategy", request.recoveryStrategy.name)
        values.put("idempotencyMode", request.idempotencyMode.name); values.put("policyVersion", request.policyVersion)
        values.put("createdAt", 500L); values.put("updatedAt", 1_500L)
        values.put("idempotencyScope", "notification.post"); values.put("idempotencyKey", fixture.id.toString())
        values.put("idempotencyKeyIssuer", "CORE"); values.put("canonicalPayloadSchemaVersion", 1)
        values.put("idempotencyRetainUntil", 100_000L); values.put("correlationId", UUID.randomUUID().toString())
        values.put("requiresConfirmation", 1); values.put("normalizedPayloadHash", confirmation.normalizedPayloadHash)
        values.put("confirmationFingerprint", confirmation.confirmationFingerprint); values.put("confirmedPayloadHash", confirmation.confirmedPayloadHash)
        values.put("confirmedAt", 1_000L); values.put("confirmationExpiresAt", 61_000L); values.put("payloadLockedAt", 1_000L)
        values.put("policyVersionAtConfirmation", confirmation.policyVersionAtConfirmation)
        if (fixture.status != ActionStatus.CONFIRMED) {
            values.put("executionId", fixture.executionId.toString()); values.put("claimedBy", "orion_migration_executor")
            values.put("leaseBootSessionId", 7); values.put("leaseStartedElapsedRealtime", 1_234L)
            values.put("leaseExpiresElapsedRealtime", 31_234L); values.put("leaseWallStartedAt", 1_200L)
            values.put("executionStartedAt", 1_200L); values.put("attemptCount", 3)
        }
        db.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
        db.execSQL("""INSERT INTO orion_action_audit(auditId,actionId,actionVersion,fromStatus,toStatus,occurredAt,correlationId)
            VALUES(?,?,?,?,?,?,?)""", arrayOf<Any>(UUID.randomUUID().toString(), fixture.id.toString(), fixture.version,
            when (fixture.status) {
                ActionStatus.CONFIRMED -> "NEEDS_CONFIRMATION"
                ActionStatus.RUNNING -> "CONFIRMED"
                else -> "RUNNING"
            }, fixture.status.name, 1_500L, UUID.randomUUID().toString()))
    }

    private fun seedQueuesAndReceipt(db: SupportSQLiteDatabase, aggregateId: String) {
        val event = UUID.randomUUID().toString()
        db.execSQL("""INSERT INTO orion_domain_event_outbox(eventId,aggregateId,aggregateVersion,eventType,schemaVersion,
            payloadSensitivity,occurredAt,correlationId,status,attemptCount,nextAttemptAt,deliveredAt,version)
            VALUES(?,?,8,'TASK_CREATED',1,'SENSITIVE',1000,?,'PENDING',2,4000,NULL,9)""",
            arrayOf(event, aggregateId, UUID.randomUUID().toString()))
        db.execSQL("INSERT INTO orion_event_consumer_receipts(eventId,consumerId,consumedAt) VALUES(?,'orion_migration_probe',1200)", arrayOf(event))
        val ingress = UUID.randomUUID().toString(); val key = UUID.randomUUID().toString()
        val scope = "notification.post"; val source = "MAIN_UI"; val command = "POST_NOTIFICATION"
        val wire = byteArrayOf(2) + "synthetic ingress".toByteArray()
        val canonical = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            fun field(text: String) { val value = text.toByteArray(); output.writeInt(value.size); output.write(value) }
            field("orion_ingress_v1"); field(scope); field(key); field(source); field(command)
            output.writeInt(1); field("SECRET"); output.writeInt(1); output.writeLong(1_000); output.writeLong(99_000)
            output.writeInt(wire.size); output.write(wire)
        } }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val envelope = encrypt(wire, FieldEncryptionContext("ingress/$ingress/$hash", "payload/1000/2000/100000", 1))
        val values = ContentValues().apply {
            put("ingressId", ingress); put("idempotencyScope", scope); put("idempotencyKey", key)
            put("canonicalPayloadHash", hash); put("schemaVersion", 1); put("commandType", command); put("source", source)
            put("priority", 1); put("correlationId", UUID.randomUUID().toString()); put("payloadSensitivity", "SECRET")
            put("payloadEnvelope", envelope); put("receivedAt", 1_000L); put("expiresAt", 2_000L)
            put("idempotencyRetainUntil", 100_000L); put("status", "QUEUED"); put("version", 4)
            put("updatedAt", 1_500L); put("attemptCount", 2); putNull("lastErrorCode")
        }
        db.insert("orion_ingress_envelopes", SQLiteDatabase.CONFLICT_ABORT, values)
    }
    private fun encrypt(bytes: ByteArray, context: FieldEncryptionContext): ByteArray =
        FieldEnvelopeCodec.encode((encryption.encrypt(bytes, context) as FieldEncryptionResult.Encrypted).envelope)
}

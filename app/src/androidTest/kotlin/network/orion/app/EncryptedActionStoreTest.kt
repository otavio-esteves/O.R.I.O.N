package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.model.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only keys. No Android Keystore, reboot or physical qualification claim. */
@RunWith(AndroidJUnit4::class)
class EncryptedActionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_encrypted_action_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var store: OrionActionStore
    private var now = Instant.parse("2026-11-03T12:00:00Z")
    private val clock = object : WallClock { override fun wallNow() = now }
    private val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    private var keyResult: FieldKeyResult = key
    private val keys = object : FieldKeyProvider {
        override fun currentKey() = keyResult
        override fun keyForVersion(version: Int) = if (version == 1) keyResult else FieldKeyResult.Unavailable
    }
    private val policy = IdempotencyKeyPolicy("CORE", "task.create", Duration.ofDays(30), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    @Before fun open() { db = database(); store = OrionActionStore(db, OrionFieldEncryption(keys), clock) }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun secretPayloadIsEncryptedAndBindingSurvivesDatabaseReopen() {
        val request = request()
        assertEquals(ActionStoreOutcome.REGISTERED, register(request).outcome)
        val entity = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(now.plus(Duration.ofDays(30)).toEpochMilli(), entity.idempotencyRetainUntil)
        assertFalse(entity.argumentsEnvelope.toString(Charsets.ISO_8859_1).contains("secret-arguments"))
        assertFalse(entity.targetEnvelope.toString(Charsets.ISO_8859_1).contains("secret-target"))
        assertEquals("ActionRequestEntity(<redacted>)", entity.toString())
        db.close()
        db = database()
        store = OrionActionStore(db, OrionFieldEncryption(keys), clock)
        val restored = loaded(request.actionId)
        assertEquals(request, restored.request)
        assertEquals(ActionStatus.PENDING, restored.status)
        assertEquals(0L, restored.version)
        // Search both SQLite/WAL files for the test-only unique plaintext markers.
        val path = context.getDatabasePath(name)
        for (file in listOf(path, java.io.File(path.path + "-wal"))) {
            if (file.exists()) {
                val contents = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(contents.contains("secret-arguments"))
                assertFalse(contents.contains("secret-target"))
            }
        }
    }

    @Test fun replayReturnsOriginalActionAndDivergentPayloadConflicts() {
        val first = request()
        register(first)
        val duplicate = first.copy(actionId = UUID.randomUUID())
        val replay = register(duplicate)
        assertEquals(ActionStoreOutcome.DEDUP, replay.outcome)
        assertEquals(first.actionId, replay.actionId)
        assertEquals(ActionStoreOutcome.IDEMPOTENCY_CONFLICT,
            register(duplicate.copy(normalizedTarget = "changed-target")).outcome)
        assertEquals(first, loaded(first.actionId).request)
        assertNull(db.actions().find(duplicate.actionId.toString()))
    }

    @Test fun confirmationRequiresExactBoundMaterialAndFreshPolicy() {
        val request = request()
        register(request)
        assertEquals(ActionStoreOutcome.APPLIED, store.requireConfirmation(request.actionId, 0).outcome)
        val binding = ActionConfirmationBinding(clock)
        val wrong = binding.recordConfirmation(request.copy(normalizedTarget = "other-target"), now.plusSeconds(60))
        assertEquals(ActionStoreOutcome.CONFIRMATION_MISMATCH,
            store.confirm(request.actionId, 1, wrong, "1").outcome)
        val correct = binding.recordConfirmation(request, now.plusSeconds(60))
        assertEquals(ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED,
            store.confirm(request.actionId, 1, correct, "2").outcome)
        assertEquals(ActionStoreOutcome.APPLIED, store.confirm(request.actionId, 1, correct, "1").outcome)
        val restored = loaded(request.actionId)
        assertEquals(ActionStatus.CONFIRMED, restored.status)
        assertEquals(correct, restored.confirmation)
        assertEquals(2L, restored.version)
        assertEquals(ActionStoreOutcome.CONFLICT, store.confirm(request.actionId, 1, correct, "1").outcome)
    }

    @Test fun expiredOrClockRolledBackConfirmationNeverMutatesState() {
        val request = request()
        register(request)
        store.requireConfirmation(request.actionId, 0)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusSeconds(60))
        now = now.plusSeconds(60)
        assertEquals(ActionStoreOutcome.CONFIRMATION_EXPIRED, store.confirm(request.actionId, 1, receipt, "1").outcome)
        now = receipt.confirmedAt.minusSeconds(1)
        assertEquals(ActionStoreOutcome.CONFIRMATION_STALE, store.confirm(request.actionId, 1, receipt, "1").outcome)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, loaded(request.actionId).status)
        assertEquals(2, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun missingOrInvalidatedKeyBlocksReadAndRegistrationWithoutPlaintextFallback() {
        val request = request()
        register(request)
        keyResult = FieldKeyResult.Unavailable
        assertEquals(ActionStoreOutcome.KEY_UNAVAILABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        val second = request.copy(actionId = UUID.randomUUID())
        assertEquals(ActionStoreOutcome.KEY_UNAVAILABLE, register(second, "other-key").outcome)
        assertNull(db.actions().find(second.actionId.toString()))
        keyResult = FieldKeyResult.Invalidated
        assertEquals(ActionStoreOutcome.KEY_INVALIDATED, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        assertEquals(ActionStatus.PENDING, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun swappedFieldsAndTamperedMetadataAreUnreadableAndCannotBeConfirmed() {
        val request = request()
        register(request)
        store.requireConfirmation(request.actionId, 0)
        val original = checkNotNull(db.actions().find(request.actionId.toString()))
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope=targetEnvelope WHERE actionId=?",
            arrayOf(request.actionId.toString()))
        assertEquals(ActionStoreOutcome.UNREADABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusSeconds(60))
        assertEquals(ActionStoreOutcome.UNREADABLE, store.confirm(request.actionId, 1, receipt, "1").outcome)
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope=?, normalizedPayloadHash=? WHERE actionId=?",
            arrayOf(original.argumentsEnvelope, "0".repeat(64), request.actionId.toString()))
        assertEquals(ActionStoreOutcome.UNREADABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun cancellationWorksWithoutDecryptingAndNeverStartsExecution() {
        val request = request()
        register(request)
        keyResult = FieldKeyResult.Unavailable
        assertEquals(ActionStoreOutcome.APPLIED, store.cancel(request.actionId, 0).outcome)
        assertEquals(ActionStatus.CANCELLED, db.actions().find(request.actionId.toString())?.status)
        assertEquals(ActionStoreOutcome.CONFLICT, store.cancel(request.actionId, 0).outcome)
    }

    @Test fun subMillisecondExpiryIsRejectedBeforePersistingAnEmptyLifetime() {
        val request = request()
        register(request)
        store.requireConfirmation(request.actionId, 0)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusNanos(999_999))
        assertEquals(ActionStoreOutcome.CONFIRMATION_EXPIRED, store.confirm(request.actionId, 1, receipt, "1").outcome)
        assertEquals(1L, loaded(request.actionId).version)
    }

    @Test fun rollbackBetweenValidationAndPersistenceReturnsStaleWithoutMutation() {
        val request = request()
        register(request)
        store.requireConfirmation(request.actionId, 0)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusSeconds(60))
        var reads = 0
        val rollback = object : WallClock {
            override fun wallNow() = if (reads++ == 0) now else now.minusSeconds(1)
        }
        val guarded = OrionActionStore(db, OrionFieldEncryption(keys), rollback)
        assertEquals(ActionStoreOutcome.CONFIRMATION_STALE, guarded.confirm(request.actionId, 1, receipt, "1").outcome)
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, loaded(request.actionId).status)
    }

    @Test fun invalidPersistedSchemaOrConfirmationMetadataIsUnreadable() {
        val request = request()
        register(request)
        store.requireConfirmation(request.actionId, 0)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusSeconds(60))
        store.confirm(request.actionId, 1, receipt, "1")
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET confirmedPayloadHash=? WHERE actionId=?",
            arrayOf("0".repeat(64), request.actionId.toString()))
        assertEquals(ActionStoreOutcome.UNREADABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentSchemaVersion=0 WHERE actionId=?",
            arrayOf(request.actionId.toString()))
        assertEquals(ActionStoreOutcome.UNREADABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
    }

    @Test fun destructiveRequestCannotDeclareConfirmationOptional() {
        val destructive = request().copy(authorizationLevel = AuthorizationLevel.DESTRUCTIVE,
            sideEffectClass = SideEffectClass.DESTRUCTIVE)
        assertThrows(IllegalArgumentException::class.java) {
            store.register(destructive, policy, "key", UUID.randomUUID(), Sensitivity.SECRET, false)
        }
        assertNull(db.actions().find(destructive.actionId.toString()))
    }

    @Test fun unknownPersistedEnumIsUnreadableForEveryEntryPoint() {
        val request = request()
        register(request)
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, now.plusSeconds(60))
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET status='BROKEN' WHERE actionId=?",
            arrayOf(request.actionId.toString()))
        assertEquals(ActionStoreOutcome.UNREADABLE, (store.read(request.actionId) as ActionReadResult.Blocked).reason)
        assertEquals(ActionStoreOutcome.UNREADABLE, store.cancel(request.actionId, 0).outcome)
        assertEquals(ActionStoreOutcome.UNREADABLE, store.confirm(request.actionId, 0, receipt, "1").outcome)
        assertEquals(ActionStoreOutcome.UNREADABLE, register(request.copy(actionId = UUID.randomUUID())).outcome)
        assertEquals(1, db.actions().auditFor(request.actionId.toString()).size)
    }

    private fun database() = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .addMigrations(OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4, OrionDatabaseMigrations.MIGRATION_4_5)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)")
            }
        }).build()
    private fun request() = NormalizedActionRequest(UUID.randomUUID(), "task.create", "{\"title\":\"secret-arguments\"}",
        "secret-target", AuthorizationLevel.SAFE_WRITE, 1, SideEffectClass.LOCAL_MUTATION,
        RecoveryStrategy.RETRY_SAFE, IdempotencyMode.KEYED, "1")
    private fun register(request: NormalizedActionRequest, key: String = "key") =
        store.register(request, policy, key, UUID.randomUUID(), Sensitivity.SECRET, true)
    private fun loaded(id: UUID) = (store.read(id) as ActionReadResult.Ready).action
}

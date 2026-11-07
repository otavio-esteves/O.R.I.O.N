package network.orion.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import network.orion.core.actions.ActionStatus
import network.orion.core.model.*
import network.orion.data.database.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android SQLite tests; fixture blobs are not cryptographic/runtime qualification evidence. */
@RunWith(AndroidJUnit4::class)
class ActionPersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_action_test_${UUID.randomUUID()}.db"
    private val migrationName = "orion_migration_test_${UUID.randomUUID()}.db"
    private lateinit var database: OrionDatabase

    @Before fun open() { database = openDatabase() }
    @After fun close() {
        database.close()
        context.deleteDatabase(name)
        context.deleteDatabase(migrationName)
    }

    @Test fun freshDatabaseHasVersionFourAndPreservesTechnicalFormat() {
        database.verifyStructure()
        assertEquals(5, database.openHelper.readableDatabase.version)
        assertEquals(1, database.metadata().formatVersion())
        assertEquals(1, database.metadata().rowCount())
    }

    @Test fun migrationFromDistributedVersionOnePreservesMetadataAndValidatesEveryTable() {
        migrations.createDatabase(migrationName, 1).use { old ->
            old.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)")
        }
        migrations.runMigrationsAndValidate(migrationName, 2, true, OrionDatabaseMigrations.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT formatVersion FROM orion_storage_metadata WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
        val upgraded = Room.databaseBuilder(context, OrionDatabase::class.java, migrationName)
            .addMigrations(OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4, OrionDatabaseMigrations.MIGRATION_4_5).build()
        try {
            upgraded.verifyStructure()
            assertEquals(5, upgraded.openHelper.readableDatabase.version)
            assertNull(upgraded.actions().find(UUID.randomUUID().toString()))
        } finally { upgraded.close() }
    }

    @Test fun registrationReplaysSamePayloadAndRejectsConflictsWithoutReplacingOriginal() {
        val action = draft()
        val dao = database.actions()
        assertEquals(ActionRegistration.INSERTED, dao.registerDraft(action, audit(action)))
        val replay = action.copy(actionId = UUID.randomUUID().toString(), argumentsEnvelope = byteArrayOf(9))
        assertEquals(ActionRegistration.DEDUP, dao.registerDraft(replay, audit(replay)))
        assertEquals(ActionRegistration.IDEMPOTENCY_CONFLICT,
            dao.registerDraft(replay.copy(normalizedPayloadHash = "b".repeat(64)), audit(replay)))
        assertEquals(ActionRegistration.IDEMPOTENCY_CONFLICT,
            dao.registerDraft(replay.copy(authorizationLevel = AuthorizationLevel.DESTRUCTIVE), audit(replay)))
        assertEquals(ActionRegistration.ACTION_ID_CONFLICT,
            dao.registerDraft(action.copy(idempotencyKey = "different"), audit(action)))
        assertEquals(action.actionId, dao.findByKey(action.idempotencyScope, action.idempotencyKey)?.actionId)
        assertEquals(1, dao.auditFor(action.actionId).size)
        assertNull(dao.find(replay.actionId))
    }

    @Test fun auditFailureRollsBackNewActionAndItsIdempotencyKey() {
        val first = draft()
        val initialAudit = audit(first)
        database.actions().registerDraft(first, initialAudit)
        val second = draft()
        assertThrows(SQLiteConstraintException::class.java) {
            database.actions().registerDraft(second, audit(second).copy(auditId = initialAudit.auditId))
        }
        assertNull(database.actions().find(second.actionId))
        assertNull(database.actions().findByKey(second.idempotencyScope, second.idempotencyKey))
        assertEquals(1, database.actions().auditFor(first.actionId).size)
    }

    @Test fun sameIdempotencyKeyInDifferentScopesRegistersIndependentActions() {
        val first = draft()
        val second = draft().copy(idempotencyKey = first.idempotencyKey, idempotencyScope = "other.operation")
        assertEquals(ActionRegistration.INSERTED, database.actions().registerDraft(first, audit(first)))
        assertEquals(ActionRegistration.INSERTED, database.actions().registerDraft(second, audit(second)))
        assertNotNull(database.actions().find(first.actionId))
        assertNotNull(database.actions().find(second.actionId))
    }

    @Test fun auditFailureRollsBackStateVersionAndTimestampTogether() {
        val action = draft()
        val initialAudit = audit(action)
        database.actions().registerDraft(action, initialAudit)
        assertThrows(SQLiteConstraintException::class.java) {
            database.actions().changeDraftStatus(action.actionId, 0, ActionStatus.CANCELLED, 200,
                audit(action, ActionStatus.PENDING, ActionStatus.CANCELLED, 1, 200).copy(auditId = initialAudit.auditId))
        }
        val saved = checkNotNull(database.actions().find(action.actionId))
        assertEquals(ActionStatus.PENDING, saved.status)
        assertEquals(0L, saved.version)
        assertEquals(100L, saved.updatedAt)
        assertEquals(1, database.actions().auditFor(action.actionId).size)
    }

    @Test fun staleVersionLeavesStateAndAuditUntouched() {
        val action = draft()
        database.actions().registerDraft(action, audit(action))
        assertEquals(ActionMutation.CONFLICT, database.actions().changeDraftStatus(action.actionId, 1,
            ActionStatus.CANCELLED, 200, audit(action, ActionStatus.PENDING, ActionStatus.CANCELLED, 2, 200)))
        assertEquals(0L, database.actions().find(action.actionId)?.version)
        assertEquals(1, database.actions().auditFor(action.actionId).size)
    }

    @Test fun concurrentWritersWithSameVersionHaveOneWinnerAndOneConflict() {
        val action = draft()
        database.actions().registerDraft(action, audit(action))
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(ActionStatus.CANCELLED, ActionStatus.NEEDS_CONFIRMATION).map { status ->
                executor.submit(Callable {
                    assertTrue(gate.await(10, TimeUnit.SECONDS))
                    database.actions().changeDraftStatus(action.actionId, 0, status, 200,
                        audit(action, ActionStatus.PENDING, status, 1, 200))
                })
            }
            gate.countDown()
            assertEquals(setOf(ActionMutation.APPLIED, ActionMutation.CONFLICT),
                futures.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(1L, database.actions().find(action.actionId)?.version)
            assertEquals(2, database.actions().auditFor(action.actionId).size)
        } finally { executor.shutdownNow() }
    }

    @Test fun confirmationLocksStoredPayloadAndPolicyInSameAuditTransaction() {
        val action = draft()
        val dao = database.actions()
        dao.registerDraft(action, audit(action))
        assertThrows(IllegalArgumentException::class.java) {
            dao.confirmDraft(action.actionId, 0, action.normalizedPayloadHash, action.confirmationFingerprint,
                action.policyVersion, 200, 300, audit(action, ActionStatus.PENDING, ActionStatus.CONFIRMED, 1, 200))
        }
        dao.changeDraftStatus(action.actionId, 0, ActionStatus.NEEDS_CONFIRMATION, 150,
            audit(action, ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION, 1, 150))
        assertThrows(IllegalArgumentException::class.java) {
            dao.confirmDraft(action.actionId, 1, "b".repeat(64), action.confirmationFingerprint,
                action.policyVersion, 200, 300, audit(action, ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED, 2, 200))
        }
        assertEquals(ActionMutation.APPLIED, dao.confirmDraft(action.actionId, 1,
            action.normalizedPayloadHash, action.confirmationFingerprint, action.policyVersion, 200, 300,
            audit(action, ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED, 2, 200)))
        val saved = checkNotNull(dao.find(action.actionId))
        assertEquals(ActionStatus.CONFIRMED, saved.status)
        assertEquals(2L, saved.version)
        assertEquals(200L, saved.confirmedAt)
        assertEquals(200L, saved.payloadLockedAt)
        assertEquals(300L, saved.confirmationExpiresAt)
        assertEquals(action.normalizedPayloadHash, saved.confirmedPayloadHash)
        assertEquals(action.policyVersion, saved.policyVersionAtConfirmation)
        assertArrayEquals(action.argumentsEnvelope, saved.argumentsEnvelope)
        assertEquals(3, dao.auditFor(action.actionId).size)
    }

    @Test fun genericPersistenceCannotStartOrRetryAnExecution() {
        val action = draft(requiresConfirmation = false)
        database.actions().registerDraft(action, audit(action))
        for (status in listOf(ActionStatus.RUNNING, ActionStatus.SUCCEEDED, ActionStatus.UNKNOWN, ActionStatus.CONFIRMED)) {
            assertThrows(IllegalArgumentException::class.java) {
                database.actions().changeDraftStatus(action.actionId, 0, status, 200,
                    audit(action, ActionStatus.PENDING, status, 1, 200))
            }
        }
        assertEquals(ActionStatus.PENDING, database.actions().find(action.actionId)?.status)
        assertEquals(1, database.actions().auditFor(action.actionId).size)
    }

    @Test fun failedConfirmationAuditRollsBackEveryConfirmationField() {
        val action = draft(requiresConfirmation = false)
        val originalAudit = audit(action)
        database.actions().registerDraft(action, originalAudit)
        assertThrows(SQLiteConstraintException::class.java) {
            database.actions().confirmDraft(action.actionId, 0, action.normalizedPayloadHash,
                action.confirmationFingerprint, action.policyVersion, 200, 300,
                audit(action, ActionStatus.PENDING, ActionStatus.CONFIRMED, 1, 200).copy(auditId = originalAudit.auditId))
        }
        val saved = checkNotNull(database.actions().find(action.actionId))
        assertEquals(ActionStatus.PENDING, saved.status)
        assertEquals(0L, saved.version)
        assertEquals(100L, saved.updatedAt)
        assertNull(saved.confirmedAt)
        assertNull(saved.confirmationExpiresAt)
        assertNull(saved.confirmedPayloadHash)
        assertNull(saved.policyVersionAtConfirmation)
        assertNull(saved.payloadLockedAt)
        assertEquals(1, database.actions().auditFor(action.actionId).size)
    }

    @Test fun uncertainAndAbandonedExecutionFixturesSurviveReopenWithoutStateInference() {
        val unknown = draft()
        val running = draft()
        database.actions().registerDraft(unknown, audit(unknown))
        database.actions().registerDraft(running, audit(running))
        // Fixture represents a future executor's already-committed state, not a production mutator.
        database.openHelper.writableDatabase.execSQL("""UPDATE orion_action_requests SET status='UNKNOWN',
            executionId='fixture', claimedBy='fixture', leaseBootSessionId=1,
            leaseStartedElapsedRealtime=100, leaseExpiresElapsedRealtime=200 WHERE actionId=?""", arrayOf(unknown.actionId))
        database.openHelper.writableDatabase.execSQL("""UPDATE orion_action_requests SET status='RUNNING',
            executionId='fixture', claimedBy='fixture', leaseBootSessionId=1,
            leaseStartedElapsedRealtime=100, leaseExpiresElapsedRealtime=200 WHERE actionId=?""", arrayOf(running.actionId))
        val replay = unknown.copy(actionId = UUID.randomUUID().toString())
        assertEquals(ActionRegistration.DEDUP, database.actions().registerDraft(replay, audit(replay)))
        assertNull(database.actions().find(replay.actionId))
        database.close()
        database = openDatabase()
        assertEquals(ActionStatus.UNKNOWN, database.actions().find(unknown.actionId)?.status)
        val persistedRunning = checkNotNull(database.actions().find(running.actionId))
        assertEquals(ActionStatus.RUNNING, persistedRunning.status)
        assertEquals(1, persistedRunning.leaseBootSessionId)
        assertEquals(200L, persistedRunning.leaseExpiresElapsedRealtime)
        assertEquals(1, database.actions().auditFor(unknown.actionId).size)
    }

    private fun openDatabase(): OrionDatabase = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .addMigrations(OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4, OrionDatabaseMigrations.MIGRATION_4_5)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)")
            }
        }).build()

    private fun draft(requiresConfirmation: Boolean = true) = ActionRequestEntity(
        actionId = UUID.randomUUID().toString(), skill = "task.create", argumentSchemaVersion = 1,
        argumentsEnvelope = byteArrayOf(1, 2), targetEnvelope = byteArrayOf(3, 4),
        payloadSensitivity = Sensitivity.SECRET, authorizationLevel = AuthorizationLevel.SAFE_WRITE,
        sideEffectClass = SideEffectClass.LOCAL_MUTATION, recoveryStrategy = RecoveryStrategy.RETRY_SAFE,
        idempotencyMode = IdempotencyMode.KEYED, policyVersion = "1", status = ActionStatus.PENDING,
        createdAt = 100, updatedAt = 100, idempotencyScope = "task.create", idempotencyKey = UUID.randomUUID().toString(),
        idempotencyKeyIssuer = "CORE", canonicalPayloadSchemaVersion = 1, idempotencyRetainUntil = 1_000,
        correlationId = UUID.randomUUID().toString(), requiresConfirmation = requiresConfirmation,
        normalizedPayloadHash = "a".repeat(64), confirmationFingerprint = "c".repeat(64),
    )

    private fun audit(action: ActionRequestEntity, from: ActionStatus? = null,
        to: ActionStatus = ActionStatus.PENDING, version: Long = 0, at: Long = 100) = ActionAuditEntity(
        UUID.randomUUID().toString(), action.actionId, version, from, to, at, action.correlationId)
}

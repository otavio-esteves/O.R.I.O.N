package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.model.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite transactions with injected clocks/test keys; no external effect or physical qualification. */
@RunWith(AndroidJUnit4::class)
class ActionExecutionOwnershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_action_lease_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var store: OrionActionStore
    private lateinit var execution: OrionActionExecutionStore
    private var wall = Instant.parse("2026-11-04T12:00:00Z")
    private var monotonic = MonotonicInstant(BootSessionId(4), 100)
    private val clock = object : OrionClock {
        override fun wallNow() = wall
        override fun monotonicNow() = monotonic
    }
    private val keys = object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun keyForVersion(version: Int) = if (version == 1) currentKey() else FieldKeyResult.Unavailable
    }
    private var ready = true
    private var allowed = true
    private var permission = true
    private var capability = true
    private var present = true
    private var policyVersion = "1"
    private val authority = object : ActionExecutionAuthority {
        override fun startupBarrierOpen() = ready
        override fun descriptorFor(skill: String) = descriptor
        override fun currentPolicyVersion() = policyVersion
        override fun policyAllows(request: NormalizedActionRequest) = allowed
        override fun capabilityAvailable(capability: Capability) = this@ActionExecutionOwnershipTest.capability
        override fun permissionGranted(permission: String) = this@ActionExecutionOwnershipTest.permission
        override fun userPresent() = present
    }
    private var descriptor = descriptor(SideEffectClass.LOCAL_MUTATION, RecoveryStrategy.RETRY_SAFE)
    private val idempotency = IdempotencyKeyPolicy("CORE", "lease.test", Duration.ofDays(30), 1,
        OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    @Before fun open() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        store = OrionActionStore(db, OrionFieldEncryption(keys), clock)
        execution = OrionActionExecutionStore(db, store, clock, authority)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun onlyOneConcurrentClaimWinsAndPersistsAtomicOwnershipAudit() {
        val request = confirmed()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll((1..2).map { owner -> Callable {
                execution.claim(request.actionId, 2, "main-$owner", Duration.ofSeconds(10))
            } }).map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ActionClaimResult.Claimed })
            val entity = checkNotNull(db.actions().find(request.actionId.toString()))
            assertEquals(ActionStatus.RUNNING, entity.status)
            assertEquals(3L, entity.version)
            assertEquals(1, entity.attemptCount)
            assertEquals(4, entity.leaseBootSessionId)
            assertEquals(100L, entity.leaseStartedElapsedRealtime)
            assertEquals(10_100L, entity.leaseExpiresElapsedRealtime)
            assertEquals(entity.executionId, entity.executionToken)
            assertEquals(4, db.actions().auditFor(request.actionId.toString()).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun startupPolicyPermissionsCapabilitiesAndPresenceDenyClaimWithoutMutation() {
        val request = confirmed()
        ready = false
        assertBlocked(request, ActionClaimFailure.STARTUP_GATED)
        ready = true; policyVersion = "2"
        assertBlocked(request, ActionClaimFailure.POLICY_CHANGED)
        policyVersion = "1"; allowed = false
        assertBlocked(request, ActionClaimFailure.POLICY_DENIED)
        allowed = true; permission = false
        assertBlocked(request, ActionClaimFailure.PERMISSION_DENIED)
        permission = true; capability = false
        assertBlocked(request, ActionClaimFailure.CAPABILITY_UNAVAILABLE)
        capability = true; present = false
        assertBlocked(request, ActionClaimFailure.USER_PRESENCE_REQUIRED)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(request.actionId.toString())?.status)
        assertEquals(3, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun expiryAndBootIdentityFailureBlockClaims() {
        val request = confirmed()
        monotonic = MonotonicInstant(null, 100)
        assertBlocked(request, ActionClaimFailure.BOOT_ID_UNAVAILABLE)
        monotonic = MonotonicInstant(BootSessionId(4), 100)
        wall = wall.plusSeconds(60)
        assertBlocked(request, ActionClaimFailure.CONFIRMATION_EXPIRED)
        assertEquals(2L, db.actions().find(request.actionId.toString())?.version)
    }

    @Test fun oldBootExecutionBecomesUnknownAndCannotBeReclaimedOrCompletedByLateCallback() {
        descriptor = descriptor(SideEffectClass.EXTERNAL_IRREVERSIBLE, RecoveryStrategy.NO_RETRY)
        val request = confirmed()
        val claim = execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Claimed
        monotonic = MonotonicInstant(BootSessionId(5), 1)
        assertEquals(1, execution.recoverAbandoned().recovered)
        val entity = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(ActionStatus.UNKNOWN, entity.status)
        assertEquals(claim.lease.executionId.toString(), entity.executionId)
        assertEquals(ActionMutation.CONFLICT, execution.finish(request.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED))
        val retry = execution.claim(request.actionId, entity.version, "main", Duration.ofSeconds(10))
        assertEquals(ActionClaimFailure.INVALID_STATE, (retry as ActionClaimResult.Blocked).reason)
        assertEquals(0, execution.recoverAbandoned().recovered)
    }

    @Test fun activeLeaseSurvivesRecoveryButExpiredLocalLeaseRequiresReview() {
        val request = confirmed()
        execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10))
        assertEquals(0, execution.recoverAbandoned().recovered)
        monotonic = MonotonicInstant(BootSessionId(4), 10_100)
        assertEquals(1, execution.recoverAbandoned().recovered)
        assertEquals(ActionStatus.MANUAL_REVIEW, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun activeOwnerCompletesExactlyOneTerminalTransition() {
        val request = confirmed()
        val claim = execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Claimed
        assertEquals(ActionMutation.CONFLICT, execution.finish(request.actionId, claim.version, UUID.randomUUID(), ActionStatus.SUCCEEDED))
        assertEquals(ActionMutation.APPLIED, execution.finish(request.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED))
        assertEquals(ActionMutation.CONFLICT, execution.finish(request.actionId, claim.version, claim.lease.executionId, ActionStatus.FAILED))
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun uncertainCompletionDoesNotAssertAnExecutionTimeOrAllowRetry() {
        descriptor = descriptor(SideEffectClass.EXTERNAL_IRREVERSIBLE, RecoveryStrategy.NO_RETRY)
        val request = confirmed()
        val claim = execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Claimed
        assertEquals(ActionMutation.APPLIED, execution.finish(request.actionId, claim.version, claim.lease.executionId, ActionStatus.UNKNOWN))
        val row = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(ActionStatus.UNKNOWN, row.status)
        assertNull(row.executedAt)
        assertEquals(ActionClaimFailure.INVALID_STATE,
            (execution.claim(request.actionId, row.version, "main", Duration.ofSeconds(10)) as ActionClaimResult.Blocked).reason)
    }

    @Test fun auditFailureRollsBackOwnershipClaim() {
        val request = confirmed()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_claim BEFORE INSERT ON orion_action_audit
            WHEN NEW.toStatus = 'RUNNING' BEGIN SELECT RAISE(ABORT, 'test audit failure'); END""")
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10))
        }
        val entity = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(ActionStatus.CONFIRMED, entity.status)
        assertEquals(2L, entity.version)
        assertNull(entity.executionId)
        assertEquals(0, entity.attemptCount)
    }

    @Test fun boundPayloadCorruptionCannotBeClaimed() {
        val request = confirmed()
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET argumentsEnvelope=targetEnvelope WHERE actionId=?",
            arrayOf(request.actionId.toString()))
        assertBlocked(request, ActionClaimFailure.UNREADABLE)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun directDaoClaimRejectsPayloadHashAndFingerprintMismatchWithoutOwnership() {
        val request = confirmed()
        val row = checkNotNull(db.actions().find(request.actionId.toString()))
        val at = wall.toEpochMilli()
        fun claim(hash: String, fingerprint: String) = db.actions().claimConfirmed(request.actionId.toString(), 2,
            hash, fingerprint, "1", UUID.randomUUID().toString(), "main", 4, 100, 10_100, at,
            ActionAuditEntity(UUID.randomUUID().toString(), row.actionId, 3, ActionStatus.CONFIRMED,
                ActionStatus.RUNNING, at, row.correlationId))
        assertThrows(IllegalArgumentException::class.java) { claim("0".repeat(64), row.confirmationFingerprint) }
        assertThrows(IllegalArgumentException::class.java) { claim(row.normalizedPayloadHash, "0".repeat(64)) }
        val preserved = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(ActionStatus.CONFIRMED, preserved.status)
        assertNull(preserved.executionId)
        assertEquals(3, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun ownershipSurvivesReopenAndOriginalLeaseCannotCompleteAfterExpiry() {
        val request = confirmed()
        val claimed = execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Claimed
        db.close()
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        store = OrionActionStore(db, OrionFieldEncryption(keys), clock)
        execution = OrionActionExecutionStore(db, store, clock, authority)
        assertEquals(claimed.lease.executionId.toString(), db.actions().find(request.actionId.toString())?.executionId)
        monotonic = MonotonicInstant(BootSessionId(4), 10_100)
        assertEquals(ActionMutation.CONFLICT, execution.finish(request.actionId, claimed.version, claimed.lease.executionId, ActionStatus.SUCCEEDED))
        assertEquals(1, execution.recoverAbandoned().recovered)
        assertEquals(ActionStatus.MANUAL_REVIEW, db.actions().find(request.actionId.toString())?.status)
    }

    @Test fun confirmationExpiringWhileWaitingForWriterCannotBeClaimed() {
        val request = confirmed()
        val result = withContendedWriter(
            operation = { execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) },
            whileWaiting = {
                wall = wall.plusSeconds(60)
                monotonic = MonotonicInstant(BootSessionId(4), 60_100)
            },
        )
        assertEquals(ActionClaimFailure.CONFIRMATION_EXPIRED, (result as ActionClaimResult.Blocked).reason)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(request.actionId.toString())?.status)
        assertEquals(0, db.actions().find(request.actionId.toString())?.attemptCount)
        assertEquals(3, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun confirmationExpiringWhileWaitingForWriterIsNotPersisted() {
        val request = awaitingConfirmation()
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, wall.plusSeconds(60))
        val result = withContendedWriter(
            operation = { store.confirm(request.actionId, 1, receipt, "1") },
            whileWaiting = { wall = wall.plusSeconds(60) },
        )
        assertEquals(ActionStoreOutcome.CONFIRMATION_EXPIRED, result.outcome)
        assertUnconfirmed(request)
    }

    @Test fun clockRollbackWhileWaitingForWriterCannotPersistFutureConfirmation() {
        val request = awaitingConfirmation()
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, wall.plusSeconds(60))
        val result = withContendedWriter(
            operation = { store.confirm(request.actionId, 1, receipt, "1") },
            whileWaiting = { wall = wall.minusSeconds(1) },
        )
        assertEquals(ActionStoreOutcome.CONFIRMATION_STALE, result.outcome)
        assertUnconfirmed(request)
    }

    @Test fun stillValidConfirmationCommitsExactReceiptAfterWaitingForWriter() {
        val request = awaitingConfirmation()
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, wall.plusSeconds(60))
        val result = withContendedWriter(
            operation = { store.confirm(request.actionId, 1, receipt, "1") },
            whileWaiting = { wall = wall.plusSeconds(1) },
        )
        assertEquals(ActionStoreOutcome.APPLIED, result.outcome)
        val loaded = (store.read(request.actionId) as ActionReadResult.Ready).action
        assertEquals(ActionStatus.CONFIRMED, loaded.status)
        assertEquals(2L, loaded.version)
        assertEquals(receipt, loaded.confirmation)
        assertEquals(3, db.actions().auditFor(request.actionId.toString()).size)
    }

    private fun assertUnconfirmed(request: NormalizedActionRequest) {
        val row = checkNotNull(db.actions().find(request.actionId.toString()))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION, row.status)
        assertEquals(1L, row.version)
        assertNull(row.confirmedAt)
        assertNull(row.confirmedPayloadHash)
        assertNull(row.payloadLockedAt)
        assertEquals(2, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun newLeaseStartsAfterAcquiringWriterInsteadOfBeforeTheWait() {
        val request = confirmed()
        val result = withContendedWriter(
            operation = { execution.claim(request.actionId, 2, "main", Duration.ofMillis(10)) },
            whileWaiting = {
                wall = wall.plusSeconds(1)
                monotonic = MonotonicInstant(BootSessionId(4), 10_000)
            },
        ) as ActionClaimResult.Claimed
        assertEquals(10_000L, result.lease.startedElapsedRealtime)
        assertEquals(10_010L, result.lease.expiresElapsedRealtime)
        assertEquals(ActionLeaseStatus.ACTIVE, result.lease.statusAt(clock.monotonicNow()))
        assertEquals(10_000L, db.actions().find(request.actionId.toString())?.leaseStartedElapsedRealtime)
    }

    @Test fun permissionRevokedWhileWaitingForWriterBlocksClaim() {
        val request = confirmed()
        val result = withContendedWriter(
            operation = { execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) },
            whileWaiting = { permission = false },
        )
        assertEquals(ActionClaimFailure.PERMISSION_DENIED, (result as ActionClaimResult.Blocked).reason)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(request.actionId.toString())?.status)
        assertEquals(3, db.actions().auditFor(request.actionId.toString()).size)
    }

    @Test fun terminalCallbackWaitingForWriterCannotUseAnExpiredLease() {
        val request = confirmed()
        val claim = execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Claimed
        val result = withContendedWriter(
            operation = { execution.finish(request.actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) },
            whileWaiting = {
                wall = wall.plusSeconds(10)
                monotonic = MonotonicInstant(BootSessionId(4), 10_100)
            },
        )
        assertEquals(ActionMutation.CONFLICT, result)
        assertEquals(ActionStatus.RUNNING, db.actions().find(request.actionId.toString())?.status)
        assertNull(db.actions().find(request.actionId.toString())?.executedAt)
        assertEquals(4, db.actions().auditFor(request.actionId.toString()).size)
    }

    /**
     * Observe BEGIN synchronously before SQLite admits the contending writer.
     * The existing writer holds its transaction while the test advances clocks
     * or authority. Both the old DAO transaction and the adapter's outer
     * transaction reach this checkpoint, so the regression fails deterministically.
     */
    private fun <T> withContendedWriter(operation: () -> T, whileWaiting: () -> Unit): T {
        db.close()
        val contender = AtomicReference<Thread?>()
        val admissionRequested = CountDownLatch(1)
        val continueAdmission = CountDownLatch(1)
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("BEGIN") && Thread.currentThread() === contender.get()) {
                    admissionRequested.countDown()
                    check(continueAdmission.await(10, TimeUnit.SECONDS))
                }
            }, Executor { it.run() })
            .build()
        db.openHelper.writableDatabase
        store = OrionActionStore(db, OrionFieldEncryption(keys), clock)
        execution = OrionActionExecutionStore(db, store, clock, authority)
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                db.runInTransaction {
                    writerEntered.countDown()
                    check(releaseWriter.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue("Writer did not acquire its transaction", writerEntered.await(10, TimeUnit.SECONDS))
            val pending = pool.submit(Callable {
                contender.set(Thread.currentThread())
                operation()
            })
            assertTrue("Operation did not request its write transaction", admissionRequested.await(10, TimeUnit.SECONDS))
            assertFalse(pending.isDone)
            whileWaiting()
            releaseWriter.countDown()
            continueAdmission.countDown()
            writer.get(10, TimeUnit.SECONDS)
            return pending.get(10, TimeUnit.SECONDS)
        } finally {
            releaseWriter.countDown()
            continueAdmission.countDown()
            pool.shutdownNow()
        }
    }

    private fun assertBlocked(request: NormalizedActionRequest, reason: ActionClaimFailure) {
        assertEquals(reason, (execution.claim(request.actionId, 2, "main", Duration.ofSeconds(10)) as ActionClaimResult.Blocked).reason)
    }

    private fun confirmed(): NormalizedActionRequest {
        val request = awaitingConfirmation()
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(request, wall.plusSeconds(60))
        assertEquals(ActionStoreOutcome.APPLIED, store.confirm(request.actionId, 1, receipt, "1").outcome)
        return request
    }

    private fun awaitingConfirmation(): NormalizedActionRequest {
        val request = NormalizedActionRequest(UUID.randomUUID(), descriptor.name, "{}", "target",
            descriptor.authorizationLevel, 1, descriptor.sideEffectClass, descriptor.recoveryStrategy, descriptor.idempotencyMode, "1")
        assertEquals(ActionStoreOutcome.REGISTERED, store.register(request, idempotency, UUID.randomUUID().toString(),
            UUID.randomUUID(), Sensitivity.SECRET, true).outcome)
        assertEquals(ActionStoreOutcome.APPLIED, store.requireConfirmation(request.actionId, 0).outcome)
        return request
    }

    private fun descriptor(effect: SideEffectClass, recovery: RecoveryStrategy) = SkillDescriptor("test.effect",
        AuthorizationLevel.SAFE_WRITE, recovery, effect, IdempotencyMode.KEYED, setOf(Capability("test.write")),
        setOf("test.PERMISSION"), true, ConfirmationPolicy.ALWAYS, false, 1)
}

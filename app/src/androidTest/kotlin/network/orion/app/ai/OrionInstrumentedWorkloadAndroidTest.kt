package network.orion.app.ai

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.core.resources.*
import network.orion.core.actions.ActionStatus
import network.orion.core.model.*
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.ActionRequestEntity
import network.orion.data.database.ActionAuditEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** EMULATOR/framework/Room controls, synthetic configured Owner and no executable native-model admission. */
@RunWith(AndroidJUnit4::class)
class OrionInstrumentedWorkloadAndroidTest {
    private class Clock : OrionClock {
        val reads = AtomicInteger()
        override fun monotonicNow() = MonotonicInstant(BootSessionId(1), 1000).also { reads.incrementAndGet() }
        override fun wallNow() = Instant.EPOCH
    }
    private class Fixture : AutoCloseable {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString(); private val name = "orion_instrumented_$id.db"
        private val root = File(app.noBackupFilesDir, "orion_instrumented_$id").also { check(it.mkdirs()) }
        val clock = Clock(); val binds = AtomicInteger(); val inventory = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir() = root
            override fun getPackageManager(): PackageManager { inventory.incrementAndGet(); return super.getPackageManager() }
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean { binds.incrementAndGet(); error("No native bind") }
        }
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
        private val importer = OrionModelImportCoordinator.open(context, db, clock, 4096, 0)
        private val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 1000), "SYNTHETIC_NOT_QUALIFIED",
            1, 128, 8, 0, 10000, 20000, 100, 20, 2)
        private val owners = mutableListOf<OrionCortexRuntimeOwner>()
        fun owner(configured: Boolean = true, core: () -> Boolean = { true }) = OrionCortexRuntimeOwner(context, importer, db, clock,
            if (configured) listOf(plan) else emptyList(), OrionCortexResourceTiming(1000, 1000), coreReady = core,
            singleAttemptTemplate = OrionCortexAttemptTemplate(HASH, 128, 1, 8, 1000, 0.1, OrionInferencePriority.INTERACTIVE,
                "SECRET_TRUSTED_TEMPLATE", ""), runtimeCaptureObservation = OrionRuntimeCaptureObservationConfiguration(10, 1))
            .also { owners.add(it) }
        fun ready() {
            db.models().registerImported(ModelRegistryEntity(HASH, "SYNTHETIC_NOT_QUALIFIED", "verified/$HASH.gguf", HASH,
                1000, null, null, null, "IMPORTED", 0, null))
            check(db.models().beginValidation(HASH, 0) == 1)
            check(db.models().completeValidation(HASH, 1, HASH, 1000, "fixture", "fixture", 128, 0) == 1)
        }
        override fun close() { owners.forEach { it.close() }; db.close(); app.deleteDatabase(name); root.deleteRecursively(); assertEquals(0, binds.get()) }
    }
    private fun context(): OrionRuntimeQualifiedContext = OrionRuntimeQualifiedContext::class.java.declaredConstructors
        .single { it.parameterCount == 4 }.apply { isAccessible = true }
        .newInstance("network.orion.qualification", "network.orion.qualification.test", android.os.Process.myUid(), android.os.Process.myPid()) as OrionRuntimeQualifiedContext
    private fun scope(owner: OrionCortexRuntimeOwner): OrionInstrumentedWorkloadScope =
        OrionInstrumentedWorkloadScope(OrionInstrumentedGraphIdentity(Any(), owner), UUID.randomUUID().toString(), UUID.randomUUID().toString())

    @Test fun emptyQueueTransactionReportsActualZeroAndClosedDbReportsUnknown() {
        Fixture().use { f ->
            assertEquals(OrionInstrumentedQueueCounts(0, 0), readInstrumentedWorkloadQueues(f.db))
            f.db.close(); assertNull(readInstrumentedWorkloadQueues(f.db))
        }
    }
    @Test fun queueTransactionCountsAllUnresolvedStatesSeparatelyFromUnknownWithoutCopyingPayloads() {
        Fixture().use { f ->
            // Disposable fixture: raw state assignment covers every stored status without pretending to execute actions.
            for (state in ActionStatus.entries) {
                val action = ActionRequestEntity(UUID.randomUUID().toString(), "task.create", 1,
                    byteArrayOf(1), byteArrayOf(2), Sensitivity.SECRET, AuthorizationLevel.SAFE_WRITE,
                    SideEffectClass.LOCAL_MUTATION, RecoveryStrategy.RETRY_SAFE, IdempotencyMode.KEYED, "1",
                    ActionStatus.PENDING, 100, 100, "fixture", UUID.randomUUID().toString(), "CORE", 1, 1000,
                    UUID.randomUUID().toString(), true, HASH, HASH)
                f.db.actions().registerDraft(action, ActionAuditEntity(UUID.randomUUID().toString(), action.actionId,
                    0, null, ActionStatus.PENDING, 100, action.correlationId))
                f.db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET status=? WHERE actionId=?",
                    arrayOf(state.name, action.actionId))
            }
            assertEquals(OrionInstrumentedQueueCounts(5, 1), readInstrumentedWorkloadQueues(f.db))
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM orion_action_requests").use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(ActionStatus.entries.size, rows.getInt(0))
            }
        }
    }
    @Test fun actualMainPssObservationWithNoAiEndpointPreservesUnavailableAiAndDoesNotBind() {
        Fixture().use { f ->
            val app = f.app.applicationContext as network.orion.app.OrionApplication
            val observed = checkNotNull(readInstrumentedWorkloadResources(f.app, f.owner(configured = false),
                AndroidOrionClock(f.app.contentResolver), checkNotNull(app.mainRuntime)))
            assertEquals(android.os.Process.myPid(), observed.mainPid)
            assertTrue(checkNotNull(observed.mainPssBytes) > 0); assertNull(observed.ai)
            assertEquals(0, f.binds.get()); assertEquals(0, f.clock.reads.get())
            assertTrue(observed.completedElapsed >= observed.startedElapsed)
        }
    }
    @Test fun wholeNativeBundleIncludesCheckpointAndRefusesMaximumTransportShapeWithoutClipping() {
        val component = android.content.ComponentName("network.orion.qualification.test", OrionRuntimeInstrumentationContext.WORKLOAD_RUNNER)
        fun shape(checkpointLength: Int) = android.os.Bundle().apply {
            putString(OrionInstrumentedCheckpointFraming.CHECKPOINT, "c".repeat(checkpointLength))
            putString(OrionRuntimeCapsuleParcelGuard.STATUS_KEY, "s".repeat(1024))
            putString(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY, "x".repeat(96 * 1024))
            putString("stream", "ORION_WORKLOAD_CHECKPOINT")
        }
        // This is native geometry only, with explicitly synthetic shape strings, not semantic SOURCE proof.
        val maximum = checkNotNull(OrionRuntimeInstrumentationFraming.measure(shape(16 * 1024), component))
        assertTrue(maximum.bundleBytes > 224 * 1024); assertFalse(maximum.withinBudget)
        val actualClosedShapeBound = checkNotNull(OrionRuntimeInstrumentationFraming.measure(shape(3840), component))
        assertTrue(actualClosedShapeBound.withinBudget)
    }
    @Test fun emptyPlansRefuseBeforeFunctionalClockRegistryInventoryOrBinding() = runBlocking {
        Fixture().use { f ->
            val owner = f.owner(configured = false, core = { error("No Core read after no-profile gate") })
            f.db.close()
            assertEquals(OrionCortexUnavailable.NO_QUALIFIED_PROFILE, owner.preResourceReadiness().unavailable)
            val refusal = owner.selectRuntimeCaptureTemplate() as OrionCortexAttemptConfiguration.Refused
            assertEquals(OrionCortexAttemptRefusal.NO_QUALIFIED_PROFILE, refusal.reason)
            assertEquals(0, f.clock.reads.get()); assertEquals(0, f.inventory.get()); assertEquals(0, f.binds.get())
            assertNull(owner.instrumentedMemoryObservation()) // No endpoint; observer does not bind or fabricate zero PSS.
        }
    }
    @Test fun configuredProgramReachesRealOwnerAndPreservesOriginalThrowableWithoutNativeBinding() = runBlocking {
        for (bootstrap in listOf(false, true)) Fixture().use { f ->
            f.ready(); val reads = AtomicInteger(); val original = AssertionError("SECRET_ORIGINAL_CORE_ERROR")
            val owner = f.owner(core = { if (reads.incrementAndGet() == 4) throw original else true })
            val selected = owner.selectRuntimeCaptureTemplate() as OrionCortexAttemptConfiguration.Ready
            val binding = checkNotNull(owner.instrumentedConfigurationBinding(selected.template)); val scope = scope(owner)
            val program = OrionCortexInstrumentedWorkloadProgram(owner, scope, binding)
            val caught = try {
                if (bootstrap) program.executeBootstrap(scope.runId, scope.fixtureId, scope.graph.nonce, UUID.randomUUID().toString()) { error("No chunks") }
                else program.executeWork(scope.runId, scope.fixtureId, scope.graph.nonce, UUID.randomUUID().toString()) { error("No chunks") }
                null
            }
                catch (failure: Throwable) { failure }
            assertSame(original, caught); assertTrue(program.lastClaimSucceeded); assertEquals(true, program.witness()?.ownerCallInvoked)
            assertEquals(4, reads.get()); assertEquals(0, f.binds.get())
        }
    }
    @Test fun configuredPreInvocationRefusalCanCleanOnlyItsOwnBuffersAndDoesNotCloseOwner() = runBlocking {
        Fixture().use { f ->
            f.ready(); val reads = AtomicInteger(); val owner = f.owner(core = { reads.incrementAndGet() != 3 })
            val selected = owner.selectRuntimeCaptureTemplate() as OrionCortexAttemptConfiguration.Ready
            val scope = scope(owner); val program = OrionCortexInstrumentedWorkloadProgram(owner, scope,
                checkNotNull(owner.instrumentedConfigurationBinding(selected.template)))
            val attempt = UUID.randomUUID().toString()
            assertNull(program.executeWork(scope.runId, scope.fixtureId, scope.graph.nonce, attempt) { error("No chunks") })
            assertEquals(false, program.witness()?.ownerCallInvoked)
            val encoded = program.export(context()); assertNull(encoded.source)
            assertEquals(OrionInstrumentedExecutionState.IDLE, program.finishManual(encoded))
            assertEquals(OrionInstrumentedSettlement.NO_OWNER_CALL, program.settlement)
            assertFalse(program.cleanup(scope.runId, scope.fixtureId, UUID.randomUUID().toString()))
            assertTrue(program.cleanup(scope.runId, scope.fixtureId, scope.graph.nonce)); assertTrue(scope.closed())
            assertNull(owner.preResourceReadiness().unavailable)
            assertEquals(0, f.binds.get())
        }
    }
    @Test fun configuredInvocationReturnsIdenticalRealOwnerRefusalAndDoesNotTurnSelectionIntoAdmission() = runBlocking {
        Fixture().use { f ->
            f.ready(); val reads = AtomicInteger(); val owner = f.owner(core = { reads.incrementAndGet() != 4 })
            val selected = owner.selectRuntimeCaptureTemplate() as OrionCortexAttemptConfiguration.Ready
            val scope = scope(owner); val program = OrionCortexInstrumentedWorkloadProgram(owner, scope,
                checkNotNull(owner.instrumentedConfigurationBinding(selected.template)))
            val original = checkNotNull(program.executeWork(scope.runId, scope.fixtureId, scope.graph.nonce,
                UUID.randomUUID().toString()) { error("No chunks from refusal") })
            assertSame(original, program.originalResult)
            assertEquals(OrionCortexUnavailable.CORE_NOT_READY, (original as OrionCortexRunResult.Unavailable).reason)
            assertEquals(true, program.witness()?.ownerCallInvoked); assertEquals(4, reads.get()); assertEquals(0, f.binds.get())
            // A real Owner return without original unload proof cannot turn into NO_OWNER_CALL or a settled bootstrap.
            assertEquals(OrionInstrumentedExecutionState.UNCERTAIN, program.finishManual(program.export(context())))
            assertEquals(OrionInstrumentedSettlement.UNPROVEN, program.settlement)
            assertFalse(program.cleanup(scope.runId, scope.fixtureId, scope.graph.nonce))
        }
    }
    companion object { private val HASH = "a".repeat(64) }
}

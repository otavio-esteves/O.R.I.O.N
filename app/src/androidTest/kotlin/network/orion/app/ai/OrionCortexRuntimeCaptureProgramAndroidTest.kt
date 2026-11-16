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
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** EMULATOR mechanics: real Owner/Room, metadata-only synthetic selection, no executable model/native admission. */
@RunWith(AndroidJUnit4::class)
class OrionCortexRuntimeCaptureProgramAndroidTest {
    private class Clock : OrionClock {
        val reads = AtomicInteger()
        override fun monotonicNow() = MonotonicInstant(BootSessionId(1), 1000).also { reads.incrementAndGet() }
        override fun wallNow() = Instant.EPOCH
    }
    private class Fixture : AutoCloseable {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString(); val name = "orion_runtime_program_$id.db"
        val root = File(app.noBackupFilesDir, "orion_runtime_program_$id").also { check(it.mkdirs()) }
        val inventory = AtomicInteger(); val binds = AtomicInteger(); val clock = Clock()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageManager(): PackageManager { inventory.incrementAndGet(); return super.getPackageManager() }
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean { binds.incrementAndGet(); error("No native bind") }
        }
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
        val importer = OrionModelImportCoordinator.open(context, db, clock, 4096, 0)
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 1000), "SYNTHETIC_NOT_QUALIFIED", 1, 128, 8, 0,
            10000, 20000, 100, 20, 2)
        val owners = mutableListOf<OrionCortexRuntimeOwner>()
        fun owner(plans: List<OrionInferenceResourcePlan> = listOf(plan), config: OrionRuntimeCaptureObservationConfiguration? = OrionRuntimeCaptureObservationConfiguration(10, 1),
            core: () -> Boolean = { true }): OrionCortexRuntimeOwner = OrionCortexRuntimeOwner(context, importer, db, clock,
                plans, OrionCortexResourceTiming(1000, 1000), coreReady = core,
                singleAttemptTemplate = OrionCortexAttemptTemplate(HASH, 128, 1, 8, 1000, 0.1,
                    OrionInferencePriority.INTERACTIVE, "SECRET_METADATA_FIXTURE", ""), runtimeCaptureObservation = config).also { owners.add(it) }
        fun ready() {
            db.models().registerImported(ModelRegistryEntity(HASH, "SYNTHETIC_NOT_QUALIFIED", "verified/$HASH.gguf", HASH,
                1000, null, null, null, "IMPORTED", 0, null))
            check(db.models().beginValidation(HASH, 0) == 1)
            check(db.models().completeValidation(HASH, 1, HASH, 1000, "fixture", "fixture", 128, 0) == 1)
        }
        override fun close() { owners.forEach { it.close() }; db.close(); app.deleteDatabase(name); root.deleteRecursively(); assertEquals(0, binds.get()) }
    }
    @Test fun realEmptyPlansRefuseBeforeClosedDbClockCoreInventoryBindingOrSampler() = runBlocking<Unit> {
        Fixture().use { f ->
            val program = OrionCortexRuntimeCaptureProgram(f.owner(plans = emptyList(), config = null, core = { error("Lazy plans gate") }))
            f.db.close()
            val refusal = program.prepare() as OrionCortexRuntimeCaptureProgram.Selection.Refusal
            assertEquals(false, refusal.witness.ownerCallInvoked); assertTrue(refusal.witness.noLoadRefusal)
            assertEquals(OrionRuntimeExportFailure.NO_QUALIFIED_PROFILE, refusal.witness.failure)
            assertEquals(0, f.clock.reads.get()); assertEquals(0, f.inventory.get()); assertEquals(0, f.binds.get())
            val repeated = program.prepare() as OrionCortexRuntimeCaptureProgram.Selection.Refusal
            assertNull(repeated.witness.ownerCallInvoked); assertFalse(repeated.witness.noLoadRefusal)
        }
    }
    @Test fun absentExplicitObservationConfigRefusesBeforeRegistryAndOldObserverIsNotRequired() = runBlocking<Unit> {
        Fixture().use { f ->
            val owner = f.owner(config = null); f.db.close()
            val refusal = OrionCortexRuntimeCaptureProgram(owner).prepare() as OrionCortexRuntimeCaptureProgram.Selection.Refusal
            assertEquals(OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE, refusal.witness.failure)
            assertEquals(false, refusal.witness.ownerCallInvoked); assertEquals(0, f.clock.reads.get()); assertEquals(0, f.inventory.get())
        }
    }
    @Test fun issuedCoreErrorKeepsExactThrowableAndTrueBoundaryWithoutRetryOrNativeWork() = runBlocking<Unit> {
        Fixture().use { f ->
            f.ready(); val calls = AtomicInteger(); val failure = AssertionError("SECRET_ORIGINAL_CORE_ERROR")
            val owner = f.owner(core = { if (calls.incrementAndGet() == 3) throw failure else true })
            val program = OrionCortexRuntimeCaptureProgram(owner)
            val candidate = (program.prepare() as OrionCortexRuntimeCaptureProgram.Selection.Selected).candidate
            val actual = try { program.execute(candidate) { error("No chunks") }; null } catch (thrown: Throwable) { thrown }
            assertSame(failure, actual); assertEquals(true, program.witness(candidate).ownerCallInvoked)
            assertFalse(program.witness(candidate).noLoadRefusal); assertEquals(3, calls.get())
            val encoded = OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE,
                witness = program.witness(candidate))
            assertFalse(encoded.status.contains("SECRET_ORIGINAL_CORE_ERROR"))
            assertTrue(encoded.status.contains("\"ownerCallInvoked\":true"))
            assertNull(program.execute(candidate) { error("No replay") }); assertEquals(3, calls.get())
            val foreign = OrionCortexRuntimeCaptureProgram(f.owner())
            assertNull(foreign.execute(candidate) { error("No foreign work") }); assertNull(foreign.witness(candidate).ownerCallInvoked)
            assertEquals(0, f.inventory.get()); assertEquals(0, f.binds.get())
        }
    }
    companion object { private val HASH = "a".repeat(64) }
}

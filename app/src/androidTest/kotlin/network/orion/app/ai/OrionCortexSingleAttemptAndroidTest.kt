package network.orion.app.ai

import android.content.Context
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
import kotlinx.coroutines.*
import network.orion.ai.api.OrionInferencePriority
import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Owner API/gates, synthetic metadata/clock. No executable model or heavy qualification. */
@RunWith(AndroidJUnit4::class)
class OrionCortexSingleAttemptAndroidTest {
    private class Clock : OrionClock {
        val reads = AtomicInteger()
        var failure: Throwable? = null
        var boot: BootSessionId? = BootSessionId(1)
        override fun monotonicNow(): MonotonicInstant {
            reads.incrementAndGet(); failure?.let { throw it }
            return MonotonicInstant(boot, 1000)
        }
        override fun wallNow() = Instant.EPOCH
    }

    private class Fixture : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val root = File(app.noBackupFilesDir, "orion_single_attempt_$id").also { check(it.mkdirs()) }
        private val name = "orion_single_attempt_$id.db"
        val clock = Clock()
        val inventory = AtomicInteger(); val binds = AtomicInteger()
        private val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageManager(): PackageManager {
                inventory.incrementAndGet(); throw AssertionError("Mechanical test never reaches process inventory")
            }
            override fun bindService(intent: Intent, owner: ServiceConnection, flags: Int): Boolean {
                binds.incrementAndGet(); throw AssertionError("Mechanical test never binds Cortex")
            }
        }
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
        private val importer = OrionModelImportCoordinator.open(context, db, clock, 1024, 0)
        val observations = OrionCortexCycleObserver(1)
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 1000), "MECHANICAL_NOT_QUALIFIED",
            2, 128, 8, 0, 10_000, 20_000, 100, 20, 2)
        private val owners = mutableListOf<OrionCortexRuntimeOwner>()
        fun template(hash: String = HASH, context: Int = 128, duration: Long = 1000) =
            OrionCortexAttemptTemplate(hash, context, 1, 8, duration, 0.0, OrionInferencePriority.INTERACTIVE,
                "SECRET_FIXED_TEST_WORKLOAD", "")
        fun owner(plans: List<OrionInferenceResourcePlan> = listOf(plan),
            timing: OrionCortexResourceTiming? = OrionCortexResourceTiming(1000, 1000),
            observer: OrionCortexCycleObserver? = observations, template: OrionCortexAttemptTemplate? = template(),
            core: () -> Boolean = { true }) = OrionCortexRuntimeOwner(context, importer, db, clock, plans, timing,
                coreReady = core, cycleObservations = observer, singleAttemptTemplate = template).also { owners.add(it) }
        fun row(status: String = "READY", context: Int = 128) {
            // Metadata-only selection control. No artifact exists and no admission may be inferred.
            db.models().registerImported(ModelRegistryEntity(HASH, "MECHANICAL_NOT_QUALIFIED", "verified/$HASH.gguf", HASH,
                1000, null, null, null, "IMPORTED", 0, null))
            if (status != "IMPORTED") {
                check(db.models().beginValidation(HASH, 0) == 1)
                check(db.models().completeValidation(HASH, 1, HASH, 1000, "fixture", "fixture", context, 0) == 1)
                if (status != "READY") check(db.models().invalidate(HASH, 2, status == "QUARANTINED") == 1)
            }
        }
        override fun close() {
            owners.forEach { it.close() }; db.close(); app.deleteDatabase(name); root.deleteRecursively()
            assertEquals(0, inventory.get()); assertEquals(0, binds.get())
        }
    }

    @Test fun emptyConfigurationRefusesBeforeClosedDatabaseRequestClockCoreInventoryOrBind() = runBlocking<Unit> {
        Fixture().use { f ->
            f.db.close()
            val driver = OrionCortexSingleAttemptDriver(f.owner(plans = emptyList(), template = null,
                core = { error("Plans gate is lazy") }))
            val refused = driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Refused
            assertEquals(OrionCortexAttemptRefusal.NO_QUALIFIED_PROFILE, refused.reason)
            assertEquals(0, f.clock.reads.get())
            assertEquals(OrionCortexAttemptRefusal.ALREADY_CONSUMED,
                (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Refused).reason)
        }
    }

    @Test fun missingInvalidForeignTemplateObserverAndEarlyGatesDoNotReadRegistry() = runBlocking<Unit> {
        Fixture().use { f ->
            f.db.close()
            val closed = f.owner().also { it.close() }
            val cases = listOf(closed to OrionCortexAttemptRefusal.CLOSED,
                f.owner(timing = null, core = { error("Timing gate is lazy") }) to OrionCortexAttemptRefusal.RESOURCE_CONFIGURATION_UNAVAILABLE,
                f.owner(core = { false }) to OrionCortexAttemptRefusal.CORE_NOT_READY,
                f.owner(observer = null) to OrionCortexAttemptRefusal.OBSERVER_UNAVAILABLE,
                f.owner(template = null) to OrionCortexAttemptRefusal.TEMPLATE_UNAVAILABLE,
                f.owner(template = f.template(context = 31)) to OrionCortexAttemptRefusal.TEMPLATE_INVALID,
                f.owner(template = f.template(hash = "b".repeat(64))) to OrionCortexAttemptRefusal.PROFILE_MISMATCH)
            for ((owner, reason) in cases) assertEquals(reason,
                (OrionCortexSingleAttemptDriver(owner).prepare() as OrionCortexSingleAttemptDriver.Selection.Refused).reason)
            assertEquals(0, f.clock.reads.get())
        }
    }

    @Test fun selectionRequiresExistingReadyAndExactProfileWithoutImportOrRecovery() = runBlocking<Unit> {
        for (status in listOf("ABSENT", "IMPORTED", "INVALID", "QUARANTINED", "READY")) Fixture().use { f ->
            if (status != "ABSENT") f.row(status, context = if (status == "READY") 64 else 128)
            val selected = OrionCortexSingleAttemptDriver(f.owner()).prepare() as OrionCortexSingleAttemptDriver.Selection.Refused
            assertEquals(if (status == "READY") OrionCortexAttemptRefusal.PROFILE_MISMATCH else OrionCortexAttemptRefusal.MODEL_NOT_READY,
                selected.reason)
            assertEquals(if (status == "ABSENT") null else status, f.db.models().find(HASH)?.status)
            assertEquals(0, f.clock.reads.get())
        }
    }

    @Test fun foreignOwnerAndForeignDriverRefuseBeforeCasAndActualOwnerRunsOnlyOnce() = runBlocking<Unit> {
        Fixture().use { f ->
            f.row()
            val coreReads = AtomicInteger()
            // prepare + execute precheck are true; original run itself observes false Core.
            val owner = f.owner(core = { coreReads.incrementAndGet() <= 2 })
            val driver = OrionCortexSingleAttemptDriver(owner)
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            assertNull(driver.readPartial(candidate)); assertFalse(driver.release(candidate))
            for (foreign in listOf(OrionCortexSingleAttemptDriver(owner), OrionCortexSingleAttemptDriver(f.owner()))) {
                assertEquals(OrionCortexAttemptRefusal.OWNER_UNAVAILABLE,
                    (foreign.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted).reason)
                assertNull(foreign.readPartial(candidate)); assertFalse(foreign.release(candidate))
            }
            assertEquals(0, f.clock.reads.get())
            val result = driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.Invoked
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CORE_NOT_READY), result.originalResult)
            assertEquals(3, coreReads.get()); assertEquals(1, f.clock.reads.get())
            assertEquals(OrionCortexAttemptRefusal.ALREADY_CONSUMED,
                (driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted).reason)
            val partial = checkNotNull(driver.readPartial(candidate)).json
            assertTrue(partial.contains("NO_ATTEMPT")); assertTrue(partial.contains("NOT_QUALIFIED"))
            assertFalse(partial.contains("SECRET")); assertFalse(candidate.toString().contains(HASH))
            val token = candidate.javaClass.getDeclaredField("token").apply { isAccessible = true }.get(candidate) as OrionCortexCycleToken
            assertSame(result.originalResult, owner.readCycleObservation(token)?.originalResult)
            assertTrue(driver.release(candidate)); assertNull(driver.readPartial(candidate))
        }
    }

    @Test fun concurrentExecuteCannotInvokeOriginalOwnerTwiceOrReadDuringRun() = runBlocking<Unit> {
        Fixture().use { f ->
            f.row()
            val coreReads = AtomicInteger()
            val original = AssertionError("Original owner predicate failure")
            // Synchronous normative predicate waits only in this controlled test; driver adds no wait.
            val started = java.util.concurrent.CountDownLatch(1); val leave = java.util.concurrent.CountDownLatch(1)
            val owner = f.owner(core = {
                if (coreReads.incrementAndGet() == 3) {
                    started.countDown(); check(leave.await(3, java.util.concurrent.TimeUnit.SECONDS)); throw original
                }
                true
            })
            val driver = OrionCortexSingleAttemptDriver(owner)
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            val first = async(Dispatchers.Default) {
                try { driver.execute(candidate) { error("No callback") }; null } catch (failure: Throwable) { failure }
            }
            try {
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertNull(driver.readPartial(candidate)); assertFalse(driver.release(candidate))
                val second = driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted
                assertEquals(OrionCortexAttemptRefusal.ALREADY_CONSUMED, second.reason)
            } finally { leave.countDown() }
            assertSame(original, first.await()); assertEquals(3, coreReads.get()); assertEquals(1, f.clock.reads.get())
            val reads = f.clock.reads.get()
            val partial = checkNotNull(driver.readPartial(candidate)).json
            assertTrue(partial.contains("OTHER")); assertFalse(partial.contains("Original owner predicate"))
            assertEquals(reads, f.clock.reads.get()); assertTrue(driver.release(candidate))
        }
    }

    @Test fun originalCancellationObjectEscapesAndOnlyLaterManualReadProducesPartial() = runBlocking<Unit> {
        Fixture().use { f ->
            f.row(); val coreReads = AtomicInteger(); val original = CancellationException("SECRET_FAILURE")
            val driver = OrionCortexSingleAttemptDriver(f.owner(core = { if (coreReads.incrementAndGet() == 3) throw original; true }))
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            val seen = try { driver.execute(candidate) { error("No callback") }; null } catch (failure: Throwable) { failure }
            assertSame(original, seen)
            val reads = f.clock.reads.get()
            val partial = checkNotNull(driver.readPartial(candidate)).json
            assertTrue(partial.contains("CANCELLED")); assertFalse(partial.contains("SECRET"))
            assertEquals(reads, f.clock.reads.get()); assertTrue(driver.release(candidate))
        }
    }

    @Test fun failedClockAndUnavailableBootDiscardOnlyUninvokedReservationAndNeverForgeCycle() = runBlocking<Unit> {
        for (throwClock in listOf(true, false)) Fixture().use { f ->
            f.row(); val owner = f.owner(); val driver = OrionCortexSingleAttemptDriver(owner)
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            if (throwClock) f.clock.failure = AssertionError("SECRET_CLOCK_FAILURE") else f.clock.boot = null
            val result = driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted
            assertEquals(OrionCortexAttemptRefusal.SELECTION_UNAVAILABLE, result.reason)
            assertNull(driver.readPartial(candidate)); assertFalse(driver.release(candidate))
            val next = checkNotNull(owner.beginCycleObservation()) // Capacity one recovered without a fake completion.
            assertTrue(owner.discardSingleAttemptReservation(next))
            assertEquals(1, f.clock.reads.get())
        }
    }

    @Test fun closedOrCoreChangedAfterSelectionNeverCreatesRequestOrRunReceipt() = runBlocking<Unit> {
        for (close in listOf(true, false)) Fixture().use { f ->
            f.row(); var ready = true
            val owner = f.owner(core = { ready }); val driver = OrionCortexSingleAttemptDriver(owner)
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            if (close) owner.close() else ready = false
            val result = driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted
            assertEquals(if (close) OrionCortexAttemptRefusal.CLOSED else OrionCortexAttemptRefusal.CORE_NOT_READY, result.reason)
            assertEquals(0, f.clock.reads.get()); assertNull(driver.readPartial(candidate))
            assertFalse(driver.release(candidate))
        }
    }

    @Test fun fullObserverRefusesBeforeRequestClockAndDoesNotDiscardAnotherReservation() = runBlocking<Unit> {
        Fixture().use { f ->
            f.row(); val owner = f.owner(); val driver = OrionCortexSingleAttemptDriver(owner)
            val candidate = (driver.prepare() as OrionCortexSingleAttemptDriver.Selection.Selected).candidate
            val other = checkNotNull(owner.beginCycleObservation())
            val result = driver.execute(candidate) { error("No callback") } as OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted
            assertEquals(OrionCortexAttemptRefusal.OBSERVER_UNAVAILABLE, result.reason)
            assertEquals(0, f.clock.reads.get()); assertNull(driver.readPartial(candidate))
            assertNull(owner.beginCycleObservation()); assertTrue(owner.discardSingleAttemptReservation(other))
            val available = checkNotNull(owner.beginCycleObservation())
            assertTrue(owner.discardSingleAttemptReservation(available))
        }
    }

    companion object { private val HASH = "a".repeat(64) }
}

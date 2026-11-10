package network.orion.app.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No usable model or resource calibration: proves gates precede storage and binding. */
@RunWith(AndroidJUnit4::class)
class OrionCortexOwnerDefaultTest {
    @Test fun defaultProfileAndClosedOwnerNeverAccessDatabaseInventoryOrBinder() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock)
        val request = request(clock)
        runBlocking {
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE),
                owner.run(HASH, request, 128, 1) { error("Unexpected inference") })
            owner.close()
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CLOSED),
                owner.run(HASH, request, 128, 1) { error("Unexpected inference") })
        }
        assertEquals(0, bindings.get())
    }

    @Test fun qualifiedFixtureStillRequiresCoreReadinessBeforeInventoryStorageOrBind() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        // Mechanical gate fixture only; no inference can pass the default-false Core gate.
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "OWNER_GATE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), OrionCortexResourceTiming(1000, 1000))
        try { runBlocking {
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CORE_NOT_READY),
                owner.run(HASH, request(clock), 128, 1) { error("Unexpected inference") })
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    private fun request(clock: AndroidOrionClock) = OrionInferenceRequest(UUID.randomUUID().toString(), 1, HASH,
        "fixture", "", 8, 0.0, OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(10_000),
        UUID.randomUUID().toString())

    private fun fixture(block: (Context, OrionDatabase, OrionModelImportCoordinator, AtomicInteger) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "orion_owner_gate_${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val bindings = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageManager(): PackageManager = throw AssertionError("Inventory accessed before readiness gate")
            override fun bindService(intent: Intent, owner: ServiceConnection, flags: Int): Boolean {
                bindings.incrementAndGet(); throw AssertionError("Unexpected Cortex bind")
            }
        }
        val name = "orion_owner_gate_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
        val importer = OrionModelImportCoordinator.open(context, db, AndroidOrionClock(context.contentResolver), 1024, 0)
        db.close() // Any accidental query would fail instead of silently creating a database.
        try { block(context, db, importer, bindings) }
        finally { app.deleteDatabase(name); root.deleteRecursively() }
    }

    companion object { private val HASH = "a".repeat(64) }
}

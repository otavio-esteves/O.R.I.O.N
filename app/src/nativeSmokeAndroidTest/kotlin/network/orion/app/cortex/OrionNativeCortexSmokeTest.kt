package network.orion.app.cortex

import android.content.Context
import android.content.ContextWrapper
import android.app.ActivityManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexClient
import network.orion.app.OrionApplication
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.modelregistry.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate opt-in test APK. Synthetic policy values exercise mechanics, never qualify hardware. */
@RunWith(AndroidJUnit4::class)
class OrionNativeCortexSmokeTest {
    @Test fun pinnedFixtureImportsLoadsStreamsAndUnloadsThroughRealAiBinder() {
        val app = ApplicationProvider.getApplicationContext<OrionApplication>()
        val root = File(app.cacheDir, "orion_native_smoke_${UUID.randomUUID()}")
        check(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val db = Room.inMemoryDatabaseBuilder(app, OrionDatabase::class.java).build()
        val clock = AndroidOrionClock(app.contentResolver)
        val identity = "sha256:$HASH"
        val model = InferenceModelProfile(identity, 32L * 1024 * 1024)
        val plan = OrionInferenceResourcePlan(model, "TEST_FIXTURE_ONLY", 1, 128, 8, 20_000,
            128L * 1024 * 1024, 256L * 1024 * 1024, 32L * 1024 * 1024, 20, 2)
        val governor = OrionMinimumResourceGovernor(clock, {
            OrionResourceObservation(clock.monotonicNow(), model, 80, 0, true,
                OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
                16L * 1024 * 1024, 32L * 1024 * 1024, 256L * 1024 * 1024, emptyList(), 1, 128, 8)
        }, listOf(plan), 1_000, 60_000)
        val client = OrionCortexClient(app, clock, OrionCortexAdmissionGuard { id, _ ->
            val decision = governor.evaluate()
            if (id != identity || !decision.allowed) null else OrionCortexApproval(id, 128, 1, 8,
                checkNotNull(decision.sampledAt), 1_000, keepAliveMs = 20_000)
        })
        try {
            val registry = OrionModelRegistry.open(isolated, db, clock)
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val size = assets.open("orion_stories260K.gguf").use { stream ->
                var bytes = 0L; val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; bytes += n }
                bytes
            }
            val imported = registry.importModel(assets.open("orion_stories260K.gguf"), size, HASH)
            assertTrue(imported is OrionModelImportResult.Ready)
            val opened = registry.openReady(identity) as? OrionModelOpenResult.Ready ?: error("Fixture validation failed.")
            val loadDone = CountDownLatch(1)
            val loads = mutableListOf<OrionCortexChunk>()
            opened.descriptor.use { fd ->
                assertNotNull(client.loadModel(identity, fd, size, timeoutMillis = 60_000) { chunk ->
                    synchronized(loads) { loads += chunk }
                    if (chunk.terminal != null) loadDone.countDown()
                })
            }
            assertTrue("Native load deadline", loadDone.await(65, TimeUnit.SECONDS))
            assertEquals(OrionCortexRequestStatus.SUCCEEDED, synchronized(loads) { loads.single().terminal })
            val inferDone = CountDownLatch(1)
            val chunks = mutableListOf<OrionCortexChunk>()
            assertNotNull(client.startInference(identity, "Once upon a time, there was", 8, timeoutMillis = 60_000) { chunk ->
                synchronized(chunks) { chunks += chunk }
                if (chunk.terminal != null) inferDone.countDown()
            })
            assertTrue("Native inference deadline", inferDone.await(65, TimeUnit.SECONDS))
            val observed = synchronized(chunks) { chunks.toList() }
            assertEquals(1, observed.count { it.terminal != null })
            assertEquals(OrionCortexRequestStatus.SUCCEEDED, observed.last().terminal)
            assertTrue(observed.any { !it.textDelta.isNullOrEmpty() })
            assertEquals(observed.indices.map(Int::toLong), observed.map { it.sequence })
            val cancelDone = CountDownLatch(1)
            val cancelled = mutableListOf<OrionCortexChunk>()
            assertNotNull(client.startInference(identity, "Once upon a time, there was", 8, timeoutMillis = 60_000) { chunk ->
                synchronized(cancelled) { cancelled += chunk }
                if (chunk.textDelta != null) client.cancelInference(chunk.requestId)
                if (chunk.terminal != null) cancelDone.countDown()
            })
            assertTrue("Real request cancellation deadline", cancelDone.await(10, TimeUnit.SECONDS))
            assertEquals(OrionCortexRequestStatus.CANCELLED, synchronized(cancelled) { cancelled.last().terminal })
            assertEquals(1, synchronized(cancelled) { cancelled.count { it.terminal != null } })
            awaitStatus(client, clock, OrionCortexHealth.READY)
            val timeoutDone = CountDownLatch(1)
            val timedOut = mutableListOf<OrionCortexChunk>()
            assertNotNull(client.startInference(identity, "Once upon a time, there was", 8, timeoutMillis = 1) { chunk ->
                synchronized(timedOut) { timedOut += chunk }
                if (chunk.terminal != null) timeoutDone.countDown()
            })
            assertTrue("Real request timeout deadline", timeoutDone.await(10, TimeUnit.SECONDS))
            assertEquals(OrionCortexRequestStatus.TIMED_OUT, synchronized(timedOut) { timedOut.last().terminal })
            assertEquals(1, synchronized(timedOut) { timedOut.count { it.terminal != null } })
            awaitStatus(client, clock, OrionCortexHealth.READY)
            assertTrue(client.unloadModel())
            awaitStatus(client, clock, OrionCortexHealth.UNLOADED)
            // Freeze only our disposable AI process before dispatch, then kill it
            // with an admitted request awaiting Binder. This makes the death
            // boundary deterministic even for a very fast tiny fixture model.
            val aiPid = app.getSystemService(ActivityManager::class.java).runningAppProcesses
                .single { it.processName == app.packageName + ":ai" }.pid
            val mainPid = Process.myPid()
            val deathDone = CountDownLatch(1)
            val deathChunks = mutableListOf<OrionCortexChunk>()
            Os.kill(aiPid, OsConstants.SIGSTOP)
            try {
                // Reload remains queued behind a stopped transport; only the
                // explicit process-death signal may terminate this request.
                val readyAgain = registry.openReady(identity) as OrionModelOpenResult.Ready
                readyAgain.descriptor.use { fd ->
                    assertNotNull(client.loadModel(identity, fd, size, timeoutMillis = 60_000) { chunk ->
                        synchronized(deathChunks) { deathChunks += chunk }
                        if (chunk.terminal != null) deathDone.countDown()
                    })
                }
            } finally { Process.killProcess(aiPid) }
            assertTrue("Binder death deadline", deathDone.await(10, TimeUnit.SECONDS))
            assertEquals(OrionCortexRequestStatus.PROCESS_DIED, synchronized(deathChunks) { deathChunks.single().terminal })
            assertEquals(mainPid, Process.myPid())
            assertNotNull(app.mainRuntime)
            // The stopped old load transaction can still unwind here. Its late
            // RemoteException must not disconnect this new explicit bind epoch.
            val rebindDone = CountDownLatch(1)
            val rebindChunks = mutableListOf<OrionCortexChunk>()
            val revalidated = registry.openReady(identity) as OrionModelOpenResult.Ready
            revalidated.descriptor.use { fd ->
                assertNotNull(client.loadModel(identity, fd, size, timeoutMillis = 60_000) { chunk ->
                    synchronized(rebindChunks) { rebindChunks += chunk }
                    if (chunk.terminal != null) rebindDone.countDown()
                })
            }
            assertTrue("Explicit rebind deadline", rebindDone.await(65, TimeUnit.SECONDS))
            assertEquals(OrionCortexRequestStatus.SUCCEEDED, synchronized(rebindChunks) { rebindChunks.single().terminal })
            awaitStatus(client, clock, OrionCortexHealth.READY)
            assertTrue(client.unloadModel())
            awaitStatus(client, clock, OrionCortexHealth.UNLOADED)
            // Controlled test injection never modifies the production set of qualified plans.
            assertEquals(0, app.mainRuntime?.cortexAccess?.qualifiedModelPlanCount ?: 0)
        } finally {
            client.close(); db.close(); check(root.deleteRecursively())
        }
    }
    private fun awaitStatus(client: OrionCortexClient, clock: AndroidOrionClock, expected: OrionCortexHealth) {
        val start = clock.monotonicNow()
        while (client.getStatus() != expected) {
            check((clock.monotonicNow().elapsedSince(start)?.toMillis() ?: Long.MAX_VALUE) < 10_000) {
                "Native fixture did not reach $expected."
            }
            Thread.sleep(10)
        }
    }
    companion object { private const val HASH = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d" }
}

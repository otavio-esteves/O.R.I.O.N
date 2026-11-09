package network.orion.app.ai

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Bundle
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.*
import network.orion.core.model.Sensitivity
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicDeadline
import network.orion.data.database.*
import network.orion.data.repository.models.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator integration only. Fixture observations never authorize or qualify product inference. */
@RunWith(AndroidJUnit4::class)
class OrionCortexRuntimeTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val clock = AndroidOrionClock(app.contentResolver)
    private val stageStart by lazy { clock.monotonicNow() }
    private val stageCount = AtomicInteger()

    // Whole-test budgets include private copy/hash/import and cleanup. Each request still expires at 120s.
    @Test(timeout = 240_000) fun importedRealModelStreamsThroughPrivateAiProcessAndUnloads() = fixture { db, importer, row ->
        runBlocking(Dispatchers.IO) {
            val decisions = AtomicInteger()
            val availability = CopyOnWriteArrayList<Boolean>()
            client(decisions, availability).use { client ->
                val chunks = mutableListOf<OrionInferenceChunk>()
                val firstDelta = AtomicBoolean()
                val request = request("The capital of France is")
                stage("OPEN_FD_BEGIN")
                val result = checkNotNull(importer.openReadOnly(row.modelId)).use { descriptor ->
                    stage("OPEN_FD_END")
                    stage("RUN_INFERENCE_BEGIN")
                    withTimeout(120_000) { client.runInference(row, descriptor, request, 512, 2) {
                        if (it.any { chunk -> !chunk.textDelta.isNullOrEmpty() } && firstDelta.compareAndSet(false, true))
                            stage("FIRST_DELTA")
                        chunks.addAll(it)
                    } }
                }
                stage("RUN_INFERENCE_END")
                assertEquals(OrionInferenceTerminal.SUCCEEDED, result.terminal)
                assertTrue(chunks.any { !it.textDelta.isNullOrEmpty() })
                assertEquals(chunks.indices.map(Int::toLong), chunks.map { it.sequence })
                assertEquals(1, chunks.count { it.isFinal })
                assertTrue("Fresh decisions are required before load and inference", decisions.get() >= 2)
                assertTrue(availability.contains(true))
                assertServiceUnloaded()
                stage("UNLOAD_CONFIRMED")
                db.verifyStructure()
                assertEquals(1, db.tasks().page(null, 10).size)
            }
        }
    }

    // Two independent request deadlines plus repeated private-file validation must fit the outer test budget.
    @Test(timeout = 360_000) fun actualAiDeathPreservesCoreAndReplayWaitsForExplicitNewRequest() = fixture { db, importer, row ->
        runBlocking(Dispatchers.IO) {
            val decisions = AtomicInteger()
            val availability = CopyOnWriteArrayList<Boolean>()
            client(decisions, availability).use { client ->
                val mainPid = Process.myPid()
                val killed = AtomicBoolean()
                val delivered = AtomicInteger()
                var killedPid = 0
                val original = request("Count from one to one hundred:")
                stage("OPEN_FD_BEGIN")
                val result = checkNotNull(importer.openReadOnly(row.modelId)).use { descriptor ->
                    stage("OPEN_FD_END")
                    stage("RUN_INFERENCE_BEGIN")
                    withTimeout(120_000) {
                        client.runInference(row, descriptor, original, 512, 2) { chunks ->
                            delivered.addAndGet(chunks.size)
                            if (chunks.any { !it.textDelta.isNullOrEmpty() } && killed.compareAndSet(false, true)) {
                                stage("FIRST_DELTA")
                                check(chunks.none { it.isFinal }) { "Inference ended before death checkpoint." }
                                val target = checkNotNull(aiProcess()) { "Actual :ai PID unavailable." }
                                check(target.pid != mainPid && target.uid == Process.myUid())
                                killedPid = target.pid
                                stage("KILL_BEGIN")
                                Process.killProcess(target.pid)
                                stage("KILL_SENT")
                            }
                        }
                    }
                }
                stage("RUN_INFERENCE_END")
                assertTrue("A real streamed chunk must precede actual SIGKILL", killed.get())
                assertTrue(killedPid > 0 && killedPid != mainPid)
                assertEquals(OrionInferenceTerminal.PROCESS_DIED, result.terminal)
                assertEquals(mainPid, Process.myPid())
                db.verifyStructure()
                assertEquals(1, db.tasks().page(null, 10).size)
                val originalTask = db.tasks().page(null, 10).single()
                assertEquals(TaskStatus.OPEN, originalTask.status)
                assertEquals(0L, originalTask.version)
                assertEquals(1, db.events().count())
                stage("CORE_PERSISTENCE_VERIFIED")

                val disappearance = clock.monotonicNow().deadlineAfter(3000)
                while (aiProcess() != null && disappearance.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
                    delay(10)
                assertNull("Dead :ai must not auto-relaunch from its retained binding", aiProcess())
                val quiet = clock.monotonicNow().deadlineAfter(250)
                while (quiet.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE) {
                    assertNull("No automatic rebind before explicit new work", aiProcess())
                    delay(10)
                }
                val bindsBeforeReplay = availability.count { it }
                val decisionsBeforeReplay = decisions.get()
                val deliveredBeforeReplay = delivered.get()
                stage("REPLAY_OPEN_FD_BEGIN")
                val replay = checkNotNull(importer.openReadOnly(row.modelId)).use { descriptor ->
                    stage("REPLAY_OPEN_FD_END")
                    stage("REPLAY_BEGIN")
                    client.runInference(row, descriptor, original, 512, 2) { error("Replay emitted output.") }
                }
                stage("REPLAY_END")
                assertEquals(OrionInferenceTerminal.PROCESS_DIED, replay.terminal)
                assertEquals(deliveredBeforeReplay, delivered.get())
                assertEquals(decisionsBeforeReplay, decisions.get())
                assertEquals(bindsBeforeReplay, availability.count { it })
                assertNull(aiProcess())

                val next = request("Hello, my name is")
                var nextChunks = 0
                stage("NEW_REQUEST_OPEN_FD_BEGIN")
                val nextResult = checkNotNull(importer.openReadOnly(row.modelId)).use { descriptor ->
                    stage("NEW_REQUEST_OPEN_FD_END")
                    stage("NEW_REQUEST_BEGIN")
                    withTimeout(120_000) { client.runInference(row, descriptor, next, 512, 2) {
                        nextChunks += it.count { chunk -> !chunk.textDelta.isNullOrEmpty() }
                    } }
                }
                stage("NEW_REQUEST_END")
                assertEquals(OrionInferenceTerminal.SUCCEEDED, nextResult.terminal)
                assertTrue(nextChunks > 0)
                assertTrue(availability.count { it } > bindsBeforeReplay)
                assertEquals(mainPid, Process.myPid())
                db.verifyStructure()
                assertEquals(originalTask.taskId, db.tasks().page(null, 10).single().taskId)
                assertServiceUnloaded()
                stage("UNLOAD_CONFIRMED")
            }
        }
    }

    /** Outer budget covers import/load. The service's independent 250ms inference deadline is tested directly. */
    @Test(timeout = 240_000) fun actualServiceDeadlineTerminatesInferenceOnceAndNativeUnwinds() = fixture { db, importer, row ->
        runBlocking(Dispatchers.IO) {
            val mainPid = Process.myPid()
            val plan = fixturePlan()
            val restartGuard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
            withCortexService { service ->
                val terminal = CountDownLatch(1)
                val chunks = CopyOnWriteArrayList<Bundle>()
                val callbackFailure = AtomicReference<Throwable>()
                val callback = object : IOrionInferenceCallback.Stub() {
                    override fun onChunk(chunk: Bundle) {
                        try {
                            OrionCortexWire.requireEnvelope(chunk, setOf("schemaVersion", "requestId", "sequence",
                                "delta", "isFinal", "finishReason", "errorCode"))
                            check(chunks.size < 64) { "Test callback capacity exceeded." }
                            chunks.add(Bundle(chunk))
                            if (OrionCortexWire.boolean(chunk, "isFinal")) terminal.countDown()
                        } catch (failure: Throwable) { callbackFailure.set(failure); terminal.countDown() }
                    }
                }
                try {
                    stage("TIMEOUT_OPEN_FD_BEGIN")
                    checkNotNull(importer.openReadOnly(row.modelId)).use { descriptor ->
                        stage("TIMEOUT_OPEN_FD_END")
                        check(restartGuard.evaluate(OrionAiExitHistory(clock.monotonicNow(), emptyList()))
                            .eligibleForResourceEvaluation)
                        check(fixtureDecision(plan, 512, 2, 1).allowed)
                        val loadId = UUID.randomUUID().toString()
                        val loadStart = clock.monotonicNow()
                        val loadDeadline = loadStart.deadlineAfter(120_000)
                        val load = Bundle().apply {
                            putInt("schemaVersion", 1); putString("requestId", loadId)
                            putString("modelId", row.modelId); putString("sha256", row.sha256)
                            putLong("sizeBytes", row.sizeBytes); putInt("contextTokens", 512); putInt("threads", 2)
                            putInt("bootCount", checkNotNull(loadStart.bootSessionId).bootCount)
                            putLong("startedElapsed", loadStart.elapsedRealtimeMillis); putLong("durationMillis", 120_000)
                        }
                        stage("TIMEOUT_LOAD_BEGIN")
                        assertEquals("ACCEPTED", service.loadModel(descriptor, load).getString("status"))
                        var loadState: String?
                        do {
                            loadState = service.getRequestStatus(loadId).getString("state")
                            if (loadState != "RUNNING") break
                            delay(10)
                        } while (loadDeadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
                        assertEquals("SUCCEEDED", loadState)
                        waitForServiceState(service, "LOADED", clock.monotonicNow().deadlineAfter(5000))
                        stage("TIMEOUT_LOAD_END")

                        val bytes = "number ".repeat(100).toByteArray(Charsets.UTF_8)
                        SharedMemory.create("orion_timeout_fixture", bytes.size).use { memory ->
                            val mapping = memory.mapReadWrite()
                            try { mapping.put(bytes) } finally { SharedMemory.unmap(mapping); bytes.fill(0) }
                            check(memory.setProtect(OsConstants.PROT_READ))
                            check(restartGuard.evaluate(OrionAiExitHistory(clock.monotonicNow(), emptyList()))
                                .eligibleForResourceEvaluation)
                            check(fixtureDecision(plan, 512, 2, 16).allowed)
                            val inferenceId = UUID.randomUUID().toString()
                            stage("TIMEOUT_INFERENCE_BEGIN")
                            val start = clock.monotonicNow()
                            val specification = Bundle().apply {
                                putInt("schemaVersion", 1); putString("requestId", inferenceId); putString("modelId", row.modelId)
                                putLong("inputSize", memory.size.toLong()); putInt("maxTokens", 16); putFloat("temperature", 0f)
                                putInt("bootCount", checkNotNull(start.bootSessionId).bootCount)
                                putLong("startedElapsed", start.elapsedRealtimeMillis); putLong("durationMillis", 250)
                            }
                            assertEquals("ACCEPTED", service.startInference(memory, specification, callback).getString("status"))
                            // No status polling or explicit cancellation: the service watcher owns expiration.
                            assertTrue("Service must deliver its timeout terminal", terminal.await(10, TimeUnit.SECONDS))
                            callbackFailure.get()?.let { throw AssertionError("Invalid timeout callback", it) }
                            val final = chunks.filter { OrionCortexWire.boolean(it, "isFinal") }
                            assertEquals(1, final.size)
                            assertEquals("TIMED_OUT", final.single().getString("finishReason"))
                            assertEquals(inferenceId, final.single().getString("requestId"))
                            assertEquals("TIMED_OUT", service.getRequestStatus(inferenceId).getString("state"))
                            stage("TIMEOUT_TERMINAL_OBSERVED")

                            val unwindDeadline = clock.monotonicNow().deadlineAfter(10_000)
                            waitForServiceState(service, "LOADED", unwindDeadline)
                            stage("TIMEOUT_NATIVE_UNWOUND")
                            service.unloadModel()
                            waitForServiceState(service, "UNLOADED", unwindDeadline)
                            stage("TIMEOUT_UNLOAD_CONFIRMED")
                            // A late callback cannot become a second terminal after native cleanup.
                            assertEquals(1, chunks.count { OrionCortexWire.boolean(it, "isFinal") })
                            assertFalse(chunks.any { it.getString("finishReason") == "SUCCEEDED" })
                        }
                    }
                    assertEquals(mainPid, Process.myPid())
                    db.verifyStructure()
                    assertEquals(1, db.tasks().page(null, 10).size)
                    assertEquals(1, db.events().count())
                    stage("TIMEOUT_CORE_PRESERVED")
                } finally { service.unloadModel() }
            }
        }
    }

    private fun client(decisions: AtomicInteger, availability: MutableList<Boolean>): OrionCortexClient {
        val plan = fixturePlan()
        val guard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
        return OrionCortexClient(app, clock, resources = { id, context, threads, tokens ->
            check(id == MODEL_SHA256)
            decisions.incrementAndGet()
            fixtureDecision(plan, context, threads, tokens)
        }, restart = { guard.evaluate(OrionAiExitHistory(clock.monotonicNow(), emptyList())) },
            availabilityChanged = {
                availability.add(it)
                stage(if (it) "BINDER_AVAILABLE" else "BINDER_UNAVAILABLE")
            })
    }

    private fun fixturePlan() = OrionInferenceResourcePlan(
        InferenceModelProfile(MODEL_SHA256, 1024L * 1024 * 1024), "ANDROID_EMULATOR_SYNTHETIC_TEST_ONLY", 2, 512, 16, 0,
        2L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024, 512L * 1024 * 1024, 20, 2)

    private fun fixtureDecision(plan: OrionInferenceResourcePlan, context: Int, threads: Int, tokens: Int): OrionResourceDecision {
        stage("RESOURCE_DECISION")
        return OrionMinimumResourceGovernor(clock, {
            OrionResourceObservation(clock.monotonicNow(), plan.model, 100, 0, true,
                OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
                100L * 1024 * 1024, 100L * 1024 * 1024, 4L * 1024 * 1024 * 1024,
                emptyList(), threads, context, tokens)
        }, listOf(plan), 1000, 1000).evaluate()
    }

    private fun request(prompt: String) = OrionInferenceRequest(UUID.randomUUID().toString(), 1, MODEL_SHA256,
        // Short functional fixture: preserve streaming/death checkpoints without claiming performance qualification.
        prompt, "", 4, 0.0, OrionInferencePriority.INTERACTIVE,
        clock.monotonicNow().deadlineAfter(120_000), UUID.randomUUID().toString())

    private fun aiProcess(): ActivityManager.RunningAppProcessInfo? =
        app.getSystemService(ActivityManager::class.java).runningAppProcesses?.singleOrNull {
            it.processName == "${app.packageName}:ai" && it.uid == Process.myUid()
        }

    /** At most 64 technical observations per test; no paths, identifiers, prompts or generated text. */
    private fun stage(name: String) {
        val ordinal = stageCount.incrementAndGet()
        if (ordinal > 64) return
        val start = stageStart
        val elapsed = clock.monotonicNow().elapsedSince(start)?.toMillis() ?: -1L
        val aiPid = aiProcess()?.pid ?: -1
        val mainPid = Process.myPid()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("orionCortexStage", name)
            putInt("orionCortexStageOrdinal", ordinal)
            putLong("orionCortexElapsedMillis", elapsed)
            putInt("orionCortexMainPid", mainPid)
            putInt("orionCortexAiPid", aiPid)
            putString("stream", "[orion_cortex_stage] $name elapsedMs=$elapsed mainPid=$mainPid aiPid=$aiPid\n")
        })
    }

    private suspend fun assertServiceUnloaded() = withCortexService { service ->
        waitForServiceState(service, "UNLOADED", clock.monotonicNow().deadlineAfter(5000))
    }

    private suspend fun waitForServiceState(service: IOrionCortex, expected: String, deadline: MonotonicDeadline) {
        var state: String
        do {
            state = service.getStatus().getString("status") ?: error("Missing status.")
            if (state == expected) return
            delay(10)
        } while (deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
        assertEquals(expected, state)
    }

    private suspend fun <T> withCortexService(block: suspend (IOrionCortex) -> T): T {
        val connected = CountDownLatch(1)
        var remote: IOrionCortex? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IOrionCortex.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        val intent = Intent().setComponent(ComponentName(app.packageName, "network.orion.ai.service.OrionCortexService"))
        assertTrue(app.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            return block(checkNotNull(remote))
        } finally { app.unbindService(connection) }
    }

    private fun fixture(block: (OrionDatabase, OrionModelImportCoordinator, ModelRegistryEntity) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("orionModelPath")
        assumeTrue("Requires explicit private model fixture; absence is not inference evidence", path != null)
        check(args.getString("orionEnvironment") == "ANDROID_EMULATOR") {
            "Explicit model fixture requires declared emulator test environment."
        }
        stage("FIXTURE_BEGIN")
        val source = File(checkNotNull(path)).canonicalFile
        check(source.parentFile == app.filesDir.canonicalFile) { "Source fixture must be app-private." }
        assertEquals(491400032L, source.length())
        val id = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "orion_cortex_fixture_$id").also { check(it.mkdirs()) }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val name = "orion_cortex_fixture_$id.db"
        try {
            val db = Room.databaseBuilder(app, OrionDatabase::class.java, name)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                    }
                }).build()
            try {
                    // Synthetic DAO blob only, never rendered/decrypted or supplied to Cortex.
                    val taskId = UUID.randomUUID().toString()
                    val now = clock.wallNow().toEpochMilli()
                    db.tasks().createWithEvent(TaskEntity(taskId, byteArrayOf(1, 2, 3), Sensitivity.PERSONAL, now, now),
                        DomainEventOutboxEntity(UUID.randomUUID().toString(), taskId, 0, "TASK_CREATED", 1,
                            Sensitivity.PERSONAL, now, UUID.randomUUID().toString()))
                    val importer = OrionModelImportCoordinator.open(isolated, db, clock,
                        maximumModelBytes = 512L * 1024 * 1024, reserveBytes = 64L * 1024 * 1024)
                    stage("IMPORT_BEGIN")
                    val imported = importer.importModel(OrionExpectedModelFile(MODEL_SHA256, source.length()), source::inputStream)
                    stage("IMPORT_END")
                    check(imported is OrionModelImportResult.Ready) { "Pinned real model import failed: $imported" }
                    val row = checkNotNull(db.models().find(MODEL_SHA256))
                    assertEquals("READY", row.status)
                    block(db, importer, row)
            } finally { db.close() }
        } finally {
            stage("FIXTURE_CLEANUP_BEGIN")
            app.deleteDatabase(name); root.deleteRecursively()
            stage("FIXTURE_CLEANUP_END")
        }
    }

    companion object {
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    }
}

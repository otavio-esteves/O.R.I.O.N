package network.orion.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.*
import network.orion.ai.api.*
import network.orion.app.resources.OrionAndroidResourceSnapshot
import network.orion.core.resources.*
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Mechanical Client controls + real owner's manual reader. No owner heavy admission/native/model proof. */
@RunWith(AndroidJUnit4::class)
class OrionCortexCycleClientTest {
    private class Clock : OrionClock {
        @Volatile var mark = MonotonicInstant(BootSessionId(1), 1000)
        val reads = AtomicInteger()
        override fun monotonicNow() = mark.also { reads.incrementAndGet() }
        override fun wallNow() = Instant.EPOCH
    }

    private class Endpoint : IOrionCortex.Stub() {
        private val ids = OrionIssuedRequestHistory<Unit> { true }
        val loads = AtomicInteger(); val infers = AtomicInteger(); val unloads = AtomicInteger()
        val health = AtomicInteger(); val discoveries = AtomicInteger(); val queries = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        var automatic = true
        var queryAction: (() -> Unit)? = null
        var modelId: String? = null
        private var inference = ""
        private var callback: IOrionInferenceCallback? = null
        val pid = android.os.Process.myPid() + 1000
        override fun getProtocolInfo() = OrionCortexWire.reply("OK").apply { putInt("major", 1); putInt("minor", 2) }
        override fun getCapabilities() = OrionCortexWire.reply("OK").apply {
            putString("capabilities", OrionCortexProtocol.ISSUED_HISTORY.value)
        }
        override fun issueRequestId() = OrionCortexWire.reply("OK").apply { putString("requestId", ids.issue()) }
        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle): Bundle {
            loads.incrementAndGet(); modelId = specification.getString("modelId")
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", specification.getString("requestId")) }
        }
        override fun getRequestStatus(requestId: String) = OrionCortexWire.reply("OK").apply {
            putString("requestId", requestId); putString("state", "SUCCEEDED")
        }
        override fun getStatus() = OrionCortexWire.reply(if (modelId == null) "UNLOADED" else "LOADED").apply {
            modelId?.let { putString("modelId", it) }
        }
        override fun startInference(input: SharedMemory?, specification: Bundle, callback: IOrionInferenceCallback): Bundle {
            infers.incrementAndGet(); inference = specification.getString("requestId")!!; this.callback = callback
            started.complete(Unit)
            if (automatic) complete()
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", inference) }
        }
        fun complete() { checkNotNull(callback).onChunk(Bundle().apply {
            putInt("schemaVersion", 1); putString("requestId", inference); putLong("sequence", 0)
            putString("delta", "SECRET_OUTPUT_NOT_RETAINED"); putBoolean("isFinal", true); putString("finishReason", "SUCCEEDED")
        }) }
        override fun cancelInference(requestId: String) = Unit
        override fun unloadModel() { unloads.incrementAndGet(); modelId = null }
        override fun healthCheck() = OrionCortexWire.reply("OK").apply {
            health.incrementAndGet(); putString("processName", "network.orion:ai"); putInt("pid", pid)
            putInt("bootCount", 1); putLong("sampledElapsed", 1000); putLong("totalPssBytes", 100)
            putBoolean("nativeIncluded", true); putBoolean("nativeRuntimeLoaded", false); putBoolean("nativeValidationCompleted", false)
        }
        override fun getGenerationMetricsCapabilities(): Bundle {
            discoveries.incrementAndGet(); return OrionGenerationMetricsWire.capabilities(0)
        }
        override fun getGenerationMetrics(query: Bundle): Bundle {
            queries.incrementAndGet(); queryAction?.invoke()
            return OrionGenerationMetricsWire.snapshot(OrionGenerationMetricsSnapshot(
                OrionGenerationMetricsWire.queryId(query), pid, null, 1, OrionInferenceTerminal.SUCCEEDED,
                OrionGenerationNativeState.UNKNOWN, OrionGenerationNativeFinish.UNKNOWN, OrionGenerationSlotState.UNKNOWN,
                OrionGenerationSnapshotState.UPDATING, OrionGenerationPartialWork.UNKNOWN, 0, OrionGenerationTimeState.UNAVAILABLE))
        }
    }

    private class Binding(base: Context, val root: File) : ContextWrapper(base) {
        val endpoint = Endpoint()
        val binds = AtomicInteger(); val unbinds = AtomicInteger()
        lateinit var connection: ServiceConnection
        override fun getApplicationContext() = this
        override fun getNoBackupFilesDir() = root
        override fun bindService(intent: Intent, owner: ServiceConnection, flags: Int): Boolean {
            binds.incrementAndGet(); connection = owner
            owner.onServiceConnected(ComponentName(packageName, "network.orion.ai.service.OrionCortexService"), endpoint)
            return true
        }
        override fun unbindService(owner: ServiceConnection) { unbinds.incrementAndGet() }
        fun disconnect() = connection.onServiceDisconnected(ComponentName(packageName, "network.orion.ai.service.OrionCortexService"))
    }

    private class Fixture : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val root = File(app.noBackupFilesDir, "orion_cycle_control_$id").apply { check(mkdirs()) }
        val context = Binding(app, root)
        val clock = Clock()
        val observer = OrionCortexCycleObserver(8)
        val file = File(root, "mechanical.bin").apply { writeBytes(byteArrayOf(65)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(65)).joinToString("") { "%02x".format(it) }
        val model = ModelRegistryEntity(hash, "fixture", file.path, hash, 1, "fixture", "fixture", 128, "READY", 0, 0)
        private val profile = InferenceModelProfile(hash, 100)
        private val plan = OrionInferenceResourcePlan(profile, "MECHANICAL_NOT_QUALIFIED", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        private val sample = OrionResourceObservation(clock.mark, profile, 80, 0, true, OrionProcessImportance.FOREGROUND,
            false, OrionMemoryPressure.NONE, 100, 200, 100_000, emptyList(), 1, 128, 8)
        private val snapshot = OrionAndroidResourceSnapshot(sample, OrionAiExitHistory(clock.mark, emptyList()), 100, emptySet())
        private val governor = OrionMinimumResourceGovernor(clock, { sample }, listOf(plan), 1000, 1000)
        private val guard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
        private val dbName = "orion_cycle_control_$id.db"
        private val db = Room.databaseBuilder(app, OrionDatabase::class.java, dbName).build()
        private val importer = OrionModelImportCoordinator.open(context, db, clock, 1024, 0)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, cycleObservations = observer)
        lateinit var client: OrionCortexClient
            private set
        init {
            db.close() // The default owner cannot perform a real heavy admission or touch this DB.
            client = OrionCortexClient(context, clock, resources = { _, _, _, _ ->
                val scope = client.cycleCallbackScope(OrionCycleCallback.RESOURCE)
                client.currentMemoryTelemetry()
                val decision = governor.evaluate()
                if (scope != null) observer.governor(scope, client.cycleEndpoint(scope), snapshot, decision)
                decision
            }, restart = {
                val scope = client.cycleCallbackScope(OrionCycleCallback.RESTART)
                val decision = guard.evaluate(snapshot.aiExitHistory)
                if (scope != null) observer.restart(scope, client.cycleEndpoint(scope), snapshot, decision)
                decision
            }, cycleObservations = observer)
            // Only this mechanical test installs an existing scripted client into the real
            // manual reader. No production constructor/port/plan or reflection path is added.
            owner.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(owner, client)
        }
        fun token() = checkNotNull(owner.beginCycleObservation()).also { check(observer.claim(it.owner, it) === it) }
        fun request(deadline: MonotonicDeadline = clock.mark.deadlineAfter(30_000)) = OrionInferenceRequest(
            client.newRequestId(), 1, hash, "SECRET_PROMPT_NOT_RETAINED", "", 8, 0.0, OrionInferencePriority.INTERACTIVE, deadline, "fixture")
        suspend fun run(token: OrionCortexCycleToken, request: OrionInferenceRequest,
            onChunks: (List<OrionInferenceChunk>) -> Unit = {}): OrionCortexRunResult.Completed {
            try {
                val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    OrionCortexRunResult.Completed(client.runInference(model, it, request, 128, 1, token, onChunks))
                }
                observer.returned(token, result)
                return result
            } catch (failure: Throwable) {
                observer.threw(token, if (failure is CancellationException) OrionCycleThrown.CANCELLED else OrionCycleThrown.OTHER)
                throw failure
            }
        }
        override fun close() { owner.close(); app.deleteDatabase(dbName); root.deleteRecursively() }
    }

    @Test fun cycleFactsAreCorrelatedAndManualReadCannotDelayOriginalReturn() = runBlocking {
        Fixture().use { f ->
            val token = f.token(); val request = f.request()
            assertEquals(OrionCycleState.PENDING, f.owner.readCycleObservation(token)?.state)
            val lists = mutableListOf<List<OrionInferenceChunk>>()
            val callback: (List<OrionInferenceChunk>) -> Unit = { lists.add(it) }
            val original = f.run(token, request, callback)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, original.status.terminal)
            assertEquals(0, f.context.endpoint.queries.get()); assertEquals(0, f.context.endpoint.discoveries.get())
            val clocks = f.clock.reads.get(); val health = f.context.endpoint.health.get()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.context.endpoint.queryAction = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val read = async(Dispatchers.Default) { f.owner.readCycleObservation(token) }
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS)); assertFalse(read.isCompleted)
                assertEquals(OrionInferenceTerminal.SUCCEEDED, original.status.terminal) // Already returned before manual RPC.
            } finally { release.countDown() }
            val receipt = checkNotNull(read.await())
            assertSame(original, receipt.originalResult)
            assertTrue(receipt.actualTurnEntered == true); assertTrue(receipt.inferenceAccepted == true)
            assertEquals(OrionCycleStreamSummary(1, 1, 1), receipt.stream); assertEquals(1, lists.size)
            assertEquals(5, receipt.resources.size)
            assertTrue(receipt.resources.any { it.callback == OrionCycleCallback.RESOURCE && it.phase == OrionCyclePhase.INFERENCE })
            assertTrue(checkNotNull(receipt.unload).unloadedReply)
            assertTrue(OrionCycleIssue.POST_REPLY_TIME_UNAVAILABLE in receipt.issues)
            assertEquals(OrionGenerationNativeState.UNKNOWN, receipt.nativeMetrics?.nativeState)
            assertEquals(OrionCycleState.INCOMPLETE, receipt.state)
            assertFalse(receipt.toString().contains("SECRET")); assertFalse(receipt.fullF5GateClosed)
            assertEquals(clocks, f.clock.reads.get()); assertEquals(health, f.context.endpoint.health.get())
            assertSame(receipt, f.owner.readCycleObservation(token)); assertEquals(1, f.context.endpoint.queries.get())
        }
    }

    @Test fun lostOwnershipEventsCannotTurnActualExecutionIntoReplay() = runBlocking {
        Fixture().use { f ->
            val token = f.token(); val request = f.request()
            val lock = f.observer.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(f.observer) as ReentrantLock
            val locked = CountDownLatch(1); val release = CountDownLatch(1); val unlocked = CountDownLatch(1)
            val holder = Thread {
                lock.lock()
                try { locked.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                finally { lock.unlock(); unlocked.countDown() }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(locked.await(1, TimeUnit.SECONDS))
                val result = f.run(token, request) {
                    release.countDown(); assertTrue(unlocked.await(1, TimeUnit.SECONDS))
                }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, result.status.terminal)
                val receipt = checkNotNull(f.owner.readCycleObservation(token))
                assertSame(result, receipt.originalResult)
                assertNotEquals(OrionCycleSource.RETAINED_RESULT, receipt.source)
                assertNull(receipt.actualTurnEntered); assertTrue(OrionCycleIssue.LOSS in receipt.issues)
                assertEquals(1, f.context.endpoint.loads.get()); assertEquals(1, f.context.endpoint.infers.get())
                assertEquals(1, f.context.endpoint.unloads.get())
            } finally { release.countDown(); holder.join(1000) }
        }
    }

    @Test fun originalCallbackErrorKeepsIdentityAndReplayIsExplicitlyRetained() = runBlocking {
        Fixture().use { f ->
            val token = f.token(); val request = f.request(); val original = AssertionError("Original callback error")
            val seen = try { f.run(token, request) { throw original }; null } catch (failure: Throwable) { failure }
            assertSame(original, seen)
            val receipt = checkNotNull(f.owner.readCycleObservation(token))
            assertEquals(OrionCycleThrown.OTHER, receipt.thrown); assertNull(receipt.originalResult)
            assertEquals(OrionCycleStreamSummary(1, 1, 0), receipt.stream)
            val replay = f.token(); val result = f.run(replay, request) { error("Replay callback") }
            assertEquals(OrionInferenceTerminal.FAILED, result.status.terminal)
            val retained = checkNotNull(f.owner.readCycleObservation(replay))
            assertEquals(OrionCycleState.RETAINED_RESULT, retained.state)
            assertNull(retained.stream); assertNull(retained.nativeMetrics)
            assertEquals(1, f.context.endpoint.infers.get()); assertEquals(1, f.context.endpoint.unloads.get())
        }
    }

    @Test fun dedupRaceJoinsWithoutAnotherTurnOrResourceAssociation() = runBlocking {
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val request = f.request(); val actualToken = f.token(); val joinedToken = f.token()
            val actual = async(Dispatchers.Default) { f.run(actualToken, request) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            val joined = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.run(joinedToken, request) { error("Joined callback") } }
            assertFalse(joined.isCompleted)
            f.context.endpoint.complete()
            assertEquals(OrionInferenceTerminal.SUCCEEDED, actual.await().status.terminal)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, joined.await().status.terminal)
            val retained = checkNotNull(f.owner.readCycleObservation(joinedToken))
            assertEquals(OrionCycleState.RETAINED_RESULT, retained.state); assertTrue(retained.resources.isEmpty())
            assertNull(retained.stream); assertFalse(retained.actualTurnEntered == true)
            assertEquals(1, f.context.endpoint.infers.get())
        }
    }

    @Test fun separateConcurrentCallsKeepTheirOwnTurnFactsAndOriginalStream() = runBlocking {
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val firstToken = f.token(); val secondToken = f.token()
            val first = async(Dispatchers.Default) { f.run(firstToken, f.request()) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.run(secondToken, f.request()) }
            assertFalse(second.isCompleted)
            f.context.endpoint.automatic = true
            f.context.endpoint.complete()
            first.await(); withTimeout(3000) { second.await() }
            val firstReceipt = checkNotNull(f.owner.readCycleObservation(firstToken))
            val secondReceipt = checkNotNull(f.owner.readCycleObservation(secondToken))
            for (receipt in listOf(firstReceipt, secondReceipt)) {
                assertTrue(receipt.resources.size <= 5)
                assertNotEquals(OrionCycleSource.RETAINED_RESULT, receipt.source)
                receipt.stream?.let {
                    assertTrue(it.acceptedChunkObservationsAtLeast <= 1)
                    assertTrue(it.callbackAttemptsAtLeast <= 1); assertTrue(it.callbacksReturnedNormallyAtLeast <= 1)
                }
                if (OrionCycleIssue.LOSS !in receipt.issues) {
                    assertEquals(5, receipt.resources.size)
                    assertEquals(OrionCycleStreamSummary(1, 1, 1), receipt.stream)
                    assertTrue(receipt.actualTurnEntered == true)
                }
            }
            assertNull(firstReceipt.nativeMetrics)
            if (OrionCycleIssue.LOSS !in secondReceipt.issues) assertNotNull(secondReceipt.nativeMetrics)
            assertEquals(2, f.context.endpoint.loads.get()); assertEquals(2, f.context.endpoint.infers.get())
        }
    }

    @Test fun callbackCancellationKeepsOriginalInstanceAndNoFabricatedCompletedResult() = runBlocking {
        Fixture().use { f ->
            val token = f.token(); val original = CancellationException("Original callback cancellation")
            val seen = try { f.run(token, f.request()) { throw original }; null } catch (failure: Throwable) { failure }
            assertSame(original, seen)
            val receipt = checkNotNull(f.owner.readCycleObservation(token))
            assertEquals(OrionCycleThrown.CANCELLED, receipt.thrown); assertNull(receipt.originalResult)
            assertEquals(OrionCycleStreamSummary(1, 1, 0), receipt.stream)
            assertEquals(1, f.context.endpoint.unloads.get())
        }
    }

    @Test fun queuedExpiryDoesNotBorrowActiveCycleResourcesOrCleanup() = runBlocking<Unit> {
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val firstToken = f.token(); val queuedToken = f.token()
            val first = async(Dispatchers.Default) { f.run(firstToken, f.request()) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            val queuedRequest = f.request(f.clock.mark.deadlineAfter(100))
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.run(queuedToken, queuedRequest) }
            assertFalse(queued.isCompleted)
            f.clock.mark = MonotonicInstant(BootSessionId(1), 1200)
            assertEquals(OrionInferenceTerminal.TIMED_OUT, withTimeout(3000) { queued.await() }.status.terminal)
            val receipt = checkNotNull(f.owner.readCycleObservation(queuedToken))
            assertFalse(receipt.actualTurnEntered == true); assertTrue(receipt.resources.isEmpty())
            assertNull(receipt.stream); assertNull(receipt.unload); assertNull(receipt.nativeMetrics)
            assertEquals(0, f.context.endpoint.unloads.get())
            f.context.endpoint.complete(); first.await()
        }
    }

    @Test fun supersessionAndCloseDuringManualQueryCannotAttachAnotherInvocation() = runBlocking {
        Fixture().use { f ->
            val first = f.token(); val second = f.token(); f.run(first, f.request()); f.run(second, f.request())
            val stale = checkNotNull(f.owner.readCycleObservation(first))
            assertNull(stale.nativeMetrics); assertTrue(OrionCycleIssue.ENDPOINT_UNAVAILABLE in stale.issues)
            assertEquals(0, f.context.endpoint.queries.get())
            f.context.endpoint.queryAction = { f.owner.close() }
            val closed = checkNotNull(f.owner.readCycleObservation(second))
            assertNull(closed.nativeMetrics); assertTrue(OrionCycleIssue.ENDPOINT_UNAVAILABLE in closed.issues)
            assertEquals(1, f.context.binds.get())
        }
    }
}

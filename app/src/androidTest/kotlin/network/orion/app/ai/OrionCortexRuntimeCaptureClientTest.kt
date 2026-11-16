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
import network.orion.app.resources.OrionCortexMemoryTelemetry
import network.orion.core.resources.*
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Mechanical scripted Client + existing Owner reader, with finite synthetic observation time only.
 * The Owner has no plans and a closed DB; these controls cannot establish heavy admission or native/physical proof. */
@RunWith(AndroidJUnit4::class)
class OrionCortexRuntimeCaptureClientTest {
    private class Clock : OrionClock {
        @Volatile var mark = MonotonicInstant(BootSessionId(1), 1000)
        val reads = AtomicInteger()
        override fun monotonicNow() = mark.also { reads.incrementAndGet() }
        override fun wallNow() = Instant.EPOCH
    }

    private class Endpoint(private val mainPackage: String) : IOrionCortex.Stub() {
        private val ids = OrionIssuedRequestHistory<Unit> { true }
        val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
        val diagnosticTrace = java.util.Collections.synchronizedList(mutableListOf<String>())
        private fun readTrace(name: String) {
            if (Thread.currentThread().name == "orion_runtime_memory_sampler") diagnosticTrace.add(name) else trace.add(name)
        }
        var loadState = "SUCCEEDED"
        var loadedStatus = "LOADED"
        var strictUnload = true
        var loadingReplies = 0
        val loads = AtomicInteger(); val infers = AtomicInteger(); val unloads = AtomicInteger()
        val health = AtomicInteger(); val discoveries = AtomicInteger(); val queries = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        var automatic = true
        var wireTemperature: Float? = null
        var queryAction: (() -> Unit)? = null
        var modelId: String? = null
        private var inference = ""
        private var callback: IOrionInferenceCallback? = null
        val pid = android.os.Process.myPid() + 1000
        override fun getProtocolInfo() = OrionCortexWire.reply("OK").apply { trace.add("protocol"); putInt("major", 1); putInt("minor", 2) }
        override fun getCapabilities() = OrionCortexWire.reply("OK").apply { trace.add("capabilities");
            putString("capabilities", OrionCortexProtocol.ISSUED_HISTORY.value)
        }
        override fun issueRequestId() = OrionCortexWire.reply("OK").apply { trace.add("issueId"); putString("requestId", ids.issue()) }
        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle): Bundle {
            trace.add("load"); loads.incrementAndGet(); modelId = specification.getString("modelId")
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", specification.getString("requestId")) }
        }
        override fun getRequestStatus(requestId: String) = OrionCortexWire.reply("OK").apply {
            trace.add("requestStatus"); putString("requestId", requestId); putString("state", loadState)
        }
        override fun getStatus() = OrionCortexWire.reply(if (modelId == null) "UNLOADED" else if (loadingReplies-- > 0) "LOADING" else loadedStatus).apply {
            readTrace("status")
            modelId?.let { putString("modelId", it) }
            if (modelId == null && !strictUnload) putString("requestId", "allowed-extra-unload-field")
        }
        override fun startInference(input: SharedMemory?, specification: Bundle, callback: IOrionInferenceCallback): Bundle {
            wireTemperature = specification.getFloat("temperature")
            trace.add("infer"); infers.incrementAndGet(); inference = specification.getString("requestId")!!; this.callback = callback
            started.complete(Unit)
            if (automatic) complete()
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", inference) }
        }
        fun complete() { checkNotNull(callback).onChunk(Bundle().apply {
            putInt("schemaVersion", 1); putString("requestId", inference); putLong("sequence", 0)
            putString("delta", "SECRET_OUTPUT_NOT_RETAINED"); putBoolean("isFinal", true); putString("finishReason", "SUCCEEDED")
        }) }
        override fun cancelInference(requestId: String) { trace.add("cancel") }
        override fun unloadModel() { trace.add("unload"); unloads.incrementAndGet(); modelId = null }
        override fun healthCheck() = OrionCortexWire.reply("OK").apply {
            readTrace("health"); health.incrementAndGet(); putString("processName", "$mainPackage:ai"); putInt("pid", pid)
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
        val endpoint = Endpoint(packageName)
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

    private class Fixture(private val provideWitness: Boolean = true, observeUnload: Boolean = false) : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val root = File(app.noBackupFilesDir, "orion_cycle_control_$id").apply { check(mkdirs()) }
        val context = Binding(app, root)
        val clock = Clock()
        private val telemetry = OrionCortexMemoryTelemetry(context.packageName, clock, 1000)
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
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock)
        lateinit var client: OrionCortexClient
            private set
        init {
            db.close() // The default owner cannot perform a real heavy admission or touch this DB.
            client = OrionCortexClient(context, clock, resources = { _, _, _, _ ->
                val window = client.runtimeHealthWindow()
                val receipt = client.currentMemoryTelemetry()
                if (receipt != null) {
                    val pid = receipt.getInt("pid")
                    val evidence = telemetry.read(receipt, pid, setOf(context.packageName, "${context.packageName}:ai"), true)
                    if (provideWitness) client.runtimeMemoryValidated(window, pid, evidence?.sampledAt)
                }
                governor.evaluate()
            }, restart = { guard.evaluate(snapshot.aiExitHistory) },
                unloadObservations = if (observeUnload) OrionCortexUnloadObservationBuffer() else null)
            // Only this mechanical test installs an existing scripted client into the real
            // manual reader. No production constructor/port/plan or reflection path is added.
            owner.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(owner, client)
        }
        fun request(deadline: MonotonicDeadline = clock.mark.deadlineAfter(30_000), temperature: Double = 0.0) = OrionInferenceRequest(
            client.newRequestId(), 1, hash, "SECRET_PROMPT_NOT_RETAINED", "", 8, temperature, OrionInferencePriority.INTERACTIVE, deadline, "fixture")
        fun capture(request: OrionInferenceRequest, unavailableClock: Boolean = false, sampling: Boolean = false): OrionCortexRuntimeCaptureSession {
            val capture = checkNotNull(owner.beginRuntimeCapture(if (sampling) OrionRuntimeSamplingConfiguration(10) else null))
            val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
                it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
            }.apply { isAccessible = true }
            val time = if (unavailableClock) null else method.invoke(OrionObservationEventClock.Companion, List(256) { 1000L }) as OrionObservationEventClock
            capture.javaClass.getDeclaredField("observationClock").apply { isAccessible = true }.set(capture, time)
            capture.javaClass.getDeclaredField("lifecycleSource").apply { isAccessible = true }.set(capture, OrionInferenceLifecycleEventSource(time))
            check(capture.claim(owner))
            return capture
        }
        suspend fun run(capture: OrionCortexRuntimeCaptureSession?, request: OrionInferenceRequest,
            onChunks: (List<OrionInferenceChunk>) -> Unit = {}): OrionInferenceStatus {
            try {
                val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    // Mechanical call only: original Owner has empty plans and closed DB, so no real heavy admission is possible.
                    capture?.selected(owner, hash, model.sizeBytes, request.requestId, request.deadline, 1000)
                    client.runInference(model, it, request, 128, 1, null, capture, onChunks)
                }
                capture?.runReturned(false)
                return result
            } catch (failure: Throwable) { capture?.runReturned(true); throw failure }
        }
        override fun close() { owner.close(); app.deleteDatabase(dbName); root.deleteRecursively() }
    }


    @Test fun captureCopiesExactOriginalWireFloatWithoutChangingFunctionalReadsRpcOrCallbackList() = runBlocking {
        suspend fun run(enabled: Boolean): Pair<List<String>, Int> = Fixture().use { f ->
            val request = f.request(temperature = 0.1)
            val capture = if (enabled) f.capture(request) else null
            var delivered: List<OrionInferenceChunk>? = null
            val original = f.run(capture, request) { delivered = it }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, original.terminal)
            assertEquals("SECRET_OUTPUT_NOT_RETAINED", checkNotNull(delivered).single().textDelta)
            val calls = f.context.endpoint.trace.toList(); val reads = f.clock.reads.get()
            assertEquals(0.1f, f.context.endpoint.wireTemperature)
            if (capture != null) {
                val actual = capture.javaClass.getDeclaredField("originalWireTemperature").apply { isAccessible = true }.get(capture) as Float
                assertEquals(f.context.endpoint.wireTemperature, actual)
                assertNotEquals(request.temperature, actual.toDouble())
                f.owner.readRuntimeCapture(capture)
                assertEquals(calls, f.context.endpoint.trace); assertEquals(reads, f.clock.reads.get())
            }
            calls to reads
        }
        assertEquals(run(false), run(true))
    }

    @Test fun capturePreservesOriginalClockRpcSequenceAndCallbackListWithoutOldCollectors() = runBlocking {
        suspend fun run(enabled: Boolean): Pair<List<String>, Int> = Fixture().use { f ->
            val request = f.request(); val capture = if (enabled) f.capture(request) else null
            var actualList: List<OrionInferenceChunk>? = null
            val callback: (List<OrionInferenceChunk>) -> Unit = { actualList = it }
            val original = f.run(capture, request, callback)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, original.terminal)
            assertEquals("SECRET_OUTPUT_NOT_RETAINED", checkNotNull(actualList).single().textDelta)
            val reads = f.clock.reads.get(); val calls = f.context.endpoint.trace.toList()
            if (capture != null) {
                val receipt = checkNotNull(f.owner.readRuntimeCapture(capture)); val facts = checkNotNull(receipt.facts)
                assertSame(original, facts.firstTerminal?.status)
                assertTrue(receipt.identityKnown); assertEquals(true, receipt.originalOwnerEntered); assertEquals(true, receipt.actualTurnEntered)
                assertEquals(listOf(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START,
                    OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH,
                    OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP), facts.markers.map { it.kind })
                assertEquals(1, facts.chunks.size); assertEquals(26, facts.chunks.single().metadata.validatedTextDeltaUtf8Bytes)
                assertEquals(0, facts.chunks.single().metadata.validatedToolDeltaUtf8Bytes)
                assertFalse(receipt.runtimeEvidenceV1Complete); assertFalse(receipt.productionInferenceApproved)
                assertEquals(reads, f.clock.reads.get()); assertEquals(calls, f.context.endpoint.trace)
                assertSame(receipt, f.owner.readRuntimeCapture(capture))
            }
            calls to reads
        }
        assertEquals(run(false), run(true))
    }

    @Test fun optionalSamplerPreservesFunctionalReadsRpcOrderCallbackListAndTerminal() = runBlocking {
        suspend fun run(sampling: Boolean): Pair<List<String>, Int> = Fixture().use { f ->
            val request = f.request(); val capture = f.capture(request, sampling = sampling)
            var delivered: List<OrionInferenceChunk>? = null
            val original = f.run(capture, request) { delivered = it }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, original.terminal)
            assertEquals("SECRET_OUTPUT_NOT_RETAINED", checkNotNull(delivered).single().textDelta)
            val calls = f.context.endpoint.trace.toList(); val reads = f.clock.reads.get()
            // Test-only drain, after original return. Production manual close never waits for this lane.
            withTimeout(5000) { while (OrionRuntimeMemorySamplingWorker.outstanding()) delay(1) }
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            receipt.facts?.firstTerminal?.let { assertSame(original, it.status) }
            assertEquals(calls, f.context.endpoint.trace); assertEquals(reads, f.clock.reads.get())
            if (sampling) {
                val report = checkNotNull(receipt.memorySampling)
                assertTrue(report.diagnosticHealthCalls <= 7); assertTrue(report.diagnosticStateCalls <= 7)
                assertFalse(report.runtimeEvidenceV1Complete); assertFalse(report.productionInferenceApproved)
            } else assertNull(receipt.memorySampling)
            calls to reads
        }
        assertEquals(run(false), run(true))
    }

    @Test fun sourceDoesNotDependOnOldMetricsCorrelationAndMissingWitnessStaysUnresolved() = runBlocking {
        for (provide in listOf(true, false)) Fixture(provide).use { f ->
            f.client.javaClass.getDeclaredField("metricsCorrelation").apply { isAccessible = true }.set(f.client, null)
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(capture, request).terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertEquals(provide, receipt.identityKnown)
            if (provide) assertEquals(1, receipt.facts?.chunks?.size)
            else { assertNull(receipt.facts); assertTrue(receipt.pendingUnassociatedMarkers.isNotEmpty()) }
        }
    }

    @Test fun replayAndConcurrentJoinNeverActivateOrBorrowOriginalSource() = runBlocking {
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val request = f.request(); val actual = f.capture(request); val joined = f.capture(request)
            val running = async(Dispatchers.Default) { f.run(actual, request) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            val joining = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.run(joined, request) { error("Joined callback") } }
            f.context.endpoint.complete()
            assertSame(running.await(), joining.await())
            val shared = checkNotNull(f.owner.readRuntimeCapture(joined))
            assertEquals(false, shared.originalOwnerEntered); assertNull(shared.facts); assertNull(shared.lifecycle)
            val replay = f.capture(request); f.run(replay, request) { error("Replay callback") }
            val retained = checkNotNull(f.owner.readRuntimeCapture(replay))
            assertEquals(false, retained.originalOwnerEntered); assertNull(retained.facts); assertNull(retained.lifecycle)
            assertEquals(1, f.context.endpoint.infers.get())
            assertEquals(true, f.owner.readRuntimeCapture(actual)?.originalOwnerEntered)
        }
    }

    @Test fun callbackErrorAndCancellationKeepOriginalInstancesAndRealWonTerminal() = runBlocking {
        for (original in listOf<Throwable>(AssertionError("original callback"), CancellationException("original cancellation"))) Fixture().use { f ->
            val request = f.request(); val capture = f.capture(request)
            assertSame(original, try { f.run(capture, request) { throw original }; null } catch (seen: Throwable) { seen })
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertTrue(receipt.threw)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, receipt.facts?.firstTerminal?.status?.terminal)
            assertEquals(1, receipt.facts?.chunks?.size)
            assertEquals(1, f.context.endpoint.unloads.get())
        }
    }

    @Test fun optionalSamplerCannotReplaceOriginalCallbackErrorOrCancellation() = runBlocking {
        for (original in listOf<Throwable>(AssertionError("original sampled callback"), CancellationException("original sampled cancellation")))
            Fixture().use { f ->
                val request = f.request(); val capture = f.capture(request, sampling = true)
                assertSame(original, try { f.run(capture, request) { throw original }; null } catch (seen: Throwable) { seen })
                withTimeout(5000) { while (OrionRuntimeMemorySamplingWorker.outstanding()) delay(1) }
                val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
                assertTrue(receipt.threw); assertEquals(1, f.context.endpoint.unloads.get())
                receipt.facts?.firstTerminal?.let { assertEquals(OrionInferenceTerminal.SUCCEEDED, it.status.terminal) }
            }
    }

    @Test fun queuedExpiryOwnsNoNativeTurnAndDoesNotUnloadAnotherCapture() = runBlocking<Unit> {
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val firstRequest = f.request(); val firstCapture = f.capture(firstRequest)
            val first = async(Dispatchers.Default) { f.run(firstCapture, firstRequest) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            val queuedRequest = f.request(f.clock.mark.deadlineAfter(100)); val queuedCapture = f.capture(queuedRequest)
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.run(queuedCapture, queuedRequest) }
            f.clock.mark = MonotonicInstant(BootSessionId(1), 1200)
            assertEquals(OrionInferenceTerminal.TIMED_OUT, withTimeout(3000) { queued.await() }.terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(queuedCapture))
            assertEquals(true, receipt.originalOwnerEntered); assertTrue(receipt.actualTurnEntered != true)
            assertFalse(receipt.identityKnown); assertNull(receipt.facts); assertEquals(0, f.context.endpoint.unloads.get())
            f.context.endpoint.complete(); first.await()
        }
    }

    @Test fun loadFalseAndNonStrictUnloadNeverBecomeInventedSuccessfulTerminals() = runBlocking {
        Fixture().use { f ->
            f.context.endpoint.loadedStatus = "UNLOADED" // Original load path returns false after an ambiguous inconsistent engine response.
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.FAILED, f.run(capture, request).terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertTrue(OrionRuntimeSessionGap.LOAD_TERMINAL_UNAVAILABLE in receipt.gaps)
            assertFalse(checkNotNull(receipt.facts).markers.any { it.kind == OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL })
            assertEquals(0, f.context.endpoint.infers.get())
        }
        Fixture().use { f ->
            f.context.endpoint.strictUnload = false
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(capture, request).terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertTrue(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED in receipt.gaps)
            assertFalse(checkNotNull(receipt.facts).markers.any { it.kind == OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP })
        }
    }

    @Test fun laterTurnCloseAndGenerationExhaustionNeverRepairOldOrUnknownFences() = runBlocking {
        Fixture().use { f ->
            val request = f.request(); val capture = f.capture(request); f.run(capture, request)
            f.run(null, f.request())
            assertTrue(checkNotNull(f.owner.readRuntimeCapture(capture)).invalidated)
        }
        Fixture().use { f ->
            val request = f.request(); val capture = f.capture(request); f.run(capture, request)
            f.client.close(); assertTrue(checkNotNull(f.owner.readRuntimeCapture(capture)).invalidated)
        }
        for (field in listOf("runtimeTurnGeneration", "runtimeConnectionEpoch")) Fixture().use { f ->
            f.client.javaClass.getDeclaredField(field).apply { isAccessible = true }.setLong(f.client, Long.MAX_VALUE)
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(capture, request).terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertFalse(receipt.identityKnown); assertNull(receipt.facts); assertTrue(receipt.invalidated)
        }
    }

    @Test fun loadingReplyIsNotCapturedAsLoadTerminalAndEndpointLossPreservesOriginalProcessDied() = runBlocking {
        Fixture().use { f ->
            f.context.endpoint.loadingReplies = 1
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(capture, request).terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            val terminals = checkNotNull(receipt.facts).markers.filter { it.kind == OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL }
            assertEquals(1, terminals.size); assertEquals(OrionInferenceTerminal.SUCCEEDED, terminals.single().status?.terminal)
            assertTrue(f.context.endpoint.trace.count { it == "requestStatus" } >= 2)
        }
        Fixture().use { f ->
            f.context.endpoint.automatic = false
            val request = f.request(); val capture = f.capture(request)
            val running = async(Dispatchers.Default) { f.run(capture, request) }
            withTimeout(3000) { f.context.endpoint.started.await() }
            f.context.disconnect()
            val original = withTimeout(3000) { running.await() }
            assertEquals(OrionInferenceTerminal.PROCESS_DIED, original.terminal)
            val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertTrue(receipt.invalidated); assertSame(original, receipt.facts?.firstTerminal?.status)
            assertNull(receipt.facts?.completed)
        }
    }


    @Test fun unavailableCaptureClockCannotChangeFunctionalClockRpcOrOutput() = runBlocking {
        suspend fun run(fault: Boolean): Pair<List<String>, Int> = Fixture().use { f ->
            val request = f.request(); val capture = if (fault) f.capture(request, true) else null
            val delivered = mutableListOf<List<OrionInferenceChunk>>()
            val original = f.run(capture, request) { delivered.add(it) }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, original.terminal)
            assertEquals(1, delivered.size); assertEquals("SECRET_OUTPUT_NOT_RETAINED", delivered.single().single().textDelta)
            val calls = f.context.endpoint.trace.toList(); val reads = f.clock.reads.get()
            if (capture != null) {
                val receipt = checkNotNull(f.owner.readRuntimeCapture(capture))
                assertTrue(receipt.invalidated); assertFalse(receipt.identityKnown); assertNull(receipt.facts)
                assertEquals(calls, f.context.endpoint.trace); assertEquals(reads, f.clock.reads.get())
            }
            calls to reads
        }
        assertEquals(run(false), run(true))
    }


    @Test fun newTurnInvalidatesCachedCaptureBeforeBlockingOriginalUnloadOwnerPublication() = runBlocking {
        Fixture(observeUnload = true).use { f ->
            val firstRequest = f.request(); val firstCapture = f.capture(firstRequest)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(firstCapture, firstRequest).terminal)
            val cached = checkNotNull(f.owner.readRuntimeCapture(firstCapture))
            assertFalse(cached.invalidated); assertTrue(cached.identityKnown)
            assertSame(cached, f.owner.readRuntimeCapture(firstCapture))
            val unloadOwner = checkNotNull(f.client.javaClass.getDeclaredField("unloadObservationOwner")
                .apply { isAccessible = true }.get(f.client))
            val guard = unloadOwner.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(unloadOwner)
            val thread = java.util.concurrent.atomic.AtomicReference<Thread?>()
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "orion_capture_turn_fence_probe").also { thread.set(it) }
            }.asCoroutineDispatcher()
            val secondRequest = f.request()
            var second: Deferred<OrionInferenceStatus>? = null
            try {
                synchronized(guard) {
                    second = async(dispatcher) { f.run(null, secondRequest) }
                    // This is a bounded test-only scheduling probe, not a runtime/source timing measurement.
                    val stopAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    var blockedAtOriginalPublication = false
                    while (System.nanoTime() < stopAt) {
                        val worker = thread.get()
                        if (worker != null && worker.state == Thread.State.BLOCKED && worker.stackTrace.any {
                            it.className == OrionCortexClient::class.java.name && it.methodName == "updateUnloadOwner"
                        }) {
                            blockedAtOriginalPublication = true
                            break
                        }
                        Thread.sleep(2)
                    }
                    assertTrue("Second turn must block in the existing unload-owner update", blockedAtOriginalPublication)
                    val previous = f.owner.readRuntimeCapture(firstCapture)
                    assertTrue("A new admitted turn must invalidate the previous cached capture before original publication can block",
                        previous == null || previous.invalidated)
                }
            } finally {
                // synchronized releases the existing guard even on the expected RED assertion.
                // Let the original second run/cleanup finish before closing this owned dispatcher/fixture.
                try {
                    withContext(NonCancellable) {
                        second?.let { pending ->
                            assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(5000) { pending.await() }.terminal)
                        }
                    }
                } finally { dispatcher.close() }
            }
        }
    }


    @Test fun lostConnectionInvalidatesCachedCaptureBeforeBlockingOriginalUnloadOwnerPublication() = runBlocking {
        Fixture(observeUnload = true).use { f ->
            val request = f.request(); val capture = f.capture(request)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(capture, request).terminal)
            val cached = checkNotNull(f.owner.readRuntimeCapture(capture))
            assertFalse(cached.invalidated); assertTrue(cached.identityKnown)
            assertSame(cached, f.owner.readRuntimeCapture(capture))
            val unloadOwner = checkNotNull(f.client.javaClass.getDeclaredField("unloadObservationOwner")
                .apply { isAccessible = true }.get(f.client))
            val guard = unloadOwner.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(unloadOwner)
            val thread = java.util.concurrent.atomic.AtomicReference<Thread?>()
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "orion_capture_lost_fence_probe").also { thread.set(it) }
            }.asCoroutineDispatcher()
            var disconnected: Deferred<Unit>? = null
            try {
                synchronized(guard) {
                    disconnected = async(dispatcher) { f.context.disconnect() }
                    // Bounded test scheduling only: this does not measure a runtime event or physical latency.
                    val stopAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    var blockedInOriginalLostConnection = false
                    while (System.nanoTime() < stopAt) {
                        val worker = thread.get()
                        if (worker != null && worker.state == Thread.State.BLOCKED) {
                            val stack = worker.stackTrace
                            if (stack.any { it.className == OrionCortexClient::class.java.name && it.methodName == "lostConnection" } &&
                                stack.any { it.className == OrionCortexClient::class.java.name && it.methodName == "updateUnloadOwner" }) {
                                blockedInOriginalLostConnection = true
                                break
                            }
                        }
                        Thread.sleep(2)
                    }
                    assertTrue("Disconnect must block in lostConnection's existing unload-owner update", blockedInOriginalLostConnection)
                    val previous = f.owner.readRuntimeCapture(capture)
                    assertTrue("A lost connection must invalidate its cached capture before original publication can block",
                        previous == null || previous.invalidated)
                }
            } finally {
                // Release the existing guard on RED as well, then finish original disconnect/unbind cleanup.
                try {
                    withContext(NonCancellable) {
                        disconnected?.let { pending -> withTimeout(5000) { pending.await() } }
                    }
                } finally { dispatcher.close() }
            }
        }
    }

}

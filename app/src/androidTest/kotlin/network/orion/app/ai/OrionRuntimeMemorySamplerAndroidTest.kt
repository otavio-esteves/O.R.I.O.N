package network.orion.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** ANDROID_EMULATOR mechanical private-endpoint controls. No admitted model, physical or calibration proof. */
@RunWith(AndroidJUnit4::class)
class OrionRuntimeMemorySamplerAndroidTest {
    private class Clock : OrionClock {
        val reads = AtomicInteger()
        override fun monotonicNow() = MonotonicInstant(BootSessionId(3), 100).also { reads.incrementAndGet() }
        override fun wallNow() = Instant.EPOCH
    }
    private class Endpoint(private val pkg: String) : IOrionCortex.Stub() {
        val health = AtomicInteger(); val states = AtomicInteger(); val mutations = AtomicInteger()
        val pid = android.os.Process.myPid() + 1000
        @Volatile var reportedPid = pid
        @Volatile var boot = 3
        @Volatile var sampled = 100L
        @Volatile var pss = 200L
        @Volatile var nativeIncluded = true
        @Volatile var runtime = false
        @Volatile var validated = false
        @Volatile var state = "UNLOADED"
        @Volatile var wrongName = false
        @Volatile var extra = false
        @Volatile var healthFailure: Throwable? = null
        @Volatile var statusFailure: Throwable? = null
        @Volatile var entered: CountDownLatch? = null
        @Volatile var release: CountDownLatch? = null
        override fun healthCheck(): Bundle {
            health.incrementAndGet(); entered?.countDown()
            release?.let { check(it.await(10, TimeUnit.SECONDS)) }
            healthFailure?.let { throw it }
            return OrionCortexWire.reply("OK").apply {
                putString("processName", if (wrongName) "wrong:ai" else "$pkg:ai"); putInt("pid", reportedPid)
                putInt("bootCount", boot); putLong("sampledElapsed", sampled); putLong("totalPssBytes", pss)
                putBoolean("nativeIncluded", nativeIncluded); putBoolean("nativeRuntimeLoaded", runtime)
                putBoolean("nativeValidationCompleted", validated)
                if (extra) putString("SECRET_SENTINEL", "SECRET_SENTINEL")
            }
        }
        override fun getStatus(): Bundle {
            states.incrementAndGet(); statusFailure?.let { throw it }
            return OrionCortexWire.reply(state).apply {
                if (state == "LOADED" || state == "INFERENCING") putString("modelId", "a".repeat(64))
            }
        }
        override fun getProtocolInfo() = error("No diagnostic handshake")
        override fun getCapabilities() = error("No diagnostic capability query")
        override fun issueRequestId() = error("No diagnostic request reservation")
        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle?) = error("No diagnostic load")
        override fun startInference(input: SharedMemory?, specification: Bundle?, callback: IOrionInferenceCallback?) = error("No diagnostic inference")
        override fun getRequestStatus(requestId: String?) = error("No diagnostic request polling")
        override fun cancelInference(requestId: String?) { mutations.incrementAndGet() }
        override fun unloadModel() { mutations.incrementAndGet() }
        override fun getGenerationMetricsCapabilities() = error("No diagnostic metrics")
        override fun getGenerationMetrics(query: Bundle?) = error("No diagnostic metrics")
    }
    private fun finite(): OrionObservationEventClock {
        val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
            it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        }.apply { isAccessible = true }
        return method.invoke(OrionObservationEventClock.Companion, List(256) { 100L }) as OrionObservationEventClock
    }
    private fun field(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
    private class Fixture(private val test: OrionRuntimeMemorySamplerAndroidTest,
        configured: Boolean = true, probeAvailable: Boolean = true,
        probeReadings: List<OrionRuntimeMainMemoryReading?> = List(7) { OrionRuntimeMainMemoryReading(100, 1000000) }) : AutoCloseable {
        val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = Any()
        val clock = Clock()
        val endpoint = Endpoint(app.packageName)
        val sourceClock = test.finite()
        val source = OrionInferenceLifecycleEventSource(sourceClock)
        val client = OrionCortexClient(app, clock, resources = { _, _, _, _ -> error("No diagnostic governor") },
            restart = { error("No diagnostic restart decision") })
        val request = OrionInferenceRequest("technical-logical", 1, "a".repeat(64), "PRIVATE_PROMPT", "", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, MonotonicInstant(BootSessionId(3), 100).deadlineAfter(1000), "technical")
        val capture = checkNotNull(OrionCortexRuntimeCaptureSession.create(owner, android.os.Process.myPid(),
            if (configured) OrionRuntimeSamplingConfiguration(10) else null,
            if (configured && probeAvailable) OrionRuntimeMainMemoryProbe.finiteForTests(probeReadings) else null))
        val lifecycle: OrionInferenceLifecycle
        val frame: OrionRuntimeCaptureFrame
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        init {
            test.field(capture, "observationClock", sourceClock); test.field(capture, "lifecycleSource", source)
            check(capture.claim(owner)); capture.selected(owner, request.modelId, 1, request.requestId, request.deadline, 10)
            check(capture.forClient(client, request.requestId, request.modelId, 1) === capture)
            lifecycle = OrionInferenceLifecycle(request, clock, events = source)
            capture.runOnceEntered(lifecycle)
            frame = checkNotNull(capture.turnEntered(client, 1))
            frame.connection = OrionRuntimeConnectionStamp(endpoint, connection, endpoint.asBinder(), 1)
            test.field(client, "remote", endpoint); test.field(client, "connection", connection)
            test.field(client, "runtimeBinder", endpoint.asBinder()); test.field(client, "runtimeFrame", frame)
            test.field(client, "runtimeConnectionEpoch", 1L); test.field(client, "runtimeTurnGeneration", 1L)
        }
        fun offer() = capture.endpointPublished(frame)
        fun sampler(): OrionRuntimeMemorySampler? = capture.javaClass.getDeclaredField("memorySampler").apply { isAccessible = true }.get(capture) as? OrionRuntimeMemorySampler
        fun ticket(phase: OrionRuntimeMemoryPhase): OrionRuntimeSamplingTicket? {
            val sampler = sampler() ?: return null
            @Suppress("UNCHECKED_CAST") val tickets = sampler.javaClass.getDeclaredField("tickets").apply { isAccessible = true }
                .get(sampler) as Array<OrionRuntimeSamplingTicket?>
            return tickets[phase.ordinal]
        }
        suspend fun await(phase: OrionRuntimeMemoryPhase) = withTimeout(5000) {
            while (ticket(phase)?.done != true) delay(1)
        }
        private suspend fun awaitIdleWorker() = withTimeout(5000) {
            while (OrionRuntimeMemorySamplingWorker.outstanding()) delay(1)
        }
        suspend fun enqueue(phase: OrionRuntimeMemoryPhase, trigger: () -> Unit) {
            // Positive fixture scheduling only: done does not mean the global worker has parked.
            // Hold its reentrant queue lock on the hook thread so the one offer cannot contend;
            // the worker starts collection only after the original hook has returned.
            awaitIdleWorker()
            val lock = OrionRuntimeMemorySamplingWorker.javaClass.getDeclaredField("lock").apply { isAccessible = true }
                .get(OrionRuntimeMemorySamplingWorker) as java.util.concurrent.locks.ReentrantLock
            lock.lock()
            try {
                assertFalse("Previous fixture work must finish before a positive phase", OrionRuntimeMemorySamplingWorker.outstanding())
                trigger()
                val offered = checkNotNull(ticket(phase))
                assertFalse("The positive phase must enqueue its single attempt", offered.done)
            } finally { lock.unlock() }
        }
        fun finish(): OrionRuntimeSessionReceipt {
            frame.ended = true; capture.runReturned(false)
            return checkNotNull(capture.readAfterRun(owner))
        }
        fun corePin(): Any? = client.javaClass.getDeclaredField("pinnedAiPid").apply { isAccessible = true }.get(client)
        fun stillConnected(): Boolean = client.runtimeDiagnosticCurrent(frame, checkNotNull(frame.connection))
        override fun close() {
            endpoint.release?.countDown(); frame.valid = false
            // Test owns only the synthetic connection; avoid creating fake unbind business effects.
            test.field(client, "remote", null); test.field(client, "connection", null); test.field(client, "runtimeFrame", null)
            client.close()
            // Release the synthetic reply first, then drain outside all queue/source locks.
            // The bounded-queue test uses finish() separately and releases its blocker before close.
            runBlocking { awaitIdleWorker() }
        }
    }

    @Test fun sevenPhasesUseOneRealPrivateReceiptPairAndNeverMutateTheCorePin() = runBlocking {
        Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            val originalReads = f.clock.reads.get()
            suspend fun phase(value: OrionRuntimeMemoryPhase, trigger: () -> Unit) {
                // The finite test endpoint alone pauses its diagnostic reply until the genuine hook returns.
                f.endpoint.entered = CountDownLatch(1); f.endpoint.release = CountDownLatch(1)
                f.enqueue(value, trigger); assertTrue(f.endpoint.entered!!.await(5, TimeUnit.SECONDS))
                f.endpoint.release!!.countDown(); f.await(value)
            }
            phase(OrionRuntimeMemoryPhase.BEFORE_LOAD) { f.offer() }
            f.endpoint.state = "LOADING"
            phase(OrionRuntimeMemoryPhase.DURING_LOAD) {
                f.capture.loadPreparing(f.frame, f.endpoint, MonotonicInstant(BootSessionId(3), 100))
            }
            f.capture.loadDispatch(f.frame, f.endpoint, "technical-load")
            f.capture.loadIssued(f.frame, f.endpoint, "technical-load", MonotonicInstant(BootSessionId(3), 100))
            f.endpoint.state = "LOADED"; f.endpoint.runtime = true; f.endpoint.validated = true
            phase(OrionRuntimeMemoryPhase.AFTER_LOAD) {
                f.capture.loadTerminal(f.frame, f.endpoint, "technical-load", OrionInferenceTerminal.SUCCEEDED, true)
            }
            f.endpoint.state = "INFERENCING"
            phase(OrionRuntimeMemoryPhase.DURING_INFERENCE) {
                f.capture.inferenceDispatch(f.frame, f.endpoint, "technical-issued", 10)
            }
            f.endpoint.state = "LOADED"
            val original = f.lifecycle.accept(OrionInferenceChunk(f.request.requestId, 1, 0, "PRIVATE_OUTPUT", null, true, OrionInferenceTerminal.SUCCEEDED))
            phase(OrionRuntimeMemoryPhase.AFTER_TERMINAL) { f.capture.terminalHint() }
            f.endpoint.state = "UNLOADING"
            phase(OrionRuntimeMemoryPhase.DURING_UNLOAD) { f.capture.unloadDispatch(f.frame, f.endpoint) }
            f.endpoint.state = "UNLOADED"
            phase(OrionRuntimeMemoryPhase.AFTER_UNLOAD) {
                f.capture.unloadCompleted(f.frame, f.endpoint, MonotonicInstant(BootSessionId(3), 100).deadlineAfter(1000), true)
            }
            val receipt = f.finish(); val facts = checkNotNull(receipt.facts)
            assertEquals(7, facts.memory.size); assertEquals(7, f.endpoint.health.get()); assertEquals(7, f.endpoint.states.get())
            assertEquals(OrionRuntimeMemoryPhase.entries.toList(), facts.memory.map { it.window.phase })
            facts.memory.forEach { assertSame(it.started, it.mainSampled); assertEquals(300L, it.aggregatePssBytes) }
            assertTrue(facts.memory.last().nativeRuntimeLoaded == true); assertTrue(facts.memory.last().nativeValidationCompleted == true)
            assertSame(original.status, facts.firstTerminal?.status)
            assertNull(f.corePin()); assertEquals(0, f.endpoint.mutations.get())
            assertEquals(originalReads + 1, f.clock.reads.get()) // Original accept only; no sampler domain-clock reads.
            val report = checkNotNull(receipt.memorySampling)
            assertEquals(127, report.attemptedMask); assertEquals(7, report.retainedRows)
            assertEquals(7L, report.diagnosticHealthCalls); assertEquals(7L, report.diagnosticStateCalls)
            assertEquals(0L, report.droppedAtLeast); assertEquals(10L, report.maximumSampleAgeMillis)
            assertFalse(report.sourceAuthenticated); assertFalse(report.fullF5GateClosed); assertFalse(report.f6Authorized)
        }
    }

    @Test fun absentConfigurationDoesNotIssueSamplingRPCs() = runBlocking {
        Fixture(this@OrionRuntimeMemorySamplerAndroidTest, configured = false).use { f ->
            f.offer(); assertNull(f.sampler()); assertEquals(0, f.endpoint.health.get()); assertEquals(0, f.endpoint.states.get())
            assertNull(f.corePin()); assertEquals(1, f.clock.reads.get())
        }
        Fixture(this@OrionRuntimeMemorySamplerAndroidTest, probeAvailable = false).use { f ->
            f.offer(); assertNull(f.sampler()); assertEquals(0, f.endpoint.health.get())
            assertTrue(f.finish().gaps.contains(OrionRuntimeSessionGap.SAMPLING_UNAVAILABLE))
        }
    }

    @Test fun actualBootDisagreementStaysUnavailableAfterReceiptCorrectionWithoutChangingCoreConnection() = runBlocking {
        Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            f.endpoint.boot = 4; f.offer(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD)
            f.endpoint.boot = 3; f.endpoint.state = "LOADING"
            f.capture.loadPreparing(f.frame, f.endpoint, MonotonicInstant(BootSessionId(3), 100))
            f.offer()
            assertNull(f.ticket(OrionRuntimeMemoryPhase.DURING_LOAD))
            assertEquals(1, f.endpoint.health.get()); assertEquals(0, f.endpoint.states.get())
            assertTrue(f.stillConnected()); assertNull(f.corePin()); assertNull(f.source.terminalView()?.firstTerminal)
            val receipt = f.finish()
            assertTrue(receipt.invalidated); assertTrue(receipt.gaps.contains(OrionRuntimeSessionGap.IDENTITY_CHANGED))
            assertEquals(0, checkNotNull(receipt.memorySampling).retainedRows)
        }
    }

    @Test fun repeatedHintsAndDiagnosticFailuresHaveNoConnectionOrLifecycleSideEffects() = runBlocking {
        for (fault in 0..8) Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            when (fault) {
                0 -> f.endpoint.healthFailure = android.os.RemoteException("PRIVATE_ERROR")
                1 -> f.endpoint.healthFailure = AssertionError("PRIVATE_ERROR")
                2 -> f.endpoint.wrongName = true
                3 -> f.endpoint.extra = true
                4 -> f.endpoint.reportedPid = android.os.Process.myPid()
                5 -> f.endpoint.boot = 4
                6 -> f.endpoint.pss = 0
                7 -> f.endpoint.nativeIncluded = false
                8 -> f.endpoint.statusFailure = android.os.RemoteException("PRIVATE_ERROR")
            }
            f.offer(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD); repeat(20) { f.offer() }
            assertEquals(1, f.endpoint.health.get()); assertTrue(f.endpoint.states.get() <= 1)
            assertTrue(f.stillConnected()); assertNull(f.corePin()); assertEquals(0, f.endpoint.mutations.get())
            assertNull(f.source.terminalView()?.firstTerminal); assertEquals(1, f.clock.reads.get())
            val report = checkNotNull(f.finish().memorySampling)
            assertEquals(0, report.retainedRows); assertFalse(report.sourceAuthenticated)
        }
    }

    @Test fun equalMillisecondPhaseCrossingAndEndpointReplacementNeverRetainRows() = runBlocking {
        for (replace in listOf(false, true)) Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            f.endpoint.entered = CountDownLatch(1); f.endpoint.release = CountDownLatch(1)
            f.offer(); assertTrue(f.endpoint.entered!!.await(5, TimeUnit.SECONDS))
            if (replace) f.frame.connection = OrionRuntimeConnectionStamp(f.endpoint, f.connection, f.endpoint.asBinder(), 2)
            else f.capture.loadPreparing(f.frame, f.endpoint, MonotonicInstant(BootSessionId(3), 100))
            f.endpoint.release!!.countDown(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD)
            if (!replace) f.await(OrionRuntimeMemoryPhase.DURING_LOAD)
            val report = checkNotNull(f.finish().memorySampling)
            assertEquals(0, report.retainedRows); assertNull(f.corePin()); assertEquals(0, f.endpoint.mutations.get())
        }
    }

    @Test fun stuckRPCUsesOneGlobalWorkerAndBoundedQueueWithoutReplacementOrHotpathWait() = runBlocking {
        val fixtures = mutableListOf<Fixture>()
        try {
            val blocked = Fixture(this@OrionRuntimeMemorySamplerAndroidTest).also(fixtures::add)
            blocked.endpoint.entered = CountDownLatch(1); blocked.endpoint.release = CountDownLatch(1)
            blocked.offer(); assertTrue(blocked.endpoint.entered!!.await(5, TimeUnit.SECONDS))
            val worker = OrionRuntimeMemorySamplingWorker.javaClass.getDeclaredField("thread").apply { isAccessible = true }
                .get(OrionRuntimeMemorySamplingWorker)
            repeat(9) { fixtures.add(Fixture(this@OrionRuntimeMemorySamplerAndroidTest).also { it.offer() }) }
            assertSame(worker, OrionRuntimeMemorySamplingWorker.javaClass.getDeclaredField("thread").apply { isAccessible = true }
                .get(OrionRuntimeMemorySamplingWorker))
            assertEquals(1, blocked.endpoint.health.get()); assertEquals(0, fixtures.drop(1).sumOf { it.endpoint.health.get() })
            val queue = OrionRuntimeMemorySamplingWorker.javaClass.getDeclaredField("queue").apply { isAccessible = true }
                .get(OrionRuntimeMemorySamplingWorker) as java.util.ArrayDeque<*>
            assertTrue(queue.size <= 7)
            val report = checkNotNull(blocked.finish().memorySampling)
            assertTrue(report.unfinished); assertEquals(0, report.retainedRows)
            fixtures.drop(1).forEach { it.finish() } // Close does not await or interrupt that same blocked worker.
            blocked.endpoint.release!!.countDown()
            withTimeout(5000) { while (OrionRuntimeMemorySamplingWorker.outstanding()) delay(1) }
            assertEquals(0, blocked.endpoint.mutations.get())
        } finally { fixtures.forEach { it.close() } }
    }

    @Test fun ownedTurnServiceBinderPidBootAndSelectedModelAreRecheckedAfterTheHealthReceipt() = runBlocking {
        for (change in 0..5) Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            f.endpoint.entered = CountDownLatch(1); f.endpoint.release = CountDownLatch(1)
            f.enqueue(OrionRuntimeMemoryPhase.BEFORE_LOAD) { f.offer() }
            assertTrue(f.endpoint.entered!!.await(5, TimeUnit.SECONDS))
            when (change) {
                0 -> field(f.client, "runtimeTurnGeneration", 2L)
                1 -> field(f.client, "remote", Endpoint(f.app.packageName))
                2 -> field(f.client, "runtimeBinder", android.os.Binder())
                3 -> f.frame.pid = f.endpoint.pid + 1
                4 -> f.endpoint.boot = 4
                5 -> {
                    field(f.capture, "model", OrionRuntimeModelReference("b".repeat(64), 1))
                    f.endpoint.state = "LOADED"; f.endpoint.runtime = true; f.endpoint.validated = true
                }
            }
            f.endpoint.release!!.countDown(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD)
            assertEquals(0, checkNotNull(f.finish().memorySampling).retainedRows)
            assertNull(f.corePin()); assertEquals(0, f.endpoint.mutations.get())
        }
    }

    @Test fun unavailableMainReadingConsumesItsAttemptWithoutCallingAiOrInventingZero() = runBlocking {
        Fixture(this@OrionRuntimeMemorySamplerAndroidTest, probeReadings = listOf(null)).use { f ->
            f.offer(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD); repeat(10) { f.offer() }
            val report = checkNotNull(f.finish().memorySampling)
            assertEquals(0, report.retainedRows); assertEquals(0, f.endpoint.health.get()); assertEquals(0, f.endpoint.states.get())
            assertEquals(1, report.attemptedMask); assertEquals(7L, report.droppedAtLeast)
            assertTrue(report.gaps.contains(OrionRuntimeSamplingGap.TELEMETRY_UNAVAILABLE))
        }
    }

    @Test fun workerLockContentionIsNotMisreportedAsAFullQueueAndDoesNotRetry() {
        Fixture(this).use { f ->
            val lock = OrionRuntimeMemorySamplingWorker.javaClass.getDeclaredField("lock").apply { isAccessible = true }
                .get(OrionRuntimeMemorySamplingWorker) as java.util.concurrent.locks.ReentrantLock
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val holder = Thread {
                lock.lock()
                try { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) } finally { lock.unlock() }
            }.apply { start() }
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS)); f.offer()
                val report = checkNotNull(f.finish().memorySampling)
                assertTrue(report.gaps.contains(OrionRuntimeSamplingGap.CONTENDED))
                assertFalse(report.gaps.contains(OrionRuntimeSamplingGap.QUEUE_FULL))
                assertEquals(0, f.endpoint.health.get()); assertEquals(1, report.attemptedMask)
            } finally { release.countDown(); holder.join(5000) }
        }
    }

    @Test fun processDeathOrTerminalBeforeDispatchDoesNotCreateInferenceOrPostDeathSamples() = runBlocking {
        for (death in listOf(false, true)) Fixture(this@OrionRuntimeMemorySamplerAndroidTest).use { f ->
            f.offer(); f.await(OrionRuntimeMemoryPhase.BEFORE_LOAD)
            if (death) f.lifecycle.processDied() else f.lifecycle.failTransport()
            f.capture.terminalHint()
            assertNull(f.ticket(OrionRuntimeMemoryPhase.DURING_INFERENCE))
            assertNull(f.ticket(OrionRuntimeMemoryPhase.AFTER_TERMINAL))
            assertEquals(1, f.endpoint.health.get())
        }
    }

    @Test fun nativeProbeReadsRealMainSelfPssAndActualTotalRamOnly() {
        val clock = finite(); val witness = MonotonicInstant(BootSessionId(3), 100)
        check(clock.bindOriginalWitness(witness, witness, MonotonicDeadlineStatus.ACTIVE))
        val reading = checkNotNull(OrionRuntimeMainMemoryProbe(InstrumentationRegistry.getInstrumentation().targetContext).sample(clock))
        assertTrue(checkNotNull(reading.reading.pssBytes) > 0); assertTrue(checkNotNull(reading.reading.totalMemoryBytes) > 0)
        assertEquals(100L, reading.at.elapsedRealtimeMillis) // Synthetic observation marker; no physical timing claim.
    }
}

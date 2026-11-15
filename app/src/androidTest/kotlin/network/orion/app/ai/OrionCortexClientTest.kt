package network.orion.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.*
import network.orion.core.resources.*
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android Binder/API fixtures only: no native model, process-death or hardware qualification. */
@RunWith(AndroidJUnit4::class)
class OrionCortexClientTest {
    private class Clock : OrionClock {
        @Volatile var mark = MonotonicInstant(BootSessionId(1), 1_000)
        @Volatile var failNextRead = false
        @Volatile var slowNextReadMillis = 0L
        @Volatile var markAfterSlowRead: MonotonicInstant? = null
        @Volatile var blockDiagnostic = false
        @Volatile var diagnosticCallerThread: String? = null
        val diagnosticEntered = CountDownLatch(1)
        val releaseDiagnostic = CountDownLatch(1)
        val diagnosticClaimed = AtomicBoolean()
        val secondClockObserved = CountDownLatch(1)
        override fun monotonicNow(): MonotonicInstant {
            val name = Thread.currentThread().name
            if (name == "orion_test_second") secondClockObserved.countDown()
            if (blockDiagnostic && (name == diagnosticCallerThread || name == "orion_cortex_unload_diagnostic") &&
                diagnosticClaimed.compareAndSet(false, true)) {
                diagnosticEntered.countDown()
                check(releaseDiagnostic.await(10, TimeUnit.SECONDS))
            }
            if (failNextRead) { failNextRead = false; error("Scripted observation clock unavailable") }
            if (slowNextReadMillis > 0) {
                val pause = slowNextReadMillis
                slowNextReadMillis = 0
                Thread.sleep(pause)
                markAfterSlowRead?.let { mark = it }
            }
            return mark
        }
        override fun wallNow() = Instant.EPOCH
    }

    private class Endpoint : IOrionCortex.Stub() {
        override fun getGenerationMetricsCapabilities() = OrionGenerationMetricsWire.status("UNSUPPORTED")
        override fun getGenerationMetrics(query: Bundle?) = OrionGenerationMetricsWire.status("UNSUPPORTED")
        private val identities = OrionIssuedRequestHistory<Unit> { true }
        var protocolMajor = 1
        var protocolMinor = 1
        var historyCapability = true
        var capabilitiesReply: Bundle? = null
        var capabilitiesUnavailable = false
        val capabilityQueries = AtomicInteger()
        val issued = AtomicInteger()
        var holdInferenceIssue = false
        val issueHeld = CountDownLatch(1)
        val releaseIssue = CountDownLatch(1)
        val loads = AtomicInteger()
        val inferences = AtomicInteger()
        val unloads = AtomicInteger()
        val statusQueries = AtomicInteger()
        val healthQueries = AtomicInteger()
        var unloadReply: (() -> Bundle)? = null
        var unloadFailure: Exception? = null
        val cancelled = CopyOnWriteArrayList<String>()
        val inferenceStarted = CompletableDeferred<Unit>()
        val readinessObserved = CompletableDeferred<Unit>()
        var autoComplete = true
        @Volatile var modelReady = true
        @Volatile private var loadedModelId: String? = null
        @Volatile private var unloading = false
        private var loadId = ""
        private var inferenceId = ""
        private var callback: IOrionInferenceCallback? = null
        override fun getProtocolInfo() = OrionCortexWire.reply("OK").apply {
            putInt("major", protocolMajor); putInt("minor", protocolMinor)
        }
        override fun getCapabilities(): Bundle {
            capabilityQueries.incrementAndGet()
            if (capabilitiesUnavailable) throw android.os.RemoteException("Capability extension unavailable.")
            return capabilitiesReply?.let(::Bundle) ?: OrionCortexWire.reply("OK").apply {
                if (historyCapability) putString("capabilities", OrionCortexProtocol.ISSUED_HISTORY.value)
            }
        }
        override fun issueRequestId() = OrionCortexWire.reply("OK").apply {
            check(capabilityQueries.get() == 1) { "Identity issued before capability negotiation." }
            if (issued.incrementAndGet() == 2 && holdInferenceIssue) {
                issueHeld.countDown()
                check(releaseIssue.await(5, TimeUnit.SECONDS))
            }
            putString("requestId", identities.issue())
        }
        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle): Bundle {
            unloading = false
            loads.incrementAndGet(); loadId = specification.getString("requestId")!!
            loadedModelId = specification.getString("modelId")!!
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", loadId) }
        }
        override fun startInference(input: SharedMemory?, specification: Bundle, callback: IOrionInferenceCallback): Bundle {
            inferences.incrementAndGet(); inferenceId = specification.getString("requestId")!!; this.callback = callback
            inferenceStarted.complete(Unit)
            if (autoComplete) complete()
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", inferenceId) }
        }
        fun complete() { callback!!.onChunk(Bundle().apply {
            putInt("schemaVersion", 1); putString("requestId", inferenceId); putLong("sequence", 0)
            putString("delta", "fixture"); putBoolean("isFinal", true); putString("finishReason", "SUCCEEDED")
        }) }
        override fun cancelInference(requestId: String) { cancelled.add(requestId) }
        override fun getRequestStatus(requestId: String) = OrionCortexWire.reply("OK").apply {
            putString("requestId", requestId); putString("state", if (requestId == loadId) "SUCCEEDED" else "RUNNING")
        }
        override fun getStatus(): Bundle {
            statusQueries.incrementAndGet()
            if (unloading) unloadReply?.let { return it() }
            val model = loadedModelId
            if (model != null && !modelReady) readinessObserved.complete(Unit)
            return OrionCortexWire.reply(when { model == null -> "UNLOADED"; modelReady -> "LOADED"; else -> "LOADING" }).apply {
                model?.let { putString("modelId", it) }
            }
        }
        override fun unloadModel() {
            unloads.incrementAndGet(); unloading = true
            unloadFailure?.let { throw it }
            loadedModelId = null
        }
        override fun healthCheck() = OrionCortexWire.reply("OK").also { healthQueries.incrementAndGet() }
    }

    private class BindingContext(base: Context) : ContextWrapper(base) {
        val endpoints = CopyOnWriteArrayList<Endpoint>()
        val connections = CopyOnWriteArrayList<ServiceConnection>()
        val bound = CompletableDeferred<Unit>()
        var autoConnect = true
        var completeInference = true
        var delayModelReadiness = false
        var protocolMajor = 1
        var protocolMinor = 1
        var historyCapability = true
        var capabilitiesReply: Bundle? = null
        var capabilitiesUnavailable = false
        var holdInferenceIssue = false
        var unloadReply: (() -> Bundle)? = null
        var unloadFailure: Exception? = null
        val unbinds = AtomicInteger()
        private val component get() = ComponentName(packageName, "network.orion.ai.service.OrionCortexService")
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val endpoint = Endpoint().also {
                it.autoComplete = completeInference; it.modelReady = !delayModelReadiness
                it.protocolMajor = protocolMajor; it.protocolMinor = protocolMinor; it.historyCapability = historyCapability
                it.capabilitiesReply = capabilitiesReply; it.capabilitiesUnavailable = capabilitiesUnavailable
                it.holdInferenceIssue = holdInferenceIssue
                it.unloadReply = unloadReply; it.unloadFailure = unloadFailure
            }
            endpoints.add(endpoint); connections.add(connection); bound.complete(Unit)
            if (autoConnect) connection.onServiceConnected(component, endpoint)
            return true
        }
        fun connect(index: Int) { connections[index].onServiceConnected(component, endpoints[index]) }
        fun disconnect(index: Int) { connections[index].onServiceDisconnected(component) }
        override fun unbindService(connection: ServiceConnection) { unbinds.incrementAndGet() }
    }

    private class Fixture(val unloadBuffer: OrionCortexUnloadObservationBuffer? = null) : AutoCloseable {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val clock = Clock()
        val file = File.createTempFile("orion_client_fixture_", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(65)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(65)).joinToString("") { "%02x".format(it) }
        val model = ModelRegistryEntity(hash, "orion_test_fixture", file.absolutePath, hash, 1,
            "fixture", "fixture", 128, "READY", 0, 0)
        val profile = InferenceModelProfile(hash, 100)
        val plan = OrionInferenceResourcePlan(profile, "fixture", 1, 128, 8, 0, 10_000, 20_000, 100, 20, 2)
        val availability = CopyOnWriteArrayList<Boolean>()
        var denyResources = false
        var denyRestart = false
        private fun sample() = OrionResourceObservation(clock.mark, profile, 80, 0, true,
            OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE, 100, 200, 100_000,
            emptyList(), 1, 128, 8)
        private val governor = OrionMinimumResourceGovernor(clock, { if (denyResources) null else sample() }, listOf(plan), 100, 100)
        private val guard = OrionCortexRestartGuard(clock, listOf(plan), 100, 100)
        val client = OrionCortexClient(context, clock, { _, _, _, _ -> governor.evaluate() },
            { guard.evaluate(OrionAiExitHistory(clock.mark, if (denyRestart) listOf(
                network.orion.core.exitinfo.ProcessExitSignal(3, Instant.EPOCH,
                    network.orion.core.exitinfo.ProcessExitKind.LOW_MEMORY)) else emptyList())) }, availability::add,
            unloadObservations = unloadBuffer)
        fun request(id: String = client.newRequestId(), modelId: String = hash,
            deadline: MonotonicDeadline = clock.mark.deadlineAfter(30_000)) = OrionInferenceRequest(id, 1, modelId,
            "test", "context", 8, 0.0, OrionInferencePriority.INTERACTIVE, deadline, id)
        suspend fun run(request: OrionInferenceRequest = request(), contextTokens: Int = 128, threads: Int = 1) =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                client.runInference(model, descriptor, request, contextTokens, threads) { }
            }
        override fun close() {
            clock.releaseDiagnostic.countDown()
            client.close(); file.delete()
        }
    }

    private suspend fun awaitDiagnosticsIdle() = withTimeout(10_000) {
        while (OrionCortexUnloadDiagnosticDispatcher.hasPendingSample()) kotlinx.coroutines.delay(1)
    }

    private suspend fun observedBatch(buffer: OrionCortexUnloadObservationBuffer): OrionCortexUnloadObservationBatch = withTimeout(10_000) {
        // pending clears only after publish/offer. Draining earlier can make the
        // nonblocking producer drop its sole observation on this test's drain lock.
        awaitDiagnosticsIdle()
        var batch = buffer.drain()
        while (batch.observations.isEmpty() && batch.samplingBusyDroppedAtLeast == 0L) {
            kotlinx.coroutines.delay(1)
            batch = buffer.drain()
        }
        batch
    }

    @Test fun deniedResourcesNeverDispatchHeavyLoad() = runBlocking {
        Fixture().use { fixture ->
            fixture.denyResources = true
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            assertEquals(0, fixture.context.endpoints.sumOf { it.loads.get() })
            assertEquals(0, fixture.context.endpoints.sumOf { it.inferences.get() })
        }
    }

    @Test fun observedUnloadAndReplayPreserveInferenceAndExactlyExistingCortexCalls() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            val request = fixture.request()
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run(request).terminal)
            val endpoint = fixture.context.endpoints.single()
            val observed = observedBatch(buffer).observations.single()
            assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, observed.outcome)
            assertNotNull(observed.clientScope); assertEquals(1L, observed.connectionEpoch)
            assertNull(observed.knownAiPid) // This fixture supplies no real PID telemetry.
            assertEquals(fixture.clock.mark, observed.deadlineStartedAt)
            assertEquals(1, endpoint.unloads.get()); assertEquals(2, endpoint.statusQueries.get())
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run(request).terminal)
            assertTrue(buffer.drain().observations.isEmpty())
            assertEquals(1, endpoint.unloads.get()); assertEquals(2, endpoint.statusQueries.get())
            assertEquals(1, endpoint.loads.get()); assertEquals(1, endpoint.inferences.get())
            assertEquals(0, endpoint.healthQueries.get())
            assertEquals(1, fixture.context.connections.size)
        }
    }

    @Test fun restartRefusalRecordsMissingEndpointWithoutBindingOrUnloadRpc() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.denyRestart = true
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            assertEquals(OrionCortexUnloadOutcome.NO_ENDPOINT, observedBatch(buffer).observations.single().outcome)
            assertTrue(fixture.context.endpoints.isEmpty())
        }
    }

    @Test fun unloadedReplyAtOrAfterDeadlineOrAcrossInvalidClockNeverConfirmsButStillPreservesSuccess() = runBlocking {
        val cases = listOf(
            MonotonicInstant(BootSessionId(1), 5999) to OrionCortexUnloadOutcome.UNLOADED_OBSERVED,
            MonotonicInstant(BootSessionId(1), 6000) to OrionCortexUnloadOutcome.DEADLINE_EXPIRED,
            MonotonicInstant(BootSessionId(1), 6001) to OrionCortexUnloadOutcome.DEADLINE_EXPIRED,
            MonotonicInstant(BootSessionId(2), 1001) to OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
            MonotonicInstant(null, 1001) to OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
            MonotonicInstant(BootSessionId(1), 999) to OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
        )
        for ((after, expected) in cases) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(buffer).use { fixture ->
                fixture.context.unloadReply = {
                    fixture.clock.mark = after
                    OrionCortexWire.reply("UNLOADED")
                }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
                assertEquals(expected, observedBatch(buffer).observations.single().outcome)
                val endpoint = fixture.context.endpoints.single()
                assertEquals(1, endpoint.unloads.get()); assertEquals(2, endpoint.statusQueries.get())
                assertEquals(0, fixture.context.unbinds.get())
            }
        }
    }

    @Test fun failingExtraObservationClockCannotEnterLostConnectionOrChangeInference() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.context.unloadReply = {
                fixture.clock.failNextRead = true
                OrionCortexWire.reply("UNLOADED")
            }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            val observation = observedBatch(buffer).observations.single()
            assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, observation.outcome)
            assertNull(observation.checkedAt)
            assertEquals(0, fixture.context.unbinds.get())
            assertEquals(listOf(true), fixture.availability.toList())
            assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun laterFunctionalClockFailureRemainsTimeReferenceInvalidAfterUnloadingReply() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.context.unloadReply = {
                fixture.clock.failNextRead = true
                OrionCortexWire.reply("UNLOADING")
            }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, observedBatch(buffer).observations.single().outcome)
            assertEquals(1, fixture.context.unbinds.get()) // Original clock failure still disconnects.
            assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun clockRegressionAcrossReplyWithinUnloadWindowNeverConfirms() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            val replies = AtomicInteger()
            fixture.context.unloadReply = {
                if (replies.incrementAndGet() == 1) {
                    fixture.clock.mark = MonotonicInstant(BootSessionId(1), 5000)
                    OrionCortexWire.reply("UNLOADING")
                } else {
                    fixture.clock.mark = MonotonicInstant(BootSessionId(1), 4000)
                    OrionCortexWire.reply("UNLOADED")
                }
            }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, observedBatch(buffer).observations.single().outcome)
            assertEquals(0, fixture.context.unbinds.get())
            assertEquals(3, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun slowDiagnosticClockAfterSuspendedCleanupCannotTriggerOriginalTimeoutOrDisconnect() = runBlocking {
        for (observing in listOf(false, true)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(if (observing) buffer else null).use { fixture ->
                val replies = AtomicInteger()
                fixture.context.unloadReply = {
                    if (replies.incrementAndGet() == 1) OrionCortexWire.reply("UNLOADING")
                    else {
                        fixture.clock.slowNextReadMillis = 6000
                        fixture.clock.markAfterSlowRead = MonotonicInstant(BootSessionId(1), 6000)
                        OrionCortexWire.reply("UNLOADED")
                    }
                }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(9_000) { fixture.run() }.terminal)
                assertEquals(0, fixture.context.unbinds.get())
                val endpoint = fixture.context.endpoints.single()
                assertEquals(1, endpoint.unloads.get()); assertEquals(3, endpoint.statusQueries.get())
                assertEquals(0, endpoint.healthQueries.get()); assertEquals(listOf(true), fixture.availability.toList())
                if (observing) assertEquals(OrionCortexUnloadOutcome.DEADLINE_EXPIRED, observedBatch(buffer).observations.single().outcome)
                else assertTrue(buffer.drain().observations.isEmpty())
            }
        }
    }

    @Test fun blockedDiagnosticCannotExpireNextRequestOrHoldItsTurn() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            val replyEntered = CountDownLatch(1)
            val releaseReply = CountDownLatch(1)
            val replies = AtomicInteger()
            fixture.context.unloadReply = {
                if (replies.incrementAndGet() == 1) {
                    replyEntered.countDown()
                    check(releaseReply.await(5, TimeUnit.SECONDS))
                    fixture.clock.diagnosticCallerThread = Thread.currentThread().name
                    fixture.clock.blockDiagnostic = true
                }
                OrionCortexWire.reply("UNLOADED")
            }
            Executors.newSingleThreadExecutor { Thread(it, "orion_test_first") }.asCoroutineDispatcher().use { firstThread ->
                Executors.newSingleThreadExecutor { Thread(it, "orion_test_second") }.asCoroutineDispatcher().use { secondThread ->
                    val first = async(firstThread) { fixture.run() }
                    var second: kotlinx.coroutines.Deferred<OrionInferenceStatus>? = null
                    try {
                        assertTrue(replyEntered.await(5, TimeUnit.SECONDS))
                        val next = fixture.request(deadline = fixture.clock.mark.deadlineAfter(500))
                        val queued = async(secondThread) { fixture.run(next) }
                        second = queued
                        assertTrue(fixture.clock.secondClockObserved.await(5, TimeUnit.SECONDS))
                        releaseReply.countDown()
                        assertTrue(fixture.clock.diagnosticEntered.await(5, TimeUnit.SECONDS))
                        // If diagnostics still own the turn, this request cannot complete. Advancing
                        // only the fixture clock then exposes the queued deadline effect explicitly.
                        val timely = withTimeoutOrNull(500) { queued.await() }
                        if (timely == null) fixture.clock.mark = MonotonicInstant(BootSessionId(1), 1500)
                        fixture.clock.releaseDiagnostic.countDown()
                        assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(3_000) { first.await() }.terminal)
                        assertEquals(OrionInferenceTerminal.SUCCEEDED,
                            (timely ?: withTimeout(3_000) { queued.await() }).terminal)
                        assertEquals(2, fixture.context.endpoints.single().loads.get())
                        assertEquals(0, fixture.context.unbinds.get())
                        awaitDiagnosticsIdle()
                        val batch = buffer.drain()
                        assertEquals(OrionCortexUnloadOutcome.EXECUTION_CHANGED, batch.observations.single().outcome)
                        assertEquals(1L, batch.samplingBusyDroppedAtLeast)
                    } finally {
                        releaseReply.countDown(); fixture.clock.releaseDiagnostic.countDown()
                        first.cancelAndJoin(); second?.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test fun blockedDiagnosticCannotChangeOuterCallerTimeout() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.context.unloadReply = {
                fixture.clock.diagnosticCallerThread = Thread.currentThread().name
                fixture.clock.blockDiagnostic = true
                OrionCortexWire.reply("UNLOADED")
            }
            Executors.newSingleThreadExecutor { Thread(it, "orion_test_first") }.asCoroutineDispatcher().use { caller ->
                val result = async(caller) { withTimeoutOrNull(300) { fixture.run() } }
                try {
                    assertTrue(fixture.clock.diagnosticEntered.await(5, TimeUnit.SECONDS))
                    val completedWithoutDiagnostic = withTimeoutOrNull(700) { result.await() }
                    fixture.clock.releaseDiagnostic.countDown()
                    val actual = completedWithoutDiagnostic ?: withTimeout(3_000) { result.await() }
                    // Preserve the pre-release observation: a later successful result must not
                    // hide that the diagnostic held the caller beyond its return deadline.
                    assertNotNull("The caller did not return while the diagnostic was blocked", completedWithoutDiagnostic)
                    assertNotNull("The diagnostic changed the outer timeout", actual)
                    assertEquals(OrionInferenceTerminal.SUCCEEDED, actual?.terminal)
                    assertEquals(0, fixture.context.unbinds.get())
                    assertEquals(1, fixture.context.endpoints.single().unloads.get())
                    awaitDiagnosticsIdle()
                } finally { fixture.clock.releaseDiagnostic.countDown(); result.cancelAndJoin() }
            }
        }
    }

    @Test fun closureOrEndpointLossWhileProviderIsBlockedInvalidatesPendingObservation() = runBlocking {
        for (close in listOf(false, true)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(buffer).use { fixture ->
                fixture.context.unloadReply = {
                    fixture.clock.diagnosticCallerThread = Thread.currentThread().name
                    fixture.clock.blockDiagnostic = true
                    OrionCortexWire.reply("UNLOADED")
                }
                Executors.newSingleThreadExecutor { Thread(it, "orion_test_first") }.asCoroutineDispatcher().use { caller ->
                    val result = async(caller) { fixture.run() }
                    try {
                        assertTrue(fixture.clock.diagnosticEntered.await(5, TimeUnit.SECONDS))
                        assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(1_000) { result.await() }.terminal)
                        if (close) fixture.client.close() else fixture.context.disconnect(0)
                        fixture.clock.releaseDiagnostic.countDown()
                        assertEquals(if (close) OrionCortexUnloadOutcome.CLIENT_CLOSED else OrionCortexUnloadOutcome.ENDPOINT_CHANGED,
                            observedBatch(buffer).observations.single().outcome)
                        assertEquals(1, fixture.context.endpoints.single().unloads.get())
                    } finally { fixture.clock.releaseDiagnostic.countDown(); result.cancelAndJoin() }
                }
            }
        }
    }

    @Test fun queuedExpiryCannotEmitUnloadOrAdvanceActualExecutionIdentity() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.context.completeInference = false
            val first = async(Dispatchers.Default) { fixture.run() }
            Executors.newSingleThreadExecutor { Thread(it, "orion_test_second") }.asCoroutineDispatcher().use { caller ->
                try {
                    withTimeout(3_000) {
                        while (fixture.context.endpoints.isEmpty()) kotlinx.coroutines.delay(1)
                        fixture.context.endpoints.single().inferenceStarted.await()
                    }
                    val request = fixture.request(deadline = fixture.clock.mark.deadlineAfter(500))
                    val queued = async(caller) { fixture.run(request) }
                    assertTrue(fixture.clock.secondClockObserved.await(3, TimeUnit.SECONDS))
                    fixture.clock.mark = MonotonicInstant(BootSessionId(1), 1500)
                    assertEquals(OrionInferenceTerminal.TIMED_OUT, withTimeout(3_000) { queued.await() }.terminal)
                    assertTrue(buffer.drain().observations.isEmpty())
                    val endpoint = fixture.context.endpoints.single()
                    assertEquals(1, endpoint.loads.get()); assertEquals(0, endpoint.unloads.get())
                    endpoint.complete()
                    assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(3_000) { first.await() }.terminal)
                    val event = observedBatch(buffer).observations.single()
                    assertEquals(1L, event.executionEpoch)
                    assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, event.outcome)
                } finally { first.cancelAndJoin() }
            }
        }
    }

    @Test fun absentCollectorKeepsOriginalClockAndCortexCallsAfterUnloadedReply() = runBlocking {
        Fixture().use { fixture ->
            fixture.context.unloadReply = {
                fixture.clock.failNextRead = true
                OrionCortexWire.reply("UNLOADED")
            }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            assertTrue(fixture.clock.failNextRead) // No diagnostic post-reply clock read on default path.
            assertEquals(0, fixture.context.unbinds.get())
            assertEquals(1, fixture.context.endpoints.single().unloads.get())
            assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun closeOrDeathDuringStatusReplyInvalidatesItsEndpointWithoutChangingCompletedTerminal() = runBlocking {
        for (close in listOf(false, true)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(buffer).use { fixture ->
                fixture.context.unloadReply = {
                    if (close) fixture.client.close() else fixture.context.disconnect(0)
                    OrionCortexWire.reply("UNLOADED")
                }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
                assertEquals(if (close) OrionCortexUnloadOutcome.CLIENT_CLOSED else OrionCortexUnloadOutcome.ENDPOINT_CHANGED,
                    observedBatch(buffer).observations.single().outcome)
                assertEquals(1, fixture.context.endpoints.single().unloads.get())
                assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
                assertEquals(1, fixture.context.connections.size)
            }
        }
    }

    @Test fun delayedStatusDoesNotHoldOwnershipMonitorAndCannotConfirmAfterConcurrentDisconnect() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.context.unloadReply = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                OrionCortexWire.reply("UNLOADED")
            }
            val result = async(Dispatchers.Default) { fixture.run() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val disconnected = async(Dispatchers.Default) { fixture.context.disconnect(0) }
                withTimeout(2_000) { disconnected.await() }
            } finally { release.countDown() }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(3_000) { result.await() }.terminal)
            assertEquals(OrionCortexUnloadOutcome.ENDPOINT_CHANGED, observedBatch(buffer).observations.single().outcome)
            assertEquals(1, fixture.context.endpoints.single().unloads.get())
            assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun malformedAndContradictoryUnloadStatusRemainUnconfirmedWithOriginalCleanupEffects() = runBlocking {
        val replies = listOf(
            OrionCortexWire.reply("UNLOADED").apply { putString("modelId", "not-retained") } to false,
            OrionCortexWire.reply("UNLOADED").apply { putString("state", "not-a-getStatus-field") } to false,
            OrionCortexWire.reply("UNLOADED").apply { putInt("status", 1) } to true,
            OrionCortexWire.reply("UNLOADED").apply { putInt("schemaVersion", 2) } to true,
        )
        for ((reply, originalDisconnects) in replies) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(buffer).use { fixture ->
                fixture.context.unloadReply = { reply }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
                assertEquals(OrionCortexUnloadOutcome.STATUS_INVALID, observedBatch(buffer).observations.single().outcome)
                assertEquals(if (originalDisconnects) 1 else 0, fixture.context.unbinds.get())
                assertEquals(1, fixture.context.endpoints.single().unloads.get())
                assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
            }
        }
    }

    @Test fun unloadRpcAndStatusTransportFailuresAreDistinguishedAndDoNotReplaceSuccess() = runBlocking {
        for (requestFails in listOf(false, true)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            Fixture(buffer).use { fixture ->
                if (requestFails) fixture.context.unloadFailure = android.os.RemoteException("Fixture request failed")
                else fixture.context.unloadReply = { throw android.os.RemoteException("Fixture status failed") }
                assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
                assertEquals(if (requestFails) OrionCortexUnloadOutcome.UNLOAD_REQUEST_FAILED else OrionCortexUnloadOutcome.TRANSPORT_FAILURE,
                    observedBatch(buffer).observations.single().outcome)
                assertEquals(1, fixture.context.unbinds.get())
                assertEquals(1, fixture.context.endpoints.single().unloads.get())
            }
        }
    }

    @Test fun existingPollingDeadlineExitAndSaturatedCollectorNeverAddPollsOrChangeResults() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer(1)
        Fixture(buffer).use { fixture ->
            fixture.context.unloadReply = {
                fixture.clock.mark = MonotonicInstant(BootSessionId(1), 6000)
                OrionCortexWire.reply("UNLOADING")
            }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            val observation = observedBatch(buffer).observations.single()
            assertEquals(OrionCortexUnloadOutcome.DEADLINE_EXPIRED, observation.outcome)
            assertEquals(2, fixture.context.endpoints.single().statusQueries.get())
            assertEquals(1, fixture.context.unbinds.get())
        }
        val saturated = OrionCortexUnloadObservationBuffer(1)
        Fixture(saturated).use { fixture ->
            repeat(2) {
                assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
                awaitDiagnosticsIdle()
            }
            awaitDiagnosticsIdle()
            val batch = saturated.drain()
            assertEquals(1, batch.observations.size); assertEquals(1L, batch.droppedAtLeast)
            assertEquals(2, fixture.context.endpoints.single().unloads.get())
            assertEquals(4, fixture.context.endpoints.single().statusQueries.get())
        }
    }

    @Test fun existingCoroutinePollingTimeoutRemainsUnconfirmedWithoutChangingSuccessfulInference() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            // Frozen fixture clock keeps the boot deadline ACTIVE; existing 5.5s coroutine bound must fire.
            fixture.context.unloadReply = { OrionCortexWire.reply("UNLOADING") }
            assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(8_000) { fixture.run() }.terminal)
            assertEquals(OrionCortexUnloadOutcome.POLL_TIMEOUT, observedBatch(buffer).observations.single().outcome)
            val endpoint = fixture.context.endpoints.single()
            assertEquals(1, endpoint.unloads.get()); assertTrue(endpoint.statusQueries.get() >= 2)
            assertEquals(0, endpoint.healthQueries.get())
            assertEquals(1, fixture.context.connections.size); assertEquals(1, fixture.context.unbinds.get())
        }
    }

    @Test fun sustainedSessionsPassBothOldLimitsAndRetiredReplayCannotBindOrInfer() = runBlocking {
        Fixture().use { fixture ->
            val first = fixture.request()
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run(first).terminal)
            repeat(128) { assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal) }
            assertEquals(129, fixture.context.endpoints.sumOf { it.loads.get() })
            assertEquals(129, fixture.context.endpoints.sumOf { it.inferences.get() })
            val bindings = fixture.context.connections.size
            assertTrue(runCatching { fixture.run(first) }.exceptionOrNull() is OrionRetiredRequestException)
            assertEquals(bindings, fixture.context.connections.size)
            assertEquals(129, fixture.context.endpoints.sumOf { it.inferences.get() })
        }
    }

    @Test fun missingNegotiatedExtensionFailsBeforeIssuanceOrHeavyWork() = runBlocking {
        for ((major, minor, capability) in listOf(Triple(2, 1, true), Triple(1, 0, true), Triple(1, 1, false))) Fixture().use { fixture ->
            fixture.context.protocolMajor = major
            fixture.context.protocolMinor = minor
            fixture.context.historyCapability = capability
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            assertEquals(if (major == 1 && minor >= 1) 1 else 0,
                fixture.context.endpoints.sumOf { it.capabilityQueries.get() })
            assertEquals(0, fixture.context.endpoints.sumOf { it.issued.get() })
            assertEquals(0, fixture.context.endpoints.sumOf { it.loads.get() })
            assertEquals(0, fixture.context.endpoints.sumOf { it.inferences.get() })
        }
    }

    @Test fun newClientDiscoversCapabilitySeparatelyBeforeIssuingIds() = runBlocking {
        Fixture().use { fixture ->
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            val endpoint = fixture.context.endpoints.single()
            assertEquals(setOf("schemaVersion", "status", "major", "minor"), endpoint.getProtocolInfo().keySet())
            assertEquals(1, endpoint.capabilityQueries.get())
            assertEquals(2, endpoint.issued.get())
            assertEquals(1, endpoint.loads.get())
            assertEquals(1, endpoint.inferences.get())
        }
    }

    @Test fun invalidOrUnavailableCapabilityReplyNeverIssuesIdsOrStartsHeavyWork() = runBlocking {
        val replies = listOf(
            OrionCortexWire.reply("UNAVAILABLE"),
            OrionCortexWire.reply("OK").apply { putInt("capabilities", 1) },
            OrionCortexWire.reply("OK").apply { putString("unexpected", "field") },
        )
        for (reply in replies + listOf(null)) Fixture().use { fixture ->
            fixture.context.capabilitiesReply = reply
            fixture.context.capabilitiesUnavailable = reply == null
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            val endpoint = fixture.context.endpoints.single()
            assertEquals(1, endpoint.capabilityQueries.get())
            assertEquals(0, endpoint.issued.get())
            assertEquals(0, endpoint.loads.get())
            assertEquals(0, endpoint.inferences.get())
        }
    }

    @Test fun cancellingDuringTransportIdentityIssueNeverStartsInference() = runBlocking {
        Fixture().use { fixture ->
            fixture.context.holdInferenceIssue = true
            val result = async(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) { fixture.context.bound.await() }
            val endpoint = fixture.context.endpoints.single()
            try {
                assertTrue(endpoint.issueHeld.await(3, TimeUnit.SECONDS))
                fixture.client.cancel()
            } finally { endpoint.releaseIssue.countDown() }
            assertEquals(OrionInferenceTerminal.CANCELLED, withTimeout(3_000) { result.await() }.terminal)
            assertEquals(1, endpoint.loads.get())
            assertEquals(0, endpoint.inferences.get())
        }
    }

    @Test fun memoryDeathRestartFencePreventsEvenLightweightAutomaticBinding() = runBlocking {
        Fixture().use { fixture ->
            fixture.denyRestart = true
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            assertTrue(fixture.context.endpoints.isEmpty())
            assertTrue(fixture.context.connections.isEmpty())
        }
    }

    @Test fun expiredOrBootInvalidRequestNeverLoads() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            val expired = fixture.request(deadline = fixture.clock.mark.deadlineAfter(0))
            assertEquals(OrionInferenceTerminal.TIMED_OUT, fixture.run(expired).terminal)
            val invalid = fixture.request(deadline = MonotonicInstant(BootSessionId(2), 1_000).deadlineAfter(1_000))
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run(invalid).terminal)
            assertTrue(fixture.context.endpoints.isEmpty())
            assertTrue(buffer.drain().observations.isEmpty())
        }
    }

    @Test fun identityAndQualifiedConfigurationMismatchesDoNotLoad() = runBlocking {
        Fixture().use { fixture ->
            assertTrue(runCatching { fixture.run(fixture.request(modelId = "orion_other")) }.isFailure)
            assertTrue(runCatching { fixture.run(contextTokens = 129) }.isFailure)
            val rejectedThreads = runCatching { fixture.run(threads = 2) }
            assertTrue(rejectedThreads.isFailure || rejectedThreads.getOrNull()?.terminal == OrionInferenceTerminal.FAILED)
            assertEquals(0, fixture.context.endpoints.sumOf { it.loads.get() })
        }
    }

    @Test fun staleDisconnectCannotTerminateInferenceAfterNewConnection() = runBlocking {
        Fixture().use { fixture ->
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run().terminal)
            fixture.context.disconnect(0)
            fixture.context.completeInference = false
            val result = async(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) {
                while (fixture.context.endpoints.size < 2) kotlinx.coroutines.delay(5)
                fixture.context.endpoints[1].inferenceStarted.await()
            }
            fixture.context.disconnect(0)
            fixture.context.endpoints[1].complete()
            assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(3_000) { result.await() }.terminal)
            assertEquals(true, fixture.availability.last())
        }
    }

    @Test fun closeDuringPendingBindRejectsLateConnectionAndFurtherBinding() = runBlocking {
        Fixture().use { fixture ->
            fixture.context.autoConnect = false
            val result = async(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) { fixture.context.bound.await() }
            fixture.client.close()
            fixture.context.connect(0)
            assertEquals(OrionInferenceTerminal.CANCELLED, withTimeout(3_000) { result.await() }.terminal)
            assertTrue(runCatching { fixture.run() }.isFailure)
            assertEquals(1, fixture.context.endpoints.size)
            assertEquals(0, fixture.context.endpoints[0].loads.get())
        }
    }

    @Test fun cancellationStillUnloadsTheSessionInNonCancellableCleanup() = runBlocking {
        val buffer = OrionCortexUnloadObservationBuffer()
        Fixture(buffer).use { fixture ->
            fixture.context.completeInference = false
            val operation = launch(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) {
                while (fixture.context.endpoints.isEmpty()) kotlinx.coroutines.delay(5)
                fixture.context.endpoints[0].inferenceStarted.await()
            }
            withTimeout(3_000) { operation.cancelAndJoin() }
            assertTrue(fixture.context.endpoints[0].unloads.get() >= 1)
            assertTrue(fixture.context.endpoints[0].cancelled.isNotEmpty())
            assertTrue(operation.isCancelled)
            assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, observedBatch(buffer).observations.single().outcome)
        }
    }

    @Test fun successfulLoadReceiptWaitsForMatchingModelReadinessBeforeInference() = runBlocking {
        Fixture().use { fixture ->
            fixture.context.delayModelReadiness = true
            val result = async(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) { fixture.context.bound.await() }
            val endpoint = fixture.context.endpoints.single()
            withTimeout(3_000) { endpoint.readinessObserved.await() }
            assertEquals(1, endpoint.loads.get())
            assertEquals(0, endpoint.inferences.get())
            assertFalse(result.isCompleted)
            endpoint.modelReady = true
            assertEquals(OrionInferenceTerminal.SUCCEEDED, withTimeout(3_000) { result.await() }.terminal)
            assertEquals(1, endpoint.inferences.get())
        }
    }

    @Test fun duplicateIdentityReturnsOriginalTerminalWithoutLoadingAgain() = runBlocking {
        Fixture().use { fixture ->
            val request = fixture.request()
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run(request).terminal)
            assertEquals(OrionInferenceTerminal.SUCCEEDED, fixture.run(request).terminal)
            assertEquals(1, fixture.context.endpoints.sumOf { it.loads.get() })
            val conflict = OrionInferenceRequest(request.requestId, 1, fixture.hash, "changed", "context", 8,
                0.0, OrionInferencePriority.INTERACTIVE, request.deadline, request.correlationId)
            assertTrue(runCatching { fixture.run(conflict) }.isFailure)
            assertEquals(1, fixture.context.endpoints.sumOf { it.loads.get() })
        }
    }
}

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.core.resources.*
import network.orion.core.time.*
import network.orion.data.database.ModelRegistryEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local Binder/API fixtures only; no actual AI/native work, model or hardware qualification. */
@RunWith(AndroidJUnit4::class)
class OrionCortexGenerationMetricsClientTest {
    private class Clock : OrionClock {
        val mark = MonotonicInstant(BootSessionId(1), 1000)
        var reads = 0
        override fun monotonicNow() = mark.also { reads++ }
        override fun wallNow() = Instant.EPOCH
    }

    private class Endpoint : IOrionCortex.Stub() {
        val ids = OrionIssuedRequestHistory<Unit> { true }
        var minor = 2
        var mandatoryHistory = true
        val legacyCapabilities = AtomicInteger()
        val loads = AtomicInteger(); val inferences = AtomicInteger(); val unloads = AtomicInteger()
        val health = AtomicInteger(); val discovery = AtomicInteger(); val queries = AtomicInteger()
        var modelId: String? = null
        var loadId = ""
        var lastTransport = ""
        var beforeQuery: (() -> Unit)? = null
        var capabilities: () -> Bundle = { OrionGenerationMetricsWire.capabilities(OrionGenerationMetric.ALL_MASK) }
        var snapshot: (String) -> Bundle = { OrionGenerationMetricsWire.snapshot(value(it)) }
        val pid = android.os.Process.myPid() + 1000 // Mechanical identity, never an observed AI PID.
        var boot = 1
        var sampledElapsed = 1000L
        override fun getProtocolInfo() = OrionCortexWire.reply("OK").apply { putInt("major", 1); putInt("minor", minor) }
        override fun getCapabilities() = OrionCortexWire.reply("OK").apply {
            legacyCapabilities.incrementAndGet()
            if (mandatoryHistory) putString("capabilities", OrionCortexProtocol.ISSUED_HISTORY.value)
        }
        override fun issueRequestId() = OrionCortexWire.reply("OK").apply {
            check(legacyCapabilities.get() == 1)
            putString("requestId", ids.issue())
        }
        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle): Bundle {
            loads.incrementAndGet(); loadId = specification.getString("requestId")!!
            modelId = specification.getString("modelId")
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", loadId) }
        }
        override fun startInference(input: SharedMemory?, specification: Bundle, callback: IOrionInferenceCallback): Bundle {
            inferences.incrementAndGet(); lastTransport = specification.getString("requestId")!!
            callback.onChunk(Bundle().apply {
                putInt("schemaVersion", 1); putString("requestId", lastTransport); putLong("sequence", 0)
                putString("delta", "fixture"); putBoolean("isFinal", true); putString("finishReason", "SUCCEEDED")
            })
            return OrionCortexWire.reply("ACCEPTED").apply { putString("requestId", lastTransport) }
        }
        override fun getRequestStatus(requestId: String) = OrionCortexWire.reply("OK").apply {
            putString("requestId", requestId); putString("state", "SUCCEEDED")
        }
        override fun getStatus() = OrionCortexWire.reply(if (modelId == null) "UNLOADED" else "LOADED").apply { modelId?.let { putString("modelId", it) } }
        override fun cancelInference(requestId: String) = Unit
        override fun unloadModel() { unloads.incrementAndGet(); modelId = null }
        override fun healthCheck() = OrionCortexWire.reply("OK").apply {
            health.incrementAndGet(); putString("processName", "network.orion:ai"); putInt("pid", pid)
            putInt("bootCount", boot); putLong("sampledElapsed", sampledElapsed); putLong("totalPssBytes", 100)
            putBoolean("nativeIncluded", true); putBoolean("nativeRuntimeLoaded", false)
            putBoolean("nativeValidationCompleted", false)
        }
        override fun getGenerationMetricsCapabilities(): Bundle {
            discovery.incrementAndGet(); check(legacyCapabilities.get() == 1)
            return capabilities()
        }
        override fun getGenerationMetrics(query: Bundle): Bundle {
            queries.incrementAndGet()
            val id = OrionGenerationMetricsWire.queryId(query)
            beforeQuery?.invoke()
            return snapshot(id)
        }
        fun value(id: String, reportedPid: Int = pid, knownBoot: Int? = null) = OrionGenerationMetricsSnapshot(
            id, reportedPid, 1, 1, OrionInferenceTerminal.SUCCEEDED, OrionGenerationNativeState.JNI_RETURNED,
            OrionGenerationNativeFinish.SUCCEEDED, OrionGenerationSlotState.RELEASED,
            OrionGenerationSnapshotState.FINAL, OrionGenerationPartialWork.NONE_OBSERVED, 0,
            if (knownBoot == null) OrionGenerationTimeState.UNAVAILABLE else OrionGenerationTimeState.KNOWN_BOOT,
            knownBoot, if (knownBoot == null) null else 1000L,
            metrics = mapOf(OrionGenerationMetric.PROMPT_TOKENS to 2L, OrionGenerationMetric.SAMPLED_TOKENS to 1L))
    }

    private class Binding(base: Context) : ContextWrapper(base) {
        val endpoint = Endpoint()
        val binds = AtomicInteger(); val unbinds = AtomicInteger()
        lateinit var owner: ServiceConnection
        private val component get() = ComponentName(packageName, "network.orion.ai.service.OrionCortexService")
        override fun getApplicationContext() = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds.incrementAndGet(); owner = connection; connection.onServiceConnected(component, endpoint); return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds.incrementAndGet() }
        fun disconnect() { owner.onServiceDisconnected(component) }
    }

    private class Fixture : AutoCloseable {
        val context = Binding(InstrumentationRegistry.getInstrumentation().targetContext)
        val clock = Clock()
        val file = File.createTempFile("orion_metrics_client_", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(65)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(65)).joinToString("") { "%02x".format(it) }
        val model = ModelRegistryEntity(hash, "fixture", file.path, hash, 1, "fixture", "fixture", 128, "READY", 0, 0)
        val profile = InferenceModelProfile(hash, 100)
        val plan = OrionInferenceResourcePlan(profile, "MECHANICAL_TEST_NOT_QUALIFIED", 1, 128, 8, 0, 10_000, 20_000, 100, 20, 2)
        private val sample = OrionResourceObservation(clock.mark, profile, 80, 0, true, OrionProcessImportance.FOREGROUND,
            false, OrionMemoryPressure.NONE, 100, 200, 100_000, emptyList(), 1, 128, 8)
        private val governor = OrionMinimumResourceGovernor(clock, { sample }, listOf(plan), 100, 100)
        private val guard = OrionCortexRestartGuard(clock, listOf(plan), 100, 100)
        var observeDuringInference = true
        var beforeResource: (() -> Unit)? = null
        private var evaluations = 0
        lateinit var client: OrionCortexClient
            private set
        init {
            client = OrionCortexClient(context, clock, { _, _, _, _ ->
                beforeResource?.invoke()
                if (++evaluations == 1 || observeDuringInference) client.currentMemoryTelemetry()
                governor.evaluate()
            }, { guard.evaluate(OrionAiExitHistory(clock.mark, emptyList())) })
        }
        fun request(id: String = client.newRequestId()) = OrionInferenceRequest(id, 1, hash, "fixture", "context", 8,
            0.0, OrionInferencePriority.INTERACTIVE, clock.mark.deadlineAfter(30_000), id)
        suspend fun run(request: OrionInferenceRequest) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            client.runInference(model, it, request, 128, 1) { }
        }
        override fun close() { client.close(); file.delete() }
    }

    @Test fun manualReadIsLazyOneDiscoveryOneQueryAndNoExtraClockHealthOrBinding() = runBlocking {
        Fixture().use { f ->
            val request = f.request()
            assertNull(f.client.generationMetrics(request.requestId)); assertEquals(0, f.context.binds.get())
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal)
            val e = f.context.endpoint; val clocks = f.clock.reads; val health = e.health.get()
            assertEquals(0, e.discovery.get()); assertEquals(0, e.queries.get())
            val result = checkNotNull(f.client.generationMetrics(request.requestId))
            assertEquals(e.lastTransport, result.requestId); assertNotEquals(request.requestId, result.requestId)
            assertEquals(OrionGenerationTimeState.UNAVAILABLE, result.timeState)
            assertNotNull(f.client.generationMetrics(request.requestId))
            assertEquals(1, e.discovery.get()); assertEquals(2, e.queries.get())
            assertEquals(clocks, f.clock.reads); assertEquals(health, e.health.get())
            assertEquals(1, f.context.binds.get()); assertEquals(0, f.context.unbinds.get())
            assertEquals(1, e.loads.get()); assertEquals(1, e.inferences.get()); assertEquals(1, e.unloads.get())
        }
    }

    @Test fun legacyOrMandatoryRefusalCannotProbeOptionalMethods() = runBlocking {
        for (mandatory in listOf(true, false)) Fixture().use { f ->
            f.context.endpoint.minor = if (mandatory) 1 else 2
            f.context.endpoint.mandatoryHistory = mandatory
            val request = f.request(); val result = f.run(request)
            assertEquals(if (mandatory) OrionInferenceTerminal.SUCCEEDED else OrionInferenceTerminal.FAILED, result.terminal)
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(0, f.context.endpoint.discovery.get()); assertEquals(0, f.context.endpoint.queries.get())
        }
    }

    @Test fun oldLoadPinWithoutNewResourceWitnessDoesNotTriggerHealthOrDiscovery() = runBlocking {
        Fixture().use { f ->
            f.observeDuringInference = false
            val request = f.request(); assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal)
            val health = f.context.endpoint.health.get()
            assertEquals(1, health)
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(health, f.context.endpoint.health.get()); assertEquals(0, f.context.endpoint.discovery.get())
        }
    }

    @Test fun wrongBootOrStaleReceiptRemainsUnavailableWithoutChangingInference() = runBlocking {
        for (boot in listOf(true, false)) Fixture().use { f ->
            f.beforeResource = { if (f.context.endpoint.health.get() > 0) {
                if (boot) f.context.endpoint.boot = 2 else f.context.endpoint.sampledElapsed = 999
            } }
            val request = f.request(); assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal)
            assertNull(f.client.generationMetrics(request.requestId)); assertEquals(0, f.context.endpoint.discovery.get())
        }
    }

    @Test fun originalResourceErrorIsNeverSwallowedByOptionalWitnessCollection() = runBlocking {
        Fixture().use { f ->
            val original = AssertionError("Original resource callback failure")
            f.beforeResource = { if (f.context.endpoint.health.get() > 0) throw original }
            val request = f.request()
            val seen = try { f.run(request); null } catch (failure: Throwable) { failure }
            assertSame(original, seen)
            assertEquals(0, f.context.endpoint.inferences.get())
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(0, f.context.endpoint.discovery.get())
        }
    }

    @Test fun unsupportedMalformedOrThrowingDiscoveryIsCachedAndCannotLoseConnection() = runBlocking {
        val replies: List<() -> Bundle> = listOf(
            { OrionGenerationMetricsWire.status("UNSUPPORTED") },
            { OrionCortexWire.reply("OK").apply { putString("secret", "DO_NOT_RETAIN") } },
            { throw android.os.RemoteException("Optional method unavailable") },
            { throw AssertionError("Scripted optional discovery Error") },
        )
        for (reply in replies) Fixture().use { f ->
            f.context.endpoint.capabilities = reply
            val request = f.request(); assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal)
            repeat(2) { assertNull(f.client.generationMetrics(request.requestId)) }
            assertEquals(1, f.context.endpoint.discovery.get()); assertEquals(0, f.context.endpoint.queries.get())
            assertEquals(1, f.context.binds.get()); assertEquals(0, f.context.unbinds.get())
            assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal) // Local replay, no RPC.
            assertEquals(1, f.context.endpoint.inferences.get())
        }
    }

    @Test fun wrongReportedPidBootRequestAndMalformedOrUnavailableRepliesReturnNull() = runBlocking {
        Fixture().use { f ->
            val request = f.request(); assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(request).terminal)
            val e = f.context.endpoint
            val replies: List<(String) -> Bundle> = listOf(
                { OrionGenerationMetricsWire.snapshot(e.value(it, reportedPid = e.pid + 1)) },
                { OrionGenerationMetricsWire.snapshot(e.value(it, knownBoot = 2)) },
                { OrionGenerationMetricsWire.snapshot(e.value(e.ids.issue())) },
                { OrionGenerationMetricsWire.status("RETIRED") },
                { OrionCortexWire.reply("SNAPSHOT") },
                { throw android.os.RemoteException("Optional query unavailable") },
                { throw AssertionError("Scripted optional query Error") },
            )
            for (reply in replies) { e.snapshot = reply; assertNull(f.client.generationMetrics(request.requestId)) }
            assertEquals(replies.size, e.queries.get()); assertEquals(1, e.discovery.get())
            assertEquals(0, f.context.unbinds.get()); assertEquals(1, f.context.binds.get())
        }
    }

    @Test fun replacedMappingAndOldLogicalReplayNeverRetargetQuery() = runBlocking {
        Fixture().use { f ->
            val first = f.request(); val second = f.request()
            f.run(first); val oldTransport = f.context.endpoint.lastTransport
            f.run(second); val newTransport = f.context.endpoint.lastTransport
            assertNotEquals(oldTransport, newTransport)
            assertNull(f.client.generationMetrics(first.requestId))
            assertEquals(newTransport, f.client.generationMetrics(second.requestId)?.requestId)
            f.run(first) // Deduplicated historical result must not replace the most recent mapping.
            assertNull(f.client.generationMetrics(first.requestId))
            assertEquals(newTransport, f.client.generationMetrics(second.requestId)?.requestId)
            assertEquals(2, f.context.endpoint.inferences.get())
        }
    }

    @Test fun mappingChangesDuringQueryDiscardOldReplyWithoutAnotherDiscovery() = runBlocking {
        Fixture().use { f ->
            val first = f.request(); val second = f.request(); f.run(first)
            val endpoint = f.context.endpoint
            endpoint.beforeQuery = {
                endpoint.beforeQuery = null
                runBlocking { assertEquals(OrionInferenceTerminal.SUCCEEDED, f.run(second).terminal) }
            }
            assertNull(f.client.generationMetrics(first.requestId))
            assertEquals(endpoint.lastTransport, f.client.generationMetrics(second.requestId)?.requestId)
            assertEquals(1, endpoint.discovery.get()); assertEquals(2, endpoint.queries.get())
            assertEquals(1, f.context.binds.get())
        }
    }

    @Test fun discoveryDoesNotHoldClientMonitorAndCloseDiscardsItsReply() = runBlocking {
        Fixture().use { f ->
            val request = f.request(); f.run(request)
            f.context.endpoint.capabilities = {
                val closed = CountDownLatch(1)
                val worker = Thread { f.client.close(); closed.countDown() }.apply { isDaemon = true; start() }
                assertTrue("Discovery held client monitor", closed.await(1, TimeUnit.SECONDS))
                worker.join(1000)
                OrionGenerationMetricsWire.capabilities(OrionGenerationMetric.ALL_MASK)
            }
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(1, f.context.endpoint.discovery.get()); assertEquals(0, f.context.endpoint.queries.get())
            assertEquals(1, f.context.binds.get())
        }
    }

    @Test fun disconnectDuringQueryDiscardsReplyWithoutRebinding() = runBlocking {
        Fixture().use { f ->
            val request = f.request(); f.run(request)
            f.context.endpoint.beforeQuery = { f.context.disconnect() }
            assertNull(f.client.generationMetrics(request.requestId))
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(1, f.context.endpoint.queries.get()); assertEquals(1, f.context.binds.get())
        }
    }

    @Test fun queryDoesNotHoldClientMonitorAndCloseDiscardsReply() = runBlocking {
        Fixture().use { f ->
            val request = f.request(); f.run(request)
            f.context.endpoint.beforeQuery = {
                val closed = CountDownLatch(1)
                val worker = Thread { f.client.close(); closed.countDown() }.apply { isDaemon = true; start() }
                assertTrue("Optional RPC held client monitor", closed.await(1, TimeUnit.SECONDS))
                worker.join(1000)
            }
            assertNull(f.client.generationMetrics(request.requestId))
            assertEquals(1, f.context.endpoint.queries.get()); assertEquals(1, f.context.binds.get())
        }
    }
}

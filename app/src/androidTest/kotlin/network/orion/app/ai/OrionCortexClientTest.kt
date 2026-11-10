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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        var mark = MonotonicInstant(BootSessionId(1), 1_000)
        override fun monotonicNow() = mark
        override fun wallNow() = Instant.EPOCH
    }

    private class Endpoint : IOrionCortex.Stub() {
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
        val cancelled = CopyOnWriteArrayList<String>()
        val inferenceStarted = CompletableDeferred<Unit>()
        val readinessObserved = CompletableDeferred<Unit>()
        var autoComplete = true
        @Volatile var modelReady = true
        @Volatile private var loadedModelId: String? = null
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
            val model = loadedModelId
            if (model != null && !modelReady) readinessObserved.complete(Unit)
            return OrionCortexWire.reply(when { model == null -> "UNLOADED"; modelReady -> "LOADED"; else -> "LOADING" }).apply {
                model?.let { putString("modelId", it) }
            }
        }
        override fun unloadModel() { unloads.incrementAndGet(); loadedModelId = null }
        override fun healthCheck() = OrionCortexWire.reply("OK")
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
        val unbinds = AtomicInteger()
        private val component get() = ComponentName(packageName, "network.orion.ai.service.OrionCortexService")
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val endpoint = Endpoint().also {
                it.autoComplete = completeInference; it.modelReady = !delayModelReadiness
                it.protocolMajor = protocolMajor; it.protocolMinor = protocolMinor; it.historyCapability = historyCapability
                it.capabilitiesReply = capabilitiesReply; it.capabilitiesUnavailable = capabilitiesUnavailable
                it.holdInferenceIssue = holdInferenceIssue
            }
            endpoints.add(endpoint); connections.add(connection); bound.complete(Unit)
            if (autoConnect) connection.onServiceConnected(component, endpoint)
            return true
        }
        fun connect(index: Int) { connections[index].onServiceConnected(component, endpoints[index]) }
        fun disconnect(index: Int) { connections[index].onServiceDisconnected(component) }
        override fun unbindService(connection: ServiceConnection) { unbinds.incrementAndGet() }
    }

    private class Fixture : AutoCloseable {
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
                    network.orion.core.exitinfo.ProcessExitKind.LOW_MEMORY)) else emptyList())) }, availability::add)
        fun request(id: String = client.newRequestId(), modelId: String = hash,
            deadline: MonotonicDeadline = clock.mark.deadlineAfter(30_000)) = OrionInferenceRequest(id, 1, modelId,
            "test", "context", 8, 0.0, OrionInferencePriority.INTERACTIVE, deadline, id)
        suspend fun run(request: OrionInferenceRequest = request(), contextTokens: Int = 128, threads: Int = 1) =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                client.runInference(model, descriptor, request, contextTokens, threads) { }
            }
        override fun close() { client.close(); file.delete() }
    }

    @Test fun deniedResourcesNeverDispatchHeavyLoad() = runBlocking {
        Fixture().use { fixture ->
            fixture.denyResources = true
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run().terminal)
            assertEquals(0, fixture.context.endpoints.sumOf { it.loads.get() })
            assertEquals(0, fixture.context.endpoints.sumOf { it.inferences.get() })
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
        Fixture().use { fixture ->
            val expired = fixture.request(deadline = fixture.clock.mark.deadlineAfter(0))
            assertEquals(OrionInferenceTerminal.TIMED_OUT, fixture.run(expired).terminal)
            val invalid = fixture.request(deadline = MonotonicInstant(BootSessionId(2), 1_000).deadlineAfter(1_000))
            assertEquals(OrionInferenceTerminal.FAILED, fixture.run(invalid).terminal)
            assertTrue(fixture.context.endpoints.isEmpty())
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
        Fixture().use { fixture ->
            fixture.context.completeInference = false
            val operation = launch(Dispatchers.Default) { fixture.run() }
            withTimeout(3_000) {
                while (fixture.context.endpoints.isEmpty()) kotlinx.coroutines.delay(5)
                fixture.context.endpoints[0].inferenceStarted.await()
            }
            withTimeout(3_000) { operation.cancelAndJoin() }
            assertTrue(fixture.context.endpoints[0].unloads.get() >= 1)
            assertTrue(fixture.context.endpoints[0].cancelled.isNotEmpty())
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

package network.orion.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.IOrionInferenceCallback
import network.orion.ai.api.OrionCortexWire
import network.orion.ai.api.OrionCortexProtocol
import network.orion.ai.api.OrionIssuedRequestHistory
import network.orion.core.time.AndroidOrionClock
import network.orion.core.time.MonotonicDeadlineStatus
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Real Binder boundary without loading a model or modifying business state. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OrionCortexBoundaryTest {
    @Test fun a_legacyClientHandshakeAndOriginalTransactionsRemainCompatible() {
        // Frozen protocol-1.0 transaction numbers and envelope: no new proxy or
        // capability knowledge is allowed to make this legacy client compatible.
        fun call(offset: Int, id: String? = null): Bundle {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("network.orion.ai.api.IOrionCortex")
                if (id != null) data.writeString(id)
                assertTrue(remote.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION + offset, data, reply, 0))
                reply.readException()
                return checkNotNull(reply.readTypedObject(Bundle.CREATOR))
            } finally { data.recycle(); reply.recycle() }
        }
        val protocol = call(0)
        OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
        assertEquals("OK", OrionCortexWire.text(protocol, "status"))
        assertEquals(1, OrionCortexWire.int(protocol, "major"))
        assertTrue(OrionCortexWire.int(protocol, "minor") >= 0)
        val id = "orion_legacy_handshake_${UUID.randomUUID()}"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("network.orion.ai.api.IOrionCortex"); data.writeString(id)
            assertTrue(remote.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION + 3, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        val status = call(4, id)
        OrionCortexWire.requireEnvelope(status, setOf("schemaVersion", "status", "requestId", "state"))
        assertEquals("OK", OrionCortexWire.text(status, "status"))
        assertEquals(id, OrionCortexWire.text(status, "requestId"))
        assertEquals("CANCELLED", OrionCortexWire.text(status, "state"))
    }

    @Test fun a_privateManifestAndVersionedProtocol() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported)
        assertEquals("${app.packageName}:ai", info.processName)
        val protocol = remote.getProtocolInfo()
        OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
        assertEquals(1, protocol.getInt("major")); assertEquals(1, protocol.getInt("minor"))
        assertEquals("OK", protocol.getString("status"))
        val capabilities = remote.getCapabilities()
        OrionCortexWire.requireEnvelope(capabilities, setOf("schemaVersion", "status", "capabilities"))
        assertEquals("OK", OrionCortexWire.text(capabilities, "status"))
        assertEquals(OrionCortexProtocol.ISSUED_HISTORY.value, OrionCortexWire.text(capabilities, "capabilities"))
        assertEquals("UNLOADED", remote.getStatus().getString("status"))
        assertEquals("OK", remote.healthCheck().getString("status"))
    }

    @Test fun b_invalidSchemaWritableAndPipeDescriptorsFailBeforeNativeLoad() {
        val model = File.createTempFile("orion_cortex_boundary_", ".bin", app.cacheDir)
        try {
            model.writeBytes(ByteArray(256))
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use {
                assertEquals("INVALID_REQUEST", remote.loadModel(it, loadSpec().apply { putInt("schemaVersion", 2) }).getString("status"))
            }
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertEquals("INVALID_REQUEST", remote.loadModel(it, loadSpec()).getString("status"))
            }
            val pipe = ParcelFileDescriptor.createPipe()
            try { assertEquals("INVALID_REQUEST", remote.loadModel(pipe[0], loadSpec()).getString("status")) }
            finally { pipe.forEach { it.close() } }
            assertEquals("UNLOADED", remote.getStatus().getString("status"))
        } finally { model.delete() }
    }

    @Test fun c_largeSharedMemoryStaysOffInlineBinderAndRequiresLoadedIdentity() {
        val callbacks = AtomicInteger()
        shared(300 * 1024).use { memory ->
            val result = remote.startInference(memory, inferSpec(UUID.randomUUID().toString(), memory.size), callback(callbacks))
            assertEquals("MODEL_MISMATCH", result.getString("status"))
        }
        assertEquals(0, callbacks.get())
        assertEquals("UNLOADED", remote.getStatus().getString("status"))
    }

    @Test fun d_cancelBeforeStartRetainsTerminalStateForDuplicateSubmission() {
        remote.cancelInference(cancelledId)
        remote.cancelInference(cancelledId)
        assertEquals("CANCELLED", remote.getRequestStatus(cancelledId).getString("state"))
        shared(8).use { memory ->
            val result = remote.startInference(memory, inferSpec(cancelledId, memory.size), callback(AtomicInteger()))
            assertEquals("OK", result.getString("status")); assertEquals("CANCELLED", result.getString("state"))
        }
    }

    @Test fun d_changedPayloadsCannotReplaceCancelledLoadOrInferenceBindings() {
        fun issue() = remote.issueRequestId().getString("requestId")!!
        val model = File.createTempFile("orion_identity_", ".bin", app.cacheDir)
        model.writeBytes(ByteArray(256))
        try {
            // Cancellation alone has no payload; its first valid submission binds it without execution.
            val load = loadSpec().apply { putString("requestId", issue()) }
            val loadId = load.getString("requestId")!!
            remote.cancelInference(loadId)
            fun submitLoad(spec: Bundle) = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use {
                remote.loadModel(it, spec)
            }
            assertEquals("CANCELLED", submitLoad(load).getString("state"))
            assertEquals("CANCELLED", submitLoad(Bundle(load)).getString("state"))
            val changes = listOf<Bundle.() -> Unit>(
                { putInt("threads", 2) }, { putInt("contextTokens", 256) },
                { putString("modelId", "b".repeat(64)); putString("sha256", "b".repeat(64)) },
                { putLong("durationMillis", 29_999) },
                { putLong("startedElapsed", getLong("startedElapsed") - 1) },
                { putInt("bootCount", getInt("bootCount") + 1) })
            for (change in changes) assertEquals("INVALID_REQUEST", submitLoad(Bundle(load).apply(change)).getString("status"))
            shared(8).use { input ->
                assertEquals("INVALID_REQUEST", remote.startInference(input, inferSpec(loadId, 8),
                    callback(AtomicInteger())).getString("status"))
            }

            val id = issue()
            remote.cancelInference(id)
            val infer = inferSpec(id, 8)
            val count = AtomicInteger()
            fun submit(spec: Bundle, fill: Byte = 65): Bundle = shared(8, fill).use {
                remote.startInference(it, spec, callback(count))
            }
            assertEquals("CANCELLED", submit(infer).getString("state"))
            assertEquals("CANCELLED", submit(Bundle(infer)).getString("state"))
            assertEquals("INVALID_REQUEST", submit(Bundle(infer), 66).getString("status"))
            for (change in listOf<Bundle.() -> Unit>(
                { putInt("maxTokens", 9) }, { putFloat("temperature", 1f) },
                { putString("modelId", "different-model") }, { putLong("durationMillis", 29_999) }))
                assertEquals("INVALID_REQUEST", submit(Bundle(infer).apply(change)).getString("status"))
            assertEquals("CANCELLED", remote.getRequestStatus(id).getString("state"))
            assertEquals(0, count.get())
            assertEquals("UNLOADED", remote.getStatus().getString("status"))
        } finally { model.delete() }
    }

    @Test fun d_retainedFailedLoadRejectsChangedPayloadAndCrossOperationReplay() {
        val file = File.createTempFile("orion_failed_load_", ".bin", app.cacheDir)
        file.writeBytes(ByteArray(256))
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val id = remote.issueRequestId().getString("requestId")!!
            val spec = loadSpec().apply {
                putString("requestId", id); putString("modelId", hash); putString("sha256", hash)
            }
            fun load(value: Bundle) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                remote.loadModel(it, value)
            }
            assertEquals("ACCEPTED", load(spec).getString("status"))
            val clock = AndroidOrionClock(app.contentResolver)
            val deadline = clock.monotonicNow().deadlineAfter(10_000)
            while (remote.getRequestStatus(id).getString("state") == "RUNNING" &&
                deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE) Thread.sleep(5)
            assertEquals("FAILED", remote.getRequestStatus(id).getString("state")) // Invalid GGUF, not real inference.
            assertEquals("FAILED", load(Bundle(spec)).getString("state"))
            assertEquals("INVALID_REQUEST", load(Bundle(spec).apply { putInt("threads", 2) }).getString("status"))
            val count = AtomicInteger()
            shared(8).use {
                assertEquals("INVALID_REQUEST", remote.startInference(it, inferSpec(id, 8), callback(count)).getString("status"))
            }
            remote.cancelInference(id)
            assertEquals("FAILED", remote.getRequestStatus(id).getString("state"))
            assertEquals(0, count.get())
            while (remote.getStatus().getString("status") != "UNLOADED" &&
                deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE) Thread.sleep(5)
            assertEquals("UNLOADED", remote.getStatus().getString("status"))
        } finally { file.delete() }
    }

    @Test fun e_issuedHistoryContinuesPastCapacityAndRetiredRequestsNeverExecute() {
        fun issue() = remote.issueRequestId().also { assertEquals("OK", it.getString("status")) }.getString("requestId")!!
        val first = issue()
        remote.cancelInference(first)
        repeat(256) {
            val id = issue()
            remote.cancelInference(id)
            assertEquals("CANCELLED", remote.getRequestStatus(id).getString("state"))
        }
        assertEquals("DUPLICATE_REQUEST", remote.getRequestStatus(first).getString("status"))
        remote.cancelInference(first) // Retired cancellation is idempotent.
        val callbacks = AtomicInteger()
        shared(8).use { memory ->
            assertEquals("DUPLICATE_REQUEST", remote.startInference(memory,
                inferSpec(first, memory.size), callback(callbacks)).getString("status"))
            val foreign = OrionIssuedRequestHistory<Unit> { true }.issue()
            assertEquals("INVALID_REQUEST", remote.startInference(memory,
                inferSpec(foreign, memory.size), callback(callbacks)).getString("status"))
        }
        assertEquals(0, callbacks.get())
        assertEquals("OK", remote.healthCheck().getString("status"))
        assertEquals("UNLOADED", remote.getStatus().getString("status"))
    }

    @Test fun f_legacyCapacityRejectsNewRequestsWithoutEvictingExistingTerminals() {
        var rejected = 0
        repeat(65) { index ->
            try { remote.cancelInference("orion_capacity_$index") }
            catch (_: RuntimeException) { rejected++ }
        }
        assertTrue(rejected > 0)
        assertEquals("DEGRADED", remote.healthCheck().getString("status"))
        assertEquals("CANCELLED", remote.getRequestStatus(cancelledId).getString("state"))
        shared(8).use { memory ->
            val result = remote.startInference(memory, inferSpec(UUID.randomUUID().toString(), memory.size), callback(AtomicInteger()))
            assertEquals("CAPACITY_REACHED", result.getString("status"))
        }
        assertEquals("CANCELLED", remote.getRequestStatus(cancelledId).getString("state"))
    }

    private fun timed(): Bundle {
        val mark = AndroidOrionClock(app.contentResolver).monotonicNow()
        return OrionCortexWire.reply("unused").apply {
            remove("status"); putInt("bootCount", requireNotNull(mark.bootSessionId).bootCount)
            putLong("startedElapsed", mark.elapsedRealtimeMillis); putLong("durationMillis", 30_000)
        }
    }

    private fun loadSpec() = timed().apply {
        putString("requestId", UUID.randomUUID().toString()); putString("modelId", "a".repeat(64))
        putString("sha256", "a".repeat(64)); putLong("sizeBytes", 256)
        putInt("contextTokens", 128); putInt("threads", 1)
    }

    private fun inferSpec(id: String, size: Int) = timed().apply {
        putString("requestId", id); putString("modelId", "orion_boundary_absent")
        putLong("inputSize", size.toLong()); putInt("maxTokens", 8); putFloat("temperature", 0f)
    }

    private fun callback(count: AtomicInteger) = object : IOrionInferenceCallback.Stub() {
        override fun onChunk(chunk: Bundle?) { count.incrementAndGet() }
    }

    private fun shared(size: Int, fill: Byte = 65): SharedMemory = SharedMemory.create("orion_boundary_prompt", size).also { memory ->
        val mapped = memory.mapReadWrite()
        try { mapped.put(ByteArray(size) { fill }) } finally { SharedMemory.unmap(mapped) }
        check(memory.setProtect(OsConstants.PROT_READ))
    }

    companion object {
        private lateinit var app: Context
        private lateinit var component: ComponentName
        private lateinit var remote: IOrionCortex
        private lateinit var connection: ServiceConnection
        private val cancelledId = "orion_cancel_before_start_${UUID.randomUUID()}"

        @BeforeClass @JvmStatic fun connect() {
            app = InstrumentationRegistry.getInstrumentation().targetContext
            component = ComponentName(app.packageName, "network.orion.ai.service.OrionCortexService")
            val connected = CountDownLatch(1)
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    remote = IOrionCortex.Stub.asInterface(binder); connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) { }
            }
            assertTrue(app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
            assertTrue(connected.await(15, TimeUnit.SECONDS))
        }

        @AfterClass @JvmStatic fun disconnect() { app.unbindService(connection) }
    }
}

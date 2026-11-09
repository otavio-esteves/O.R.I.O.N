package network.orion.app.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.IOrionInferenceCallback
import network.orion.ai.api.OrionCortexWire
import network.orion.core.time.AndroidOrionClock
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
    @Test fun a_privateManifestAndVersionedProtocol() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported)
        assertEquals("${app.packageName}:ai", info.processName)
        val protocol = remote.getProtocolInfo()
        OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
        assertEquals(1, protocol.getInt("major")); assertEquals(0, protocol.getInt("minor"))
        assertEquals("OK", protocol.getString("status"))
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

    @Test fun e_capacityRejectsNewRequestsWithoutEvictingExistingTerminals() {
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

    private fun shared(size: Int): SharedMemory = SharedMemory.create("orion_boundary_prompt", size).also { memory ->
        val mapped = memory.mapReadWrite()
        try { mapped.put(ByteArray(size) { 65 }) } finally { SharedMemory.unmap(mapped) }
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

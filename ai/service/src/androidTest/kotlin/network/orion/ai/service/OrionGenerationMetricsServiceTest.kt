package network.orion.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.OrionGenerationMetric
import network.orion.ai.api.OrionGenerationMetricsWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder metadata only: no model/runtime construction, inference or memory stimulus. */
@RunWith(AndroidJUnit4::class)
class OrionGenerationMetricsServiceTest {
    @Test fun optionalQueriesPreserveLegacyEnvelopeAndDoNotStartRuntime() = service { remote ->
        val old = remote.capabilities
        assertEquals(setOf("schemaVersion", "status", "capabilities"), old.keySet())
        assertEquals("OK", old.getString("status"))
        val discovery = remote.generationMetricsCapabilities
        assertEquals(if (BuildConfig.ORION_GENERATION_METRICS) OrionGenerationMetric.ALL_MASK else 0L,
            OrionGenerationMetricsWire.capabilityMask(discovery))
        val reservation = checkNotNull(remote.issueRequestId().getString("requestId"))
        repeat(3) { assertStatus("NOT_FOUND", remote.getGenerationMetrics(OrionGenerationMetricsWire.query(reservation))) }
        val foreign = "orion_request/${UUID.randomUUID()}/1"
        assertStatus("NOT_FOUND", remote.getGenerationMetrics(OrionGenerationMetricsWire.query(foreign)))
        remote.cancelInference(reservation) // Original non-inference cancellation tombstone.
        assertStatus("INVALID_QUERY", remote.getGenerationMetrics(OrionGenerationMetricsWire.query(reservation)))
        assertStatus("UNSUPPORTED", remote.getGenerationMetrics(Bundle().apply {
            putInt("schemaVersion", 2); putString("requestId", reservation)
        }))
        for (invalid in listOf("legacy", "orion_request/scope/0", "orion_request/scope/001", "orion_request/scope/NaN")) {
            assertStatus("INVALID_QUERY", remote.getGenerationMetrics(Bundle().apply {
                putInt("schemaVersion", 1); putString("requestId", invalid)
            }))
        }
        assertStatus("INVALID_QUERY", remote.getGenerationMetrics(Bundle().apply {
            putInt("schemaVersion", 1); putString("requestId", reservation); putBoolean("enable", true)
        }))
        assertEquals("UNLOADED", remote.status.getString("status"))
        val health = remote.healthCheck()
        assertFalse(health.getBoolean("nativeRuntimeLoaded"))
        assertFalse(health.getBoolean("nativeValidationCompleted"))
        val after = remote.capabilities
        assertEquals(old.keySet(), after.keySet())
        assertEquals(old.getInt("schemaVersion"), after.getInt("schemaVersion"))
        assertEquals(old.getString("status"), after.getString("status"))
        assertEquals(old.getString("capabilities"), after.getString("capabilities"))
    }

    @Test fun originalHistoryRetirementRemainsIrreversibleForMetrics() = service { remote ->
        val oldId = checkNotNull(remote.issueRequestId().getString("requestId"))
        remote.cancelInference(oldId)
        var latest = oldId
        repeat(64) {
            latest = checkNotNull(remote.issueRequestId().getString("requestId"))
            remote.cancelInference(latest)
        }
        repeat(3) { assertStatus("RETIRED", remote.getGenerationMetrics(OrionGenerationMetricsWire.query(oldId))) }
        assertStatus("INVALID_QUERY", remote.getGenerationMetrics(OrionGenerationMetricsWire.query(latest)))
        assertEquals("UNLOADED", remote.status.getString("status"))
        assertFalse(remote.healthCheck().getBoolean("nativeRuntimeLoaded"))
    }

    private fun assertStatus(status: String, reply: Bundle) {
        assertEquals(setOf("schemaVersion", "status"), reply.keySet())
        assertEquals(1, reply.getInt("schemaVersion")); assertEquals(status, reply.getString("status"))
    }

    private fun service(body: (IOrionCortex) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IOrionCortex>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote.set(IOrionCortex.Stub.asInterface(checkNotNull(binder))); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        val bound = context.bindService(Intent().setComponent(ComponentName(context, OrionCortexService::class.java)),
            connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound); assertTrue(connected.await(10, TimeUnit.SECONDS))
            body(checkNotNull(remote.get()))
        } finally { if (bound) context.unbindService(connection) }
    }
}

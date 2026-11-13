package network.orion.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexClient
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android API objects; captured binds make lifecycle interleavings deterministic. */
@RunWith(AndroidJUnit4::class)
class OrionCortexClientLifecycleTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val component = ComponentName(base.packageName, OrionCortexService::class.java.name)
    private val clock = MonotonicClock { MonotonicInstant(BootSessionId(1), 100) }
    private val guard = OrionCortexAdmissionGuard { model, _ -> approval(model) }

    private fun approval(model: String) = OrionCortexApproval(model, 512, 1, 8,
        clock.monotonicNow(), 5_000)

    private class CapturedBindings(base: Context) : ContextWrapper(base) {
        val connections = mutableListOf<ServiceConnection>()
        var unbinds = 0
        var onUnbind: () -> Unit = {}
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connections += connection
            return true // No AI process or native engine starts in these interleaving fixtures.
        }
        override fun unbindService(connection: ServiceConnection) {
            assertTrue(connection in connections)
            unbinds++
            onUnbind()
        }
    }

    private fun request(client: OrionCortexClient, listener: (OrionCortexChunk) -> Unit = {}): UUID =
        checkNotNull(client.startInference("fixture", "synthetic", 8, onChunk = listener))

    @Test fun connectionCallbacksAfterCloseDoNotEscapeOrRepeatTerminal() {
        val bindings = CapturedBindings(base)
        val terminals = mutableListOf<OrionCortexRequestStatus>()
        val client = OrionCortexClient(bindings, clock, guard)
        val id = request(client) { it.terminal?.let(terminals::add) }
        val old = bindings.connections.single()
        try {
            client.close()
            old.onServiceConnected(component, Binder())
            old.onServiceDisconnected(component)
            old.onBindingDied(component)
            old.onNullBinding(component)
            client.close()
            assertEquals(listOf(OrionCortexRequestStatus.CANCELLED), terminals)
            assertEquals(OrionCortexRequestStatus.CANCELLED, client.getRequestStatus(id))
            assertEquals(1, bindings.unbinds)
            assertEquals(1, bindings.connections.size)
        } finally { client.close() }
    }

    @Test fun reconnectFromHealthCallbackSurvivesOldDisconnectCleanup() {
        val bindings = CapturedBindings(base)
        val holder = AtomicReference<OrionCortexClient>()
        val fresh = AtomicReference<UUID>()
        val retryOnce = AtomicBoolean()
        val oldTerminals = AtomicInteger()
        val freshTerminals = AtomicInteger()
        val client = OrionCortexClient(bindings, clock, guard) { health ->
            if (health == OrionCortexHealth.UNAVAILABLE && retryOnce.compareAndSet(false, true))
                fresh.set(request(holder.get()) { if (it.terminal != null) freshTerminals.incrementAndGet() })
        }
        holder.set(client)
        try {
            val old = request(client) { if (it.terminal != null) oldTerminals.incrementAndGet() }
            val oldConnection = bindings.connections.single()
            oldConnection.onServiceDisconnected(component)
            assertNotNull(fresh.get())
            assertEquals(2, bindings.connections.size)
            assertEquals(OrionCortexRequestStatus.PROCESS_DIED, client.getRequestStatus(old))
            assertEquals(OrionCortexRequestStatus.QUEUED, client.getRequestStatus(fresh.get()))
            assertEquals(OrionCortexHealth.CONNECTING, client.getStatus())
            oldConnection.onServiceDisconnected(component)
            oldConnection.onBindingDied(component)
            oldConnection.onNullBinding(component)
            assertEquals(1, oldTerminals.get())
            assertEquals(0, freshTerminals.get())
            client.close()
            assertEquals(1, freshTerminals.get())
        } finally { client.close() }
    }

    @Test fun reconnectFromTerminalCallbackSurvivesRemainingOldFailedTerminals() {
        val bindings = CapturedBindings(base)
        val fresh = AtomicReference<UUID>()
        val firstTerminals = AtomicInteger()
        val secondTerminals = AtomicInteger()
        OrionCortexClient(bindings, clock, guard).use { client ->
            val first = request(client) { chunk ->
                if (chunk.terminal != null && firstTerminals.incrementAndGet() == 1)
                    fresh.set(request(client))
            }
            val second = request(client) { if (it.terminal != null) secondTerminals.incrementAndGet() }
            bindings.connections.single().onNullBinding(component)
            assertEquals(OrionCortexRequestStatus.FAILED, client.getRequestStatus(first))
            assertEquals(OrionCortexRequestStatus.FAILED, client.getRequestStatus(second))
            assertEquals(OrionCortexRequestStatus.QUEUED, client.getRequestStatus(checkNotNull(fresh.get())))
            assertEquals(OrionCortexHealth.CONNECTING, client.getStatus())
            assertEquals(1, firstTerminals.get())
            assertEquals(1, secondTerminals.get())
            assertEquals(2, bindings.connections.size)
        }
    }

    @Test fun reconnectDuringUnbindKeepsNewRequestAndHealth() {
        val bindings = CapturedBindings(base)
        val retryOnce = AtomicBoolean()
        val fresh = AtomicReference<UUID>()
        OrionCortexClient(bindings, clock, guard).use { client ->
            val old = request(client)
            bindings.onUnbind = {
                if (retryOnce.compareAndSet(false, true)) fresh.set(request(client))
            }
            bindings.connections.single().onNullBinding(component)
            assertEquals(OrionCortexRequestStatus.FAILED, client.getRequestStatus(old))
            assertEquals(OrionCortexRequestStatus.QUEUED, client.getRequestStatus(checkNotNull(fresh.get())))
            assertEquals(OrionCortexHealth.CONNECTING, client.getStatus())
            assertEquals(2, bindings.connections.size)
        }
    }

    @Test fun closeDuringBindApprovalCancelsExactlyOnceWithoutBinding() {
        val bindings = CapturedBindings(base)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val terminals = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val id = AtomicReference<UUID>()
        val guarded = OrionCortexAdmissionGuard { model, operation ->
            if (operation == OrionCortexOperation.BIND) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
            approval(model)
        }
        val client = OrionCortexClient(bindings, clock, guarded)
        val producer = Thread {
            try { id.set(request(client) { if (it.terminal != null) terminals.incrementAndGet() }) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            producer.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            client.close()
            proceed.countDown()
            producer.join(10_000)
            assertFalse(producer.isAlive)
            assertNull(failure.get())
            assertEquals(OrionCortexRequestStatus.CANCELLED, client.getRequestStatus(checkNotNull(id.get())))
            assertEquals(1, terminals.get())
            assertTrue(bindings.connections.isEmpty())
        } finally {
            proceed.countDown()
            producer.join(10_000)
            client.close()
        }
    }
}

package network.orion.app.ai

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.data.database.ModelRegistryEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MAIN client, synthetic pre-bind policy hold: no model load, service or resource approval. */
@RunWith(AndroidJUnit4::class)
class OrionCortexQueuedDeadlineTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test(timeout = 15_000) fun queuedTimeoutAndReplayReturnBeforeActivePolicyHoldWithoutBinding() = runBlocking {
        val mark = AtomicReference(MonotonicInstant(BootSessionId(1), 1000))
        val clock = object : OrionClock {
            override fun monotonicNow() = mark.get()
            override fun wallNow() = Instant.EPOCH
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restartCalls = AtomicInteger()
        val resourceCalls = AtomicInteger()
        val bindingChanges = AtomicInteger()
        val file = File.createTempFile("orion_queue_fixture_", ".bin", app.cacheDir)
        file.writeBytes(byteArrayOf(1))
        val hash = "a".repeat(64)
        val model = ModelRegistryEntity(hash, "Synthetic queue fixture", "verified/$hash.gguf", hash,
            1, "llama", "F32", 512, "READY", 0, 0, 2)
        val client = OrionCortexClient(app, clock, resources = { _, _, _, _ ->
            resourceCalls.incrementAndGet(); error("Queued request reached resource policy.")
        }, restart = {
            restartCalls.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic pre-bind hold was not released." }
            error("Synthetic policy denies the held owner before bind.")
        }, availabilityChanged = { bindingChanges.incrementAndGet() })
        fun request(id: String, prompt: String, duration: Long) = OrionInferenceRequest(id, 1, hash, prompt, "", 1,
            0.0, OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(duration), "fixture")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val active = async(Dispatchers.IO) {
                    ParcelFileDescriptor.dup(descriptor.fileDescriptor).use {
                        client.runInference(model, it, request("active", "synthetic active", 10_000), 512, 1) {
                            error("Denied owner emitted output.")
                        }
                    }
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val short = request("queued", "synthetic queued", 250)
                    val queued = async(start = CoroutineStart.UNDISPATCHED) {
                        client.runInference(model, descriptor, short, 512, 1) { error("Expired waiter emitted output.") }
                    }
                    mark.set(MonotonicInstant(BootSessionId(1), 1250))
                    val result = withTimeout(1000) { queued.await() }
                    assertEquals(OrionInferenceTerminal.TIMED_OUT, result.terminal)
                    assertFalse(active.isCompleted)
                    assertEquals(1, restartCalls.get())
                    assertEquals(0, resourceCalls.get())
                    assertEquals(0, bindingChanges.get())
                    val replay = withTimeout(1000) {
                        client.runInference(model, descriptor, short, 512, 1) { error("Expired replay emitted output.") }
                    }
                    assertEquals(result, replay)
                    val conflict = OrionInferenceRequest(short.requestId, 1, hash, "changed", "", 1, 0.0,
                        short.priority, short.deadline, short.correlationId)
                    assertTrue(runCatching {
                        client.runInference(model, descriptor, conflict, 512, 1) { error("Conflict emitted output.") }
                    }.exceptionOrNull() is IllegalStateException)
                    assertEquals(1, restartCalls.get())
                    assertEquals(0, resourceCalls.get())
                    assertEquals(0, bindingChanges.get())
                } finally { release.countDown() }
                assertEquals(OrionInferenceTerminal.FAILED, withTimeout(1000) { active.await() }.terminal)
            }
        } finally { release.countDown(); client.close(); file.delete() }
    }
}

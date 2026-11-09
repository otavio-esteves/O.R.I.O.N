package network.orion.ai.cortex

import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.Executor
import network.orion.ai.api.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionCortexRuntimeTest {
    private class Clock : MonotonicClock {
        var now = MonotonicInstant(BootSessionId(1), 100)
        override fun monotonicNow() = now
        fun advance(ms: Long) { now = now.copy(elapsedRealtimeMillis = now.elapsedRealtimeMillis + ms) }
    }
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Engine : OrionInferenceEngine {
        var loads = 0; var inferences = 0; var unloads = 0
        var onLoad: ((() -> Boolean) -> Unit)? = null
        var onInfer: (((String) -> Boolean, () -> Boolean) -> Unit)? = null
        override fun load(model: FileDescriptor, contextSize: Int, threads: Int, shouldCancel: () -> Boolean) {
            loads++; onLoad?.invoke(shouldCancel)
        }
        override fun infer(prompt: String, maxTokens: Int, temperature: Float, emit: (String) -> Boolean, shouldCancel: () -> Boolean) {
            inferences++; onInfer?.invoke(emit, shouldCancel) ?: emit("answer")
        }
        override fun unload() { unloads++ }
    }
    private val clock = Clock()
    private val worker = Worker()
    private val engine = Engine()
    private var stops = 0
    private var factories = 0
    private val runtime = OrionCortexRuntime(OrionInferenceEngineFactory { factories++; engine }, clock, worker, { stops++ })
    private val chunks = mutableListOf<OrionCortexChunk>()
    private fun approval(model: String, context: Int, threads: Int) = OrionCortexApproval(model, context, threads,8,
        clock.monotonicNow(), 500, 30_000)
    private fun OrionCortexRuntime.loadModel(id: UUID, model: String, descriptor: FileDescriptor, context: Int, threads: Int,
        timeout: Long, callback: (OrionCortexChunk) -> Unit, release: () -> Unit = {}): OrionCortexAdmission =
        loadModel(id,model,descriptor,context,threads,timeout,callback,release,30_000,approval(model,context,threads))
    private fun OrionCortexRuntime.startInference(id: UUID, model: String, prompt: String, context: Int, threads: Int,
        tokens: Int, temperature: Float, timeout: Long, priority: Int, callback: (OrionCortexChunk) -> Unit): OrionCortexAdmission =
        startInference(id,model,prompt,context,threads,tokens,temperature,timeout,priority,callback,
            keepAliveMillis = 30_000, approval = approval(model,context,threads))
    private fun load(id: UUID = UUID.randomUUID(), release: () -> Unit = {}): UUID {
        assertEquals(OrionCortexAdmission.ACCEPTED,
            runtime.loadModel(id, "sha256:fixture", FileDescriptor(), 512, 2, 5_000, chunks::add, release))
        return id
    }
    private fun infer(id: UUID = UUID.randomUUID(), model: String = "sha256:fixture", context: Int = 512,
        threads: Int = 2, priority: Int = 0): UUID {
        assertEquals(OrionCortexAdmission.ACCEPTED,
            runtime.startInference(id, model, "prompt", context, threads, 8, 0f, 5_000, priority, chunks::add))
        return id
    }
    private fun terminal(id: UUID) = chunks.filter { it.requestId == id && it.terminal != null }.single().terminal
    @Test fun `construction remains unloaded and creates no engine`() {
        assertEquals(OrionCortexHealth.UNLOADED, runtime.getStatus()); assertEquals(0, factories)
    }
    @Test fun `load inference and unload serialize and emit a single terminal with contiguous chunks`() {
        val loading = load(); worker.drain()
        assertEquals(OrionCortexRequestStatus.SUCCEEDED, terminal(loading)); assertEquals(OrionCortexHealth.READY, runtime.getStatus())
        val request = infer(); worker.drain()
        assertEquals(listOf(0L, 1L), chunks.filter { it.requestId == request }.map { it.sequence })
        assertEquals(OrionCortexRequestStatus.SUCCEEDED, terminal(request))
        assertTrue(runtime.unloadModel()); worker.drain(); assertEquals(OrionCortexHealth.UNLOADED, runtime.getStatus())
    }
    @Test fun `duplicate request IDs never execute twice including terminal history`() {
        val id = load(); worker.drain()
        assertEquals(OrionCortexAdmission.DUPLICATE_REQUEST,
            runtime.loadModel(id, "sha256:fixture", FileDescriptor(), 512, 2, 5_000, chunks::add))
        assertEquals(1, engine.loads)
    }
    @Test fun `cancel before admission creates bounded tombstone`() {
        val id = UUID.randomUUID(); runtime.cancelInference(id)
        assertEquals(OrionCortexRequestStatus.CANCELLED, runtime.getRequestStatus(id))
        assertEquals(OrionCortexAdmission.DUPLICATE_REQUEST,
            runtime.loadModel(id, "sha256:fixture", FileDescriptor(), 512, 2, 5_000, chunks::add))
        assertEquals(0, factories)
    }
    @Test fun `cancel queued load releases descriptor without ever creating native runtime`() {
        var released = 0
        val id = load(release = { released++ }); runtime.cancelInference(id); runtime.cancelInference(id); worker.drain()
        assertEquals(OrionCortexRequestStatus.CANCELLED, terminal(id)); assertEquals(0, factories); assertEquals(1, released)
    }
    @Test fun `cancel during loading is cooperative and clears loaded state`() {
        lateinit var id: UUID
        engine.onLoad = { cancelled -> runtime.cancelInference(id); assertTrue(cancelled()) }
        id = load(); worker.drain()
        assertEquals(OrionCortexRequestStatus.CANCELLED, terminal(id)); assertEquals(OrionCortexHealth.UNLOADED, runtime.getStatus())
    }
    @Test fun `cancel during decode refuses further text and ignores late success`() {
        load(); worker.drain()
        lateinit var id: UUID
        engine.onInfer = { emit, cancelled ->
            assertTrue(emit("first")); runtime.cancelInference(id)
            assertTrue(cancelled()); assertFalse(emit("late"))
        }
        id = infer(); worker.drain()
        assertEquals(OrionCortexRequestStatus.CANCELLED, terminal(id))
        assertEquals(listOf("first"), chunks.filter { it.requestId == id }.mapNotNull { it.textDelta })
    }
    @Test fun `cancel after terminal is a no-op`() {
        val id = load(); worker.drain(); runtime.cancelInference(id)
        assertEquals(OrionCortexRequestStatus.SUCCEEDED, terminal(id))
    }
    @Test fun `timeout includes queue wait and never starts expired native loading`() {
        val id = load(); clock.advance(5_000); runtime.checkTimeouts(); worker.drain()
        assertEquals(OrionCortexRequestStatus.TIMED_OUT, terminal(id)); assertEquals(0, factories)
    }
    @Test fun `deadline and cancellation watchdog stop an unresponsive AI worker once`() {
        engine.onLoad = {
            clock.advance(5_000); runtime.checkTimeouts(); assertEquals(0, stops)
            clock.advance(1_000); runtime.checkTimeouts(); runtime.checkTimeouts(); assertEquals(1, stops)
        }
        val id = load(); worker.drain()
        assertEquals(OrionCortexRequestStatus.TIMED_OUT, terminal(id)); assertEquals(1, stops)
        assertEquals(OrionCortexHealth.UNAVAILABLE, runtime.getStatus())
    }
    @Test fun `boot invalidation cannot make an old deadline valid`() {
        val id = load(); clock.now = MonotonicInstant(BootSessionId(2), 100); runtime.checkTimeouts(); worker.drain()
        assertEquals(OrionCortexRequestStatus.TIMED_OUT, terminal(id)); assertEquals(0, factories)
    }
    @Test fun `queue is bounded and explicit priority preserves one native invocation at a time`() {
        load(); worker.drain(); val order = mutableListOf<UUID>()
        fun enqueue(priority: Int): UUID {
            val id = UUID.randomUUID()
            assertEquals(OrionCortexAdmission.ACCEPTED, runtime.startInference(id, "sha256:fixture", "prompt", 512, 2,
                8, 0f, 5_000, priority, { if (it.terminal != null) order += id }))
            return id
        }
        val first = enqueue(0); val low = enqueue(0); val high = enqueue(4)
        enqueue(3); enqueue(2)
        assertEquals(OrionCortexAdmission.BUSY, runtime.startInference(UUID.randomUUID(), "sha256:fixture", "prompt", 512, 2,
            8, 0f, 5_000, 0, chunks::add))
        assertFalse(runtime.unloadModel()); worker.drain()
        assertEquals(first, order.first()); assertEquals(high, order[1]); assertEquals(low, order.last())
        assertEquals(5, engine.inferences)
    }
    @Test fun `model or qualified context thread changes require a fresh load`() {
        for ((model, context, threads) in listOf(Triple("sha256:other", 512, 2), Triple("sha256:fixture", 128, 2), Triple("sha256:fixture", 512, 1))) {
            load(); worker.drain(); val id = infer(model = model, context = context, threads = threads); worker.drain()
            assertEquals(OrionCortexRequestStatus.FAILED, terminal(id))
        }
        assertEquals(0, engine.inferences)
    }
    @Test fun `native failures are terminal and clear uncertain model state`() {
        engine.onLoad = { error("synthetic private failure") }
        val id = load(); worker.drain()
        assertEquals(OrionCortexRequestStatus.FAILED, terminal(id)); assertEquals(OrionCortexHealth.UNLOADED, runtime.getStatus())
        assertFalse(chunks.toString().contains("synthetic"))
    }
    @Test fun `malformed unicode and oversized native output fail boundedly`() {
        load(); worker.drain()
        engine.onInfer = { emit, _ -> emit("\uD800") }
        val id = infer(); worker.drain(); assertEquals(OrionCortexRequestStatus.FAILED, terminal(id))
        load(); worker.drain()
        engine.onInfer = { emit, _ -> repeat(65) { emit("x".repeat(4096)) } }
        val overflow = infer(); worker.drain(); assertEquals(OrionCortexRequestStatus.FAILED, terminal(overflow))
        assertEquals(262_144, chunks.filter { it.requestId == overflow }.sumOf { it.textDelta?.length ?: 0 })
    }
    @Test fun `invalid envelope rejects before queueing and releases models`() {
        var released = 0
        assertEquals(OrionCortexAdmission.INVALID, runtime.loadModel(UUID.randomUUID(), "bad/path", FileDescriptor(),
            512, 2, 5_000, chunks::add, { released++ }, approval = approval("fixture",512,2)))
        assertEquals(1, released); assertEquals(0, factories)
        assertEquals(OrionCortexAdmission.INVALID, runtime.startInference(UUID.randomUUID(), "fixture", "", 512, 2,
            8, Float.NaN, 5_000, 0, chunks::add))
    }
    @Test fun `close cancels queued work and releases all resources`() {
        var released = 0
        val id = load(release = { released++ }); runtime.close(); worker.drain()
        assertEquals(OrionCortexRequestStatus.CANCELLED, terminal(id)); assertEquals(1, released); assertEquals(0, factories)
    }
    @Test fun `idle model unloads at bounded keepalive without a new request`() {
        load(); worker.drain(); clock.advance(29_999); runtime.checkTimeouts()
        assertEquals(OrionCortexHealth.READY, runtime.getStatus())
        clock.advance(1); runtime.checkTimeouts(); worker.drain()
        assertEquals(OrionCortexHealth.UNLOADED, runtime.getStatus())
    }
    @Test fun `clock provider failure never retains inference permission or an unbounded native call`() {
        var broken = false; var killed = 0
        val failingClock = MonotonicClock { if (broken) error("unavailable") else clock.monotonicNow() }
        val controller = OrionCortexRuntime(OrionInferenceEngineFactory { engine }, failingClock, worker, { killed++ })
        engine.onLoad = { broken = true; controller.checkTimeouts(); controller.checkTimeouts() }
        val id = UUID.randomUUID()
        assertEquals(OrionCortexAdmission.ACCEPTED, controller.loadModel(id, "fixture", FileDescriptor(),512,2,5_000,chunks::add))
        worker.drain(); assertEquals(1, killed); assertEquals(OrionCortexRequestStatus.FAILED, terminal(id))
        assertEquals(OrionCortexHealth.UNAVAILABLE, controller.getStatus())
    }
    @Test fun `completed canceled ownership cannot escalate against the next healthy request`() {
        load(); worker.drain()
        val cancelled = infer(); runtime.cancelInference(cancelled); worker.drain()
        clock.advance(1_000)
        val healthy = infer()
        runtime.checkTimeouts(); worker.drain(); runtime.checkTimeouts()
        assertEquals(0, stops)
        assertEquals(OrionCortexRequestStatus.CANCELLED, terminal(cancelled))
        assertEquals(OrionCortexRequestStatus.SUCCEEDED, terminal(healthy))
    }
    @Test fun `hard stop fences every later native admission`() {
        engine.onLoad = {
            clock.advance(5_000); runtime.checkTimeouts(); clock.advance(1_000); runtime.checkTimeouts()
            assertEquals(OrionCortexAdmission.UNAVAILABLE, runtime.startInference(UUID.randomUUID(), "fixture", "prompt",512,2,
                8,0f,5_000,0,chunks::add))
        }
        load(); worker.drain(); assertEquals(1, stops); assertEquals(0, engine.inferences)
    }
    @Test fun `queued approval expiration never enters native loading or inference`() {
        val id = load(); clock.advance(500); worker.drain()
        assertEquals(OrionCortexRequestStatus.FAILED, terminal(id)); assertEquals(0, engine.loads)
        load(); worker.drain()
        val request = infer(); clock.advance(500); worker.drain()
        assertEquals(OrionCortexRequestStatus.FAILED, terminal(request)); assertEquals(0, engine.inferences)
    }
    @Test fun `close releases waiting model descriptors even though no further queue task can start`() {
        var activeReleased = 0; var waitingReleased = 0
        val first = load(release = { activeReleased++ })
        val waiting = load(release = { waitingReleased++ })
        runtime.close()
        assertEquals(1,waitingReleased); assertEquals(0,activeReleased)
        worker.drain()
        assertEquals(1,activeReleased); assertEquals(1,waitingReleased)
        assertEquals(OrionCortexRequestStatus.CANCELLED,terminal(first))
        assertEquals(OrionCortexRequestStatus.CANCELLED,terminal(waiting)); assertEquals(0,engine.loads)
    }
    @Test fun `pressure before work fences the service without autoload or restart`() {
        runtime.onMemoryPressure(); runtime.onMemoryPressure()
        assertEquals(OrionCortexHealth.DEGRADED,runtime.getStatus())
        var released = 0
        assertEquals(OrionCortexAdmission.UNAVAILABLE,runtime.loadModel(UUID.randomUUID(),"fixture",FileDescriptor(),512,2,
            5_000,chunks::add,{ released++ }))
        worker.drain(); assertEquals(1,released); assertEquals(0,factories); assertEquals(0,stops)
        assertTrue(chunks.isEmpty())
    }
    @Test fun `pressure retires all pending load payloads and never clears itself on a fresh approval`() {
        var released = 0
        val first = load(release = { released++ }); val waiting = load(release = { released++ })
        runtime.onMemoryPressure(); assertEquals(1,released)
        assertEquals(OrionCortexRequestStatus.FAILED,terminal(first)); assertEquals(OrionCortexRequestStatus.FAILED,terminal(waiting))
        worker.drain(); assertEquals(2,released); assertEquals(0,factories)
        clock.advance(60_000)
        assertEquals(OrionCortexAdmission.UNAVAILABLE,runtime.startInference(UUID.randomUUID(),"fixture","prompt",512,2,
            8,0f,5_000,0,chunks::add))
        assertEquals(OrionCortexHealth.DEGRADED,runtime.getStatus()); assertEquals(0,engine.inferences)
    }
    @Test fun `pressure during decode cancels native work drops late text and unloads buffers`() {
        load(); worker.drain(); val previousUnloads = engine.unloads
        engine.onInfer = { emit,cancelled ->
            assertTrue(emit("before")); runtime.onMemoryPressure()
            assertTrue(cancelled()); assertFalse(emit("after"))
        }
        val id = infer(); worker.drain()
        assertEquals(OrionCortexRequestStatus.FAILED,terminal(id))
        assertEquals(listOf("before"),chunks.filter { it.requestId == id }.mapNotNull { it.textDelta })
        assertTrue(engine.unloads > previousUnloads); assertEquals(1,engine.loads)
        assertEquals(OrionCortexHealth.DEGRADED,runtime.getStatus()); assertEquals(0,stops)
    }
    @Test fun `idle pressure unloads the resident model without waiting for keepalive`() {
        load(); worker.drain(); val previous = engine.unloads
        runtime.onMemoryPressure(); worker.drain()
        assertEquals(previous + 1,engine.unloads); assertFalse(runtime.needsWatchdog())
        assertEquals(OrionCortexHealth.DEGRADED,runtime.getStatus()); assertEquals(1,factories)
    }
    @Test fun `unresponsive pressured native loading is stopped once without relaunch`() {
        engine.onLoad = {
            runtime.onMemoryPressure(); clock.advance(1_000); runtime.checkTimeouts(); runtime.checkTimeouts()
        }
        val id = load(); worker.drain()
        assertEquals(OrionCortexRequestStatus.FAILED,terminal(id)); assertEquals(1,stops); assertEquals(1,factories)
        assertEquals(OrionCortexHealth.UNAVAILABLE,runtime.getStatus())
    }
    @Test fun `only explicitly recreated runtime can accept work after pressure`() {
        runtime.onMemoryPressure(); assertEquals(0,factories)
        val recreated = OrionCortexRuntime(OrionInferenceEngineFactory { factories++;engine },clock,worker,{ stops++ })
        val id = UUID.randomUUID()
        assertEquals(OrionCortexAdmission.ACCEPTED,recreated.loadModel(id,"fixture",FileDescriptor(),512,2,5_000,chunks::add))
        worker.drain(); assertEquals(1,factories); assertEquals(OrionCortexRequestStatus.SUCCEEDED,terminal(id))
        assertEquals(OrionCortexHealth.DEGRADED,runtime.getStatus())
    }
    @Test fun `logical cancellation and timeout keep physical inference busy until native returns`() {
        load(); worker.drain()
        for (expectedTerminal in listOf(OrionCortexRequestStatus.CANCELLED,OrionCortexRequestStatus.TIMED_OUT)) {
            lateinit var id: UUID
            engine.onInfer = { _,_ ->
                if (expectedTerminal == OrionCortexRequestStatus.CANCELLED) runtime.cancelInference(id)
                else { clock.advance(5_000); runtime.checkTimeouts() }
                assertEquals(expectedTerminal,runtime.getRequestStatus(id))
                assertEquals(OrionCortexHealth.INFERENCING,runtime.getStatus())
                assertFalse(runtime.unloadModel())
            }
            id = infer(); worker.drain()
            assertEquals(expectedTerminal,terminal(id))
            assertEquals(OrionCortexHealth.READY,runtime.getStatus())
        }
    }
}

package network.orion.ai.service

import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.Executor
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexRuntime
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionCortexWatchdogLifecycleTest {
    @Test fun `destruction continues monitoring native close beyond the original cancellation grace`() {
        var now = MonotonicInstant(BootSessionId(1),100)
        val clock = MonotonicClock { now }
        val tasks = ArrayDeque<Runnable>()
        val worker = Executor { tasks.addLast(it) }
        val lifecycle = OrionCortexWatchdogLifecycle()
        var stops = 0
        var closing = false
        lateinit var runtime: OrionCortexRuntime
        val engine = object : OrionInferenceEngine {
            override fun load(model: FileDescriptor,contextSize: Int,threads: Int,shouldCancel: () -> Boolean) { }
            override fun infer(prompt: String,maxTokens: Int,temperature: Float,emit: (String) -> Boolean,shouldCancel: () -> Boolean) {
                runtime.close(); lifecycle.beginDestruction()
                now = now.copy(elapsedRealtimeMillis = 1_099) // Inference returns just before its one-second grace.
                runtime.checkTimeouts(); assertEquals(0,stops)
                assertEquals(OrionCortexWatchdogDisposition.MONITOR,lifecycle.disposition(runtime.needsWatchdog(),false))
            }
            override fun unload() {
                if (!closing) return
                // Native close now hangs beyond two seconds since onDestroy began.
                now = now.copy(elapsedRealtimeMillis = 2_100)
                assertEquals(OrionCortexWatchdogDisposition.MONITOR,lifecycle.disposition(runtime.needsWatchdog(),false))
                runtime.checkTimeouts(); runtime.checkTimeouts(); assertEquals(1,stops)
            }
            override fun close() { closing = true; unload() }
        }
        runtime = OrionCortexRuntime(OrionInferenceEngineFactory { engine },clock,worker,{ stops++ })
        val permit = OrionCortexApproval("fixture",128,1,8,now,5_000,30_000)
        assertEquals(OrionCortexAdmission.ACCEPTED,runtime.loadModel(UUID.randomUUID(),"fixture",FileDescriptor(),128,1,
            5_000,{},approval = permit))
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(OrionCortexAdmission.ACCEPTED,runtime.startInference(UUID.randomUUID(),"fixture","synthetic",128,1,
            8,0f,5_000,4,{},approval = permit))
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertTrue(closing); assertEquals(1,stops)
        assertEquals(OrionCortexWatchdogDisposition.SHUTDOWN,lifecycle.disposition(runtime.needsWatchdog(),true))
    }

    @Test fun `queued close keeps the monitor alive until the worker actually terminates`() {
        val lifecycle = OrionCortexWatchdogLifecycle()
        assertEquals(OrionCortexWatchdogDisposition.IDLE,lifecycle.disposition(false,false))
        lifecycle.beginDestruction()
        assertEquals(OrionCortexWatchdogDisposition.MONITOR,lifecycle.disposition(false,false))
        assertEquals(OrionCortexWatchdogDisposition.MONITOR,lifecycle.disposition(true,true))
        assertEquals(OrionCortexWatchdogDisposition.SHUTDOWN,lifecycle.disposition(false,true))
    }
}

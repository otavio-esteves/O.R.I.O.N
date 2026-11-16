package network.orion.app.ai

import network.orion.ai.api.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Finite synthetic scalar controls only; no Android/physical workload or calibrated settings. */
class OrionRuntimeMemorySamplerTest {
    private fun finite(values: List<Long?>): OrionObservationEventClock {
        val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
            it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        }.apply { isAccessible = true }
        return method.invoke(OrionObservationEventClock.Companion, values) as OrionObservationEventClock
    }
    private fun moments(vararg values: Long): List<OrionObservationMoment> {
        val clock = finite(values.toList())
        val witness = MonotonicInstant(BootSessionId(3), 100)
        check(clock.bindOriginalWitness(witness, witness, MonotonicDeadlineStatus.ACTIVE))
        return values.map { checkNotNull(clock.observe()) }
    }
    private fun terminal(): OrionLifecycleTerminalEvent {
        val clock = finite(listOf(140L)); val start = MonotonicInstant(BootSessionId(3), 100)
        val source = OrionInferenceLifecycleEventSource(clock)
        val lifecycle = OrionInferenceLifecycle(OrionInferenceRequest("technical", 1, "model", "private", "", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, start.deadlineAfter(1000), "technical"), MonotonicClock { start }, events = source)
        check(lifecycle.activateEventCapture()); lifecycle.cancel()
        return checkNotNull(source.terminalView()?.firstTerminal)
    }
    private fun time(value: Long) = OrionRuntimeEventTime(3, value)
    private fun row(phase: OrionRuntimeMemoryPhase, at: OrionObservationMoment, end: OrionObservationMoment,
        aiAt: Long = at.elapsedRealtimeMillis, main: Long? = 100, ai: Long = 200, total: Long? = 1000,
        boot: Int = 3, pid: Int = 2, native: Boolean = true, loaded: Boolean = true, validated: Boolean = true,
        state: OrionRuntimeServiceState = OrionRuntimeServiceState.LOADED) =
        OrionRuntimeCollectedMemory(phase, 0, at, end, 1, OrionRuntimeMainMemoryReading(main, total),
            OrionRuntimeAiMemoryReading(pid, boot, aiAt, ai, native, loaded, validated, state))

    @Test fun `all seven real phase windows are explicit and do not select a freshness default`() {
        for (invalid in listOf(-1L, 0L, 1001L, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { OrionRuntimeSamplingConfiguration(invalid) }
        assertEquals(7, OrionRuntimeMemoryPhase.entries.size)
        val timeline = OrionRuntimeSamplingTimeline(loadStart = time(110), loadEnd = time(120), loadSucceeded = true,
            inferenceStart = time(130), terminal = terminal(), unloadStart = time(150), unloadEnd = time(160), end = time(170))
        val starts = listOf(100L, 110, 120, 130, 140, 150, 160)
        val ends = listOf(110L, 120, 130, 140, 150, 160, 170)
        val states = listOf(OrionRuntimeServiceState.UNLOADED, OrionRuntimeServiceState.LOADING,
            OrionRuntimeServiceState.LOADED, OrionRuntimeServiceState.INFERENCING, OrionRuntimeServiceState.LOADED,
            OrionRuntimeServiceState.UNLOADING, OrionRuntimeServiceState.UNLOADED)
        OrionRuntimeMemoryPhase.entries.forEachIndexed { index, phase ->
            val window = checkNotNull(timeline.window(phase, time(100)))
            assertEquals(starts[index], window.started.elapsedMillis); assertEquals(ends[index], window.completed.elapsedMillis)
            val points = moments(starts[index], ends[index])
            assertTrue(OrionRuntimeMemorySampler.validWindow(row(phase, points[0], points[1], state = states[index]), window, timeline))
        }
        assertNull(OrionRuntimeSamplingTimeline().window(OrionRuntimeMemoryPhase.DURING_LOAD, time(100)))
        assertNull(OrionRuntimeSamplingTimeline(loadEnd = time(120), loadSucceeded = false).phase())
        val beforeDispatch = timeline.copy(inferenceStart = null, unloadStart = null, unloadEnd = null, end = null,
            terminalBeforeDispatch = true)
        assertEquals(OrionRuntimeMemoryPhase.AFTER_LOAD, beforeDispatch.phase())
        assertNull(beforeDispatch.window(OrionRuntimeMemoryPhase.AFTER_TERMINAL, time(100)))
        assertNull(beforeDispatch.copy(loadSucceeded = false).phase())
        assertEquals(OrionRuntimeMemoryPhase.AFTER_LOAD, beforeDispatch.copy(inferenceStart = time(145)).phase())
    }

    @Test fun `freshness equality boot identity PSS totals flags and cross-clock samples fail closed`() {
        val points = moments(100, 110, 120)
        val phase = OrionRuntimeMemoryPhase.AFTER_LOAD
        val valid = row(phase, points[0], points[1], aiAt = 105)
        assertTrue(OrionRuntimeMemorySampler.validMemory(valid, 11, points[0]))
        assertFalse(OrionRuntimeMemorySampler.validMemory(valid, 10, points[0]))
        assertFalse(OrionRuntimeMemorySampler.validMemory(valid, 1001, points[0]))
        assertFalse(OrionRuntimeMemorySampler.validMemory(valid, 11, moments(100).single()))
        assertFalse(OrionRuntimeMemorySampler.validMemory(valid, 11, points[2]))
        val invalid = listOf(row(phase, points[0], points[1], aiAt = 99), row(phase, points[0], points[1], aiAt = 111),
            row(phase, points[0], points[1], boot = 4), row(phase, points[0], points[1], pid = 1),
            row(phase, points[0], points[1], main = null), row(phase, points[0], points[1], main = 0),
            row(phase, points[0], points[1], ai = 0), row(phase, points[0], points[1], total = null),
            row(phase, points[0], points[1], total = 299), row(phase, points[0], points[1], main = Long.MAX_VALUE),
            row(phase, points[0], points[1], native = false), row(phase, points[0], points[1], loaded = false),
            row(phase, points[0], points[1], validated = false))
        invalid.forEach { assertFalse(OrionRuntimeMemorySampler.validMemory(it, 100, points[0])) }
    }

    @Test fun `straddling or reversed window is not clipped and unloaded may retain native wrapper`() {
        val points = moments(100, 110, 120)
        val phase = OrionRuntimeMemoryPhase.AFTER_UNLOAD
        val row = row(phase, points[0], points[1], state = OrionRuntimeServiceState.UNLOADED)
        val timeline = OrionRuntimeSamplingTimeline(unloadEnd = time(100), end = time(120))
        assertTrue(OrionRuntimeMemorySampler.validMemory(row, 20, points[0]))
        assertTrue(OrionRuntimeMemorySampler.validWindow(row, OrionRuntimePhaseWindow(phase, time(100), time(120)), timeline))
        assertFalse(OrionRuntimeMemorySampler.validWindow(row, OrionRuntimePhaseWindow(phase, time(101), time(120)), timeline))
        assertFalse(OrionRuntimeMemorySampler.validWindow(row, OrionRuntimePhaseWindow(phase, time(100), time(109)), timeline))
        assertFalse(OrionRuntimeMemorySampler.validWindow(row, OrionRuntimePhaseWindow(phase, time(120), time(100)), timeline))
    }

    @Test fun `finite probe owns the exact MAIN collection-start moment without domain clock or callback`() {
        val clock = finite(listOf(100, 110))
        val witness = MonotonicInstant(BootSessionId(3), 100)
        check(clock.bindOriginalWitness(witness, witness, MonotonicDeadlineStatus.ACTIVE))
        val reading = OrionRuntimeMainMemoryReading(100, 1000)
        val probe = OrionRuntimeMainMemoryProbe.finiteForTests(listOf(reading))
        val sample = checkNotNull(probe.sample(clock))
        assertSame(reading, sample.reading); assertEquals(100L, sample.at.elapsedRealtimeMillis)
        assertNull(probe.sample(clock)); assertEquals(2L, checkNotNull(clock.snapshot()).counterReadsAtLeast)
        assertThrows(IllegalArgumentException::class.java) {
            OrionRuntimeMainMemoryProbe.finiteForTests(List(8) { reading })
        }
    }
}

package network.orion.core.resources

import java.time.Instant
import java.util.concurrent.CancellationException
import network.orion.core.exitinfo.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionMinimumResourceGovernorTest {
    private class Clock : OrionClock {
        var wall = Instant.ofEpochMilli(10_000)
        var mark = MonotonicInstant(BootSessionId(1), 1_000)
        override fun wallNow() = wall
        override fun monotonicNow() = mark
    }
    private val clock = Clock()
    private val model = InferenceModelProfile("orion_fixture", 100)
    private val plan = OrionInferenceResourcePlan(model, "fixture", 2, 512, 64, 500,
        1_000, 1_500, 200, 20, 2)
    private var sample: OrionResourceObservation? = healthy()
    private fun healthy() = OrionResourceObservation(MonotonicInstant(BootSessionId(1), 900), model,
        80, 0, true, OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
        200, 400, 2_000, emptyList(), 2, 512, 64)
    private fun governor(plans: List<OrionInferenceResourcePlan> = listOf(plan)) =
        OrionMinimumResourceGovernor(clock, { sample }, plans, 500, 2_000)

    @Test fun `healthy qualified request returns matching atomic limits`() {
        val governor = governor(); val decision = governor.evaluate()
        assertTrue(decision.allowed); assertNull(decision.block)
        assertEquals(plan, decision.plan); assertEquals(sample!!.sampledAt, decision.sampledAt)
        assertEquals(MemoryRisk.LOW, decision.memoryRisk)
        assertEquals(800L, decision.estimatedHeadroomBytes)
        assertTrue(governor.canRunInference()); assertTrue(governor.canLoadModel(model))
        assertEquals("fixture", governor.allowedModelTier())
        assertEquals(2, governor.maxThreads()); assertEquals(512, governor.maxContext())
        assertEquals(64, governor.maxTokens()); assertEquals(500L, governor.keepAliveMs())
    }
    private fun denied(governor: OrionMinimumResourceGovernor, reason: OrionResourceBlock? = null) {
        val decision = governor.evaluate(); assertFalse(decision.allowed)
        if (reason != null) assertEquals(reason, decision.block)
        assertNull(decision.plan); assertFalse(governor.canRunInference()); assertFalse(governor.canLoadModel(model))
        assertNull(governor.allowedModelTier()); assertEquals(0, governor.maxThreads())
        assertEquals(0, governor.maxContext()); assertEquals(0, governor.maxTokens())
        assertEquals(0L, governor.keepAliveMs())
    }
    @Test fun `missing observation or model qualification never permits work`() {
        denied(governor(emptyList()), OrionResourceBlock.MODEL_UNQUALIFIED)
        sample = null; denied(governor(), OrionResourceBlock.OBSERVATION_UNAVAILABLE)
    }
    @Test fun `caller cannot substitute model identity or underestimate qualified reservation`() {
        assertFalse(governor().canLoadModel(model.copy(estimatedMemoryBytes = 1)))
        assertFalse(governor().canLoadModel(model.copy(modelId = "orion_other")))
        sample = sample!!.copy(model = model.copy(estimatedMemoryBytes = 1))
        denied(governor(), OrionResourceBlock.MODEL_UNQUALIFIED)
    }
    @Test fun `each call resamples battery thermal and foreground evidence`() {
        val governor = governor(); assertTrue(governor.canRunInference())
        sample = healthy().copy(batteryPercent = 19); denied(governor, OrionResourceBlock.BATTERY)
        sample = healthy().copy(thermalStatus = 3); denied(governor, OrionResourceBlock.THERMAL)
        sample = healthy().copy(foreground = false); denied(governor, OrionResourceBlock.BACKGROUND)
        sample = healthy().copy(importance = OrionProcessImportance.BACKGROUND); denied(governor, OrionResourceBlock.BACKGROUND)
        sample = healthy().copy(powerSave = true); denied(governor, OrionResourceBlock.POWER_SAVER)
    }
    @Test fun `missing or inconsistent readings are distinct from healthy zero values`() {
        val invalid = listOf(healthy().copy(batteryPercent = null), healthy().copy(batteryPercent = 101),
            healthy().copy(thermalStatus = null), healthy().copy(thermalStatus = 7),
            healthy().copy(foreground = null), healthy().copy(powerSave = null),
            healthy().copy(importance = OrionProcessImportance.UNKNOWN),
            healthy().copy(processMemoryBytes = -1), healthy().copy(aggregateAppMemoryBytes = 199),
            healthy().copy(systemAvailableMemoryBytes = null), healthy().copy(processMemoryBytes = null))
        invalid.forEach { sample = it; denied(governor(), OrionResourceBlock.OBSERVATION_INVALID) }
        sample = healthy().copy(processMemoryBytes = 0, aggregateAppMemoryBytes = 0, thermalStatus = 0)
        assertTrue(governor().canRunInference())
    }
    @Test fun `boot identity monotonic age and rollback bound every decision`() {
        val governor = governor()
        clock.mark = MonotonicInstant(BootSessionId(1), 1_399); assertTrue(governor.canRunInference())
        clock.mark = MonotonicInstant(BootSessionId(1), 1_400); denied(governor, OrionResourceBlock.OBSERVATION_STALE)
        clock.mark = MonotonicInstant(BootSessionId(2), 1_000); denied(governor, OrionResourceBlock.OBSERVATION_STALE)
        clock.mark = MonotonicInstant(null, 1_000); denied(governor, OrionResourceBlock.OBSERVATION_STALE)
        clock.mark = MonotonicInstant(BootSessionId(1), 899); denied(governor, OrionResourceBlock.OBSERVATION_STALE)
    }
    @Test fun `wall clock jumps do not expire same boot resource observations`() {
        clock.wall = Instant.MIN; assertTrue(governor().canRunInference())
        clock.wall = Instant.MAX; assertTrue(governor().canRunInference())
    }
    @Test fun `aggregate budget blocks when inference process alone would fit`() {
        sample = healthy().copy(aggregateAppMemoryBytes = 1_401)
        denied(governor(), OrionResourceBlock.MEMORY_BUDGET)
        sample = healthy().copy(processMemoryBytes = 901, aggregateAppMemoryBytes = 1_000)
        denied(governor(), OrionResourceBlock.MEMORY_BUDGET)
        sample = healthy().copy(systemAvailableMemoryBytes = 299)
        denied(governor(), OrionResourceBlock.MEMORY_BUDGET)
    }
    @Test fun `aggregate ceiling may be lower than the individual process ceiling`() {
        assertTrue(governor(listOf(plan.copy(aggregateBudgetBytes = 500))).canRunInference())
        denied(governor(listOf(plan.copy(aggregateBudgetBytes = 499))), OrionResourceBlock.MEMORY_BUDGET)
    }
    @Test fun `exact reservation boundary fits without overflow or extra permission`() {
        sample = healthy().copy(processMemoryBytes = 900, aggregateAppMemoryBytes = 1_400, systemAvailableMemoryBytes = 300)
        assertTrue(governor().canRunInference()); assertEquals(100L, governor().estimatedHeadroom())
        sample = healthy().copy(processMemoryBytes = Long.MAX_VALUE, aggregateAppMemoryBytes = Long.MAX_VALUE)
        denied(governor(), OrionResourceBlock.MEMORY_BUDGET)
        assertEquals(0L, governor().estimatedHeadroom())
    }
    @Test fun `pressure and unavailable exit history cannot appear healthy`() {
        sample = healthy().copy(memoryPressure = OrionMemoryPressure.MODERATE)
        denied(governor(), OrionResourceBlock.MEMORY_PRESSURE); assertEquals(MemoryRisk.HIGH, governor().memoryRisk())
        sample = healthy().copy(memoryPressure = OrionMemoryPressure.CRITICAL)
        denied(governor(), OrionResourceBlock.MEMORY_PRESSURE); assertEquals(MemoryRisk.CRITICAL, governor().memoryRisk())
        sample = healthy().copy(memoryPressure = OrionMemoryPressure.UNKNOWN)
        denied(governor(), OrionResourceBlock.OBSERVATION_INVALID)
        sample = healthy().copy(recentExits = null)
        denied(governor(), OrionResourceBlock.EXIT_HISTORY_UNAVAILABLE)
    }
    @Test fun `unclassified exits cannot be treated as healthy evidence`() {
        sample = healthy().copy(recentExits = listOf(ProcessExitSignal(0, clock.wall)))
        denied(governor(), OrionResourceBlock.OBSERVATION_INVALID)
        sample = healthy().copy(recentExits = listOf(ProcessExitSignal(1, clock.wall, ProcessExitKind.NORMAL_EXIT)))
        assertTrue(governor().canRunInference())
    }
    @Test fun `limiter and low memory exits change policy before next request`() {
        val governor = governor(); assertTrue(governor.canLoadModel(model))
        for (signal in listOf(ProcessExitSignal(3, clock.wall), ProcessExitSignal(3, clock.wall, ProcessExitKind.LOW_MEMORY),
            ProcessExitSignal(99, clock.wall, ProcessExitKind.MEMORY_LIMITER),
            ProcessExitSignal(13, clock.wall, ProcessExitKind.OTHER_SYSTEM, true))) {
            sample = healthy().copy(recentExits = listOf(signal))
            repeat(3) { denied(governor, OrionResourceBlock.MEMORY_EXIT) }
            assertEquals(MemoryRisk.HIGH, governor.memoryRisk())
        }
    }
    @Test fun `memory cooldown uses monotonic time and duplicates never extend it`() {
        val governor = governor()
        val signal = ProcessExitSignal(3, Instant.MIN, ProcessExitKind.LOW_MEMORY)
        sample = healthy().copy(recentExits = listOf(signal))
        denied(governor, OrionResourceBlock.MEMORY_EXIT)
        clock.wall = Instant.MAX
        clock.mark = MonotonicInstant(BootSessionId(1), 2_999)
        sample = sample!!.copy(sampledAt = clock.mark)
        denied(governor, OrionResourceBlock.MEMORY_EXIT)
        clock.wall = Instant.MIN
        clock.mark = MonotonicInstant(BootSessionId(1), 3_000)
        sample = sample!!.copy(sampledAt = clock.mark)
        assertTrue(governor.canRunInference())
        sample = sample!!.copy(recentExits = listOf(signal.copy(reason = 99)))
        denied(governor, OrionResourceBlock.MEMORY_EXIT)
    }
    @Test fun `new boot reinitializes the memory fence from previous exit evidence`() {
        val governor = governor()
        sample = healthy().copy(recentExits = listOf(ProcessExitSignal(3, clock.wall, ProcessExitKind.LOW_MEMORY)))
        denied(governor, OrionResourceBlock.MEMORY_EXIT)
        clock.mark = MonotonicInstant(BootSessionId(2), 10)
        sample = sample!!.copy(sampledAt = clock.mark)
        denied(governor, OrionResourceBlock.MEMORY_EXIT)
        clock.mark = MonotonicInstant(BootSessionId(2), 2_010)
        sample = sample!!.copy(sampledAt = clock.mark)
        assertTrue(governor.canRunInference())
    }
    @Test fun `exit history cannot create unbounded policy state`() {
        sample = healthy().copy(recentExits = (0..64).map {
            ProcessExitSignal(it, clock.wall, ProcessExitKind.LOW_MEMORY)
        })
        denied(governor(), OrionResourceBlock.OBSERVATION_INVALID)
    }
    @Test fun `oversized lazy history is rejected before unbounded copying`() {
        var reads = 0
        val huge = object : AbstractList<ProcessExitSignal>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): ProcessExitSignal {
                check(++reads <= 65)
                return ProcessExitSignal(1, clock.wall, ProcessExitKind.NORMAL_EXIT)
            }
        }
        sample = healthy().copy(recentExits = huge)
        assertEquals(OrionResourceBlock.OBSERVATION_INVALID, governor().evaluate().block)
        assertEquals(65, reads)
    }
    @Test fun `provider interruption is preserved for the calling thread`() {
        val interrupted = OrionMinimumResourceGovernor(clock, { throw InterruptedException() }, listOf(plan), 500, 2_000)
        try {
            assertThrows(InterruptedException::class.java) { interrupted.canRunInference() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun `requested settings must fit the exact qualified envelope`() {
        listOf(healthy().copy(requestedThreads = 3), healthy().copy(requestedContext = 513),
            healthy().copy(requestedTokens = 65), healthy().copy(requestedContext = 0)).forEach {
            sample = it; denied(governor(), OrionResourceBlock.REQUEST_LIMITS)
        }
    }
    @Test fun `unavailable provider fails closed but cancellation remains cancellation`() {
        val broken = OrionMinimumResourceGovernor(clock, { error("synthetic") }, listOf(plan), 500, 2_000)
        denied(broken, OrionResourceBlock.OBSERVATION_UNAVAILABLE)
        val cancelled = OrionMinimumResourceGovernor(clock, { throw CancellationException() }, listOf(plan), 500, 2_000)
        assertThrows(CancellationException::class.java) { cancelled.canRunInference() }
    }
    @Test fun `decisions never share mutable exit lists with observers or callers`() {
        val exits = mutableListOf(ProcessExitSignal(1, clock.wall, ProcessExitKind.NORMAL_EXIT))
        sample = healthy().copy(recentExits = exits)
        val decision = governor().evaluate(); exits.clear(); assertEquals(1, decision.recentExits.size)
        assertThrows(UnsupportedOperationException::class.java) { (decision.recentExits as MutableList).clear() }
    }
    @Test fun `configuration rejects ambiguous duplicate profiles or unsafe calibrated bounds`() {
        assertThrows(IllegalArgumentException::class.java) { governor(listOf(plan, plan)) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(maxThermalStatus = 6) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(processBudgetBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { OrionMinimumResourceGovernor(clock, { sample }, listOf(plan), 0, 2_000) }
    }
}

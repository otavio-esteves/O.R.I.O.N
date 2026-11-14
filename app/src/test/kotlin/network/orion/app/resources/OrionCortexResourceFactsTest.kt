package network.orion.app.resources

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.resources.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

/** Mechanical policy/collector fixtures only: no model, profile approval or generation is supplied. */
class OrionCortexResourceFactsTest {
    private class Clock : OrionClock {
        val mark = MonotonicInstant(BootSessionId(7), 1000)
        var reads = 0
        override fun monotonicNow() = mark.also { reads++ }
        override fun wallNow(): Instant = error("No wall-clock observation expected")
    }
    private val model = InferenceModelProfile("a".repeat(64), 100)
    private val plan = OrionInferenceResourcePlan(model, "MECHANICAL_FACT_TEST_ONLY", 1, 128, 8, 0,
        10_000, 20_000, 100, 20, 2)
    private val exit = ProcessExitSignal(2, Instant.EPOCH, ProcessExitKind.SIGNALLED)

    private fun snapshot(clock: Clock, exits: List<ProcessExitSignal>? = emptyList(),
        limitations: Set<OrionResourceObservationLimitation> = emptySet()): OrionAndroidResourceSnapshot {
        val observation = OrionResourceObservation(clock.mark, model, 90, 0, true,
            OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
            200, 300, 50_000, exits, 1, 128, 8)
        return OrionAndroidResourceSnapshot(observation,
            exits?.let { OrionAiExitHistory(clock.mark, it) }, 100, limitations)
    }

    private fun governor(clock: Clock, sample: OrionAndroidResourceSnapshot?) = OrionMinimumResourceGovernor(
        clock, { sample?.observation }, listOf(plan), 1000, 1000)

    @Test fun `recording pairs the collected snapshot with the same decision without another evaluation or clock`() {
        val clock = Clock()
        var observed = 0
        var evaluated = 0
        fun source() = snapshot(clock).also { observed++ }
        val sample = source()
        val governor = OrionMinimumResourceGovernor(clock, { sample.observation.also { evaluated++ } }, listOf(plan), 1000, 1000)
        val decision = governor.evaluate()
        val before = clock.reads
        val buffer = OrionCortexResourceFactBuffer()
        assertSame(decision, buffer.recordGovernorFact(sample, decision))
        assertEquals(1, observed); assertEquals(1, evaluated); assertEquals(before, clock.reads)
        val fact = buffer.drain().facts.single() as OrionCortexResourceFact.Governor
        assertEquals(sample.observation.sampledAt, fact.snapshot?.sampledAt)
        assertEquals(sample.mainPssBytes, fact.snapshot?.mainPssBytes)
        assertEquals(sample.observation.processMemoryBytes, fact.snapshot?.aiPssBytes)
        assertEquals(sample.observation.aggregateAppMemoryBytes, fact.snapshot?.aggregateAppMemoryBytes)
        assertEquals(decision.allowed, fact.decision.allowedAsReported)
        assertEquals(decision.block, fact.decision.block)
        assertEquals(decision.sampledAt, fact.decision.sampledAt)
        assertEquals(decision.estimatedHeadroomBytes, fact.decision.estimatedHeadroomBytes)
        assertEquals(plan.processBudgetBytes, fact.decision.plan?.processBudgetBytes)
        assertEquals(plan.model.modelId, fact.decision.plan?.model?.sha256)
    }

    @Test fun `restart captures actual guard result once without changing fence or another clock read`() {
        val clock = Clock()
        val sample = snapshot(clock, listOf(exit))
        val guard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
        val decision = guard.evaluate(sample.aiExitHistory)
        val before = clock.reads
        val buffer = OrionCortexResourceFactBuffer()
        assertSame(decision, buffer.recordRestartFact(sample, decision))
        assertEquals(before, clock.reads)
        val fact = buffer.drain().facts.single() as OrionCortexResourceFact.Restart
        assertEquals(decision.block, fact.decision.block)
        assertEquals(decision.eligibleForResourceEvaluation, fact.decision.eligibleAsReported)
        assertEquals(decision.memoryDeaths, fact.decision.memoryDeathsAsReported)
        assertEquals(decision.preloadAllowed, fact.decision.preloadAllowedAsReported)
        assertEquals(sample.aiExitHistory?.sampledAt, fact.snapshot?.aiExitHistory?.sampledAt)
        assertEquals(listOf(exit), fact.snapshot?.aiExitHistory?.exits?.entries)
        assertEquals(decision, guard.evaluate(sample.aiExitHistory)) // Recording did not change guard state.
    }

    @Test fun `missing snapshot or history stays unavailable while actual refusal is retained`() {
        val clock = Clock()
        val buffer = OrionCortexResourceFactBuffer()
        val decision = governor(clock, null).evaluate()
        val before = clock.reads
        assertSame(decision, buffer.recordGovernorFact(null, decision))
        val guard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
        val restart = guard.evaluate(null)
        assertSame(restart, buffer.recordRestartFact(null, restart))
        assertEquals(before, clock.reads)
        val facts = buffer.drain().facts
        assertNull(facts[0].snapshot); assertNull(facts[1].snapshot)
        assertEquals(OrionResourceBlock.OBSERVATION_UNAVAILABLE, (facts[0] as OrionCortexResourceFact.Governor).decision.block)
        assertEquals(OrionRestartBlock.HISTORY_UNAVAILABLE, (facts[1] as OrionCortexResourceFact.Restart).decision.block)
        val limits = setOf(OrionResourceObservationLimitation.MEMORY_TELEMETRY_UNAVAILABLE,
            OrionResourceObservationLimitation.EXIT_HISTORY_TRUNCATED)
        val missing = snapshot(clock, null, limits).let { it.copy(observation = it.observation.copy(
            processMemoryBytes = null, aggregateAppMemoryBytes = null, batteryPercent = null), mainPssBytes = null) }
        buffer.recordGovernorFact(missing, governor(clock, missing).evaluate())
        val fact = buffer.drain().facts.single().snapshot
        assertNull(fact?.mainPssBytes); assertNull(fact?.aiPssBytes); assertNull(fact?.aggregateAppMemoryBytes)
        assertNull(fact?.batteryPercent); assertNull(fact?.recentExits?.entries)
        assertNull(fact?.aiExitHistory)
        assertEquals(limits, fact?.limitations)
    }

    @Test fun `default null collector does not project or inspect source collections`() {
        val clock = Clock()
        val good = snapshot(clock)
        val decision = governor(clock, good).evaluate()
        var accessed = 0
        val broken = object : AbstractList<ProcessExitSignal>() {
            override val size: Int get() { accessed++; error("Must not inspect disabled source") }
            override fun get(index: Int): ProcessExitSignal = error("Unexpected projection")
        }
        val sample = good.copy(observation = good.observation.copy(recentExits = broken))
        val buffer: OrionCortexResourceFactBuffer? = null
        val before = clock.reads
        assertSame(decision, buffer.recordGovernorFact(sample, decision))
        assertEquals(0, accessed); assertEquals(before, clock.reads)
    }

    @Test fun `facts retain immutable bounded copies rather than original mutable containers`() {
        val clock = Clock()
        val exits = mutableListOf(exit)
        val limits = mutableSetOf(OrionResourceObservationLimitation.POWER_UNAVAILABLE)
        val sample = snapshot(clock, exits, limits)
        val buffer = OrionCortexResourceFactBuffer()
        buffer.recordGovernorFact(sample, governor(clock, sample).evaluate())
        val batch = buffer.drain()
        exits.clear(); limits.clear()
        val fact = checkNotNull(batch.facts.single().snapshot)
        assertEquals(listOf(exit), fact.recentExits.entries)
        assertEquals(listOf(exit), fact.aiExitHistory?.exits?.entries)
        assertEquals(setOf(OrionResourceObservationLimitation.POWER_UNAVAILABLE), fact.limitations)
        assertThrows(UnsupportedOperationException::class.java) { (batch.facts as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fact.recentExits.entries as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fact.limitations as MutableSet<*>).clear() }
        assertTrue(buffer.drain().facts.isEmpty())
    }

    @Test fun `history projection has its own sentinel without pretending platform completeness`() {
        val clock = Clock()
        var visited = 0
        val oversized = object : AbstractList<ProcessExitSignal>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): ProcessExitSignal { check(++visited <= 130); return exit }
        }
        val sample = snapshot(clock, oversized).copy(aiExitHistory = null)
        val decision = governor(clock, sample).evaluate()
        assertEquals(65, visited)
        val buffer = OrionCortexResourceFactBuffer()
        buffer.recordGovernorFact(sample, decision)
        assertEquals(130, visited)
        val fact = buffer.drain().facts.single() as OrionCortexResourceFact.Governor
        assertEquals(64, fact.snapshot?.recentExits?.entries?.size)
        assertEquals(64, fact.decision.recentExits.entries?.size)
        assertTrue(checkNotNull(fact.snapshot).recentExits.truncatedByCapture)
        assertTrue(fact.decision.recentExits.truncatedByCapture)
        assertTrue(checkNotNull(fact.snapshot).limitations.isEmpty()) // No new platform limitation is invented.
    }

    @Test fun `only fixed hash model identity is retained without tier or arbitrary model content`() {
        val clock = Clock()
        val sample = snapshot(clock).let { it.copy(observation = it.observation.copy(model =
            InferenceModelProfile("MODEL_CONTENT_NOT_RETAINED", 100))) }
        val control = plan.copy(model = checkNotNull(sample.observation.model), tier = "TIER_CONTENT_NOT_RETAINED")
        val governor = OrionMinimumResourceGovernor(clock, { sample.observation }, listOf(control), 1000, 1000)
        val decision = governor.evaluate()
        val buffer = OrionCortexResourceFactBuffer()
        assertSame(decision, buffer.recordGovernorFact(sample, decision))
        val fact = buffer.drain().facts.single() as OrionCortexResourceFact.Governor
        assertNull(fact.snapshot?.model?.sha256); assertNull(fact.decision.plan?.model?.sha256)
        assertFalse(fact.toString().contains("CONTENT_NOT_RETAINED"))
        assertEquals(100L, fact.snapshot?.model?.estimatedMemoryBytes)
    }

    @Test fun `faulty source projection cannot replace original decision and reports loss`() {
        val clock = Clock()
        var broken = false
        val source = object : AbstractList<ProcessExitSignal>() {
            override val size = 1
            override fun get(index: Int): ProcessExitSignal {
                if (broken) throw AssertionError("Scripted diagnostic projection fault")
                return exit
            }
        }
        val sample = snapshot(clock, source)
        val decision = governor(clock, sample).evaluate()
        broken = true
        val before = clock.reads
        val buffer = OrionCortexResourceFactBuffer()
        assertSame(decision, buffer.recordGovernorFact(sample, decision))
        assertEquals(before, clock.reads)
        val batch = buffer.drain()
        assertTrue(batch.facts.isEmpty()); assertEquals(1L, batch.droppedAtLeast)
        assertEquals(1L, batch.recordingFailuresAtLeast); assertFalse(batch.lossAccountingComplete)
    }

    @Test fun `full collector drops new facts without reevaluation and keeps oldest records`() {
        val clock = Clock()
        val sample = snapshot(clock)
        val first = governor(clock, sample).evaluate()
        val secondSample = sample.copy(observation = sample.observation.copy(memoryPressure = OrionMemoryPressure.CRITICAL))
        val second = governor(clock, secondSample).evaluate()
        val before = clock.reads
        val buffer = OrionCortexResourceFactBuffer(1)
        assertSame(first, buffer.recordGovernorFact(sample, first))
        assertSame(second, buffer.recordGovernorFact(secondSample, second))
        assertEquals(before, clock.reads)
        val batch = buffer.drain()
        assertEquals(first.block, (batch.facts.single() as OrionCortexResourceFact.Governor).decision.block)
        assertEquals(1L, batch.droppedAtLeast)
    }

    @Test fun `contended collector and drain return without waiting or projecting`() {
        val clock = Clock()
        val sample = snapshot(clock)
        val decision = governor(clock, sample).evaluate()
        val buffer = OrionCortexResourceFactBuffer()
        val field = buffer.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(buffer) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            assertSame(decision, worker.submit(Callable { buffer.recordGovernorFact(sample, decision) }).get(1, TimeUnit.SECONDS))
            val busy = worker.submit(Callable { buffer.drain() }).get(1, TimeUnit.SECONDS)
            assertTrue(busy.drainContended); assertTrue(busy.facts.isEmpty())
        } finally { lock.unlock(); worker.shutdownNow() }
        val batch = buffer.drain()
        assertEquals(1L, batch.droppedAtLeast); assertEquals(1L, batch.contendedAtLeast)
    }

    @Test fun `concurrent facts remain bounded with lower bound cumulative accounting`() {
        val clock = Clock()
        val sample = snapshot(clock)
        val decision = governor(clock, sample).evaluate()
        val before = clock.reads
        val buffer = OrionCortexResourceFactBuffer(64)
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 4).map { workers.submit {
                start.await(); repeat(1000) { assertSame(decision, buffer.recordGovernorFact(sample, decision)) }
            } }
            start.countDown(); jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            val batch = buffer.drain()
            assertTrue(batch.facts.size <= 64)
            assertTrue(batch.facts.size + batch.droppedAtLeast <= 4000)
            if (batch.lossAccountingComplete) assertEquals(4000L, batch.facts.size + batch.droppedAtLeast)
        } finally { workers.shutdownNow() }
        assertEquals(before, clock.reads)
    }

    @Test fun `collector rejects unbounded or zero capacity`() {
        for (capacity in listOf(0, 65, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { OrionCortexResourceFactBuffer(capacity) }
    }
}

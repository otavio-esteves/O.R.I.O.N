package network.orion.core.resources

import java.time.Instant
import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

class OrionCortexRestartGuardTest {
    private class Clock : OrionClock {
        var mark = MonotonicInstant(BootSessionId(1), 1_000)
        var wall = Instant.EPOCH
        override fun monotonicNow() = mark
        override fun wallNow() = wall
    }
    private val clock = Clock()
    private val base = OrionInferenceResourcePlan(InferenceModelProfile("orion_fixture_large", 300),
        "large", 4, 1024, 128, 500, 1000, 2000, 100, 20, 2)
    private val medium = base.copy(model = InferenceModelProfile("orion_fixture_medium", 200),
        tier = "medium", maxThreads = 2, maxContext = 512, maxTokens = 64, keepAliveMs = 250)
    private val small = medium.copy(model = InferenceModelProfile("orion_fixture_small", 100),
        tier = "small", maxThreads = 1, maxContext = 256, maxTokens = 32, keepAliveMs = 100)
    private val plans = listOf(base, medium, small)
    private fun guard() = OrionCortexRestartGuard(clock, plans, 100, 1_000)
    private fun memory(index: Long = 1) = ProcessExitSignal(3, Instant.ofEpochMilli(index), ProcessExitKind.LOW_MEMORY)
    private fun history(vararg exits: ProcessExitSignal) = OrionAiExitHistory(clock.mark, exits.toList())
    private fun advance(ms: Long) { clock.mark = clock.mark.copy(elapsedRealtimeMillis = clock.mark.elapsedRealtimeMillis + ms) }

    @Test fun `clean history only offers the configured plan for separate resource evaluation`() {
        val decision = guard().evaluate(history())
        assertTrue(decision.eligibleForResourceEvaluation)
        assertEquals(base, decision.candidatePlan)
        assertEquals(0, decision.memoryDeaths)
        assertFalse(decision.degraded)
        assertTrue(decision.preloadAllowed)
    }

    @Test fun `successive distinct memory exits reduce qualified plans then block all further attempts`() {
        val guard = guard()
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history(memory())).block)
        advance(1_000)
        val first = guard.evaluate(history(memory()))
        assertTrue(first.eligibleForResourceEvaluation)
        assertEquals(medium.copy(keepAliveMs = 0), first.candidatePlan)
        assertTrue(first.degraded)
        assertFalse(first.preloadAllowed)
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history(memory(), memory(2))).block)
        advance(1_000)
        assertEquals(small.copy(keepAliveMs = 0), guard.evaluate(history(memory(), memory(2))).candidatePlan)
        val exhausted = guard.evaluate(history(memory(), memory(2), memory(3)))
        assertEquals(OrionRestartBlock.FALLBACK_EXHAUSTED, exhausted.block)
        advance(1_000_000)
        repeat(100) {
            val decision = guard.evaluate(history())
            assertFalse(decision.eligibleForResourceEvaluation)
            assertNull(decision.candidatePlan)
            assertEquals(3, decision.memoryDeaths)
        }
    }

    @Test fun `duplicates do not renew cooldown and history shrinking cannot restore larger plans`() {
        val guard = guard()
        guard.evaluate(history(memory(), memory()))
        advance(999)
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history(memory())).block)
        advance(1)
        val decision = guard.evaluate(history())
        assertTrue(decision.eligibleForResourceEvaluation)
        assertEquals(1, decision.memoryDeaths)
        assertEquals(medium.copy(keepAliveMs = 0), decision.candidatePlan)
    }

    @Test fun `a newly observed exit renews the monotonic fence`() {
        val guard = guard()
        guard.evaluate(history(memory()))
        advance(999)
        guard.evaluate(history(memory(), memory(2)))
        advance(1)
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history(memory(), memory(2))).block)
        advance(999)
        assertTrue(guard.evaluate(history(memory(), memory(2))).eligibleForResourceEvaluation)
    }

    @Test fun `wall rollback and forward cannot shorten cooldown`() {
        val guard = guard()
        guard.evaluate(history(memory()))
        clock.wall = Instant.MAX
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history(memory())).block)
        clock.wall = Instant.MIN
        advance(1_000)
        assertTrue(guard.evaluate(history(memory())).eligibleForResourceEvaluation)
    }

    @Test fun `reboot preserves degraded tier but starts a fresh boot bound cooldown`() {
        val guard = guard()
        guard.evaluate(history(memory()))
        advance(1_000)
        assertTrue(guard.evaluate(history(memory())).eligibleForResourceEvaluation)
        clock.mark = MonotonicInstant(BootSessionId(2), 10)
        assertEquals(OrionRestartBlock.COOLDOWN, guard.evaluate(history()).block)
        advance(1_000)
        assertEquals(medium.copy(keepAliveMs = 0), guard.evaluate(history()).candidatePlan)
    }

    @Test fun `main reconstruction replays historical evidence and cannot bypass a new cooldown`() {
        val prior = guard()
        prior.evaluate(history(memory(), memory(2)))
        advance(1_000)
        assertTrue(prior.evaluate(history(memory(), memory(2))).eligibleForResourceEvaluation)
        val recreated = guard()
        assertEquals(OrionRestartBlock.COOLDOWN, recreated.evaluate(history(memory(), memory(2))).block)
        advance(1_000)
        assertEquals(small.copy(keepAliveMs = 0), recreated.evaluate(history(memory(), memory(2))).candidatePlan)
    }

    @Test fun `missing stale boot invalid future and unknown histories never allow resource evaluation`() {
        val guard = guard()
        assertEquals(OrionRestartBlock.HISTORY_UNAVAILABLE, guard.evaluate(null).block)
        val original = history()
        advance(100)
        assertEquals(OrionRestartBlock.HISTORY_STALE, guard.evaluate(original).block)
        assertEquals(OrionRestartBlock.HISTORY_STALE,
            guard.evaluate(OrionAiExitHistory(MonotonicInstant(BootSessionId(2), 100), emptyList())).block)
        assertEquals(OrionRestartBlock.HISTORY_STALE,
            guard.evaluate(OrionAiExitHistory(clock.mark.copy(elapsedRealtimeMillis = 1_101), emptyList())).block)
        assertEquals(OrionRestartBlock.HISTORY_INVALID,
            guard.evaluate(history(ProcessExitSignal(0, Instant.EPOCH))).block)
        clock.mark = MonotonicInstant(null, 0)
        assertEquals(OrionRestartBlock.HISTORY_STALE, guard.evaluate(history()).block)
    }

    @Test fun `platform memory limiter encodings all force degradation before next evaluation`() {
        listOf(memory(), memory().copy(reason = 99, kind = ProcessExitKind.MEMORY_LIMITER),
            memory().copy(reason = 13, kind = ProcessExitKind.OTHER_SYSTEM, memoryLimiterEvidence = true),
            memory().copy(kind = ProcessExitKind.UNKNOWN)).forEach { exit ->
            val decision = guard().evaluate(history(exit))
            assertEquals(OrionRestartBlock.COOLDOWN, decision.block)
            assertTrue(decision.degraded)
            assertFalse(decision.preloadAllowed)
        }
    }

    @Test fun `oversized lazy history is rejected after a bounded copy`() {
        var reads = 0
        val huge = object : AbstractList<ProcessExitSignal>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): ProcessExitSignal {
                check(++reads <= 65)
                return memory(index.toLong())
            }
        }
        val decision = guard().evaluate(OrionAiExitHistory(clock.mark, huge))
        assertEquals(65, reads)
        assertEquals(OrionRestartBlock.HISTORY_INVALID, decision.block)
        assertFalse(decision.eligibleForResourceEvaluation)
    }

    @Test fun `all configuration must be bounded and each fallback must reduce qualified demand`() {
        fun rejected(plans: List<OrionInferenceResourcePlan>) {
            assertThrows(IllegalArgumentException::class.java) { OrionCortexRestartGuard(clock, plans, 100, 1_000) }
        }
        rejected(emptyList())
        rejected(List(65) { base })
        rejected(listOf(base, base))
        rejected(listOf(base, medium.copy(model = medium.model.copy(modelId = base.model.modelId))))
        rejected(listOf(base, medium.copy(maxThreads = 5)))
        rejected(listOf(base, medium.copy(maxContext = 1025)))
        rejected(listOf(base, medium.copy(maxTokens = 129)))
        rejected(listOf(base, medium.copy(keepAliveMs = 501)))
        assertThrows(IllegalArgumentException::class.java) { OrionCortexRestartGuard(clock, plans, 0, 1_000) }
        assertThrows(IllegalArgumentException::class.java) { OrionCortexRestartGuard(clock, plans, 100, 0) }
    }
}

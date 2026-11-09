package network.orion.core.resources

import java.time.Instant
import network.orion.core.exitinfo.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionAiRestartGuardTest {
    private class Clock : MonotonicClock {
        var now = MonotonicInstant(BootSessionId(1), 1_000)
        override fun monotonicNow() = now
    }
    private val clock = Clock()
    private fun guard() = OrionAiRestartGuard(clock, 3, 100, 1_000, 2)
    private fun limiter(index: Long = 1) = ProcessExitSignal(99, Instant.ofEpochSecond(index), ProcessExitKind.MEMORY_LIMITER, true)
    private fun attempt(guard: OrionAiRestartGuard, exits: List<ProcessExitSignal>? = emptyList()): Boolean {
        guard.observeExits(exits)
        return guard.allowAttempt()
    }

    @Test fun `every explicit attempt needs available freshly supplied exit history`() {
        val guard = guard()
        assertFalse(guard.allowAttempt())
        assertTrue(attempt(guard))
        assertFalse(guard.allowAttempt())
        clock.now = MonotonicInstant(BootSessionId(1), 1_100)
        assertFalse(attempt(guard, null))
        assertTrue(attempt(guard))
    }

    @Test fun `launch interval and total attempts prevent an automatic rebind loop`() {
        val guard = guard()
        assertTrue(attempt(guard))
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_100)
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_200)
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 100_000)
        assertFalse(attempt(guard))
    }

    @Test fun `new memory event fences launch and duplicate snapshots never extend the fence`() {
        val guard = guard()
        val signal = limiter()
        assertFalse(attempt(guard, listOf(signal)))
        clock.now = MonotonicInstant(BootSessionId(1), 1_999)
        assertFalse(attempt(guard, listOf(signal)))
        clock.now = MonotonicInstant(BootSessionId(1), 2_000)
        assertTrue(attempt(guard, listOf(signal)))
        assertFalse(attempt(guard, listOf(signal, limiter(2))))
        clock.now = MonotonicInstant(BootSessionId(1), 999_999)
        assertFalse(attempt(guard, listOf(signal, limiter(2))))
    }

    @Test fun `history reapplies the safety fence after core reconstruction and reboot`() {
        val signal = limiter()
        val guard = guard()
        assertFalse(attempt(guard, listOf(signal)))
        clock.now = MonotonicInstant(BootSessionId(1), 2_000)
        assertTrue(attempt(guard, listOf(signal)))
        assertFalse(attempt(guard(), listOf(signal)))
        clock.now = MonotonicInstant(BootSessionId(2), 10)
        assertFalse(attempt(guard, listOf(signal)))
        clock.now = MonotonicInstant(BootSessionId(2), 1_010)
        assertTrue(attempt(guard, listOf(signal)))
    }

    @Test fun `death callback discards permission and repeated known memory deaths open circuit`() {
        val guard = guard()
        guard.observeExits(emptyList())
        guard.recordDeath(false)
        assertFalse(guard.allowAttempt())
        guard.recordDeath(true)
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 2_000)
        assertTrue(attempt(guard))
        guard.recordDeath(true)
        clock.now = MonotonicInstant(BootSessionId(1), 3_000)
        assertFalse(attempt(guard))
    }

    @Test fun `unknown boot unknown exits oversized history and monotonic rollback all deny`() {
        val guard = guard()
        assertFalse(attempt(guard, listOf(ProcessExitSignal(0, Instant.EPOCH))))
        assertFalse(attempt(guard, List(65) { limiter(it.toLong()) }))
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 999)
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(null, 2_000)
        assertFalse(attempt(guard))
    }

    @Test fun `all memory encodings fence even without a dedicated public reason`() {
        for (signal in listOf(ProcessExitSignal(3, Instant.EPOCH),
            ProcessExitSignal(13, Instant.EPOCH, ProcessExitKind.OTHER_SYSTEM, true),
            ProcessExitSignal(99, Instant.EPOCH, ProcessExitKind.MEMORY_LIMITER))) {
            assertFalse(attempt(guard(), listOf(signal)))
        }
    }

    @Test fun `guard accepts no unbounded or zero operational configuration`() {
        assertThrows(IllegalArgumentException::class.java) { OrionAiRestartGuard(clock, 0, 100, 1_000, 2) }
        assertThrows(IllegalArgumentException::class.java) { OrionAiRestartGuard(clock, 3, 0, 1_000, 2) }
        assertThrows(IllegalArgumentException::class.java) { OrionAiRestartGuard(clock, 3, 100, 0, 2) }
        assertThrows(IllegalArgumentException::class.java) { OrionAiRestartGuard(clock, 3, 100, 1_000, 65) }
    }

    @Test fun `temporarily unavailable boot identity never resets a tripped circuit`() {
        val guard = guard()
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_100)
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_200)
        assertTrue(attempt(guard))
        clock.now = MonotonicInstant(null, 2_000)
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 2_100)
        assertFalse(attempt(guard))
    }

    @Test fun `unknown boot memory callback retains evidence and starts a full cooldown when restored`() {
        val guard = guard()
        clock.now = MonotonicInstant(null, 100)
        guard.recordDeath(true)
        clock.now = MonotonicInstant(BootSessionId(1), 200)
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_199)
        assertFalse(attempt(guard))
        clock.now = MonotonicInstant(BootSessionId(1), 1_200)
        assertTrue(attempt(guard))
    }
}

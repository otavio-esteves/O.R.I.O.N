package network.orion.ai.service

import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionNativeUnresponsiveWatchdogTest {
    private class Clock : MonotonicClock {
        var now = MonotonicInstant(BootSessionId(1), 1_000)
        override fun monotonicNow() = now
    }
    private class Scheduler : OrionWatchdogScheduler {
        class Task(val delay: Long, val action: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
        var closed = false
        var beforeReturn: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, task: () -> Unit): OrionWatchdogCancellation {
            check(!closed)
            val scheduled = Task(delayMillis, task).also(tasks::add)
            beforeReturn?.invoke()
            return OrionWatchdogCancellation { scheduled.cancelled = true }
        }
        override fun close() { closed = true }
        fun fire(index: Int = tasks.lastIndex) { tasks[index].action() }
    }
    private val clock = Clock()
    private val scheduler = Scheduler()
    private var kills = 0
    private fun guard() = OrionNativeUnresponsiveWatchdog(clock, 5_000, scheduler) { kills++ }

    @Test fun `normal native work never starts a polling timer or terminates the process`() {
        val guard = guard()
        val work = guard.beginWork()
        clock.now = clock.now.copy(elapsedRealtimeMillis = Long.MAX_VALUE)
        guard.finishWork(work)
        assertTrue(scheduler.tasks.isEmpty()); assertEquals(0, kills)
    }

    @Test fun `one stuck armed operation escalates once at the monotonic deadline`() {
        val guard = guard(); val work = guard.beginWork(); guard.arm(work)
        assertEquals(5_000L, scheduler.tasks.single().delay)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 5_999)
        scheduler.fire(); assertEquals(0, kills)
        assertEquals(1L, scheduler.tasks.last().delay)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 6_000)
        scheduler.fire(); scheduler.fire()
        assertEquals(1, kills); assertTrue(scheduler.closed)
        assertThrows(IllegalStateException::class.java) { guard.beginWork() }
    }

    @Test fun `duplicate cancellation arms preserve the first grace deadline`() {
        val guard = guard(); val work = guard.beginWork(); guard.arm(work)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 5_000)
        repeat(100) { guard.arm(work); guard.armAll() }
        assertEquals(1, scheduler.tasks.size)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 6_000)
        scheduler.fire(); assertEquals(1, kills)
    }

    @Test fun `worker completion before deadline cancels its future and stale callbacks cannot kill`() {
        val guard = guard(); val work = guard.beginWork(armed = true)
        guard.finishWork(work); guard.finishWork(work)
        assertTrue(scheduler.tasks.single().cancelled)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 100_000)
        scheduler.fire(); assertEquals(0, kills)
        assertEquals(1, scheduler.tasks.size)
    }

    @Test fun `finishing an old ticket cannot cancel or terminate unrelated new work`() {
        val guard = guard(); val old = guard.beginWork(armed = true)
        guard.finishWork(old)
        val current = guard.beginWork()
        clock.now = clock.now.copy(elapsedRealtimeMillis = 100_000)
        scheduler.fire(0); guard.arm(old); guard.finishWork(old)
        assertEquals(0, kills); assertEquals(1, scheduler.tasks.size)
        guard.finishWork(current)
    }

    @Test fun `close while busy survives destruction and terminates a stuck cleanup`() {
        val guard = guard(); val native = guard.beginWork()
        val cleanup = guard.beginWork(armed = true)
        guard.closeWhenQuiet()
        assertFalse(scheduler.closed)
        guard.finishWork(native)
        assertFalse(scheduler.closed)
        clock.now = clock.now.copy(elapsedRealtimeMillis = 6_000)
        scheduler.fire(0)
        assertEquals(1, kills); assertTrue(scheduler.closed)
        guard.finishWork(cleanup)
    }

    @Test fun `normal unload and completed destruction close quietly without killing`() {
        val guard = guard(); val cleanup = guard.beginWork(armed = true)
        guard.closeWhenQuiet(); assertFalse(scheduler.closed)
        guard.finishWork(cleanup)
        assertTrue(scheduler.closed); assertTrue(scheduler.tasks.single().cancelled)
        scheduler.fire(); assertEquals(0, kills)
    }

    @Test fun `closing an idle guard creates no timer`() {
        val guard = guard(); guard.closeWhenQuiet(); guard.closeWhenQuiet()
        assertTrue(scheduler.closed); assertTrue(scheduler.tasks.isEmpty()); assertEquals(0, kills)
    }

    @Test fun `unknown boot changed boot or monotonic rollback cannot extend an armed grace forever`() {
        listOf(MonotonicInstant(null, 2_000), MonotonicInstant(BootSessionId(2), 2_000),
            MonotonicInstant(BootSessionId(1), 999)).forEach { invalid ->
            val clock = Clock(); val scheduler = Scheduler(); var killed = 0
            val guard = OrionNativeUnresponsiveWatchdog(clock, 5_000, scheduler) { killed++ }
            val work = guard.beginWork(armed = true)
            clock.now = invalid
            scheduler.fire(); assertEquals(1, killed)
            guard.finishWork(work)
        }
    }

    @Test fun `missing boot at arm schedules immediate safe self escalation only for busy work`() {
        clock.now = MonotonicInstant(null, 1_000)
        val guard = guard(); guard.beginWork(armed = true)
        assertEquals(0L, scheduler.tasks.single().delay)
        scheduler.fire(); assertEquals(1, kills)
    }

    @Test fun `completion during scheduler registration still cancels the published future`() {
        val guard = guard(); val work = guard.beginWork()
        scheduler.beforeReturn = { guard.finishWork(work) }
        guard.arm(work)
        assertTrue(scheduler.tasks.single().cancelled)
        scheduler.fire(); assertEquals(0, kills)
    }
}

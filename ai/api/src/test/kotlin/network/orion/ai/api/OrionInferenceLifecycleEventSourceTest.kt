package network.orion.ai.api

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic lifecycle events only: no runOnce/app association, delivery, native or physical proof. */
class OrionInferenceLifecycleEventSourceTest {
    private class Clock : MonotonicClock {
        var at = MonotonicInstant(BootSessionId(7), 100)
        var failure: Throwable? = null
        val reads = mutableListOf<MonotonicInstant>()
        override fun monotonicNow(): MonotonicInstant {
            failure?.let { throw it }
            reads.add(at); return at
        }
    }
    private fun eventClock(values: List<Long?> = List(256) { 100L }): OrionObservationEventClock {
        // The finite source stays internal in core/time. Reflection is confined to this JVM fixture.
        val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
            it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        }
        method.isAccessible = true
        return method.invoke(OrionObservationEventClock.Companion, values) as OrionObservationEventClock
    }
    private fun extendedFinite(): OrionObservationEventClock {
        // Test-only capacity proof: extend the private finite scalar collection, not its production API.
        // No native stub, arbitrary callback or unbounded time source is introduced.
        val clock = eventClock(listOf(100L))
        val counter = clock.javaClass.getDeclaredField("counter").apply { isAccessible = true }.get(clock)
        counter.javaClass.getDeclaredField("values").apply { isAccessible = true }.set(counter, List(4099) { 100L })
        return clock
    }
    private val prepared = MonotonicInstant(BootSessionId(7), 100)
    private fun request(schema: Int = 1) = OrionInferenceRequest("request-private", schema, "model-private",
        "PROMPT_SECRET", "CONTEXT_SECRET", 128, 0.0, OrionInferencePriority.INTERACTIVE,
        prepared.deadlineAfter(1000), "correlation-private")
    private fun chunk(sequence: Long = 0, text: String? = null, tool: String? = null,
        terminal: OrionInferenceTerminal? = null, id: String = "request-private", schema: Int = 1) =
        OrionInferenceChunk(id, schema, sequence, text, tool, terminal != null, terminal)
    private fun state(source: OrionInferenceLifecycleEventSource) = checkNotNull(source.snapshot())

    @Test fun `construction is inactive and activation uses only the existing original witness once`() {
        val clock = Clock(); val time = eventClock(); val source = OrionInferenceLifecycleEventSource(time)
        val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
        assertEquals(1, clock.reads.size); assertFalse(state(source).active)
        assertFalse(checkNotNull(time.snapshot()).bound)
        assertTrue(lifecycle.activateEventCapture())
        assertEquals(1, clock.reads.size); assertTrue(checkNotNull(time.snapshot()).bound)
        assertEquals(0L, checkNotNull(time.snapshot()).counterReadsAtLeast)
        assertFalse(lifecycle.activateEventCapture()); assertTrue(state(source).invalidated)
        assertEquals(1, clock.reads.size)
    }

    @Test fun `terminal before activation is unavailable and never recaptured or retimestamped`() {
        for (schema in listOf(1, 2)) {
            val clock = Clock(); val time = eventClock(); val source = OrionInferenceLifecycleEventSource(time)
            val lifecycle = OrionInferenceLifecycle(request(schema), clock, events = source)
            if (schema == 1) lifecycle.cancel()
            val before = clock.reads.toList()
            assertFalse(lifecycle.activateEventCapture())
            assertEquals(before, clock.reads); assertNull(state(source).firstTerminal)
            assertTrue(state(source).firstTerminalRecordUnavailable)
            assertEquals(0L, checkNotNull(time.snapshot()).counterReadsAtLeast)
        }
    }

    @Test fun `absent present and unavailable sources preserve original calls output and identities`() {
        val inputs = listOf(chunk(2, tool = "工具", terminal = OrionInferenceTerminal.SUCCEEDED),
            chunk(1, "é", "🌍"), chunk(1, "é", "🌍"), chunk(0, "", ""), chunk(0, "LATE_SECRET"))
        fun run(source: OrionInferenceLifecycleEventSource?): Pair<List<Any>, List<MonotonicInstant>> {
            val clock = Clock(); val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
            lifecycle.activateEventCapture()
            val results = mutableListOf<Any>(lifecycle.status(), lifecycle.markLoading(), lifecycle.markDecoding())
            inputs.forEachIndexed { index, input ->
                val update = lifecycle.accept(input)
                results.add(update.receipt); results.add(update.status); results.add(update.chunks)
                if (index == 3) {
                    assertSame(inputs[3], update.chunks[0]); assertSame(inputs[1], update.chunks[1]); assertSame(inputs[0], update.chunks[2])
                }
            }
            val terminal = lifecycle.status()
            assertSame(terminal, lifecycle.cancel()); assertSame(terminal, lifecycle.processDied()); assertSame(terminal, lifecycle.failTransport())
            results.add(terminal)
            return results to clock.reads
        }
        val source = OrionInferenceLifecycleEventSource(eventClock())
        val absent = run(null)
        assertEquals(absent, run(source)); assertEquals(absent, run(OrionInferenceLifecycleEventSource(null)))
        assertEquals(listOf(0L, 1L, 2L), state(source).orderedEvents.map { it.metadata.sequence })
        assertTrue(state(source).chunkSequenceIntact)
        assertSame(state(source).firstTerminal?.at, state(source).orderedEvents.last().at)
        assertEquals(3, state(source).orderedEvents.size)
    }

    @Test fun `real event times differ from old deadline samples and final shares the terminal moment`() {
        val clock = Clock(); val source = OrionInferenceLifecycleEventSource(eventClock(listOf(111, 121)))
        val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
        assertTrue(lifecycle.activateEventCapture())
        clock.at = MonotonicInstant(BootSessionId(7), 110)
        lifecycle.accept(chunk(0, "é", "🌍"))
        clock.at = MonotonicInstant(BootSessionId(7), 120)
        val result = lifecycle.accept(chunk(1, terminal = OrionInferenceTerminal.SUCCEEDED))
        val captured = state(source)
        assertEquals(listOf(111L, 121L), captured.orderedEvents.map { it.at.elapsedRealtimeMillis })
        assertEquals(listOf(100L, 110L, 120L), clock.reads.map { it.elapsedRealtimeMillis })
        assertSame(captured.firstTerminal?.at, captured.orderedEvents.last().at)
        assertSame(result.status, captured.firstTerminal?.status)
    }

    @Test fun `original byte carrier works without old collector and revalidates pending parts`() {
        val source = OrionInferenceLifecycleEventSource(eventClock()); val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
        lifecycle.activateEventCapture()
        val pending = chunk(1, "é", "🌍", OrionInferenceTerminal.SUCCEEDED)
        lifecycle.accept(pending)
        pending.javaClass.getDeclaredField("textDelta").apply { isAccessible = true }.set(pending, "🌍")
        pending.javaClass.getDeclaredField("toolDelta").apply { isAccessible = true }.set(pending, "é")
        val update = lifecycle.accept(chunk(0, "", ""))
        assertSame(pending, update.chunks.last())
        val metadata = state(source).orderedEvents.map { it.metadata }
        assertEquals(listOf(0, 4), metadata.map { it.validatedTextDeltaUtf8Bytes })
        assertEquals(listOf(0, 2), metadata.map { it.validatedToolDeltaUtf8Bytes })
        assertEquals(listOf(0, 6), metadata.map { it.validatedTotalContentUtf8Bytes })
    }

    @Test fun `one shared carrier contains faults and preserves original guard totals and known zeros`() {
        val old = OrionInferenceLifecycleFactBuffer()
        val source = OrionInferenceLifecycleEventSource(eventClock())
        val lifecycle = OrionInferenceLifecycle(request(), Clock(), old, source)
        lifecycle.activateEventCapture()
        val carrier = checkNotNull(source.byteParts(old))
        carrier.record(-1, 0, 0)
        assertEquals(-1L, carrier.packedParts)
        assertTrue(state(source).recordingFailuresAtLeast > 0)
        assertTrue(checkNotNull(old.snapshot()).recordingFailuresAtLeast > 0)
        for (input in listOf(chunk(), chunk(text = ""), chunk(tool = ""), chunk(text = "é", tool = "🌍"),
            chunk(text = "x".repeat(65537)))) {
            assertEquals(input.boundedContentBytes(65536), input.boundedContentBytes(65536, carrier))
        }
        val clean = OrionInferenceLifecycleEventSource(eventClock())
        val other = OrionInferenceLifecycle(request(), Clock(), events = clean)
        other.activateEventCapture(); val update = other.accept(chunk())
        val list = update.chunks
        val snapshot = state(clean)
        assertSame(list, update.chunks); assertEquals(0, snapshot.orderedEvents.single().metadata.validatedTextDeltaUtf8Bytes)
        assertEquals(0, snapshot.orderedEvents.single().metadata.validatedToolDeltaUtf8Bytes)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.orderedEvents as MutableList<OrionLifecycleOrderedEvent>).clear()
        }
        assertFalse(snapshot.toString().contains("SECRET"))
    }

    @Test fun `unknown or invalid original witness cannot activate or replace original outcomes`() {
        for (at in listOf(MonotonicInstant(null, 100), MonotonicInstant(BootSessionId(8), 100),
            MonotonicInstant(BootSessionId(7), 99))) {
            val clock = Clock().apply { this.at = at }
            val time = eventClock(); val source = OrionInferenceLifecycleEventSource(time)
            val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
            val result = lifecycle.status()
            val reads = clock.reads.toList()
            assertFalse(lifecycle.activateEventCapture()); assertEquals(reads, clock.reads)
            assertEquals(OrionInferenceFailure.INVALID_TIME_REFERENCE, result.failure)
            assertNull(state(source).firstTerminal); assertTrue(state(source).firstTerminalRecordUnavailable)
            assertEquals(0L, checkNotNull(time.snapshot()).counterReadsAtLeast)
        }
    }

    @Test fun `missing or regressed observational counter does not fail an original accepted update`() {
        for (value in listOf<Long?>(null, -1L, 99L)) {
            val clock = Clock(); val source = OrionInferenceLifecycleEventSource(eventClock(listOf(value)))
            val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
            lifecycle.activateEventCapture()
            val input = chunk()
            val update = lifecycle.accept(input)
            assertEquals(OrionInferenceReceipt.ACCEPTED, update.receipt); assertSame(input, update.chunks.single())
            assertEquals(2, clock.reads.size); assertTrue(state(source).invalidated)
            assertFalse(state(source).chunkSequenceIntact); assertTrue(state(source).orderedEvents.isEmpty())
            val result = lifecycle.cancel()
            assertSame(result, state(source).firstTerminal?.status); assertTrue(state(source).firstTerminalRecordUnavailable)
        }
    }

    @Test fun `one source cannot attach to another lifecycle or obtain a fresh binding`() {
        val source = OrionInferenceLifecycleEventSource(eventClock())
        val original = OrionInferenceLifecycle(request(), Clock(), events = source)
        assertTrue(original.activateEventCapture())
        val other = OrionInferenceLifecycle(request(), Clock(), events = source)
        assertFalse(other.activateEventCapture()); assertTrue(state(source).invalidated)
        assertEquals(OrionInferenceTerminal.CANCELLED, original.cancel().terminal)
        assertTrue(state(source).firstTerminalRecordUnavailable)
    }

    @Test fun `original pending revalidation failure leaves the staged update unpublished`() {
        val source = OrionInferenceLifecycleEventSource(eventClock()); val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
        lifecycle.activateEventCapture()
        val pending = chunk(1, "é")
        lifecycle.accept(pending)
        pending.javaClass.getDeclaredField("textDelta").apply { isAccessible = true }.set(pending, "é".repeat(32769))
        assertThrows(IllegalStateException::class.java) { lifecycle.accept(chunk(0)) }
        val captured = state(source)
        assertTrue(captured.orderedEvents.isEmpty()); assertFalse(captured.chunkSequenceIntact)
        assertTrue(captured.pendingUpdatesAtLeast > 0)
        assertTrue(OrionLifecycleEventGap.UNPUBLISHED_UPDATE in captured.gaps)
    }

    @Test fun `optional publication Error cannot replace the real lifecycle return or reserved terminal`() {
        val clock = Clock(); val source = OrionInferenceLifecycleEventSource(eventClock())
        val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
        lifecycle.activateEventCapture()
        // Test-only corruption of the existing optional storage; no production fault callback/factory.
        val poisoned = object : ArrayList<OrionLifecycleOrderedEvent>() {
            override fun add(element: OrionLifecycleOrderedEvent): Boolean = throw AssertionError("OPTIONAL_SECRET")
        }
        source.javaClass.getDeclaredField("ordered").apply { isAccessible = true }.set(source, poisoned)
        val input = chunk()
        val update = lifecycle.accept(input)
        assertEquals(OrionInferenceReceipt.ACCEPTED, update.receipt); assertSame(input, update.chunks.single())
        assertEquals(2, clock.reads.size); assertTrue(state(source).recordingFailuresAtLeast > 0)
        val result = lifecycle.cancel()
        assertSame(result, state(source).firstTerminal?.status)
        assertFalse(state(source).chunkSequenceIntact); assertFalse(state(source).toString().contains("OPTIONAL_SECRET"))
    }

    @Test fun `first true terminals preserve origin and never get overwritten`() {
        for (origin in listOf(OrionLifecycleTerminalOrigin.CANCELLATION, OrionLifecycleTerminalOrigin.TRANSPORT_FAILURE,
            OrionLifecycleTerminalOrigin.PROCESS_DEATH, OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED,
            OrionLifecycleTerminalOrigin.INVALID_TIME, OrionLifecycleTerminalOrigin.REJECTED_CHUNK)) {
            val clock = Clock(); val source = OrionInferenceLifecycleEventSource(eventClock(List(8) { 1200L }))
            val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
            lifecycle.activateEventCapture()
            val terminal = when (origin) {
                OrionLifecycleTerminalOrigin.CANCELLATION -> lifecycle.cancel()
                OrionLifecycleTerminalOrigin.TRANSPORT_FAILURE -> lifecycle.failTransport()
                OrionLifecycleTerminalOrigin.PROCESS_DEATH -> lifecycle.processDied()
                OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED -> { clock.at = MonotonicInstant(BootSessionId(7), 1100); lifecycle.status() }
                OrionLifecycleTerminalOrigin.INVALID_TIME -> { clock.at = MonotonicInstant(null, 110); lifecycle.status() }
                else -> lifecycle.accept(chunk(schema = 2)).status
            }
            val first = checkNotNull(state(source).firstTerminal)
            assertSame(terminal, first.status); assertEquals(origin, first.origin)
            lifecycle.cancel(); lifecycle.processDied(); lifecycle.failTransport(); lifecycle.accept(chunk())
            assertSame(first, state(source).firstTerminal)
            if (origin == OrionLifecycleTerminalOrigin.INVALID_TIME) {
                assertNull(first.at); assertTrue(state(source).firstTerminalRecordUnavailable)
            }
        }
    }

    @Test fun `original clock throwable identities and reads are unchanged even with failed capture`() {
        for (failure in listOf(IllegalStateException("ORIGINAL"), AssertionError("ORIGINAL"))) {
            for (enabled in listOf(false, true)) {
                val clock = Clock(); val source = if (enabled) OrionInferenceLifecycleEventSource(null) else null
                val lifecycle = OrionInferenceLifecycle(request(), clock, events = source)
                lifecycle.activateEventCapture(); clock.failure = failure
                for (operation in listOf<() -> Any>({ lifecycle.status() }, { lifecycle.accept(chunk()) },
                    { lifecycle.cancel() }, { lifecycle.processDied() })) {
                    val seen = try { operation(); null } catch (caught: Throwable) { caught }
                    assertSame(failure, seen); assertEquals(1, clock.reads.size)
                }
            }
        }
    }

    @Test fun `capacity is independent from the old32 facts and includes4097 ordered events`() {
        val time = extendedFinite(); val source = OrionInferenceLifecycleEventSource(time)
        val old = OrionInferenceLifecycleFactBuffer(); val clock = Clock()
        val lifecycle = OrionInferenceLifecycle(request(), clock, old, source)
        lifecycle.activateEventCapture()
        repeat(4097) { index ->
            lifecycle.accept(chunk(index.toLong(), terminal = if (index == 4096) OrionInferenceTerminal.SUCCEEDED else null))
        }
        val captured = state(source)
        assertEquals(4097, captured.orderedEvents.size); assertTrue(captured.chunkSequenceIntact)
        assertEquals(32, checkNotNull(old.snapshot()).updates.size)
        assertEquals(4098, clock.reads.size) // Original init plus one check per accepted input.
        assertEquals(4097L, checkNotNull(time.snapshot()).counterReadsAtLeast) // Final shares one terminal read.
        assertSame(captured.firstTerminal?.at, captured.orderedEvents.last().at)
        assertFalse(captured.sourceAuthenticated); assertFalse(captured.runtimeEvidenceV1Complete); assertFalse(captured.f6Authorized)
    }

    @Test fun `terminal reservation survives chunk saturation and optional clock failure`() {
        val time = extendedFinite(); val source = OrionInferenceLifecycleEventSource(time)
        val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
        lifecycle.activateEventCapture()
        repeat(4098) { index -> lifecycle.accept(chunk(index.toLong())) }
        lifecycle.accept(chunk(4098, terminal = OrionInferenceTerminal.SUCCEEDED))
        assertEquals(4097, state(source).orderedEvents.size)
        assertNotNull(state(source).firstTerminal); assertFalse(state(source).chunkSequenceIntact)
        assertTrue(OrionLifecycleEventGap.CAPACITY in state(source).gaps)
        val missing = OrionInferenceLifecycleEventSource(eventClock(listOf(null)))
        val other = OrionInferenceLifecycle(request(), Clock(), events = missing)
        other.activateEventCapture(); val result = other.cancel()
        assertSame(result, state(missing).firstTerminal?.status)
        assertNull(state(missing).firstTerminal?.at); assertTrue(state(missing).firstTerminalRecordUnavailable)
    }

    @Test fun `rejections and buffered admissions publish no ordered events`() {
        for (input in listOf(chunk(id = "other"), chunk(schema = 2), chunk(9), chunk(Long.MAX_VALUE),
            chunk(text = "x".repeat(65537)), chunk(tool = "é".repeat(32769)), chunk(1))) {
            val source = OrionInferenceLifecycleEventSource(eventClock()); val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
            lifecycle.activateEventCapture(); lifecycle.accept(input)
            assertTrue(state(source).orderedEvents.isEmpty())
        }
        val source = OrionInferenceLifecycleEventSource(eventClock()); val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
        lifecycle.activateEventCapture(); lifecycle.accept(chunk(1, "é")); lifecycle.accept(chunk(1, "é"))
        lifecycle.accept(chunk(1, "different"))
        assertTrue(state(source).orderedEvents.isEmpty())
        assertEquals(OrionInferenceFailure.CONFLICTING_CHUNK, state(source).firstTerminal?.status?.failure)
    }

    @Test fun `unavailable bytes stay null and contention never changes the original result`() {
        val source = OrionInferenceLifecycleEventSource(eventClock()); val lifecycle = OrionInferenceLifecycle(request(), Clock(), events = source)
        lifecycle.activateEventCapture()
        val staged = checkNotNull(source.beginAccept())
        staged.ordered(0, false, null, null, 6, -1L)
        source.publish(staged, OrionInferenceUpdate(OrionInferenceReceipt.ACCEPTED, emptyList(), lifecycle.status()))
        val metadata = state(source).orderedEvents.single().metadata
        assertEquals(6, metadata.validatedTotalContentUtf8Bytes); assertNull(metadata.validatedTextDeltaUtf8Bytes)
        assertNull(metadata.validatedToolDeltaUtf8Bytes); assertFalse(state(source).chunkSequenceIntact)
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source) as ReentrantLock
        val executor = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            assertEquals(OrionInferenceTerminal.CANCELLED,
                executor.submit(Callable { lifecycle.cancel().terminal }).get(5, TimeUnit.SECONDS))
        } finally { lock.unlock(); executor.shutdownNow() }
        assertTrue(state(source).firstTerminalRecordUnavailable)
    }
}

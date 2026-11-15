package network.orion.ai.api

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Mechanical source facts only: no native, delivery timing or hardware qualification. */
class OrionInferenceLifecycleFactsTest {
    private class Clock : MonotonicClock {
        var at = MonotonicInstant(BootSessionId(1), 100)
        val reads = mutableListOf<MonotonicInstant>()
        var failure: Throwable? = null
        override fun monotonicNow(): MonotonicInstant {
            failure?.let { throw it }
            reads.add(at)
            return at
        }
    }
    private val start = MonotonicInstant(BootSessionId(1), 100)
    private fun request(schema: Int = 1) = OrionInferenceRequest("private-request", schema, "private-model",
        "PRIVATE_PROMPT", "PRIVATE_CONTEXT", 128, 0.5, OrionInferencePriority.INTERACTIVE, start.deadlineAfter(100), "private-correlation")
    private fun chunk(sequence: Long, text: String? = "é", tool: String? = null,
        terminal: OrionInferenceTerminal? = null, id: String = "private-request", schema: Int = 1) =
        OrionInferenceChunk(id, schema, sequence, text, tool, terminal != null, terminal)
    private fun snapshot(buffer: OrionInferenceLifecycleFactBuffer) = checkNotNull(buffer.snapshot())
    private fun releases(fact: OrionLifecycleUpdateFact) = checkNotNull(fact.orderedRelease)

    @Test fun `observation preserves clock order results and original chunk identities`() {
        val input = listOf(chunk(2, terminal = OrionInferenceTerminal.SUCCEEDED), chunk(1), chunk(1),
            chunk(0, tool = "工具"), chunk(2), chunk(0, id = "other"))
        fun run(buffer: OrionInferenceLifecycleFactBuffer?): Pair<List<Any>, List<MonotonicInstant>> {
            val clock = Clock(); val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
            val output = mutableListOf<Any>(lifecycle.status(), lifecycle.markLoading(), lifecycle.markDecoding())
            for ((index, value) in input.withIndex()) {
                clock.at = MonotonicInstant(BootSessionId(1), 110L + index)
                val update = lifecycle.accept(value)
                output.add(update.receipt); output.add(update.status); output.add(update.chunks)
                if (index == 3) input.take(2).reversed().forEachIndexed { released, original ->
                    assertSame(original, update.chunks[released + 1])
                }
            }
            output.add(lifecycle.cancel()); output.add(lifecycle.failTransport()); output.add(lifecycle.processDied())
            return output to clock.reads
        }
        val buffer = OrionInferenceLifecycleFactBuffer()
        val plain = run(null); val observed = run(buffer)
        assertEquals(plain, observed)
        assertEquals(8, observed.second.size) // init, status, loading, decoding and four pre-terminal accepts.
        assertEquals(observed.second, snapshot(buffer).deadlineSamples.map { it.sampledAt })
    }

    @Test fun `original clock exceptions and errors retain instance and read count`() {
        for (failure in listOf(IllegalStateException("ORIGINAL"), AssertionError("ORIGINAL"))) {
            for (enabled in listOf(false, true)) {
                val clock = Clock(); val buffer = if (enabled) OrionInferenceLifecycleFactBuffer() else null
                val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
                clock.failure = failure
                for (operation in listOf<() -> Any>({ lifecycle.status() }, { lifecycle.accept(chunk(0)) },
                    { lifecycle.cancel() }, { lifecycle.processDied() })) {
                    val seen = try { operation(); null } catch (caught: Throwable) { caught }
                    assertSame(failure, seen); assertEquals(1, clock.reads.size)
                }
                buffer?.let { assertEquals(1, snapshot(it).deadlineSamples.size); assertNull(snapshot(it).firstTerminal) }
            }
        }
    }

    @Test fun `schema initialization has no clock and no manufactured sample`() {
        val clock = Clock().apply { failure = AssertionError("Schema path cannot read time") }
        val buffer = OrionInferenceLifecycleFactBuffer()
        val lifecycle = OrionInferenceLifecycle(request(2), clock, buffer)
        assertEquals(OrionInferenceFailure.SCHEMA_MISMATCH, lifecycle.status().failure)
        val facts = snapshot(buffer)
        assertTrue(facts.deadlineSamples.isEmpty())
        assertEquals(OrionLifecycleTerminalOrigin.INIT_SCHEMA, facts.firstTerminal?.origin)
        assertEquals(OrionLifecycleOperation.INITIALIZE, facts.firstTerminal?.operation)
        assertNull(facts.firstTerminal?.associatedOriginalDeadlineSample)
    }

    @Test fun `boundary timeout and invalid samples retain exact original references`() {
        for (now in listOf(MonotonicInstant(BootSessionId(1), 200), MonotonicInstant(BootSessionId(2), 101),
            MonotonicInstant(null, 101), MonotonicInstant(BootSessionId(1), 99))) {
            val clock = Clock(); val buffer = OrionInferenceLifecycleFactBuffer()
            val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
            clock.at = now
            val result = lifecycle.accept(chunk(0, terminal = OrionInferenceTerminal.SUCCEEDED))
            val facts = snapshot(buffer); val sample = facts.deadlineSamples.last()
            assertSame(now, sample.sampledAt); assertEquals(OrionLifecycleOperation.ACCEPT, sample.operation)
            assertSame(sample, facts.firstTerminal?.associatedOriginalDeadlineSample)
            assertSame(sample, facts.updates.single().associatedOriginalDeadlineSample)
            assertEquals(if (now.elapsedRealtimeMillis == 200L) MonotonicDeadlineStatus.EXPIRED
                else MonotonicDeadlineStatus.INVALID_REFERENCE, sample.classification)
            assertEquals(if (now.elapsedRealtimeMillis == 200L) OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED
                else OrionLifecycleTerminalOrigin.INVALID_TIME, facts.firstTerminal?.origin)
            assertEquals(OrionInferenceReceipt.IGNORED_TERMINAL, result.receipt)
            assertTrue(result.chunks.isEmpty())
        }
    }

    @Test fun `buffered final admission ordered release and late input remain distinct`() {
        val clock = Clock(); val buffer = OrionInferenceLifecycleFactBuffer()
        val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
        val last = chunk(2, "", "工具", OrionInferenceTerminal.SUCCEEDED)
        lifecycle.accept(last); assertNull(snapshot(buffer).firstTerminal)
        lifecycle.accept(chunk(1, null, "é")); lifecycle.accept(chunk(1, null, "é"))
        val delivered = lifecycle.accept(chunk(0, "a🌍", "é"))
        val terminal = snapshot(buffer).firstTerminal
        lifecycle.accept(chunk(0, "LATE_PRIVATE")); lifecycle.accept(chunk(0, id = "other"))
        lifecycle.cancel(); lifecycle.processDied(); lifecycle.failTransport(); lifecycle.status()
        val facts = snapshot(buffer)
        assertSame(terminal, facts.firstTerminal)
        assertEquals(OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION, terminal?.origin)
        assertEquals(OrionLifecycleOperation.ACCEPT, terminal?.operation)
        assertEquals(listOf(2L, 1L, 1L, 0L, 0L, 0L), facts.updates.map { it.submitted.sequence })
        assertEquals(OrionInferenceReceipt.BUFFERED, facts.updates[0].receipt)
        assertEquals(6, facts.updates[0].bufferedAdmission?.validatedTotalContentUtf8Bytes)
        assertTrue(releases(facts.updates[0]).isEmpty())
        assertEquals(OrionInferenceReceipt.DUPLICATE, facts.updates[2].receipt)
        assertNull(facts.updates[2].bufferedAdmission); assertTrue(releases(facts.updates[2]).isEmpty())
        val accepted = facts.updates[3]
        assertNull(accepted.bufferedAdmission)
        assertEquals(listOf(0L, 1L, 2L), releases(accepted).map { it.sequence })
        assertEquals(listOf(7, 2, 6), releases(accepted).map { it.validatedTotalContentUtf8Bytes })
        assertSame(last, delivered.chunks.last())
        for (late in facts.updates.drop(4)) {
            assertEquals(OrionInferenceReceipt.IGNORED_TERMINAL, late.receipt)
            assertNull(late.associatedOriginalDeadlineSample); assertNull(late.submitted.validatedTotalContentUtf8Bytes)
            assertTrue(releases(late).isEmpty())
        }
        val rendered = facts.toString()
        for (secret in listOf("PRIVATE", "private-request", "private-model", "private-correlation", "工具", "🌍"))
            assertFalse(rendered.contains(secret))
    }

    @Test fun `only bytes actually validated are available including known empty content`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        lifecycle.accept(chunk(0, id = "other"))
        lifecycle.accept(chunk(0, null, null))
        lifecycle.accept(chunk(0, "duplicate content never validated"))
        lifecycle.accept(chunk(2, "é", "🌍"))
        lifecycle.accept(chunk(2, "é", "🌍"))
        lifecycle.accept(chunk(2, "changed"))
        val facts = snapshot(buffer)
        assertEquals(listOf(null, 0, null, 6, 6, 7), facts.updates.map { it.submitted.validatedTotalContentUtf8Bytes })
        assertEquals(OrionInferenceFailure.CONFLICTING_CHUNK, facts.firstTerminal?.status?.failure)
        assertEquals(OrionLifecycleTerminalOrigin.REJECTED_CHUNK, facts.firstTerminal?.origin)
        assertTrue(releases(facts.updates.last()).isEmpty())
    }

    @Test fun `rejected schema oversized and gap inputs do not invent byte counts`() {
        for (input in listOf(chunk(0, schema = 2), chunk(0, "é".repeat(32769)), chunk(9), chunk(Long.MAX_VALUE))) {
            val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
            val update = lifecycle.accept(input)
            assertEquals(OrionInferenceReceipt.FAILED, update.receipt)
            assertNull(snapshot(buffer).updates.single().submitted.validatedTotalContentUtf8Bytes)
            assertTrue(releases(snapshot(buffer).updates.single()).isEmpty())
        }
    }

    @Test fun `first final before other chunks clears buffered content without claiming release`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        lifecycle.accept(chunk(1)); lifecycle.accept(chunk(0, terminal = OrionInferenceTerminal.SUCCEEDED))
        val facts = snapshot(buffer)
        assertEquals(listOf(0L), releases(facts.updates.last()).map { it.sequence })
        assertEquals(OrionInferenceTerminal.SUCCEEDED, facts.firstTerminal?.status?.terminal)
    }

    @Test fun `terminal origin and original status object survive every later operation`() {
        for ((origin, terminal, operation) in listOf(
            Triple(OrionLifecycleTerminalOrigin.CANCELLATION, OrionInferenceTerminal.CANCELLED, OrionLifecycleOperation.CANCEL),
            Triple(OrionLifecycleTerminalOrigin.TRANSPORT_FAILURE, OrionInferenceTerminal.FAILED, OrionLifecycleOperation.FAIL_TRANSPORT),
            Triple(OrionLifecycleTerminalOrigin.PROCESS_DEATH, OrionInferenceTerminal.PROCESS_DIED, OrionLifecycleOperation.PROCESS_DIED))) {
            val buffer = OrionInferenceLifecycleFactBuffer(); val clock = Clock()
            val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
            val first = when (operation) {
                OrionLifecycleOperation.CANCEL -> lifecycle.cancel()
                OrionLifecycleOperation.FAIL_TRANSPORT -> lifecycle.failTransport()
                else -> lifecycle.processDied()
            }
            val recorded = snapshot(buffer).firstTerminal
            assertSame(first, recorded?.status); assertEquals(origin, recorded?.origin); assertEquals(operation, recorded?.operation)
            assertEquals(terminal, first.terminal)
            clock.failure = AssertionError("No later terminal clock reads")
            assertSame(first, lifecycle.status()); assertSame(first, lifecycle.cancel()); assertSame(first, lifecycle.processDied())
            assertSame(first, lifecycle.failTransport()); assertFalse(lifecycle.markLoading()); assertFalse(lifecycle.markDecoding())
            assertSame(recorded, snapshot(buffer).firstTerminal)
        }
    }

    @Test fun `concurrent cancellation death and final retain exactly the original winning terminal`() {
        repeat(12) {
            val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
            val threads = listOf<() -> Unit>({ lifecycle.cancel() }, { lifecycle.processDied() },
                { lifecycle.accept(chunk(0, terminal = OrionInferenceTerminal.SUCCEEDED)) }).map { Thread(it) }
            threads.forEach(Thread::start); threads.forEach(Thread::join)
            assertSame(lifecycle.status(), snapshot(buffer).firstTerminal?.status)
            val before = snapshot(buffer).firstTerminal
            lifecycle.cancel(); lifecycle.processDied()
            assertSame(before, snapshot(buffer).firstTerminal)
        }
    }

    @Test fun `saturation keeps reserved terminal and retained collections are immutable`() {
        val buffer = OrionInferenceLifecycleFactBuffer(1); val clock = Clock()
        val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
        lifecycle.accept(chunk(1)); lifecycle.accept(chunk(0, terminal = OrionInferenceTerminal.SUCCEEDED))
        val facts = snapshot(buffer)
        assertEquals(1, facts.deadlineSamples.size); assertEquals(1, facts.updates.size)
        assertNotNull(facts.firstTerminal); assertFalse(facts.firstTerminalRecordUnavailable)
        assertTrue(facts.droppedAtLeast >= 3); assertTrue(facts.lossAccountingComplete)
        assertThrows(UnsupportedOperationException::class.java) { (facts.deadlineSamples as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (facts.updates as MutableList<*>).clear() }
        val acceptedBuffer = OrionInferenceLifecycleFactBuffer()
        OrionInferenceLifecycle(request(), Clock(), acceptedBuffer).accept(chunk(0))
        val ordered = releases(snapshot(acceptedBuffer).updates.single())
        assertThrows(UnsupportedOperationException::class.java) { (ordered as MutableList<*>).clear() }
        assertNotSame(facts, snapshot(buffer))
        assertEquals(1, facts.updates.size)
    }

    @Test fun `collector contention never waits and reports unavailable first terminal`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        val lock = buffer.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(buffer) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val busy = worker.submit(Callable { snapshot(buffer) }).get(1, TimeUnit.SECONDS)
            assertTrue(busy.snapshotContended); assertTrue(busy.deadlineSamples.isEmpty()); assertNull(busy.firstTerminal)
            val cancelled = worker.submit(Callable { lifecycle.cancel() }).get(1, TimeUnit.SECONDS)
            assertEquals(OrionInferenceTerminal.CANCELLED, cancelled.terminal)
        } finally { lock.unlock(); worker.shutdownNow() }
        val facts = snapshot(buffer)
        assertNull(facts.firstTerminal); assertTrue(facts.firstTerminalRecordUnavailable); assertTrue(facts.droppedAtLeast >= 2)
        assertEquals(OrionInferenceTerminal.CANCELLED, lifecycle.processDied().terminal)
        assertNull(snapshot(buffer).firstTerminal)
    }

    @Test fun `diagnostic publication fault cannot replace original output or exception`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val clock = Clock()
        val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
        val field = buffer.javaClass.getDeclaredField("updates").apply { isAccessible = true }
        val originalQueue = field.get(buffer)
        val originalChunk = chunk(0)
        val result = try { field.set(buffer, null); lifecycle.accept(originalChunk) } finally { field.set(buffer, originalQueue) }
        assertSame(originalChunk, result.chunks.single()); assertEquals(OrionInferenceReceipt.ACCEPTED, result.receipt)
        val facts = snapshot(buffer)
        assertTrue(facts.updates.isEmpty()); assertTrue(facts.recordingFailuresAtLeast >= 1); assertFalse(facts.lossAccountingComplete)
        val original = AssertionError("original clock failure")
        clock.failure = original
        assertSame(original, try { lifecycle.status(); null } catch (failure: Throwable) { failure })
    }

    @Test fun `failed optional release bookkeeping stays unavailable without substituting zero`() {
        val buffer = OrionInferenceLifecycleFactBuffer()
        val metadata = checkNotNull(buffer.releases())
        metadata.released(-1, false, null, null, 0) // Mechanical diagnostic fault only.
        buffer.update(OrionInferenceReceipt.ACCEPTED, OrionInferenceStatus(OrionInferenceStage.DECODING, null, null),
            null, 0, false, null, null, 2, metadata)
        val facts = snapshot(buffer); val update = facts.updates.single()
        assertNull(update.orderedRelease); assertFalse(update.orderedReleaseMetadataAvailable)
        assertEquals(2, update.submitted.validatedTotalContentUtf8Bytes)
        assertTrue(facts.recordingFailuresAtLeast >= 1)
    }

    @Test fun `one buffer cannot collect another lifecycle and capacity is fixed`() {
        for (capacity in listOf(0, -1, 65, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { OrionInferenceLifecycleFactBuffer(capacity) }
        val buffer = OrionInferenceLifecycleFactBuffer()
        val first = OrionInferenceLifecycle(request(), Clock(), buffer)
        val second = OrionInferenceLifecycle(request(), Clock(), buffer)
        first.cancel(); val retained = snapshot(buffer)
        second.processDied(); second.accept(chunk(0))
        assertEquals(retained, snapshot(buffer))
    }
}

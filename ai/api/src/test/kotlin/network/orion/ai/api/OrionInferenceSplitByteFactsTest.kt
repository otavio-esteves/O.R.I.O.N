package network.orion.ai.api

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Passive byte-source regressions only; no execution timing or native/hardware evidence. */
class OrionInferenceSplitByteFactsTest {
    private class Clock : MonotonicClock {
        var now = MonotonicInstant(BootSessionId(7), 100)
        var failure: Throwable? = null
        val reads = mutableListOf<MonotonicInstant>()
        override fun monotonicNow(): MonotonicInstant {
            failure?.let { throw it }
            reads.add(now)
            return now
        }
    }
    private val start = MonotonicInstant(BootSessionId(7), 100)
    private fun request() = OrionInferenceRequest("request-private", 1, "model-private", "PROMPT_SECRET", "CONTEXT_SECRET",
        128, 0.0, OrionInferencePriority.INTERACTIVE, start.deadlineAfter(1000), "correlation-private")
    private fun chunk(sequence: Long = 0, text: String? = null, tool: String? = null,
        terminal: OrionInferenceTerminal? = null, id: String = "request-private", schema: Int = 1) =
        OrionInferenceChunk(id, schema, sequence, text, tool, terminal != null, terminal)
    private fun facts(buffer: OrionInferenceLifecycleFactBuffer) = checkNotNull(buffer.snapshot())
    private fun assertBytes(text: Int?, tool: Int?, total: Int?, metadata: OrionLifecycleChunkMetadata) {
        assertEquals(text, metadata.validatedTextDeltaUtf8Bytes)
        assertEquals(tool, metadata.validatedToolDeltaUtf8Bytes)
        assertEquals(total, metadata.validatedTotalContentUtf8Bytes)
        if (text != null && tool != null) assertEquals(total, text + tool)
    }

    @Test fun `null empty multibyte and mixed fields retain actual original part counts`() {
        val inputs = listOf(chunk(), chunk(text = ""), chunk(tool = ""), chunk(text = "", tool = ""),
            chunk(text = "é"), chunk(tool = "🌍"), chunk(text = "a🌍", tool = "工具"),
            chunk(text = "\u0000", tool = "\uD800"))
        val expected = listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0, 2 to 0, 0 to 4, 5 to 6, 1 to 1)
        for ((input, bytes) in inputs.zip(expected)) {
            val buffer = OrionInferenceLifecycleFactBuffer()
            val result = OrionInferenceLifecycle(request(), Clock(), buffer).accept(input)
            val originalList = result.chunks
            assertSame(input, originalList.single())
            val update = facts(buffer).updates.single()
            assertBytes(bytes.first, bytes.second, bytes.first + bytes.second, update.submitted)
            assertBytes(bytes.first, bytes.second, bytes.first + bytes.second, checkNotNull(update.orderedRelease).single())
            assertSame(originalList, result.chunks); assertSame(result.status, update.status)
        }
    }

    @Test fun `observed and absent execution preserve results identities and clock order`() {
        val input = listOf(chunk(2, tool = "工具", terminal = OrionInferenceTerminal.SUCCEEDED),
            chunk(1, "é", "🌍"), chunk(1, "é", "🌍"), chunk(0, "", ""), chunk(0, "LATE_SECRET"))
        fun run(buffer: OrionInferenceLifecycleFactBuffer?): Pair<List<Any>, List<MonotonicInstant>> {
            val clock = Clock(); val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
            val result = mutableListOf<Any>(lifecycle.status(), lifecycle.markLoading(), lifecycle.markDecoding())
            for ((index, value) in input.withIndex()) {
                clock.now = MonotonicInstant(BootSessionId(7), 110L + index)
                val update = lifecycle.accept(value)
                result.add(update.receipt); result.add(update.status); result.add(update.chunks)
                if (index == 3) {
                    assertSame(input[3], update.chunks[0]); assertSame(input[1], update.chunks[1]); assertSame(input[0], update.chunks[2])
                }
            }
            val terminal = lifecycle.status()
            assertSame(terminal, lifecycle.cancel()); assertSame(terminal, lifecycle.processDied()); assertSame(terminal, lifecycle.failTransport())
            result.add(terminal)
            return result to clock.reads
        }
        val buffer = OrionInferenceLifecycleFactBuffer()
        val plain = run(null); val observed = run(buffer)
        assertEquals(plain, observed)
        assertEquals(observed.second, facts(buffer).deadlineSamples.map { it.sampledAt })
        assertEquals(listOf(0, 2, 0), facts(buffer).updates[3].orderedRelease?.map { it.validatedTextDeltaUtf8Bytes })
        assertEquals(listOf(0, 4, 6), facts(buffer).updates[3].orderedRelease?.map { it.validatedToolDeltaUtf8Bytes })
    }

    @Test fun `original guard returns unchanged values with absent present or failed scalar bookkeeping`() {
        fun original(input: OrionInferenceChunk, limit: Int): Int? {
            val chars = (input.textDelta?.length?.toLong() ?: 0L) + (input.toolDelta?.length?.toLong() ?: 0L)
            if (chars > limit) return null
            val bytes = (input.textDelta?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
                (input.toolDelta?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            return bytes.takeIf { it <= limit }
        }
        val buffer = OrionInferenceLifecycleFactBuffer()
        val carrier = checkNotNull(buffer.byteParts())
        carrier.record(-1, 0, 0) // Synthetic diagnostic-only fault, never a content guard input.
        assertTrue(facts(buffer).recordingFailuresAtLeast >= 1)
        for (input in listOf(chunk(), chunk(text = "é", tool = "🌍"), chunk(text = "x".repeat(65)), chunk(tool = "é".repeat(33)))) {
            for (limit in listOf(-1, 0, 1, 2, 6, 64, 65, 66)) {
                val expected = original(input, limit)
                assertEquals(expected, input.boundedContentBytes(limit))
                assertEquals(expected, input.boundedContentBytes(limit, carrier))
                if (expected == null) assertEquals(-1L, carrier.packedParts)
            }
        }
    }

    @Test fun `prevalidation rejections and postvalidation oversize never invent zeros or parts`() {
        for (input in listOf(chunk(id = "other"), chunk(schema = 2), chunk(9), chunk(Long.MAX_VALUE),
            chunk(text = "x".repeat(65537)), chunk(tool = "é".repeat(32769)))) {
            val buffer = OrionInferenceLifecycleFactBuffer()
            val result = OrionInferenceLifecycle(request(), Clock(), buffer).accept(input)
            val metadata = facts(buffer).updates.single().submitted
            assertBytes(null, null, null, metadata)
            assertTrue(result.chunks.isEmpty())
        }
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        lifecycle.accept(chunk(text = "é", tool = "🌍"))
        lifecycle.accept(chunk(text = "already delivered and never validated"))
        assertBytes(null, null, null, facts(buffer).updates.last().submitted)
        lifecycle.cancel(); lifecycle.accept(chunk(text = "late"))
        assertBytes(null, null, null, facts(buffer).updates.last().submitted)
        assertNull(facts(buffer).updates.last().associatedOriginalDeadlineSample)
    }

    @Test fun `buffer duplicate conflict and aggregate rejection retain only validated submitted parts`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        lifecycle.accept(chunk(1, "é", "🌍")); lifecycle.accept(chunk(1, "é", "🌍"))
        lifecycle.accept(chunk(1, "工具", ""))
        val updates = facts(buffer).updates
        assertBytes(2, 4, 6, updates[0].submitted); assertSame(updates[0].submitted, updates[0].bufferedAdmission)
        assertEquals(OrionInferenceReceipt.DUPLICATE, updates[1].receipt); assertBytes(2, 4, 6, updates[1].submitted)
        assertEquals(OrionInferenceReceipt.FAILED, updates[2].receipt); assertBytes(6, 0, 6, updates[2].submitted)
        assertEquals(OrionInferenceFailure.CONFLICTING_CHUNK, updates[2].status.failure)
        assertTrue(updates.all { checkNotNull(it.orderedRelease).isEmpty() })
        val largeBuffer = OrionInferenceLifecycleFactBuffer(); val full = OrionInferenceLifecycle(request(), Clock(), largeBuffer)
        for (sequence in 1L..4L) full.accept(chunk(sequence, "x".repeat(32768), "x".repeat(32768)))
        val failed = full.accept(chunk(5, "é", ""))
        assertEquals(OrionInferenceFailure.REORDER_LIMIT, failed.status.failure)
        assertBytes(2, 0, 2, facts(largeBuffer).updates.last().submitted)
        assertNull(facts(largeBuffer).updates.last().bufferedAdmission)
    }

    @Test fun `pending removal retains parts from original revalidation and does not rewrite admission`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
        val pending = chunk(1, "é", "🌍", OrionInferenceTerminal.SUCCEEDED)
        lifecycle.accept(pending)
        val admitted = facts(buffer).updates.single().submitted
        // Test-only reflection exposes the existing second validation without an encoding callback or test seam.
        // Total stays six: no accounting/limit change, and production chunk properties remain immutable.
        pending.javaClass.getDeclaredField("textDelta").apply { isAccessible = true }.set(pending, "🌍")
        pending.javaClass.getDeclaredField("toolDelta").apply { isAccessible = true }.set(pending, "é")
        val update = lifecycle.accept(chunk(0, "", ""))
        assertSame(pending, update.chunks.last())
        assertBytes(2, 4, 6, admitted)
        assertBytes(0, 0, 0, facts(buffer).updates.last().submitted)
        assertBytes(4, 2, 6, checkNotNull(facts(buffer).updates.last().orderedRelease).last())
    }

    @Test fun `failed original pending revalidation publishes no failed-to-return update`() {
        for (enabled in listOf(false, true)) {
            val buffer = if (enabled) OrionInferenceLifecycleFactBuffer() else null
            val lifecycle = OrionInferenceLifecycle(request(), Clock(), buffer)
            val pending = chunk(1, "é", "")
            lifecycle.accept(pending)
            pending.javaClass.getDeclaredField("textDelta").apply { isAccessible = true }.set(pending, "é".repeat(32769))
            assertThrows(IllegalStateException::class.java) { lifecycle.accept(chunk(0)) }
            buffer?.let { assertEquals(1, facts(it).updates.size); assertNull(facts(it).firstTerminal) }
        }
    }

    @Test fun `scalar fault leaves both parts unavailable while preserving known original total`() {
        val buffer = OrionInferenceLifecycleFactBuffer()
        val carrier = checkNotNull(buffer.byteParts())
        carrier.record(2, 4, 6); assertTrue(carrier.packedParts >= 0)
        carrier.record(2, 4, 7)
        assertEquals(-1L, carrier.packedParts)
        buffer.update(OrionInferenceReceipt.BUFFERED, OrionInferenceStatus(OrionInferenceStage.CREATED, null, null),
            null, 1, false, null, null, 6, null, carrier.packedParts)
        val missing = facts(buffer).updates.single()
        assertBytes(null, null, 6, missing.submitted); assertSame(missing.submitted, missing.bufferedAdmission)
        assertTrue(facts(buffer).recordingFailuresAtLeast >= 1); assertFalse(facts(buffer).lossAccountingComplete)
        val inconsistent = buffer.chunk(0, false, null, null, 6, Long.MAX_VALUE)
        assertBytes(null, null, 6, inconsistent)
        assertThrows(IllegalArgumentException::class.java) { OrionLifecycleChunkMetadata(0, false, null, null, 6, 2, null) }
        assertThrows(IllegalArgumentException::class.java) { OrionLifecycleChunkMetadata(0, false, null, null, 6, 2, 3) }
    }

    @Test fun `publication failure does not replace original update chunk list status or clock exception`() {
        val buffer = OrionInferenceLifecycleFactBuffer(); val clock = Clock()
        val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
        val queueField = buffer.javaClass.getDeclaredField("updates").apply { isAccessible = true }
        val queue = queueField.get(buffer); val original = chunk(text = "é", tool = "🌍")
        val result = try { queueField.set(buffer, null); lifecycle.accept(original) } finally { queueField.set(buffer, queue) }
        val originalList = result.chunks
        assertEquals(OrionInferenceReceipt.ACCEPTED, result.receipt); assertSame(original, originalList.single())
        assertTrue(facts(buffer).updates.isEmpty()); assertTrue(facts(buffer).recordingFailuresAtLeast >= 1)
        assertSame(originalList, result.chunks); assertSame(result.status, lifecycle.status())
        for (enabled in listOf(false, true)) {
            val faultClock = Clock(); val observed = if (enabled) OrionInferenceLifecycleFactBuffer() else null
            val reducer = OrionInferenceLifecycle(request(), faultClock, observed)
            for (failure in listOf(IllegalStateException("ORIGINAL"), AssertionError("ORIGINAL"))) {
                faultClock.failure = failure
                assertSame(failure, try { reducer.accept(original); null } catch (caught: Throwable) { caught })
                assertEquals(1, faultClock.reads.size)
            }
        }
    }

    @Test fun `loss and snapshots remain bounded immutable and content free with split fields`() {
        val buffer = OrionInferenceLifecycleFactBuffer(1); val clock = Clock()
        val lifecycle = OrionInferenceLifecycle(request(), clock, buffer)
        val result = lifecycle.accept(chunk(text = "PRIVATE_TEXT", tool = "PRIVATE_TOOL", terminal = OrionInferenceTerminal.SUCCEEDED))
        val snapshot = facts(buffer); val metadata = checkNotNull(snapshot.updates.single().orderedRelease)
        assertBytes(12, 12, 24, metadata.single()); assertSame(result.status, snapshot.firstTerminal?.status)
        assertThrows(UnsupportedOperationException::class.java) { (metadata as MutableList<*>).clear() }
        lifecycle.accept(chunk(text = "late"))
        assertEquals(1, facts(buffer).updates.size); assertTrue(facts(buffer).droppedAtLeast >= 2)
        assertSame(snapshot.firstTerminal, facts(buffer).firstTerminal)
        assertBytes(12, 12, 24, metadata.single())
        for (secret in listOf("PRIVATE_TEXT", "PRIVATE_TOOL", "PROMPT_SECRET", "request-private", "model-private", "correlation-private"))
            assertFalse(snapshot.toString().contains(secret))
        val contended = OrionInferenceLifecycleFactBuffer(); val reducer = OrionInferenceLifecycle(request(), Clock(), contended)
        val lock = contended.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(contended) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val original = chunk(text = "é", tool = "🌍")
            val update = worker.submit(Callable { reducer.accept(original) }).get(1, TimeUnit.SECONDS)
            assertSame(original, update.chunks.single()); assertEquals(OrionInferenceReceipt.ACCEPTED, update.receipt)
        } finally { lock.unlock(); worker.shutdownNow() }
        assertTrue(facts(contended).updates.isEmpty()); assertTrue(facts(contended).droppedAtLeast >= 2)
    }
}

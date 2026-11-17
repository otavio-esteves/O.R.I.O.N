package network.orion.app.ai

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic finite time and already-observed DTOs only. No Android/native/profile/owner-admission proof. */
class OrionCortexRuntimeCaptureSessionTest {
    private class Clock : MonotonicClock {
        var at = MonotonicInstant(BootSessionId(3), 100)
        var failure: Throwable? = null
        val reads = mutableListOf<MonotonicInstant>()
        override fun monotonicNow(): MonotonicInstant {
            failure?.let { throw it }; reads.add(at); return at
        }
    }
    private fun finite(values: List<Long?> = List(256) { 100L }): OrionObservationEventClock {
        val method = OrionObservationEventClock.Companion::class.java.declaredMethods.single {
            it.name.startsWith("finiteForTests") && it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        }
        method.isAccessible = true
        return method.invoke(OrionObservationEventClock.Companion, values) as OrionObservationEventClock
    }
    private class Fixture(values: List<Long?>, private val createClock: (List<Long?>) -> OrionObservationEventClock) {
        val owner = Any(); val client = Any(); val service = Any(); val connection = Any(); val binder = Any()
        val clock = Clock()
        val time = createClock(values)
        val source = OrionInferenceLifecycleEventSource(time)
        val capture = checkNotNull(OrionCortexRuntimeCaptureSession.create(owner, 10))
        val request = OrionInferenceRequest("private-logical", 1, "a".repeat(64), "PROMPT_SECRET", "CONTEXT_SECRET", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, clock.at.deadlineAfter(1000), "private-label")
        val lifecycle: OrionInferenceLifecycle
        val frame: OrionRuntimeCaptureFrame
        init {
            // Test-only finite source replacement; production has no callback/provider or fake factory.
            capture.javaClass.getDeclaredField("observationClock").apply { isAccessible = true }.set(capture, time)
            capture.javaClass.getDeclaredField("lifecycleSource").apply { isAccessible = true }.set(capture, source)
            check(capture.claim(owner))
            capture.selected(owner, request.modelId, 1, request.requestId, request.deadline, 10,
                128, 1, request.maxTokens, setOf("network.orion.qualification", "network.orion.qualification:ai"))
            check(capture.forClient(client, request.requestId, request.modelId, 1) === capture)
            lifecycle = OrionInferenceLifecycle(request, clock, events = source)
            capture.runOnceEntered(lifecycle)
            frame = checkNotNull(capture.turnEntered(client, 1))
            frame.connection = OrionRuntimeConnectionStamp(service, connection, binder, 1)
            frame.pid = 11
        }
        fun identity(boot: Int? = 3) {
            frame.resourceThread = Thread.currentThread()
            try { capture.originalMemoryWitness(OrionRuntimeHealthWindow(frame, checkNotNull(frame.connection)), 11,
                MonotonicInstant(boot?.let(::BootSessionId), 100)) }
            finally { frame.resourceThread = null }
        }
        fun finish(throwing: Boolean = false): OrionRuntimeSessionReceipt {
            frame.ended = true; capture.runReturned(throwing)
            return checkNotNull(capture.readAfterRun(owner))
        }
    }
    private fun fixture(values: List<Long?> = List(256) { 100L }) = Fixture(values, ::finite)
    private fun chunk(sequence: Long = 0, final: Boolean = true) = OrionInferenceChunk("private-logical", 1, sequence,
        "é", "工具", final, if (final) OrionInferenceTerminal.SUCCEEDED else null)

    private fun exportContext(pid: Int = 10): OrionRuntimeQualifiedContext =
        OrionRuntimeQualifiedContext::class.java.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
            .newInstance("network.orion.qualification", "network.orion.qualification.test", 10000, pid) as OrionRuntimeQualifiedContext

    @Test fun `export seal requires actual manual return close and current frame without another clock or snapshot`() {
        val f = fixture(); f.identity(); f.lifecycle.accept(chunk())
        val config = OrionRuntimeSamplingConfiguration(10)
        val start = f.capture.javaClass.getDeclaredField("start").apply { isAccessible = true }.get(f.capture) as OrionObservationMoment
        val sampler = OrionRuntimeMemorySampler(f.capture, config, f.time,
            OrionRuntimeMainMemoryProbe.finiteForTests(emptyList()), start, 10)
        f.capture.javaClass.getDeclaredField("samplingConfiguration").apply { isAccessible = true }.set(f.capture, config)
        f.capture.javaClass.getDeclaredField("memorySampler").apply { isAccessible = true }.set(f.capture, sampler)
        assertNull(f.capture.freezeForExport(f.owner, exportContext()))
        f.capture.runReturned(false)
        assertNull(f.capture.freezeForExport(f.owner, exportContext()))
        val receipt = checkNotNull(f.capture.readAfterRun(f.owner))
        val reads = f.clock.reads.toList(); val observationReads = checkNotNull(f.time.snapshot()).counterReadsAtLeast
        assertFalse(sampler.open())
        val sealed = checkNotNull(f.capture.freezeForExport(f.owner, exportContext()))
        assertSame(receipt, sealed.receipt); assertEquals(reads, f.clock.reads)
        assertSame(sealed, f.capture.freezeForExport(f.owner, exportContext()))
        assertEquals(128, sealed.scalars.contextTokens); assertEquals(1, sealed.scalars.threads)
        assertEquals(8, sealed.scalars.maxTokens); assertEquals(1100L, sealed.scalars.deadlineElapsed)
        assertEquals(listOf("network.orion.qualification", "network.orion.qualification:ai"), sealed.scalars.processInventory)
        assertEquals(observationReads, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        assertNull(sealed.scalars.wireTemperature) // This fixture never reached the original Client wire expression.
        assertNull(OrionRuntimeCapsuleEncoder.encode(sealed).source)
        assertNull(f.capture.freezeForExport(Any(), exportContext()))
        assertNull(f.capture.freezeForExport(f.owner, exportContext(12)))
        f.frame.valid = false
        assertNull(f.capture.freezeForExport(f.owner, exportContext()))
        assertSame(receipt.facts, sealed.receipt.facts) // No second snapshot/import or mutation of the historical receipt.
    }

    @Test fun `historical death equality matches old endpoint pid and boot without reviving invalidated capture`() {
        val f = fixture(listOf(100, 250, 300)); f.identity()
        f.clock.at = MonotonicInstant(BootSessionId(3), 250)
        f.lifecycle.processDied(); f.frame.ended = true; f.frame.valid = false; f.capture.runReturned(false)
        val endpoint = OrionQualificationEndpointKey(f.client, f.connection, f.binder, 1)
        fun incident(key: OrionQualificationEndpointKey, pid: Int = 11, boot: Int = 3): OrionQualificationExitIncident {
            val record = checkNotNull(OrionQualificationExitSanitizer.record(pid, 10000, 10000,
                "network.orion.qualification:ai", 1000250, 13, "MemoryLimiter:AnonSwap", null))
            val pair = OrionQualificationTimePair(boot, 300, 1000300, 1000301)
            return OrionQualificationExitIncident(record, OrionQualificationProcWitness(pid, 10000, 99), key, 10, boot,
                210, 1000211, OrionQualificationObservationWindow(pair, pair), 1, true)
        }
        val reads = f.clock.reads.toList(); val native = checkNotNull(f.time.snapshot()).counterReadsAtLeast
        assertTrue(f.capture.matchesHistoricalProcessDeath(f.owner, incident(endpoint)))
        assertFalse(f.capture.matchesHistoricalProcessDeath(f.owner, incident(OrionQualificationEndpointKey(f.client, f.connection, Any(), 1))))
        assertFalse(f.capture.matchesHistoricalProcessDeath(f.owner, incident(endpoint, pid = 12)))
        assertFalse(f.capture.matchesHistoricalProcessDeath(f.owner, incident(endpoint, boot = 4)))
        assertFalse(f.capture.matchesHistoricalProcessDeath(Any(), incident(endpoint)))
        assertFalse(f.frame.valid); assertNull(f.capture.freezeForExport(f.owner, exportContext()))
        assertEquals(reads, f.clock.reads); assertEquals(native, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
    }

    @Test fun `manual watchdog samples only dedicated bound clock after return without retimestamping source`() {
        val f = fixture(); f.identity(); f.lifecycle.accept(chunk())
        assertNull(f.capture.manualExportMoment(f.owner))
        val receipt = f.finish(); val completed = receipt.facts?.completed
        val functional = f.clock.reads.toList(); val before = checkNotNull(f.time.snapshot()).counterReadsAtLeast
        assertNotNull(f.capture.manualExportMoment(f.owner))
        assertEquals(before + 1, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        assertEquals(functional, f.clock.reads); assertSame(completed, receipt.facts?.completed)
        assertNull(f.capture.manualExportMoment(Any()))
        assertEquals(before + 1, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        f.frame.valid = false
        assertNull(f.capture.manualExportMoment(f.owner))
        assertEquals(before + 1, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
    }

    @Test fun `unconfigured session cannot mint a source seal from a manual receipt`() {
        val f = fixture(); f.identity(); f.lifecycle.accept(chunk()); f.finish()
        assertNull(f.capture.freezeForExport(f.owner, exportContext()))
    }

    @Test fun `manual import retains historical terminal and chunks before later unload markers without retiming`() {
        val f = fixture(listOf(100, 105, 110, 120, 130, 140, 150, 160))
        f.identity()
        f.capture.loadDispatch(f.frame, f.service, "private-load")
        f.capture.loadIssued(f.frame, f.service, "private-load", MonotonicInstant(BootSessionId(3), 100))
        f.capture.loadTerminal(f.frame, f.service, "private-load", OrionInferenceTerminal.SUCCEEDED, true)
        f.capture.inferenceDispatch(f.frame, f.service, "private-transport", 10)
        f.clock.at = MonotonicInstant(BootSessionId(3), 130)
        val original = f.lifecycle.accept(chunk())
        f.capture.unloadDispatch(f.frame, f.service)
        f.capture.unloadCompleted(f.frame, f.service, MonotonicInstant(BootSessionId(3), 140).deadlineAfter(20), true)
        val reads = f.clock.reads.toList()
        val result = f.finish(); val facts = checkNotNull(result.facts)
        assertEquals(reads, f.clock.reads); assertSame(original.status, facts.firstTerminal?.status)
        assertEquals(130L, facts.firstTerminal?.at?.elapsedMillis)
        assertEquals(130L, facts.chunks.single().at.elapsedMillis)
        assertEquals(2, facts.chunks.single().metadata.validatedTextDeltaUtf8Bytes)
        assertEquals(6, facts.chunks.single().metadata.validatedToolDeltaUtf8Bytes)
        assertEquals(listOf(105L, 100L, 110L, 120L, 140L, 150L), facts.markers.map { it.at.elapsedMillis })
        assertEquals(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START, facts.markers[1].kind)
        assertTrue(result.loadDispatchObserved); assertEquals(true, result.loadReplyAccepted)
        assertEquals(160L, facts.completed?.elapsedMillis); assertTrue(facts.chunkSequenceIntact)
        assertTrue(result.originalOwnerEntered == true); assertTrue(result.actualTurnEntered == true); assertTrue(result.identityKnown)
        assertSame(result, f.capture.readAfterRun(f.owner)); assertEquals(8L, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
    }

    @Test fun `return and cleanup publish scalars only and manual finalization is separate and one shot`() {
        val f = fixture(); f.identity(); f.lifecycle.accept(chunk())
        assertNull(f.capture.readAfterRun(f.owner))
        val before = checkNotNull(f.time.snapshot()).counterReadsAtLeast
        f.frame.ended = true; f.capture.runReturned(false)
        assertEquals(before, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        val result = checkNotNull(f.capture.readAfterRun(f.owner))
        assertEquals(before + 1, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        assertSame(result, f.capture.readAfterRun(f.owner)); assertEquals(before + 1, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        assertNull(f.capture.readAfterRun(Any()))
        assertThrows(UnsupportedOperationException::class.java) { (checkNotNull(result.facts).chunks as MutableList<*>).clear() }
    }

    @Test fun `missing pid remains unresolved with unassociated genuine markers and cannot be repaired after seal`() {
        val f = fixture()
        f.capture.loadDispatch(f.frame, f.service, "load")
        f.capture.loadIssued(f.frame, f.service, "load", f.clock.at)
        f.lifecycle.accept(chunk())
        val result = f.finish()
        assertFalse(result.identityKnown); assertNull(result.facts)
        assertEquals(2, result.pendingUnassociatedMarkers.size)
        assertTrue(OrionRuntimeSessionGap.IDENTITY_UNAVAILABLE in result.gaps)
        assertNotNull(result.lifecycle); assertFalse(result.runtimeEvidenceV1Complete)
        f.identity()
        assertNull(f.capture.readAfterRun(f.owner))
    }

    @Test fun `wrong boot or superseded connection permanently disables association without a provider read`() {
        val f = fixture(); val reads = f.clock.reads.size
        f.identity(4); f.identity(3)
        val result = f.finish()
        assertEquals(reads, f.clock.reads.size); assertFalse(result.identityKnown); assertTrue(result.invalidated)
        val other = fixture(); other.identity()
        other.frame.connection = OrionRuntimeConnectionStamp(other.service, Any(), other.binder, 2)
        other.capture.loadDispatch(other.frame, other.service, "load")
        other.lifecycle.accept(chunk())
        val stale = other.finish()
        assertTrue(stale.invalidated); assertTrue(checkNotNull(stale.facts).invalidated)
        assertTrue(OrionRuntimeSessionGap.IDENTITY_CHANGED in stale.gaps)
    }

    @Test fun `old endpoint death or later turn prevents new measurement but retains genuine historical status`() {
        val f = fixture(); f.identity()
        val original = f.lifecycle.processDied()
        f.frame.valid = false
        val before = checkNotNull(f.time.snapshot()).counterReadsAtLeast
        val result = f.finish()
        assertEquals(before, checkNotNull(f.time.snapshot()).counterReadsAtLeast)
        assertTrue(result.invalidated); assertSame(original, result.facts?.firstTerminal?.status)
        assertNull(result.facts?.completed)
        assertEquals(OrionInferenceTerminal.PROCESS_DIED, result.facts?.firstTerminal?.status?.terminal)
    }

    @Test fun `import uses the exact source and observation scope not another retained constructor`() {
        val f = fixture(); f.identity(); f.lifecycle.accept(chunk())
        val destination = f.capture.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(f.capture) as OrionRuntimeEventRecorder
        val association = f.capture.javaClass.getDeclaredField("fence").apply { isAccessible = true }.get(f.capture) as OrionRuntimeCaptureFence
        val start = f.capture.javaClass.getDeclaredField("start").apply { isAccessible = true }.get(f.capture) as OrionObservationMoment
        assertNull(destination.importOwnedLifecycle(association, OrionInferenceLifecycleEventSource(f.time), start, null))
        assertTrue(checkNotNull(destination.snapshot()).invalidated)
        assertNull(checkNotNull(destination.snapshot()).firstTerminal)
        val wrongScope = fixture(); wrongScope.identity(); wrongScope.lifecycle.accept(chunk())
        val recorder = wrongScope.capture.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(wrongScope.capture) as OrionRuntimeEventRecorder
        val fence = wrongScope.capture.javaClass.getDeclaredField("fence").apply { isAccessible = true }.get(wrongScope.capture) as OrionRuntimeCaptureFence
        assertNotNull(recorder.importOwnedLifecycle(fence, wrongScope.source, start, null))
        assertTrue(checkNotNull(recorder.snapshot()).firstTerminalUnavailable)
        assertTrue(checkNotNull(recorder.snapshot()).chunks.isEmpty())
    }

    @Test fun `unavailable source terminal is not repaired from the returned final chunk or status`() {
        val f = fixture(); f.identity()
        val lock = f.source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(f.source) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try { assertEquals(OrionInferenceTerminal.SUCCEEDED,
            worker.submit(Callable { f.lifecycle.accept(chunk()) }).get(1, TimeUnit.SECONDS).status.terminal) }
        finally { lock.unlock(); worker.shutdownNow() }
        val result = f.finish()
        assertNull(result.facts?.firstTerminal); assertTrue(checkNotNull(result.facts).firstTerminalUnavailable)
        assertFalse(checkNotNull(result.facts).chunkSequenceIntact)
    }

    @Test fun `source publication faults preserve original output and remain explicit in the manual summary`() {
        val f = fixture(); f.identity()
        val queue = f.source.javaClass.getDeclaredField("ordered").apply { isAccessible = true }
        val retained = queue.get(f.source); val input = chunk()
        val original = try { queue.set(f.source, null); f.lifecycle.accept(input) } finally { queue.set(f.source, retained) }
        assertSame(input, original.chunks.single())
        val receipt = f.finish(); assertSame(original.status, receipt.facts?.firstTerminal?.status)
        assertTrue(checkNotNull(receipt.lifecycle).recordingFailuresAtLeast >= 1)
        assertFalse(checkNotNull(receipt.facts).chunkSequenceIntact)
    }

    @Test fun `historical import is independent of old32 facts and keeps multibyte counts through ordered release`() {
        val f = fixture(); f.identity()
        repeat(64) { f.lifecycle.accept(chunk(it.toLong(), false)) }
        val original = f.lifecycle.accept(chunk(64))
        val receipt = f.finish(); val facts = checkNotNull(receipt.facts)
        assertEquals(65, facts.chunks.size); assertEquals(65, receipt.lifecycle?.orderedEventCount)
        assertTrue(facts.chunkSequenceIntact); assertSame(original.status, facts.firstTerminal?.status)
        assertTrue(facts.chunks.all { it.metadata.validatedTextDeltaUtf8Bytes == 2 && it.metadata.validatedToolDeltaUtf8Bytes == 6 })
    }

    @Test fun `late or nonstrict unload response stays unconfirmed and void dispatch is not completion`() {
        for (strict in listOf(true, false)) {
            val f = fixture(); f.identity()
            f.capture.unloadDispatch(f.frame, f.service)
            f.capture.unloadCompleted(f.frame, f.service, MonotonicInstant(BootSessionId(3), 90).deadlineAfter(10), strict)
            val result = f.finish()
            assertTrue(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED in result.gaps)
            assertEquals(listOf(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH), result.facts?.markers?.map { it.kind })
        }
    }

    @Test fun `retained result never activates constructor source and default receipts grant no authority`() {
        val owner = Any(); val capture = checkNotNull(OrionCortexRuntimeCaptureSession.create(owner, 10))
        assertTrue(capture.claim(owner)); capture.retainedResult(); capture.runReturned(false)
        val result = checkNotNull(capture.readAfterRun(owner))
        assertEquals(false, result.originalOwnerEntered); assertEquals(false, result.actualTurnEntered)
        assertNull(result.lifecycle); assertNull(result.facts); assertFalse(result.identityKnown)
        assertTrue(OrionRuntimeSessionGap.RETAINED_OR_JOINED in result.gaps)
        assertFalse(result.sourceAuthenticated); assertFalse(result.physicalVerified); assertFalse(result.profileApproved)
        assertFalse(result.productionInferenceApproved); assertFalse(result.runtimeEvidenceV1Complete)
        assertFalse(result.fullF5GateClosed); assertFalse(result.fullSoakGateClosed); assertFalse(result.f6Authorized)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `original clock error remains original and optional session bookkeeping error cannot escape`() {
        val f = fixture(); f.identity()
        val original = AssertionError("original")
        f.clock.failure = original
        assertSame(original, try { f.lifecycle.accept(chunk()); null } catch (failure: Throwable) { failure })
        f.clock.failure = null
        val pending = f.capture.javaClass.getDeclaredField("pending").apply { isAccessible = true }
        val queue = pending.get(f.capture)
        // Force a diagnostic-only failure on an unresolved session, without introducing a callback seam.
        val unresolved = fixture()
        val field = unresolved.capture.javaClass.getDeclaredField("pending").apply { isAccessible = true }
        val held = field.get(unresolved.capture)
        try { field.set(unresolved.capture, null); unresolved.capture.loadDispatch(unresolved.frame, unresolved.service, "load") }
        finally { field.set(unresolved.capture, held) }
        val receipt = unresolved.finish()
        assertTrue(receipt.recordingFailuresAtLeast >= 1); assertTrue(receipt.invalidated)
        assertSame(queue, pending.get(f.capture))
    }
}

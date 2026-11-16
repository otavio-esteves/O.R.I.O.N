package network.orion.app.ai

import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic already-observed DTOs only. This never invokes a Clock, RPC, owner, model or real source hook. */
class OrionRuntimeEventRecorderTest {
    private fun at(value: Long? = 1, boot: Int? = 3) = OrionRuntimeEventTime(boot, value)
    private fun status(terminal: OrionInferenceTerminal = OrionInferenceTerminal.SUCCEEDED) =
        OrionInferenceStatus(OrionInferenceStage.TERMINAL, terminal, null)
    private fun metadata(sequence: Long, final: OrionInferenceTerminal? = null, text: Int? = 0, tool: Int? = 0,
        total: Int? = if (text != null && tool != null) text + tool else null) =
        OrionLifecycleChunkMetadata(sequence, final != null, final, null, total, text, tool)
    private class Fixture {
        val owner = Any(); val token = Any(); val client = Any(); val connection = Any(); val binder = Any()
        val model = OrionRuntimeModelReference("a".repeat(64), 1)
        val id = OrionRuntimeTechnicalId("private-logical-id")
        fun fence(owner: Any? = this.owner, token: Any? = this.token, client: Any? = this.client,
            connection: Any? = this.connection, binder: Any? = this.binder, model: OrionRuntimeModelReference = this.model,
            epoch: Long? = 1, turn: Long? = 1, mainPid: Int? = 100, aiPid: Int? = 101, boot: Int? = 3,
            id: OrionRuntimeTechnicalId = this.id) =
            OrionRuntimeCaptureFence(owner, token, client, connection, binder, epoch, turn, mainPid, aiPid, boot, model, id)
        val selected = fence()
        val recorder = OrionRuntimeEventRecorder(selected, 10_000, 10)
    }
    private fun snapshot(recorder: OrionRuntimeEventRecorder) = checkNotNull(recorder.snapshot())
    private fun started(f: Fixture) { assertTrue(f.recorder.sessionStarted(f.selected, at())) }
    private fun row(time: Long = 2, phase: OrionRuntimeMemoryPhase = OrionRuntimeMemoryPhase.BEFORE_LOAD,
        state: OrionRuntimeServiceState = OrionRuntimeServiceState.UNLOADED, boot: Int? = 3,
        main: Long? = 100, ai: Long? = 200, sum: Long? = 300, mainPid: Int? = 100, aiPid: Int? = 101,
        complete: Long = time, windowEnd: Long = complete, included: Boolean? = true,
        nativeLoaded: Boolean? = false) = OrionRuntimeMemoryRow(
        OrionRuntimePhaseWindow(phase, at(time, boot), at(windowEnd, boot)), at(time, boot), at(complete, boot),
        at(time, boot), at(time, boot), mainPid, aiPid, main, ai, sum, 1000, included, included, state, nativeLoaded,
        false, OrionRuntimeMemorySource.SELF_DEBUG_MEMORY_INFO_PSS)

    @Test fun `foundation is inactive unwired and retains no fabricated event or authority`() {
        val f = Fixture(); val initial = snapshot(f.recorder)
        assertNull(initial.started); assertNull(initial.completed); assertNull(initial.firstTerminal); assertNull(initial.association)
        assertTrue(initial.chunks.isEmpty()); assertTrue(initial.memory.isEmpty()); assertTrue(initial.markers.isEmpty())
        assertTrue(OrionRuntimeCaptureGap.UNWIRED_SOURCE in initial.gaps)
        assertFalse(initial.wired); assertFalse(initial.sourceAuthenticated); assertFalse(initial.runtimeEvidenceV1Complete)
        assertFalse(initial.physicalVerified); assertFalse(initial.profileApproved); assertFalse(initial.productionInferenceApproved)
        assertFalse(initial.fullF5GateClosed); assertFalse(initial.fullSoakGateClosed); assertFalse(initial.f6Authorized)
        assertFalse(f.recorder.orderedChunk(f.selected, at(2), metadata(0)))
        assertNull(snapshot(f.recorder).started)
        started(f)
        assertEquals(at(), snapshot(f.recorder).started)
        assertFalse(f.recorder.sessionStarted(f.selected, at(2)))
        assertTrue(snapshot(f.recorder).invalidated)
    }

    @Test fun `exact fence association includes owner token client endpoint epoch turn model pid boot and logical id`() {
        val makers: List<(Fixture) -> OrionRuntimeCaptureFence> = listOf(
            { it.fence(owner = Any()) }, { it.fence(token = Any()) }, { it.fence(client = Any()) },
            { it.fence(connection = Any()) }, { it.fence(binder = Any()) }, { it.fence(epoch = 2) },
            { it.fence(turn = 2) }, { it.fence(mainPid = 102) }, { it.fence(aiPid = 103) }, { it.fence(boot = 4) },
            { it.fence(model = OrionRuntimeModelReference("a".repeat(64), 1)) },
            { it.fence(id = OrionRuntimeTechnicalId("different-logical-id")) })
        for (other in makers) {
            val f = Fixture(); started(f)
            assertFalse(f.recorder.trueOperation(other(f), OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, at(2))))
            assertTrue(snapshot(f.recorder).invalidated)
            assertTrue(OrionRuntimeCaptureGap.IDENTITY_CHANGED in snapshot(f.recorder).gaps)
            assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, at(3))))
        }
        val f = Fixture()
        val missing = OrionRuntimeEventRecorder(f.fence(owner = null), 10_000, 10)
        assertFalse(missing.sessionStarted(f.selected, at()))
        assertTrue(OrionRuntimeCaptureGap.IDENTITY_UNAVAILABLE in snapshot(missing).gaps)
    }

    @Test fun `native supplied times are preserved and unknown negative cross boot or regressed values invalidate without repair`() {
        for (bad in listOf(at(null), at(2, null), at(-1), at(2, 4), at(0))) {
            val f = Fixture(); started(f)
            assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, bad)))
            assertTrue(snapshot(f.recorder).invalidated)
            assertFalse(f.recorder.sessionCompleted(f.selected, at(3)))
        }
        val f = Fixture(); started(f)
        val actual = at(123)
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, actual)))
        assertSame(actual, snapshot(f.recorder).markers.single().at)
        assertTrue(f.recorder.sessionCompleted(f.selected, at(124)))
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_RETURN, at(125))))
        assertEquals(124L, snapshot(f.recorder).completed?.elapsedMillis)
    }

    @Test fun `reserved first terminal remains original despite all three exact capacities saturating`() {
        val f = Fixture(); started(f)
        repeat(32) { assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, at(2)))) }
        repeat(4097) { assertTrue(f.recorder.orderedChunk(f.selected, at(2), metadata(it.toLong()))) }
        repeat(128) { assertTrue(f.recorder.memoryRow(f.selected, row())) }
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, at(2))))
        assertFalse(f.recorder.memoryRow(f.selected, row()))
        val terminal = status()
        assertTrue(f.recorder.trueFirstTerminal(f.selected, at(2), terminal, OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertFalse(f.recorder.orderedChunk(f.selected, at(2), metadata(4097, OrionInferenceTerminal.SUCCEEDED)))
        val value = snapshot(f.recorder)
        assertEquals(32, value.markers.size); assertEquals(4097, value.chunks.size); assertEquals(128, value.memory.size)
        assertSame(terminal, value.firstTerminal?.status); assertFalse(value.firstTerminalUnavailable)
        assertTrue(value.droppedAtLeast >= 3); assertFalse(value.chunkSequenceIntact)
        assertEquals(4096L, value.chunks.last().metadata.sequence)
        assertFalse(f.recorder.trueFirstTerminal(f.selected, at(3), status(OrionInferenceTerminal.PROCESS_DIED), OrionLifecycleTerminalOrigin.PROCESS_DEATH))
        assertSame(value.firstTerminal, snapshot(f.recorder).firstTerminal)
    }

    @Test fun `staged ordered decisions retain earlier times and winning final shares exact terminal time`() {
        val f = Fixture(); started(f)
        assertTrue(f.recorder.trueFirstTerminal(f.selected, at(5), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertTrue(f.recorder.orderedChunk(f.selected, at(2), metadata(0, text = 2, tool = 4)))
        assertTrue(f.recorder.orderedChunk(f.selected, at(3), metadata(1, text = 0, tool = 0)))
        assertTrue(f.recorder.orderedChunk(f.selected, at(5), metadata(2, OrionInferenceTerminal.SUCCEEDED)))
        val facts = snapshot(f.recorder)
        assertEquals(listOf(2L, 3L, 5L), facts.chunks.map { it.at.elapsedMillis })
        assertEquals(facts.firstTerminal?.at, facts.chunks.last().at)
        assertFalse(f.recorder.orderedChunk(f.selected, at(5), metadata(3)))
        assertFalse(snapshot(f.recorder).chunkSequenceIntact)
        val mismatch = Fixture(); started(mismatch)
        assertTrue(mismatch.recorder.trueFirstTerminal(mismatch.selected, at(5), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertFalse(mismatch.recorder.orderedChunk(mismatch.selected, at(4), metadata(0, OrionInferenceTerminal.SUCCEEDED)))
        assertTrue(OrionRuntimeCaptureGap.TERMINAL_MISMATCH in snapshot(mismatch.recorder).gaps)
    }

    @Test fun `sequence bytes zero unknown and callback versus main terminals remain distinct`() {
        val f = Fixture(); started(f)
        assertTrue(f.recorder.orderedChunk(f.selected, at(2), metadata(0, text = 0, tool = 0)))
        assertTrue(f.recorder.orderedChunk(f.selected, at(3), metadata(1, text = null, tool = null, total = 2)))
        val facts = snapshot(f.recorder)
        assertEquals(0, facts.chunks[0].metadata.validatedTextDeltaUtf8Bytes)
        assertNull(facts.chunks[1].metadata.validatedTextDeltaUtf8Bytes)
        assertEquals(2, facts.chunks[1].metadata.validatedTotalContentUtf8Bytes)
        assertTrue(OrionRuntimeCaptureGap.CHUNK_METADATA_UNAVAILABLE in facts.gaps); assertFalse(facts.chunkSequenceIntact)
        assertFalse(f.recorder.orderedChunk(f.selected, at(4), metadata(3)))
        for ((origin, time) in listOf(OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION to 5L,
            OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED to 10_000L)) {
            val run = Fixture(); started(run); val original = status(OrionInferenceTerminal.TIMED_OUT)
            assertTrue(run.recorder.trueFirstTerminal(run.selected, at(time), original, origin))
            assertSame(original, snapshot(run.recorder).firstTerminal?.status)
            assertEquals(origin, snapshot(run.recorder).firstTerminal?.origin)
            if (origin == OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION)
                assertTrue(run.recorder.orderedChunk(run.selected, at(time), metadata(0, OrionInferenceTerminal.TIMED_OUT)))
            else assertFalse(run.recorder.orderedChunk(run.selected, at(time), metadata(0, OrionInferenceTerminal.TIMED_OUT)))
        }
    }

    @Test fun `contention first terminal loss cannot be repaired and never waits for a lock`() {
        val f = Fixture(); started(f)
        val lock = f.recorder.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(f.recorder) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            assertFalse(worker.submit(Callable {
                f.recorder.trueFirstTerminal(f.selected, at(2), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION)
            }).get(1, TimeUnit.SECONDS))
            assertTrue(checkNotNull(worker.submit(Callable { f.recorder.snapshot() }).get(1, TimeUnit.SECONDS)).snapshotContended)
        } finally { lock.unlock(); worker.shutdownNow() }
        val value = snapshot(f.recorder)
        assertNull(value.firstTerminal); assertTrue(value.firstTerminalUnavailable); assertTrue(value.droppedAtLeast >= 2)
        assertFalse(f.recorder.trueFirstTerminal(f.selected, at(3), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertNull(snapshot(f.recorder).firstTerminal)
    }

    @Test fun `memory windows validate actual order age pids flags state sum and process death without clipping`() {
        val wrongRows = listOf(row(main = null), row(ai = 0), row(main = Long.MAX_VALUE, ai = 1, sum = Long.MAX_VALUE),
            row(sum = 299), row(mainPid = 102), row(aiPid = null), row(included = null), row(nativeLoaded = null),
            row(complete = 12, windowEnd = 12), row(complete = 1, windowEnd = 2), row(state = OrionRuntimeServiceState.UNKNOWN))
        for (bad in wrongRows) { val f = Fixture(); started(f); assertFalse(f.recorder.memoryRow(f.selected, bad)); assertTrue(snapshot(f.recorder).memory.isEmpty()) }
        val f = Fixture(); started(f)
        val states = listOf(OrionRuntimeServiceState.UNLOADED, OrionRuntimeServiceState.LOADING, OrionRuntimeServiceState.LOADED,
            OrionRuntimeServiceState.INFERENCING, OrionRuntimeServiceState.LOADED, OrionRuntimeServiceState.UNLOADING, OrionRuntimeServiceState.UNLOADED)
        for ((index, phase) in OrionRuntimeMemoryPhase.entries.withIndex())
            assertTrue(f.recorder.memoryRow(f.selected, row(2L + index, phase, states[index])))
        assertEquals(7, snapshot(f.recorder).memory.size)
        assertFalse(f.recorder.memoryRow(f.selected, row(20))) // Phase cannot move backward.
        val dead = Fixture(); started(dead)
        assertTrue(dead.recorder.trueFirstTerminal(dead.selected, at(3), status(OrionInferenceTerminal.PROCESS_DIED), OrionLifecycleTerminalOrigin.PROCESS_DEATH))
        assertFalse(dead.recorder.memoryRow(dead.selected, row(4, OrionRuntimeMemoryPhase.AFTER_TERMINAL, OrionRuntimeServiceState.LOADED)))
        assertFalse(dead.recorder.trueOperation(dead.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, at(4))))
    }

    @Test fun `manual snapshots preserve immutable records and bookkeeping faults never replace original status`() {
        val f = Fixture(); started(f)
        val original = status()
        assertTrue(f.recorder.trueFirstTerminal(f.selected, at(2), original, OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertTrue(f.recorder.orderedChunk(f.selected, at(2), metadata(0, OrionInferenceTerminal.SUCCEEDED)))
        assertTrue(f.recorder.memoryRow(f.selected, row()))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_ENTRY, at(2))))
        val saved = snapshot(f.recorder)
        assertThrows(UnsupportedOperationException::class.java) { (saved.chunks as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (saved.memory as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (saved.markers as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (saved.gaps as MutableSet<*>).clear() }
        assertSame(original, saved.firstTerminal?.status)
        val field = f.recorder.javaClass.getDeclaredField("markers").apply { isAccessible = true }
        val old = field.get(f.recorder)
        try {
            field.set(f.recorder, null)
            assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.DELIVERY_RETURN, at(3))))
        } finally { field.set(f.recorder, old) }
        val after = snapshot(f.recorder)
        assertTrue(after.recordingFailuresAtLeast >= 1); assertFalse(after.lossAccountingComplete)
        assertSame(original, after.firstTerminal?.status); assertEquals(1, saved.markers.size)
    }

    @Test fun `technical association is minimized redacted and has a separate bounded hash seam`() {
        val f = Fixture(); started(f)
        val load = OrionRuntimeTechnicalId("actual-load-issued-id")
        val transport = OrionRuntimeTechnicalId("actual-inference-issued-id")
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, at(2), load)))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, at(3), transport, inputBytes = 1)))
        val value = snapshot(f.recorder); val association = checkNotNull(value.association)
        assertSame(f.model, association.model); assertEquals(100, association.mainPid); assertEquals(101, association.aiPid)
        assertEquals(64, association.logicalIdSha256ForManualExport()?.length)
        assertEquals(load.sha256ForManualExport(), value.markers[0].issuedIdSha256ForManualExport())
        assertEquals(transport.sha256ForManualExport(), value.markers[1].issuedIdSha256ForManualExport())
        assertNotEquals(value.markers[0].issuedIdSha256ForManualExport(), value.markers[1].issuedIdSha256ForManualExport())
        assertTrue(Modifier.isPrivate(OrionRuntimeTechnicalId::class.java.getDeclaredField("raw").modifiers))
        for (text in listOf(value.toString(), association.toString(), load.toString(), f.selected.toString(), value.markers.toString()))
            for (secret in listOf("actual-load-issued-id", "actual-inference-issued-id", "private-logical-id")) assertFalse(text.contains(secret))
        for (id in listOf("", "x".repeat(129), "bad\nID", "\uD800", "\uDC00"))
            assertThrows(IllegalArgumentException::class.java) { OrionRuntimeTechnicalId(id) }
        val hostile = object { override fun toString(): String = error("Never invoked")
            override fun equals(other: Any?): Boolean = error("Never invoked")
            override fun hashCode(): Int = error("Never invoked") }
        val same = f.fence(owner = hostile)
        val recorder = OrionRuntimeEventRecorder(same, 10_000, 10)
        assertTrue(recorder.sessionStarted(same, at())); assertNotNull(recorder.snapshot())
    }

    @Test fun `true operation seams require source supplied dispatch terminal and cleanup metadata`() {
        val f = Fixture(); started(f)
        val load = OrionRuntimeTechnicalId("issued-load")
        val inference = OrionRuntimeTechnicalId("issued-inference")
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, at(2))))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, at(2), load)))
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, at(3), load, status())))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, at(3), load,
            status(), loadedModelConfirmed = true)))
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, at(4), inference)))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, at(4), inference, inputBytes = 1)))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, at(5))))
        assertFalse(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP, at(6))))
        assertTrue(f.recorder.trueOperation(f.selected, OrionRuntimeOperationMarker(OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP, at(6), cleanup = OrionRuntimeCleanupResult.UNLOADED)))
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), snapshot(f.recorder).markers.map { it.at.elapsedMillis })
    }

    @Test fun `original terminal origin is validated and concurrent winners cannot overwrite one another`() {
        val invalid = Fixture(); started(invalid)
        assertFalse(invalid.recorder.trueFirstTerminal(invalid.selected, at(2), status(), OrionLifecycleTerminalOrigin.CANCELLATION))
        assertTrue(snapshot(invalid.recorder).firstTerminalUnavailable)
        assertFalse(invalid.recorder.trueFirstTerminal(invalid.selected, at(3), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        val f = Fixture(); started(f)
        val candidates = listOf(status(OrionInferenceTerminal.CANCELLED) to OrionLifecycleTerminalOrigin.CANCELLATION,
            status(OrionInferenceTerminal.PROCESS_DIED) to OrionLifecycleTerminalOrigin.PROCESS_DEATH,
            status() to OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION)
        val threads = candidates.map { (original, origin) -> Thread { f.recorder.trueFirstTerminal(f.selected, at(2), original, origin) } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        val selected = checkNotNull(snapshot(f.recorder).firstTerminal)
        assertTrue(candidates.any { it.first === selected.status && it.second == selected.origin })
        assertFalse(f.recorder.trueFirstTerminal(f.selected, at(3), status(), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION))
        assertSame(selected, snapshot(f.recorder).firstTerminal)
    }

}

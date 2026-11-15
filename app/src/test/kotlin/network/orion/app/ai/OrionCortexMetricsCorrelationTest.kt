package network.orion.app.ai

import network.orion.ai.api.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

/** Mechanical identities/times only; these facts do not approve a model or runtime. */
class OrionCortexMetricsCorrelationTest {
    private val now = MonotonicInstant(BootSessionId(1), 1000)
    private val service = Any()
    private val owner = Any()
    private val ids = OrionIssuedRequestHistory<Unit> { true }

    private fun connected(minor: Int = 2, epoch: Long? = 1L) = OrionCortexMetricsCorrelation().also {
        it.connected(service, owner, epoch, minor)
    }

    private fun candidate(gate: OrionCortexMetricsCorrelation, logical: String = "logical",
        transport: String = ids.issue(), callback: OrionCortexMetricsCorrelation.ResourceCallback? = null,
        at: MonotonicInstant = now): OrionCortexMetricsCorrelation.Binding? =
        gate.candidate(service, owner, logical, transport, callback, true, at, now.deadlineAfter(100), 8, 128)

    private fun admitted(gate: OrionCortexMetricsCorrelation, logical: String = "logical"): OrionCortexMetricsCorrelation.Binding {
        val callback = gate.beginResourceCallback()
        gate.memory(service, owner, 42, now)
        gate.endResourceCallback(callback)
        val selected = checkNotNull(candidate(gate, logical, callback = callback))
        gate.beginningInference(); gate.admitted(selected)
        return selected
    }

    private fun snapshot(binding: OrionCortexMetricsCorrelation.Binding, pid: Int = 42,
        request: String = binding.transportId, boot: Int? = null, captured: Long = 1000, wrapperStarted: Long? = null) = OrionGenerationMetricsSnapshot(
        request, pid, null, 1, OrionInferenceTerminal.CANCELLED, OrionGenerationNativeState.UNKNOWN,
        OrionGenerationNativeFinish.UNKNOWN, OrionGenerationSlotState.UNKNOWN,
        OrionGenerationSnapshotState.UPDATING, OrionGenerationPartialWork.UNKNOWN, 0,
        if (boot == null) OrionGenerationTimeState.UNAVAILABLE else OrionGenerationTimeState.KNOWN_BOOT,
        boot, if (boot == null) null else captured, if (boot == null) null else wrapperStarted)

    @Test fun `fresh witness binds actual transport and limits without fabricating native completion`() {
        val gate = connected(); val binding = admitted(gate)
        assertSame(binding, gate.select("logical"))
        assertEquals(8, binding.maxTokens); assertEquals(128, binding.contextTokens)
        val value = snapshot(binding)
        assertTrue(gate.accepts(binding, value))
        assertEquals(OrionGenerationNativeState.UNKNOWN, value.nativeState)
        assertEquals(OrionGenerationSnapshotState.UPDATING, value.snapshotState)
        assertNull(value.metric(OrionGenerationMetric.PROMPT_TOKENS))
    }

    @Test fun `no identity or old load witness cannot stand in for this resource evaluation`() {
        val gate = connected()
        assertNull(candidate(gate))
        gate.memory(service, owner, 42, now)
        val callback = gate.beginResourceCallback()
        assertNull(candidate(gate, callback = callback))
        gate.memory(service, owner, 42, now) // Same timestamp is legal; this is another actual read.
        gate.endResourceCallback(callback)
        assertNotNull(candidate(gate, callback = callback))
    }

    @Test fun `no accepted start means no query mapping and new attempt retires previous mapping`() {
        val gate = connected(); val first = admitted(gate, "first")
        gate.beginningInference()
        assertNull(gate.select("first")); assertFalse(gate.current(first))
        gate.admitted(null)
        assertNull(gate.select("first"))
    }

    @Test fun `only one last logical request survives and an old logical replay cannot retarget`() {
        val gate = connected(); val first = admitted(gate, "first"); val second = admitted(gate, "second")
        assertNull(gate.select("first")); assertFalse(gate.accepts(first, snapshot(first)))
        assertSame(second, gate.select("second"))
        assertFalse(gate.accepts(second, snapshot(second, request = first.transportId)))
    }

    @Test fun `actual endpoint references and epoch replacement invalidate delayed replies`() {
        val gate = connected(); val old = admitted(gate)
        gate.connected(service, owner, 2, 2) // Even numerically identical PID/boot cannot preserve a connection epoch.
        assertFalse(gate.current(old)); assertNull(gate.select("logical"))
        gate.memory(Any(), owner, 42, now); assertNull(candidate(gate))
        gate.memory(service, Any(), 42, now); assertNull(candidate(gate))
    }

    @Test fun `legacy peer or exhausted epoch never enables discovery or correlation`() {
        for ((minor, epoch) in listOf(1 to 1L, 2 to 0L, 2 to null)) {
            val gate = connected(minor, epoch); gate.memory(service, owner, 42, now)
            assertNull(candidate(gate))
        }
    }

    @Test fun `fresh callback must match decision boot and real request window`() {
        val gate = connected(); val callback = gate.beginResourceCallback()
        gate.memory(service, owner, 42, now); gate.endResourceCallback(callback)
        val id = ids.issue()
        for (decision in listOf(null, MonotonicInstant(null, 1000), MonotonicInstant(BootSessionId(2), 1000),
                MonotonicInstant(BootSessionId(1), 1001), MonotonicInstant(BootSessionId(1), 999))) {
            assertNull(gate.candidate(service, owner, "logical", id, callback, true, decision, now.deadlineAfter(100), 8, 128))
        }
        assertNull(gate.candidate(service, owner, "logical", id, callback, false, now, now.deadlineAfter(100), 8, 128))
        assertNull(gate.candidate(service, owner, "logical", id, callback, true, now, now.deadlineAfter(0), 8, 128))
    }

    @Test fun `stale or future receipt cannot become admission witness by global limits`() {
        for (elapsed in listOf(999L, 1100L, Long.MAX_VALUE)) {
            val gate = connected(); val callback = gate.beginResourceCallback()
            gate.memory(service, owner, 42, MonotonicInstant(BootSessionId(1), elapsed)); gate.endResourceCallback(callback)
            assertNull(candidate(gate, callback = callback))
        }
        val gate = connected(); val callback = gate.beginResourceCallback()
        gate.memory(service, owner, 42, now); gate.endResourceCallback(callback)
        assertNull(candidate(gate, transport = "unissued", callback = callback))
        assertNull(gate.candidate(service, owner, "logical", ids.issue(), callback, true, now, now.deadlineAfter(100), 0, 128))
    }

    @Test fun `missing changed or regressed expected identity cannot be repaired for old invocation`() {
        for (change in listOf(MonotonicInstant(BootSessionId(2), 1001), MonotonicInstant(BootSessionId(1), 999))) {
            val gate = connected(); val old = admitted(gate)
            gate.memory(service, owner, 42, change)
            assertFalse(gate.accepts(old, snapshot(old)))
            gate.memory(service, owner, 42, now)
            assertNull(candidate(gate)) // Poisoned identity for this connection, no retrospective repair.
        }
        val gate = connected(); val old = admitted(gate)
        gate.memoryUnavailable(); assertFalse(gate.current(old))
    }

    @Test fun `concurrent telemetry outside synchronous callback cannot fabricate its witness`() {
        val gate = connected(); val callback = gate.beginResourceCallback()
        val other = Thread { gate.memory(service, owner, 42, now) }.apply { start() }
        other.join(1000); assertFalse(other.isAlive)
        gate.endResourceCallback(callback)
        assertNull(candidate(gate, callback = callback))
    }

    @Test fun `reported positive pid and known boot are checked while unknown time stays unknown`() {
        val gate = connected(); val selected = admitted(gate)
        assertTrue(gate.accepts(selected, snapshot(selected, boot = 1)))
        assertFalse(gate.accepts(selected, snapshot(selected, pid = 43)))
        assertFalse(gate.accepts(selected, snapshot(selected, boot = 2)))
        assertTrue(gate.accepts(selected, snapshot(selected)))
        gate.memory(service, owner, 43, now); assertFalse(gate.current(selected))
    }

    @Test fun `known capture and wrapper start cannot precede the real pre admission identity witness`() {
        val gate = connected(); val selected = admitted(gate)
        assertFalse(gate.accepts(selected, snapshot(selected, boot = 1, captured = 999)))
        assertFalse(gate.accepts(selected, snapshot(selected, boot = 1, wrapperStarted = 999)))
        assertTrue(gate.accepts(selected, snapshot(selected, boot = 1, captured = 1200, wrapperStarted = 1100)))
        assertTrue(gate.accepts(selected, snapshot(selected))) // Missing source clocks stay unavailable.
    }

    @Test fun `diagnostic allocation or projection failure clears correlation without escaping`() {
        val gate = connected(); val old = admitted(gate)
        assertNull(gate.isolatedMetrics { throw OutOfMemoryError("Scripted diagnostic allocation") })
        assertFalse(gate.current(old)); assertNull(gate.select("logical"))
        val disabled: OrionCortexMetricsCorrelation? = null
        assertNull(disabled.isolatedMetrics { error("Disabled diagnostic operation must not execute") })
    }

    @Test fun `discovery is claimed once and failed discovery remains unavailable without retry`() {
        val gate = connected(); val selected = admitted(gate)
        val discovery = checkNotNull(gate.claimDiscovery(selected))
        assertNull(gate.claimDiscovery(selected)); assertNull(gate.mask(selected))
        gate.discovered(discovery, null)
        assertNull(gate.mask(selected)); assertNull(gate.claimDiscovery(selected))
        gate.discovered(discovery, 1L); assertNull(gate.mask(selected))
    }

    @Test fun `zero support mask is valid and cached only for the same endpoint`() {
        val gate = connected(); val selected = admitted(gate)
        val discovery = checkNotNull(gate.claimDiscovery(selected)); gate.discovered(discovery, 0)
        assertEquals(0L, gate.mask(selected))
        gate.clear(); gate.connected(service, owner, 2, 2)
        val replacement = admitted(gate)
        gate.discovered(discovery, 1) // Old delayed discovery cannot populate new epoch.
        assertNull(gate.mask(replacement))
        assertNotNull(gate.claimDiscovery(replacement))
    }
}

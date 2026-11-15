package network.orion.ai.service

import java.util.concurrent.atomic.AtomicLong
import network.orion.ai.api.*
import network.orion.ai.llama.OrionLlamaFinish
import network.orion.ai.llama.OrionLlamaServiceGenerationReceipt
import network.orion.ai.llama.OrionLlamaServiceMetric
import org.junit.Assert.*
import org.junit.Test

/** Mechanical receipt fixtures; no JNI, model, generation, clock, device or admission is supplied. */
class OrionServiceGenerationMetricsTest {
    private val history = OrionIssuedRequestHistory<Any> { false }
    private val id = history.issue()
    private fun recorder(maxTokens: Int = 1, context: Int = 128,
        ids: OrionServiceInvocationIds = OrionServiceInvocationIds()) =
        OrionServiceGenerationMetrics(id, 123, maxTokens, context, ids = ids)

    private fun oneToken() = mapOf(
        OrionLlamaServiceMetric.PROMPT_TOKENS to 4L,
        OrionLlamaServiceMetric.SAMPLED_TOKENS to 1L,
        OrionLlamaServiceMetric.EOG_TOKENS to 0L,
        OrionLlamaServiceMetric.CALLBACKS_ATTEMPTED to 1L,
        OrionLlamaServiceMetric.CALLBACKS_RETURNED to 1L,
        OrionLlamaServiceMetric.PREFILL_CALLS to 1L,
        OrionLlamaServiceMetric.PREFILL_SUCCEEDED to 1L,
        OrionLlamaServiceMetric.PREFILL_SUBMITTED to 4L,
        OrionLlamaServiceMetric.PREFILL_SUCCESSFUL_TOKENS to 4L,
        OrionLlamaServiceMetric.DECODE_CALLS to 0L,
        OrionLlamaServiceMetric.DECODE_SUCCEEDED to 0L,
        OrionLlamaServiceMetric.DECODE_SUBMITTED to 0L,
        OrionLlamaServiceMetric.DECODE_SUCCESSFUL_TOKENS to 0L,
        OrionLlamaServiceMetric.TOKENIZATION_NANOS to 7L,
        OrionLlamaServiceMetric.PREFILL_NANOS to 8L,
    )

    // The port intentionally cannot be manufactured by service production code. Reflection is
    // confined to this test to supply reviewed mechanical frames without opening that API/JNI.
    private fun receipt(finish: OrionLlamaFinish = OrionLlamaFinish.TOKEN_LIMIT,
        values: Map<OrionLlamaServiceMetric, Long> = oneToken(), stages: Int = 7,
        partial: Boolean = false): OrionLlamaServiceGenerationReceipt =
        OrionLlamaServiceGenerationReceipt::class.java.declaredConstructors.single().let {
            it.isAccessible = true
            it.newInstance(finish, values, stages, partial) as OrionLlamaServiceGenerationReceipt
        }

    @Test fun `initial and repeated reads are cached unavailable-time facts without source invocation`() {
        val recorder = recorder()
        val initial = checkNotNull(recorder.snapshot())
        repeat(1000) { assertSame(initial, recorder.snapshot()) }
        assertEquals(1L, initial.snapshotSequence)
        assertEquals(id, initial.requestId); assertEquals(123, initial.servicePid)
        assertEquals(OrionGenerationNativeState.NOT_STARTED, initial.nativeState)
        assertEquals(OrionGenerationSlotState.OWNED, initial.slotState)
        assertNull(initial.invocationId); assertNull(initial.logicalTerminal)
        assertEquals(0L, initial.availableMetricsMask)
        assertEquals(OrionGenerationTimeState.UNAVAILABLE, initial.timeState)
        assertNull(initial.bootCount); assertNull(initial.capturedElapsedMillis)
        assertNull(initial.wrapperStartedElapsed); assertNull(initial.wrapperFinishedElapsed)
    }

    @Test fun `a wrapper attempt does not manufacture running knowledge or invocation identity`() {
        val recorder = recorder()
        recorder.wrapperAttempted()
        val attempted = checkNotNull(recorder.snapshot())
        assertEquals(2L, attempted.snapshotSequence)
        assertEquals(OrionGenerationNativeState.UNKNOWN, attempted.nativeState)
        assertEquals(OrionGenerationPartialWork.UNKNOWN, attempted.partialWorkState)
        assertNull(attempted.invocationId)
        assertEquals(0L, attempted.availableMetricsMask)
    }

    @Test fun `real port fields map one to one without changing token limit into logical terminal`() {
        val recorder = recorder()
        recorder.wrapperAttempted(); recorder.returned(receipt())
        val returned = checkNotNull(recorder.snapshot())
        assertEquals(OrionGenerationNativeState.JNI_RETURNED, returned.nativeState)
        assertEquals(OrionGenerationNativeFinish.TOKEN_LIMIT, returned.nativeFinish)
        assertEquals(1L, returned.invocationId)
        assertNull(returned.logicalTerminal)
        assertEquals(OrionGenerationSlotState.OWNED, returned.slotState)
        assertEquals(OrionGenerationSnapshotState.UPDATING, returned.snapshotState)
        assertEquals(OrionGenerationMetric.ALL_MASK xor OrionGenerationMetric.DECODE_NANOS.bit, returned.availableMetricsMask)
        for ((metric, value) in oneToken())
            assertEquals(value, returned.metric(OrionGenerationMetric.entries.single { it.key == metric.key }))
        assertEquals(0L, returned.metric(OrionGenerationMetric.DECODE_CALLS)) // maxTokens=1.
        assertNull(returned.metric(OrionGenerationMetric.DECODE_NANOS)) // No decode API call, no timer.
        assertEquals(7, returned.completedCallStagesMask)
    }

    @Test fun `early cancellation remains independent of later native finish and worker finality`() {
        val recorder = recorder()
        recorder.wrapperAttempted(); recorder.terminal(OrionInferenceTerminal.CANCELLED)
        val early = checkNotNull(recorder.snapshot())
        assertEquals(OrionGenerationNativeState.UNKNOWN, early.nativeState)
        assertEquals(OrionGenerationSnapshotState.UPDATING, early.snapshotState)
        recorder.returned(receipt())
        val returned = checkNotNull(recorder.snapshot())
        assertEquals(OrionInferenceTerminal.CANCELLED, returned.logicalTerminal)
        assertEquals(OrionGenerationNativeFinish.TOKEN_LIMIT, returned.nativeFinish)
        assertEquals(OrionGenerationSlotState.OWNED, returned.slotState)
        recorder.workerReleased()
        val final = checkNotNull(recorder.snapshot())
        assertEquals(returned.snapshotSequence + 1, final.snapshotSequence)
        assertEquals(OrionGenerationSnapshotState.FINAL, final.snapshotState)
        assertEquals(OrionGenerationSlotState.RELEASED, final.slotState)
        repeat(100) { assertSame(final, recorder.snapshot()) }
        recorder.terminal(OrionInferenceTerminal.CANCELLED)
        assertSame(final, recorder.snapshot())
        assertEquals(OrionGenerationSlotState.OWNED, early.slotState) // Old snapshots are immutable.
    }

    @Test fun `cancelled worker before wrapper proves not executed but no zero token measurements`() {
        val recorder = recorder()
        recorder.terminal(OrionInferenceTerminal.CANCELLED); recorder.workerReleased()
        val final = checkNotNull(recorder.snapshot())
        assertEquals(OrionGenerationNativeState.NOT_EXECUTED, final.nativeState)
        assertEquals(OrionGenerationSnapshotState.FINAL, final.snapshotState)
        assertEquals(OrionGenerationNativeFinish.NONE, final.nativeFinish)
        assertNull(final.invocationId)
        assertEquals(0L, final.availableMetricsMask)
        assertNull(final.metric(OrionGenerationMetric.SAMPLED_TOKENS))
    }

    @Test fun `partial cancelled prompt work retains known code0 counts and absent duration honestly`() {
        val recorder = recorder(8)
        val values = oneToken().toMutableMap().apply {
            this[OrionLlamaServiceMetric.SAMPLED_TOKENS] = 0
            this[OrionLlamaServiceMetric.CALLBACKS_ATTEMPTED] = 0
            this[OrionLlamaServiceMetric.CALLBACKS_RETURNED] = 0
            this[OrionLlamaServiceMetric.PREFILL_SUCCEEDED] = 0
            this[OrionLlamaServiceMetric.PREFILL_SUCCESSFUL_TOKENS] = 0
        }
        recorder.wrapperAttempted(); recorder.returned(receipt(OrionLlamaFinish.CANCELLED, values, 1, true))
        recorder.terminal(OrionInferenceTerminal.CANCELLED); recorder.workerReleased()
        val final = checkNotNull(recorder.snapshot())
        assertEquals(OrionGenerationPartialWork.POSSIBLE, final.partialWorkState)
        assertEquals(1, final.completedCallStagesMask)
        assertEquals(4L, final.metric(OrionGenerationMetric.PREFILL_SUBMITTED))
        assertEquals(0L, final.metric(OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS))
        assertNull(final.metric(OrionGenerationMetric.DECODE_NANOS))
        assertEquals(OrionGenerationSnapshotState.FINAL, final.snapshotState)
    }

    @Test fun `missing receipt after unwind stays unavailable through terminal and cleanup`() {
        val recorder = recorder()
        recorder.wrapperAttempted(); recorder.returned(null)
        recorder.terminal(OrionInferenceTerminal.FAILED); recorder.workerReleased()
        assertNull(recorder.snapshot())
    }

    @Test fun `attempt left unknown at cleanup cannot promise a future final receipt`() {
        val recorder = recorder()
        recorder.wrapperAttempted(); recorder.terminal(OrionInferenceTerminal.TIMED_OUT); recorder.workerReleased()
        assertNull(recorder.snapshot())
    }

    @Test fun `impossible decode for actual max1 faults only telemetry while max8 remains valid`() {
        val values = oneToken().toMutableMap().apply {
            this[OrionLlamaServiceMetric.DECODE_CALLS] = 1
            this[OrionLlamaServiceMetric.DECODE_SUCCEEDED] = 1
            this[OrionLlamaServiceMetric.DECODE_SUBMITTED] = 1
            this[OrionLlamaServiceMetric.DECODE_SUCCESSFUL_TOKENS] = 1
            this[OrionLlamaServiceMetric.DECODE_NANOS] = 0 // An entered call can observe zero clock resolution.
        }
        val rejected = recorder()
        rejected.wrapperAttempted(); rejected.returned(receipt(OrionLlamaFinish.CANCELLED, values, 3))
        assertNull(rejected.snapshot())
        val accepted = recorder(8)
        accepted.wrapperAttempted(); accepted.returned(receipt(OrionLlamaFinish.CANCELLED, values, 3))
        assertEquals(1L, accepted.snapshot()?.metric(OrionGenerationMetric.DECODE_SUCCEEDED))
        assertEquals(0L, accepted.snapshot()?.metric(OrionGenerationMetric.DECODE_NANOS))
    }

    @Test fun `actual loaded context is checked instead of nominal global context`() {
        val values = oneToken().toMutableMap().apply {
            this[OrionLlamaServiceMetric.PROMPT_TOKENS] = 30
            this[OrionLlamaServiceMetric.PREFILL_SUBMITTED] = 30
            this[OrionLlamaServiceMetric.PREFILL_SUCCESSFUL_TOKENS] = 30
        }
        val recorder = recorder(8, 32)
        recorder.wrapperAttempted(); recorder.returned(receipt(OrionLlamaFinish.CANCELLED, values, 3))
        assertNull(recorder.snapshot())
    }

    @Test fun `invocation exhaustion and repeated receipt never reuse an identity`() {
        val counter = AtomicLong(Long.MAX_VALUE - 1)
        val ids = OrionServiceInvocationIds(counter)
        assertEquals(Long.MAX_VALUE, ids.next()); assertNull(ids.next()); assertNull(ids.next())
        val exhausted = recorder(ids = ids)
        exhausted.wrapperAttempted(); exhausted.returned(receipt()); assertNull(exhausted.snapshot())
        val freshIds = OrionServiceInvocationIds()
        val repeated = recorder(ids = freshIds)
        repeated.wrapperAttempted(); repeated.returned(receipt()); repeated.returned(receipt())
        assertNull(repeated.snapshot()); assertEquals(2L, freshIds.next())
    }

    @Test fun `duplicate terminal facts keep sequence but conflicting or duplicate attempt faults telemetry`() {
        val recorder = recorder()
        recorder.terminal(OrionInferenceTerminal.CANCELLED)
        val terminal = recorder.snapshot()
        recorder.terminal(OrionInferenceTerminal.CANCELLED); assertSame(terminal, recorder.snapshot())
        recorder.terminal(OrionInferenceTerminal.SUCCEEDED); assertNull(recorder.snapshot())
        val repeated = recorder()
        repeated.wrapperAttempted(); repeated.wrapperAttempted(); assertNull(repeated.snapshot())
    }

    @Test fun `sequence exhaustion never wraps or creates a zero sequence`() {
        val recorder = recorder()
        val field = recorder.javaClass.getDeclaredField("current").apply { isAccessible = true }
        field.set(recorder, OrionGenerationMetricsSnapshot(id, 123, null, Long.MAX_VALUE, null,
            OrionGenerationNativeState.NOT_STARTED, OrionGenerationNativeFinish.NONE, OrionGenerationSlotState.OWNED,
            OrionGenerationSnapshotState.UPDATING, OrionGenerationPartialWork.NONE_OBSERVED, 0,
            OrionGenerationTimeState.UNAVAILABLE))
        recorder.terminal(OrionInferenceTerminal.CANCELLED)
        assertNull(recorder.snapshot())
    }

    @Test fun `release without logical terminal stays unavailable rather than false final`() {
        val recorder = recorder()
        recorder.wrapperAttempted(); recorder.returned(receipt()); recorder.workerReleased()
        assertNull(recorder.snapshot())
    }

    @Test fun `disabled legacy and faulty capture creation cannot become admission failures`() {
        assertNull(createServiceGenerationMetrics(false, id, -1, -1, null, "INVALID"))
        assertNull(createServiceGenerationMetrics(true, "legacy", 123, 1, 128, null))
        assertNull(createServiceGenerationMetrics(true, id, 123, 1, null, null))
        assertNull(createServiceGenerationMetrics(true, id, 0, 1, 128, null))
        assertNull(createServiceGenerationMetrics(true, id, 123, 1, 128, "INVALID"))
        val created = createServiceGenerationMetrics(true, id, 123, 1, 128, "CANCELLED")
        assertEquals(OrionInferenceTerminal.CANCELLED, created?.snapshot()?.logicalTerminal)
    }

    @Test fun `retired entry cannot be recreated by its late worker marker`() {
        val history = OrionIssuedRequestHistory<OrionServiceGenerationMetrics>(1) { it.snapshot()?.logicalTerminal != null }
        val oldId = history.issue()
        val old = OrionServiceGenerationMetrics(oldId, 123, 1, 128)
        history.put(oldId, old); old.terminal(OrionInferenceTerminal.CANCELLED)
        val replacementId = history.issue()
        val replacement = OrionServiceGenerationMetrics(replacementId, 123, 1, 128)
        history.put(replacementId, replacement)
        assertThrows(OrionRetiredRequestException::class.java) { history.find(oldId) }
        old.unavailable(); old.workerReleased() // Same discard branch used after retained-entry check.
        assertNull(old.snapshot())
        assertThrows(OrionRetiredRequestException::class.java) { history.find(oldId) }
        assertSame(replacement, history.find(replacementId)); assertEquals(1, history.retainedCount)
    }
}

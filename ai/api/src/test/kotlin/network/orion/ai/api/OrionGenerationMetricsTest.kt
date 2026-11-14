package network.orion.ai.api

import org.junit.Assert.*
import org.junit.Test

class OrionGenerationMetricsTest {
    private fun snapshot(metrics: Map<OrionGenerationMetric, Long> = emptyMap(),
        finish: OrionGenerationNativeFinish = OrionGenerationNativeFinish.TOKEN_LIMIT,
        state: OrionGenerationSnapshotState = OrionGenerationSnapshotState.FINAL,
        slot: OrionGenerationSlotState = OrionGenerationSlotState.RELEASED,
        terminal: OrionInferenceTerminal? = OrionInferenceTerminal.SUCCEEDED,
        time: OrionGenerationTimeState = OrionGenerationTimeState.UNAVAILABLE,
        boot: Int? = null, captured: Long? = null, started: Long? = null, ended: Long? = null,
        stages: Int = 0, native: OrionGenerationNativeState = OrionGenerationNativeState.JNI_RETURNED,
        requestId: String = "orion_request/test/1",
    ) = OrionGenerationMetricsSnapshot(requestId, 1, 1, 1, terminal,
        native, finish, slot, state,
        OrionGenerationPartialWork.NONE_OBSERVED, stages, time, boot, captured, started, ended, metrics)

    @Test fun maxOneHasOneCallbackAndNoDecode() {
        val metrics = mapOf(OrionGenerationMetric.PROMPT_TOKENS to 3L,
            OrionGenerationMetric.SAMPLED_TOKENS to 1L, OrionGenerationMetric.EOG_TOKENS to 0L,
            OrionGenerationMetric.CALLBACKS_ATTEMPTED to 1L, OrionGenerationMetric.CALLBACKS_RETURNED to 1L,
            OrionGenerationMetric.DECODE_CALLS to 0L, OrionGenerationMetric.DECODE_SUBMITTED to 0L)
        snapshot(metrics).requireAdmittedBounds(1, 32)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(metrics + (OrionGenerationMetric.DECODE_CALLS to 1L) + (OrionGenerationMetric.DECODE_SUBMITTED to 1L))
                .requireAdmittedBounds(1, 32)
        }
    }
    @Test fun eogIsSampledWithoutCallback() {
        snapshot(mapOf(OrionGenerationMetric.SAMPLED_TOKENS to 1L, OrionGenerationMetric.EOG_TOKENS to 1L,
            OrionGenerationMetric.CALLBACKS_ATTEMPTED to 0L), OrionGenerationNativeFinish.SUCCEEDED).requireAdmittedBounds(8, 32)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(mapOf(OrionGenerationMetric.SAMPLED_TOKENS to 1L, OrionGenerationMetric.EOG_TOKENS to 1L,
                OrionGenerationMetric.CALLBACKS_ATTEMPTED to 1L), OrionGenerationNativeFinish.SUCCEEDED)
        }
    }
    @Test fun missingCountsAreAbsentAndInputIsCopied() {
        val source = mutableMapOf(OrionGenerationMetric.SAMPLED_TOKENS to 1L)
        val value = snapshot(source); source.clear()
        assertEquals(1L, value.metric(OrionGenerationMetric.SAMPLED_TOKENS))
        assertNull(value.metric(OrionGenerationMetric.PROMPT_TOKENS))
        assertFalse(value.toString().contains("orion_request"))
    }
    @Test fun finalityCannotBeInferredFromTerminal() {
        assertThrows(IllegalArgumentException::class.java) { snapshot(slot = OrionGenerationSlotState.OWNED) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(terminal = null) }
        snapshot(state = OrionGenerationSnapshotState.UPDATING, slot = OrionGenerationSlotState.OWNED)
    }
    @Test fun clockAndBootAreAllOrExplicitlyUnavailable() {
        snapshot(time = OrionGenerationTimeState.KNOWN_BOOT, boot = 0, captured = 10, started = 3, ended = 8)
        assertThrows(IllegalArgumentException::class.java) { snapshot(boot = 0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(time = OrionGenerationTimeState.KNOWN_BOOT, boot = 0, captured = 10, started = 8, ended = 3) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(time = OrionGenerationTimeState.KNOWN_BOOT) }
    }
    @Test fun actualAdmittedLimitsOverrideRepresentationMaxima() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(mapOf(OrionGenerationMetric.PROMPT_TOKENS to 30L)).requireAdmittedBounds(8, 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(mapOf(OrionGenerationMetric.SAMPLED_TOKENS to 7L)).requireAdmittedBounds(8, 32)
        }
        assertThrows(IllegalArgumentException::class.java) { snapshot(mapOf(OrionGenerationMetric.PROMPT_TOKENS to 0L)) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(mapOf(OrionGenerationMetric.DECODE_NANOS to -1L)) }
    }
    @Test fun completedTokenizerAndPrefillRequireTheirMeasuredOperands() {
        val prompt = mapOf(OrionGenerationMetric.PROMPT_TOKENS to 3L)
        assertThrows(IllegalArgumentException::class.java) { snapshot(stages = 1) }
        snapshot(prompt, stages = 1)
        assertThrows(IllegalArgumentException::class.java) { snapshot(prompt, stages = 3) }
        val prefill = prompt + (OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L)
        assertThrows(IllegalArgumentException::class.java) { snapshot(prefill, stages = 2) }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(prompt + (OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 2L), stages = 3)
        }
        snapshot(prefill, stages = 3)
    }

    @Test fun completedLoopRequiresPrefillNativeReturnAndSuccessfulNativeFinish() {
        val counts = mapOf(OrionGenerationMetric.PROMPT_TOKENS to 3L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L)
        assertThrows(IllegalArgumentException::class.java) { snapshot(counts, stages = 5) }
        for (finish in listOf(OrionGenerationNativeFinish.NONE, OrionGenerationNativeFinish.UNKNOWN,
            OrionGenerationNativeFinish.CANCELLED)) {
            assertThrows(IllegalArgumentException::class.java) { snapshot(counts, finish, stages = 7) }
        }
        for (native in listOf(OrionGenerationNativeState.RUNNING, OrionGenerationNativeState.UNKNOWN)) {
            assertThrows(IllegalArgumentException::class.java) {
                snapshot(counts, OrionGenerationNativeFinish.NONE, OrionGenerationSnapshotState.UPDATING,
                    OrionGenerationSlotState.OWNED, stages = 7, native = native)
            }
        }
        for (finish in listOf(OrionGenerationNativeFinish.SUCCEEDED, OrionGenerationNativeFinish.TOKEN_LIMIT))
            snapshot(counts, finish, stages = 7).requireAdmittedBounds(1, 32)
    }

    @Test fun missingPrefillOperandsStayAbsentAndCountsDoNotInventStageBits() {
        for (metrics in listOf(
            mapOf(OrionGenerationMetric.PREFILL_CALLS to 0L),
            mapOf(OrionGenerationMetric.PREFILL_SUBMITTED to 3L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 1L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L))) {
            val value = snapshot(metrics, OrionGenerationNativeFinish.CANCELLED)
            assertEquals(0, value.completedCallStagesMask)
            for (metric in OrionGenerationMetric.entries)
                if (metric !in metrics) assertNull(value.metric(metric))
        }
        val complete = mapOf(OrionGenerationMetric.PROMPT_TOKENS to 3L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L)
        assertEquals(0, snapshot(complete).completedCallStagesMask)
    }

    @Test fun prefillCountsCannotDescribeTokensWithoutCallsOrEmptySuccessfulBatches() {
        for (metrics in listOf(
            mapOf(OrionGenerationMetric.PREFILL_CALLS to 0L, OrionGenerationMetric.PREFILL_SUBMITTED to 3L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 1L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 0L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 0L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 2L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 1L))) {
            assertThrows(IllegalArgumentException::class.java) { snapshot(metrics) }
        }
        snapshot(mapOf(OrionGenerationMetric.PREFILL_CALLS to 1L,
            OrionGenerationMetric.PREFILL_SUBMITTED to 3L, OrionGenerationMetric.PREFILL_SUCCEEDED to 1L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L))
        snapshot(mapOf(OrionGenerationMetric.PREFILL_CALLS to 1L,
            OrionGenerationMetric.PREFILL_SUBMITTED to 3L, OrionGenerationMetric.PREFILL_SUCCEEDED to 0L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 0L))
    }

    @Test fun metricsIdentityRequiresEmittableSyntaxWithoutPretendingToAuthenticateScope() {
        for (id in listOf("orion_request/x/001", "orion_request/x/0", "orion_request/x/-1",
            "orion_request/x/+1", "orion_request/x/NaN", "orion_request/x/9223372036854775808",
            "orion_request/", "orion_request/x", "orion_request/x/", "orion_request//1",
            "orion_request/ /1", "orion_request/x/y/1", "orion_request/x/1/2",
            " orion_request/x/1", "orion_request/x/1 ", "orion_request/x\n/1",
            "orion_request/" + "x".repeat(128) + "/1")) {
            assertThrows(IllegalArgumentException::class.java) { snapshot(requestId = id) }
        }
        for (id in listOf("orion_request/foreign-opaque-scope/1", "orion_request/x/${Long.MAX_VALUE}"))
            assertEquals(id, snapshot(requestId = id).requestId)
    }

}

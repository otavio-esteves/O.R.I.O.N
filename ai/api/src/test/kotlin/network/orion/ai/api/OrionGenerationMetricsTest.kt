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
    ) = OrionGenerationMetricsSnapshot("orion_request/test/1", 1, 1, 1, terminal,
        OrionGenerationNativeState.JNI_RETURNED, finish, slot, state,
        OrionGenerationPartialWork.NONE_OBSERVED, 7, time, boot, captured, started, ended, metrics)

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
}

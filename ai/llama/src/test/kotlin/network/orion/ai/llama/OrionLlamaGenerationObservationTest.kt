package network.orion.ai.llama

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OrionLlamaGenerationObservationTest {
    private fun oneToken() = longArrayOf(1, 4, 2, 1, 0, 1, 1, 1, 1, 4, 4, 0, 0, 0, 0, 0, 0, 1, 1, -1)
    private fun decode(raw: LongArray? = oneToken(), code: Int? = 2 or ORION_NATIVE_METRICS_PUBLISHED, limit: Int = 1) =
        decodeLlamaGenerationObservation(raw, code, limit)

    @Test fun `one token limit returns one sampled callback and no token decode`() {
        val result = decode()
        assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, result.state)
        val metrics = checkNotNull(result.metrics)
        assertEquals(OrionLlamaFinish.TOKEN_LIMIT, metrics.finish)
        assertEquals(4, metrics.promptTokens)
        assertEquals(2, metrics.tokenizerCalls)
        assertEquals(1, metrics.sampledTokens)
        assertEquals(1, metrics.callbacksAttempted)
        assertEquals(1, metrics.callbacksReturnedNormally)
        assertEquals(0, metrics.decode.tokensSubmitted)
        assertNull(metrics.decode.apiWallNanos)
    }

    @Test fun `eog sample does not imply a callback or decode token`() {
        val raw = oneToken().apply { this[3] = 1; this[4] = 1; this[5] = 0; this[6] = 0 }
        val metrics = checkNotNull(decode(raw, ORION_NATIVE_METRICS_PUBLISHED).metrics)
        assertEquals(OrionLlamaFinish.SUCCEEDED, metrics.finish)
        assertEquals(1, metrics.sampledTokens)
        assertEquals(1, metrics.eogSamples)
        assertEquals(0, metrics.callbacksReturnedNormally)
        assertEquals(0, metrics.decode.tokensInSuccessfulCalls)
    }

    @Test fun `token limit omits last token evaluation but eog after output does not`() {
        val raw = oneToken().apply {
            this[3] = 3; this[5] = 3; this[6] = 3
            this[11] = 2; this[12] = 2; this[13] = 2; this[14] = 2; this[19] = 1
        }
        assertEquals(2, decode(raw, 2 or ORION_NATIVE_METRICS_PUBLISHED, 3).metrics?.decode?.tokensInSuccessfulCalls)
        raw[3] = 4; raw[4] = 1
        raw[11] = 3; raw[12] = 3; raw[13] = 3; raw[14] = 3
        assertEquals(3, decode(raw, ORION_NATIVE_METRICS_PUBLISHED, 8).metrics?.decode?.tokensInSuccessfulCalls)
    }

    @Test fun `callback false is a normal return without acceptance or delivery assertion`() {
        val metrics = checkNotNull(decode(oneToken(), 1 or ORION_NATIVE_METRICS_PUBLISHED).metrics)
        assertEquals(OrionLlamaFinish.CANCELLED, metrics.finish)
        assertEquals(1, metrics.callbacksAttempted)
        assertEquals(1, metrics.callbacksReturnedNormally)
        assertEquals(0, metrics.decode.tokensSubmitted)
    }

    @Test fun `cancel before tokenizer leaves unknown prompt not zero prompt`() {
        val raw = LongArray(ORION_NATIVE_METRIC_SLOTS).apply {
            this[0] = 1; this[1] = -1; this[17] = -1; this[18] = -1; this[19] = -1
        }
        val metrics = checkNotNull(decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED).metrics)
        assertNull(metrics.promptTokens)
        assertEquals(0, metrics.tokenizerCalls)
        assertNull(metrics.tokenizeApiWallNanos)
        assertEquals(0, metrics.prefill.tokensSubmitted)
    }

    @Test fun `successful decode preceding cancellation is retained without claiming aborted work exact`() {
        val raw = oneToken().apply { this[11] = 1; this[12] = 1; this[13] = 1; this[14] = 1; this[19] = 1 }
        val completed = checkNotNull(decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED, 8).metrics)
        assertEquals(1, completed.decode.tokensInSuccessfulCalls)
        assertFalse(completed.decode.partialWorkUnknown)
        raw[12] = 0; raw[14] = 0; raw[16] = 1
        val aborted = checkNotNull(decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED, 8).metrics)
        assertEquals(1, aborted.decode.tokensSubmitted)
        assertEquals(0, aborted.decode.tokensInSuccessfulCalls)
        assertTrue(aborted.decode.partialWorkUnknown)
    }

    @Test fun `prefill aborted batch reports known prefix and unknown partial work`() {
        val raw = longArrayOf(1, 1024, 2, 0, 0, 0, 0, 2, 1, 1024, 512, 0, 0, 0, 0, 1, 0, 1, 1, -1)
        val metrics = checkNotNull(decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED, 8).metrics)
        assertEquals(1024, metrics.prefill.tokensSubmitted)
        assertEquals(512, metrics.prefill.tokensInSuccessfulCalls)
        assertTrue(metrics.prefill.partialWorkUnknown)
    }

    @Test fun `successful call must account for all its submitted tokens even before sampling`() {
        val raw = oneToken().apply { this[3] = 0; this[5] = 0; this[6] = 0 }
        assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED, 8).state)
        raw[10] = 3
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(raw, 1 or ORION_NATIVE_METRICS_PUBLISHED, 8).state)
    }

    @Test fun `zero duration and unavailable clock are distinct from not executed phase`() {
        val raw = oneToken().apply { this[17] = 0; this[18] = 0 }
        assertEquals(0L, decode(raw).metrics?.tokenizeApiWallNanos)
        assertEquals(0L, decode(raw).metrics?.prefill?.apiWallNanos)
        raw[17] = -1; raw[18] = -1
        assertNull(decode(raw).metrics?.tokenizeApiWallNanos)
        assertNull(decode(raw).metrics?.prefill?.apiWallNanos)
        raw[19] = 0 // No decode call: cannot claim its duration was measured zero.
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(raw).state)
    }

    @Test fun `without publication bit every byte of array is ignored and finish preserved`() {
        for (code in 0..2) {
            assertEquals(OrionLlamaObservationState.UNAVAILABLE_EXPORT, decode(oneToken(), code).state)
            assertEquals(llamaGenerationFinish(code, false), llamaGenerationFinish(code, true))
        }
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_GENERATION, decode(oneToken(), null).state)
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_EXPORT, decode(null).state)
    }

    @Test fun `unknown metadata flags cannot replace a valid functional finish`() {
        val code = 2 or ORION_NATIVE_METRICS_PUBLISHED or (1 shl 9)
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(oneToken(), code).state)
        assertEquals(OrionLlamaFinish.TOKEN_LIMIT, llamaGenerationFinish(code, true))
        for (invalid in listOf(-1, 3, 255)) {
            assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(oneToken(), invalid).state)
        }
    }

    @Test fun `schema size counts relations and duration ranges are closed`() {
        for (raw in listOf(LongArray(0), LongArray(19), LongArray(21), oneToken().apply { this[0] = 2 },
            oneToken().apply { this[1] = 0 }, oneToken().apply { this[1] = Long.MAX_VALUE },
            oneToken().apply { this[2] = 1 }, oneToken().apply { this[3] = 2 }, oneToken().apply { this[4] = 1 },
            oneToken().apply { this[5] = 2 }, oneToken().apply { this[6] = 0 }, oneToken().apply { this[10] = 5 },
            oneToken().apply { this[15] = 1 }, oneToken().apply { this[17] = -2 })) {
            assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(raw).state)
        }
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(oneToken(), limit = 0).state)
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, decode(oneToken(), limit = 32769).state)
    }

    @Test fun `fresh holder never sees older call on rejection or failed generation`() {
        val first = OrionLlamaGenerationCapture()
        first.claim(); first.complete(oneToken(), 2 or ORION_NATIVE_METRICS_PUBLISHED, 1)
        val saved = first.observation
        assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, saved.state)
        try { first.claim(); fail("Capture reused") } catch (_: IllegalStateException) { }
        assertSame(saved, first.observation) // Historical first invocation, never reused for another call.
        val second = OrionLlamaGenerationCapture()
        assertEquals(OrionLlamaObservationState.NOT_STARTED, second.observation.state)
        second.claim()
        assertEquals(OrionLlamaObservationState.IN_PROGRESS, second.observation.state)
        second.complete(null, null, 1)
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_GENERATION, second.observation.state)
        assertNull(second.observation.metrics)
    }

    @Test fun `holder contains parsing faults and keeps only immutable scalar copies`() {
        val capture = OrionLlamaGenerationCapture()
        capture.claim(); capture.complete(longArrayOf(1), 2 or ORION_NATIVE_METRICS_PUBLISHED, 1)
        assertEquals(OrionLlamaObservationState.UNAVAILABLE_FORMAT, capture.observation.state)
        assertNull(capture.observation.metrics)
        val raw = oneToken()
        val copied = OrionLlamaGenerationCapture()
        copied.claim(); copied.complete(raw, 2 or ORION_NATIVE_METRICS_PUBLISHED, 1)
        raw.fill(Long.MAX_VALUE)
        assertEquals(1, copied.observation.metrics?.sampledTokens)
        assertEquals(4, copied.observation.metrics?.prefill?.tokensInSuccessfulCalls)
    }

    @Test fun `one shot claim allows at most one concurrent invocation`() {
        val capture = OrionLlamaGenerationCapture()
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 4).map { workers.submit(Callable {
                start.await()
                try { capture.claim(); 1 } catch (_: IllegalStateException) { 0 }
            }) }
            start.countDown()
            assertEquals(1, jobs.sumOf { it.get(3, TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }
}

package network.orion.ai.llama

import org.junit.Assert.*
import org.junit.Test

class OrionLlamaServiceGenerationCaptureTest {
    private fun capture(code: Int = 2, raw: LongArray = longArrayOf(1, 4, 2, 1, 0, 1, 1, 1, 1, 4, 4, 0, 0, 0, 0, 0, 0, 1, 1, -1)) =
        OrionLlamaServiceGenerationCapture().apply { local.claim(); local.complete(raw, code or ORION_NATIVE_METRICS_PUBLISHED, 1) }

    @Test fun servicePortPreservesMeasuredSpecialTokenAndLastTokenSemantics() {
        val receipt = checkNotNull(capture().receipt())
        assertEquals(OrionLlamaFinish.TOKEN_LIMIT, receipt.finish)
        assertEquals(4L, receipt.metrics[OrionLlamaServiceMetric.PROMPT_TOKENS])
        assertEquals(1L, receipt.metrics[OrionLlamaServiceMetric.CALLBACKS_RETURNED])
        assertEquals(0L, receipt.metrics[OrionLlamaServiceMetric.DECODE_CALLS])
        assertFalse(receipt.metrics.containsKey(OrionLlamaServiceMetric.DECODE_NANOS))
        assertEquals(7, receipt.completedCallStagesMask)
        assertFalse(receipt.partialWorkPossible)
    }
    @Test fun receiptCannotMutateNativeFactsOrInventUnavailableSource() {
        assertNull(OrionLlamaServiceGenerationCapture().receipt())
        val value = capture()
        val receipt = checkNotNull(value.receipt())
        assertThrows(UnsupportedOperationException::class.java) { (receipt.metrics as MutableMap).clear() }
        assertEquals(1L, value.receipt()?.metrics?.get(OrionLlamaServiceMetric.SAMPLED_TOKENS))
        assertNull(capture(raw = LongArray(1)).receipt())
    }
    @Test fun normalFalseCallbackDoesNotClaimLoopCompletion() {
        val receipt = checkNotNull(capture(code = 1).receipt())
        assertEquals(OrionLlamaFinish.CANCELLED, receipt.finish)
        assertEquals(3, receipt.completedCallStagesMask)
        assertEquals(1L, receipt.metrics[OrionLlamaServiceMetric.CALLBACKS_RETURNED])
    }
}

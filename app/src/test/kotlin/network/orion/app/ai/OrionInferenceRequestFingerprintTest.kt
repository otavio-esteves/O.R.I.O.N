package network.orion.app.ai

import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionInferenceRequestFingerprintTest {
    private val deadline = MonotonicInstant(BootSessionId(1), 100).deadlineAfter(10_000)
    private fun request(id: String = "request", prompt: String = "?", context: String = "") =
        OrionInferenceRequest(id, 1, "a".repeat(64), prompt, context, 1, 0.0,
            OrionInferencePriority.INTERACTIVE, deadline, "trace")

    @Test fun `malformed prompt or context is rejected before admission and fingerprinting`() {
        for (malformed in listOf("\uD800", "\uDC00", "before\uD800after", "before\uDC00after")) {
            for (input in listOf(request(prompt = malformed), request(context = malformed))) {
                assertThrows(IllegalArgumentException::class.java) { validateOrionInferenceRequest(input, 32, 1) }
                assertThrows(IllegalArgumentException::class.java) { orionInferenceRequestFingerprint(input, 32, 1) }
            }
        }
    }

    @Test fun `valid Unicode stays distinct and exact replay retains the original result`() = runBlocking<Unit> {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val input = request(id, "Olá \uD83D\uDE80", "é / e\u0301")
        validateOrionInferenceRequest(input, 32, 1)
        val fingerprint = orionInferenceRequestFingerprint(input, 32, 1)
        val result = OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null)
        ledger.runOnce(id, fingerprint) { result }
        assertEquals(result, ledger.existingResult(id, orionInferenceRequestFingerprint(input, 32, 1)))
        val changed = request(id, "Olá \uD83D\uDE81", "é / e\u0301")
        val changedFingerprint = orionInferenceRequestFingerprint(changed, 32, 1)
        assertNotEquals(fingerprint, changedFingerprint)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ledger.existingResult(id, changedFingerprint) }
        }
    }

    @Test fun `malformed replay cannot alias a retained question mark request`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val valid = request(id)
        val result = OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null)
        val fingerprint = orionInferenceRequestFingerprint(valid, 32, 1)
        ledger.runOnce(id, fingerprint) { result }
        for (malformed in listOf("\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) {
                orionInferenceRequestFingerprint(request(id, malformed), 32, 1)
            }
        }
        assertEquals(result, ledger.existingResult(id, fingerprint))
    }
}

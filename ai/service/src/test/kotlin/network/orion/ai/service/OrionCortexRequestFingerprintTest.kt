package network.orion.ai.service

import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionCortexRequestFingerprintTest {
    private val start = MonotonicInstant(BootSessionId(1), 100)
    private val deadline = start.deadlineAfter(1000)

    @Test fun loadBindsEveryMaterialFieldAndItsBootBoundDeadline() {
        fun load(model: String = "model", hash: String = "hash", size: Long = 100, context: Int = 128,
            threads: Int = 1, at: network.orion.core.time.MonotonicDeadline = deadline) =
            OrionCortexRequestFingerprint.load(model, hash, size, context, threads, at)
        val first = load()
        assertEquals(first, load())
        for (changed in listOf(load(model = "other"), load(hash = "other"), load(size = 101),
            load(context = 256), load(threads = 2), load(at = start.deadlineAfter(999)),
            load(at = MonotonicInstant(BootSessionId(2), 100).deadlineAfter(1000)),
            load(at = MonotonicInstant(BootSessionId(1), 101).deadlineAfter(1000)))) assertNotEquals(first, changed)
    }

    @Test fun malformedStringsNeverCollideThroughUtf8Replacement() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            OrionCortexRequestFingerprint.inference("bad" + 0xd800.toChar(), "input", 5, 8, 0f, deadline)
        }
    }

    @Test fun inferenceBindsSnapshotContentAndFramedFieldsWithoutRetainingRawText() {
        fun infer(model: String = "model", text: String = "secret fixture", size: Long = 14,
            tokens: Int = 8, temperature: Float = 0f) =
            OrionCortexRequestFingerprint.inference(model, text, size, tokens, temperature, deadline)
        val first = infer()
        assertEquals(first, infer())
        assertEquals(first, infer(temperature = -0f))
        for (changed in listOf(infer(model = "other"), infer(text = "changed bytes!"), infer(size = 15),
            infer(tokens = 9), infer(temperature = 1f))) assertNotEquals(first, changed)
        assertNotEquals(infer(model = "ab", text = "c"), infer(model = "a", text = "bc"))
        assertNotEquals(first, OrionCortexRequestFingerprint.load("model", "secret fixture", 14, 8, 1, deadline))
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertFalse(first.contains("secret"))
    }
}

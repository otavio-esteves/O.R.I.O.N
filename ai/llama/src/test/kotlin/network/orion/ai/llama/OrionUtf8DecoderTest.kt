package network.orion.ai.llama

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class OrionUtf8DecoderTest {
    @Test fun `every byte split preserves supplementary unicode and accents`() {
        val text = "Olá 🌌 Orion 日本語"
        val decoder = OrionUtf8Decoder()
        val output = buildString {
            for (byte in text.toByteArray()) append(decoder.append(byteArrayOf(byte)))
            append(decoder.append(byteArrayOf(), true))
        }
        assertEquals(text, output)
    }
    @Test fun `truncated or malformed pieces fail without replacement characters`() {
        assertThrows(CharacterCodingException::class.java) { OrionUtf8Decoder().append(byteArrayOf(0xc0.toByte()), true) }
        val decoder = OrionUtf8Decoder()
        assertEquals("", decoder.append(byteArrayOf(0xf0.toByte())))
        assertThrows(CharacterCodingException::class.java) { decoder.append(byteArrayOf(), true) }
    }
    @Test fun `oversized native callback is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OrionUtf8Decoder().append(ByteArray(65537)) }
    }
}

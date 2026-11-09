package network.orion.ai.service

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class OrionUtf8PiecesTest {
    @Test fun `multi byte characters survive arbitrary token boundaries`() {
        val original = "Orion: ação 🛰️ 漢字"
        val decoder = OrionUtf8Pieces()
        val result = buildString {
            original.toByteArray(Charsets.UTF_8).forEach { append(decoder.accept(byteArrayOf(it))) }
            append(decoder.accept(ByteArray(0), final = true))
        }
        assertEquals(original, result)
    }

    @Test fun `ascii pieces are delivered incrementally without retaining prior output`() {
        val decoder = OrionUtf8Pieces()
        assertEquals("one", decoder.accept("one".toByteArray()))
        assertEquals("two", decoder.accept("two".toByteArray()))
        assertEquals("", decoder.accept(ByteArray(0), final = true))
    }

    @Test fun `truncated final code point fails instead of replacing invalid content`() {
        val decoder = OrionUtf8Pieces()
        assertEquals("", decoder.accept(byteArrayOf(0xe2.toByte(), 0x82.toByte())))
        assertThrows(CharacterCodingException::class.java) { decoder.accept(ByteArray(0), final = true) }
    }

    @Test fun `malformed utf8 never silently repairs output`() {
        assertThrows(CharacterCodingException::class.java) {
            OrionUtf8Pieces().accept(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        }
    }

    @Test fun `native piece size is bounded before decoder allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionUtf8Pieces().accept(ByteArray(OrionUtf8Pieces.MAX_PIECE_BYTES + 1))
        }
    }

    @Test fun `whole request output has an independent finite bound`() {
        val decoder = OrionUtf8Pieces()
        val piece = ByteArray(OrionUtf8Pieces.MAX_PIECE_BYTES) { 65 }
        repeat(OrionUtf8Pieces.MAX_OUTPUT_BYTES / piece.size) { assertEquals(piece.size, decoder.accept(piece).length) }
        assertThrows(IllegalArgumentException::class.java) { decoder.accept(byteArrayOf(65)) }
    }
}

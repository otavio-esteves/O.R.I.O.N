package network.orion.ai.llama

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Token pieces can divide a UTF-8 scalar; transport only complete decoded text. */
internal class OrionUtf8Decoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private var pending = byteArrayOf()
    fun append(piece: ByteArray, end: Boolean = false): String {
        require(piece.size <= 65536)
        val input = ByteBuffer.wrap(pending + piece)
        val output = CharBuffer.allocate(input.remaining() + 2)
        val result = decoder.decode(input, output, end)
        if (result.isError) result.throwException()
        check(!result.isOverflow)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        check(pending.size <= 3)
        if (end) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            check(!flushed.isOverflow && pending.isEmpty())
        }
        output.flip()
        return output.toString()
    }
}

package network.orion.ai.service

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Incremental strict token decoding: a token may end midway through UTF-8. */
internal class OrionUtf8Pieces {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var pending = ByteArray(0)
    private var totalBytes = 0

    fun accept(bytes: ByteArray, final: Boolean = false): String {
        require(bytes.size <= MAX_PIECE_BYTES && bytes.size <= MAX_OUTPUT_BYTES - totalBytes)
        totalBytes += bytes.size
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(MAX_PIECE_BYTES + 4)
        val result = decoder.decode(input, output, final)
        if (result.isError) result.throwException()
        check(!result.isOverflow)
        pending = ByteArray(input.remaining()).also(input::get)
        check(pending.size <= 3)
        if (final) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            check(!flushed.isOverflow && pending.isEmpty())
        }
        output.flip()
        return output.toString()
    }

    companion object { const val MAX_PIECE_BYTES = 16 * 1024; const val MAX_OUTPUT_BYTES = 1024 * 1024 }
}

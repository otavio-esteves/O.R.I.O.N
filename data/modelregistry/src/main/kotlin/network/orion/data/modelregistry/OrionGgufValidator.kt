package network.orion.data.modelregistry

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction

enum class OrionGgufFailure { TRUNCATED, UNSUPPORTED_VERSION, UNSUPPORTED_TYPE, INVALID_FORMAT, LIMIT_EXCEEDED }

class OrionGgufValidationException(val reason: OrionGgufFailure) : IOException(reason.name)

/** Structural validity is independent of native loadability, model trust and resource qualification. */
data class OrionGgufMetadata(val version: Int, val architecture: String, val contextLimit: Int?,
    val quantization: String, val tensorCount: Int, val sizeBytes: Long) {
    override fun toString(): String = "OrionGgufMetadata(<redacted>)"
}

/**
 * Bounded little-endian GGUF v3 reader. Supported tensor layouts are explicit;
 * unknown/removed quantizations and split models are refused rather than guessed.
 * Metadata text is transient and is never logged or retained in the registry.
 * The caller must supply a read-only channel to an immutable private artifact.
 * Specification: ggml-org/ggml docs/gguf.md; block sizes: ggml-common.h.
 */
object OrionGgufValidator {
    fun validate(channel: SeekableByteChannel): OrionGgufMetadata {
        try {
            channel.position(0)
            return read(Reader(channel))
        } catch (failure: OrionGgufValidationException) { throw failure }
        catch (_: ArithmeticException) { fail(OrionGgufFailure.INVALID_FORMAT) }
        catch (_: IllegalArgumentException) { fail(OrionGgufFailure.INVALID_FORMAT) }
        finally { if (channel.isOpen) channel.position(0) }
    }

    private fun read(reader: Reader): OrionGgufMetadata {
        if (reader.u32() != 0x46554747L) fail()
        if (reader.u32() != 3L) fail(OrionGgufFailure.UNSUPPORTED_VERSION)
        val tensorCount = reader.count(100_000)
        val metadataCount = reader.count(10_000)
        if (tensorCount == 0 || metadataCount == 0) fail()
        val keys = HashSet<String>()
        var architecture: String? = null
        var alignment = 32L
        val contexts = HashMap<String, Int>()
        repeat(metadataCount) {
            val key = reader.string(256)
            if (!key.matches(Regex("[A-Za-z0-9_.-]{1,256}")) || !keys.add(key)) fail()
            val type = reader.u32().toInt()
            when {
                key == "general.architecture" -> {
                    if (type != 8) fail()
                    architecture = reader.string(64).also { if (!it.matches(Regex("[a-z][a-z0-9_]{0,63}"))) fail() }
                }
                key == "general.alignment" -> {
                    if (type != 4) fail()
                    alignment = reader.u32()
                    if (alignment !in 8L..65_536L || alignment and (alignment - 1) != 0L) fail()
                }
                key == "general.type" -> { if (type != 8 || reader.string(32) != "model") fail() }
                key == "split.count" -> { if (reader.nonnegativeInteger(type) != 1L) fail() }
                key == "split.no" -> { if (reader.nonnegativeInteger(type) != 0L) fail() }
                key.endsWith(".context_length") -> {
                    val value = reader.nonnegativeInteger(type)
                    if (value !in 1..1_048_576L) fail(OrionGgufFailure.LIMIT_EXCEEDED)
                    contexts[key] = value.toInt()
                }
                else -> reader.value(type)
            }
        }
        val arch = architecture ?: fail()
        val names = HashSet<String>()
        val layouts = sortedSetOf<Int>()
        val ranges = ArrayList<Pair<Long, Long>>(tensorCount)
        repeat(tensorCount) {
            val name = reader.string(64)
            if (name.isEmpty() || !name.matches(Regex("[A-Za-z0-9_.-]+")) || !names.add(name)) fail()
            val dimensions = reader.u32()
            if (dimensions !in 1L..4L) fail()
            val shape = LongArray(dimensions.toInt()) { reader.u64().also { if (it == 0L) fail() } }
            val type = reader.u32().toInt()
            val layout = tensorLayouts[type] ?: fail(OrionGgufFailure.UNSUPPORTED_TYPE)
            if (shape[0] % layout.first != 0L) fail()
            var bytes = Math.multiplyExact(shape[0] / layout.first, layout.second)
            for (dimension in shape.drop(1)) bytes = Math.multiplyExact(bytes, dimension)
            val offset = reader.u64()
            if (offset % alignment != 0L) fail()
            ranges += offset to Math.addExact(offset, bytes)
            layouts += type
        }
        val data = aligned(reader.position, alignment)
        if (data > reader.size) fail(OrionGgufFailure.TRUNCATED)
        var previousEnd = 0L
        for ((offset, end) in ranges.sortedBy { it.first }) {
            if (offset < previousEnd) fail()
            if (Math.addExact(data, end) > reader.size) fail(OrionGgufFailure.TRUNCATED)
            previousEnd = end
        }
        // Permit the format's final alignment padding, not arbitrary trailing payloads.
        if (reader.size > Math.addExact(data, aligned(previousEnd, alignment))) fail()
        return OrionGgufMetadata(3, arch, contexts["$arch.context_length"], layouts.joinToString(","), tensorCount, reader.size)
    }

    private class Reader(private val channel: SeekableByteChannel) {
        val size = channel.size()
        val position: Long get() = channel.position()
        private var arrayItems = 0L
        private val scalar = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(): Long = number(4).int.toLong() and 0xffff_ffffL
        fun u64(): Long = number(8).long.also { if (it < 0) fail(OrionGgufFailure.LIMIT_EXCEEDED) }
        fun count(maximum: Int): Int = u64().also { if (it > maximum) fail(OrionGgufFailure.LIMIT_EXCEEDED) }.toInt()
        fun string(maximum: Int = 1_048_576): String {
            val length = count(maximum)
            val data = ByteBuffer.allocate(length)
            read(data)
            data.flip()
            return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(data).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { fail() }
        }
        fun nonnegativeInteger(type: Int): Long = when (type) {
            0 -> number(1).get().toLong() and 0xff
            2 -> number(2).short.toLong() and 0xffff
            4 -> u32()
            10 -> u64()
            else -> fail()
        }
        fun value(type: Int) {
            when (type) {
                0, 1 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                7 -> if (number(1).get().toInt() !in 0..1) fail()
                8 -> string()
                9 -> {
                    val element = u32().toInt()
                    if (element !in 0..12 || element == 9) fail(OrionGgufFailure.UNSUPPORTED_TYPE)
                    val count = count(2_000_000)
                    arrayItems += count
                    if (arrayItems > 2_000_000) fail(OrionGgufFailure.LIMIT_EXCEEDED)
                    repeat(count) { value(element) }
                }
                10, 11, 12 -> skip(8)
                else -> fail(OrionGgufFailure.UNSUPPORTED_TYPE)
            }
        }
        private fun number(length: Int): ByteBuffer { scalar.clear().limit(length); read(scalar); scalar.flip(); return scalar }
        private fun skip(bytes: Long) { checkRange(bytes); channel.position(position + bytes) }
        private fun read(buffer: ByteBuffer) {
            checkRange(buffer.remaining().toLong())
            while (buffer.hasRemaining()) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                if (channel.read(buffer) <= 0) fail(OrionGgufFailure.TRUNCATED)
            }
        }
        private fun checkRange(bytes: Long) {
            val end = Math.addExact(position, bytes)
            if (end > size) fail(OrionGgufFailure.TRUNCATED)
            if (end > 128L * 1024 * 1024) fail(OrionGgufFailure.LIMIT_EXCEEDED)
        }
    }

    // (elements per block, encoded bytes per block), explicit supported GGML layouts.
    private val tensorLayouts = mapOf(0 to (1L to 4L), 1 to (1L to 2L), 2 to (32L to 18L),
        3 to (32L to 20L), 6 to (32L to 22L), 7 to (32L to 24L), 8 to (32L to 34L),
        9 to (32L to 36L), 10 to (256L to 84L), 11 to (256L to 110L), 12 to (256L to 144L),
        13 to (256L to 176L), 14 to (256L to 210L), 15 to (256L to 292L), 30 to (1L to 2L))
    private fun aligned(value: Long, alignment: Long) = Math.multiplyExact(Math.addExact(value, alignment - 1) / alignment, alignment)
    private fun fail(reason: OrionGgufFailure = OrionGgufFailure.INVALID_FORMAT): Nothing = throw OrionGgufValidationException(reason)
}

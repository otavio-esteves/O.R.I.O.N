package network.orion.data.repository.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.Collections

enum class OrionGgufTensorType(val code: Int, internal val block: Int, internal val bytes: Int) {
    F32(0, 1, 4), F16(1, 1, 2), Q4_0(2, 32, 18), Q4_1(3, 32, 20), Q5_0(6, 32, 22), Q8_0(8, 32, 34),
    Q4_K(12, 256, 144), Q5_K(13, 256, 176), Q6_K(14, 256, 210), BF16(30, 1, 2),
}

class OrionGgufMetadata internal constructor(val architecture: String, val contextLimit: Int,
    val tensorCount: Int, val alignment: Int, types: Set<OrionGgufTensorType>) {
    val tensorTypes: Set<OrionGgufTensorType> = Collections.unmodifiableSet(types.toSet())
    override fun toString() = "OrionGgufMetadata(<redacted>)"
}

/** Structural safety subset only: does not assert native loadability or qualified resources. */
class OrionGgufValidator : OrionModelFileValidator {
    override fun validate(file: File): Boolean = inspect(file) != null

    fun inspect(file: File): OrionGgufMetadata? = try {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) null
        else file.inputStream().use { inspect(it, file.length()) }
    } catch (_: IOException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: ArithmeticException) { null }

    internal fun inspect(input: InputStream, sizeBytes: Long): OrionGgufMetadata? = try {
        Parser(input, sizeBytes).parse()
    } catch (_: CharacterCodingException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: ArithmeticException) { null }

    private class Parser(private val input: InputStream, private val fileSize: Long) {
        private var values = 0L
        private val buffer = ByteArray(64 * 1024)
        private var bufferPosition = 0
        private var bufferSize = 0
        private var position = 0L

        fun parse(): OrionGgufMetadata {
            require(u32() == 0x46554747L && u32() == 3L)
            val tensors = u64().also { require(it in 1..MAX_TENSORS.toLong()) }.toInt()
            val metadataCount = u64().also { require(it in 1..MAX_METADATA.toLong()) }.toInt()
            val keys = mutableSetOf<String>()
            val retained = mutableMapOf<String, Any>()
            repeat(metadataCount) {
                val key = string(65535)
                require(key.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)*")) && keys.add(key))
                val type = u32().toInt()
                val value = value(type, 0)
                if (key == "general.architecture" || key == "general.alignment" ||
                    key == "general.quantization_version" || key.endsWith(".context_length") || key == "split.count") {
                    // Standard numeric metadata must be unsigned UINT32/UINT64 (split count also UINT16).
                    if (key == "general.architecture") require(type == 8)
                    else if (key == "split.count") require(type in setOf(2, 4, 10))
                    else if (key.endsWith(".context_length")) require(type == 4 || type == 10)
                    else require(type == 4)
                    require(value != null)
                    retained[key] = value
                }
            }
            val architecture = retained["general.architecture"] as? String ?: invalid()
            require(architecture.matches(Regex("[a-z0-9]{1,64}")))
            val context = retained["$architecture.context_length"] as? Long ?: invalid()
            require(context in 1..Int.MAX_VALUE.toLong())
            val alignment = (retained["general.alignment"] as? Long ?: 32L)
                .also { require(it in 8..4096 && it % 8 == 0L) }.toInt()
            require(retained["split.count"] == null || retained["split.count"] == 1L)
            val names = mutableSetOf<String>()
            val types = mutableSetOf<OrionGgufTensorType>()
            val ranges = ArrayList<Pair<Long, Long>>(tensors)
            repeat(tensors) {
                val name = string(64)
                require(name.isNotBlank() && name.none(Char::isISOControl) && names.add(name))
                val dimensions = u32().also { require(it in 1..4) }.toInt()
                val shape = LongArray(dimensions) { u64().also { n -> require(n > 0) } }
                val typeCode = u32()
                val type = OrionGgufTensorType.entries.find { it.code.toLong() == typeCode } ?: invalid()
                types.add(type)
                val offset = u64()
                require(offset % alignment == 0L && shape[0] % type.block == 0L)
                var size = Math.multiplyExact(shape[0] / type.block, type.bytes.toLong())
                for (index in 1 until shape.size) size = Math.multiplyExact(size, shape[index])
                ranges.add(offset to Math.addExact(offset, size))
            }
            if (types.any { it.block > 1 }) require(retained["general.quantization_version"] == 2L)
            val dataStart = Math.addExact(position, (alignment - position % alignment) % alignment)
            require(dataStart <= fileSize)
            // Padding is explicitly zero in this supported format subset.
            while (position < dataStart) require(byte() == 0)
            val dataBytes = fileSize - dataStart
            var previousEnd = 0L
            for ((start, end) in ranges.sortedBy { it.first }) {
                require(start >= previousEnd && end <= dataBytes)
                previousEnd = end
            }
            return OrionGgufMetadata(architecture, context.toInt(), tensors, alignment, types)
        }

        private fun value(type: Int, depth: Int): Any? {
            require(++values <= MAX_VALUES && depth <= MAX_ARRAY_DEPTH)
            return when (type) {
                0 -> byte().toLong()
                1 -> byte().toByte().toLong()
                2 -> (byte() or (byte() shl 8)).toLong()
                3 -> (byte() or (byte() shl 8)).toShort().toLong()
                4 -> u32()
                5 -> u32().toInt().toLong()
                6 -> { require(Float.fromBits(u32().toInt()).isFinite()); null }
                7 -> byte().also { require(it == 0 || it == 1) }.toLong()
                8 -> string(MAX_STRING_BYTES)
                9 -> {
                    require(depth < MAX_ARRAY_DEPTH)
                    val element = u32().also { require(it in 0..12) }.toInt()
                    val count = u64().also { require(it <= MAX_ARRAY_ELEMENTS && it <= MAX_VALUES - values) }
                    var index = 0L
                    while (index++ < count) value(element, depth + 1)
                    null
                }
                10 -> u64()
                11 -> raw64()
                12 -> { require(Double.fromBits(raw64()).isFinite()); null }
                else -> invalid()
            }
        }

        private fun string(maximumBytes: Int): String {
            val size = u64().also { require(it <= maximumBytes) }.toInt()
            budget(size.toLong())
            val bytes = ByteArray(size) { byte().toByte() }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }

        private fun byte(): Int {
            budget(1)
            if (bufferPosition == bufferSize) {
                bufferSize = input.read(buffer)
                bufferPosition = 0
                require(bufferSize > 0)
            }
            position++
            return buffer[bufferPosition++].toInt() and 255
        }
        private fun u32(): Long {
            var value = 0L
            repeat(4) { value = value or (byte().toLong() shl (it * 8)) }
            return value
        }
        private fun raw64(): Long {
            var value = 0L
            repeat(8) { value = value or (byte().toLong() shl (it * 8)) }
            return value
        }
        private fun u64(): Long = raw64().also { require(it >= 0) }
        private fun budget(bytes: Long) {
            require(position <= MAX_HEADER_BYTES && bytes <= MAX_HEADER_BYTES - position && bytes <= fileSize - position)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("GGUF validation interrupted.")
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Unsupported GGUF structure.")
    }

    companion object {
        private const val MAX_TENSORS = 16384
        private const val MAX_METADATA = 4096
        private const val MAX_HEADER_BYTES = 64 * 1024 * 1024L
        private const val MAX_STRING_BYTES = 1024 * 1024
        private const val MAX_ARRAY_ELEMENTS = 1_000_000L
        private const val MAX_VALUES = 2_000_000L
        private const val MAX_ARRAY_DEPTH = 2
    }
}

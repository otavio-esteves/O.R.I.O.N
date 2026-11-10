package network.orion.data.repository.models

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrionGgufValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val validator = OrionGgufValidator()
    private class Writer {
        val output = ByteArrayOutputStream()
        fun u32(value: Long) { repeat(4) { output.write((value ushr (it * 8)).toInt() and 255) } }
        fun u64(value: Long) { repeat(8) { output.write((value ushr (it * 8)).toInt() and 255) } }
        fun string(value: String) = rawString(value.toByteArray())
        fun rawString(value: ByteArray) { u64(value.size.toLong()); output.write(value) }
        fun text(key: String, value: String) { string(key); u32(8); string(value) }
        fun number(key: String, value: Long, type: Int = 4) { string(key); u32(type.toLong()); if (type == 10) u64(value) else u32(value) }
    }
    private data class Tensor(val name: String = "weight", val shape: List<Long> = listOf(4), val type: Int = 0, val offset: Long = 0)
    private fun model(tensors: List<Tensor> = listOf(Tensor()), metadataExtra: Int = 0,
        architecture: String = "llama", context: Long = 2048, alignment: Long = 32,
        dataSize: Int = 16, version: Long = 3, extra: Writer.() -> Unit = {}): ByteArray {
        val w = Writer()
        w.u32(0x46554747); w.u32(version); w.u64(tensors.size.toLong()); w.u64(3L + metadataExtra)
        w.text("general.architecture", architecture)
        w.number("$architecture.context_length", context, 10)
        w.number("general.alignment", alignment)
        w.extra()
        for (tensor in tensors) {
            w.string(tensor.name); w.u32(tensor.shape.size.toLong()); tensor.shape.forEach(w::u64)
            w.u32(tensor.type.toLong()); w.u64(tensor.offset)
        }
        // A malformed alignment must not make the test builder loop or divide by zero.
        val align = alignment.takeIf { it in 1..4096 }?.toInt() ?: 32
        while (w.output.size() % align != 0) w.output.write(0)
        w.output.write(ByteArray(dataSize))
        return w.output.toByteArray()
    }
    private fun inspect(bytes: ByteArray): OrionGgufMetadata? = validator.inspect(temporary.newFile().also { it.writeBytes(bytes) })
    private fun assertInvalid(bytes: ByteArray) = assertNull(inspect(bytes))

    @Test fun floatHeaderYieldsMinimalImmutableMetadata() {
        val result = checkNotNull(inspect(model()))
        assertEquals("llama", result.architecture)
        assertEquals(2048, result.contextLimit)
        assertEquals(1, result.tensorCount)
        assertEquals(setOf(OrionGgufTensorType.F32), result.tensorTypes)
        try { (result.tensorTypes as MutableSet<OrionGgufTensorType>).clear(); fail("Mutable metadata") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun everySupportedLayoutChecksItsExactBlockLength() {
        for (type in OrionGgufTensorType.entries) {
            val extraCount = if (type.block > 1) 1 else 0
            val bytes = model(listOf(Tensor(type = type.code, shape = listOf(type.block.toLong()))),
                metadataExtra = extraCount, dataSize = type.bytes) {
                if (extraCount > 0) number("general.quantization_version", 2)
            }
            assertEquals(setOf(type), inspect(bytes)?.tensorTypes)
            assertInvalid(bytes.dropLast(1).toByteArray())
        }
    }

    @Test fun quantizationVersionAndRowBlockDivisibilityAreMandatory() {
        assertInvalid(model(listOf(Tensor(shape = listOf(32), type = 2)), dataSize = 18))
        assertInvalid(model(listOf(Tensor(shape = listOf(31, 32), type = 2)), metadataExtra = 1, dataSize = 600) {
            number("general.quantization_version", 2)
        })
        assertInvalid(model(listOf(Tensor(shape = listOf(32), type = 2)), metadataExtra = 1, dataSize = 18) {
            number("general.quantization_version", 3)
        })
    }

    @Test fun mixedQwenQ5ZeroLayoutChecksBlockWidthAndExactByteRange() {
        val valid = model(listOf(Tensor(type = 6, shape = listOf(32, 2))), metadataExtra = 1, dataSize = 44) {
            number("general.quantization_version", 2)
        }
        assertEquals(setOf(OrionGgufTensorType.Q5_0), inspect(valid)?.tensorTypes)
        assertInvalid(valid.dropLast(1).toByteArray())
        assertInvalid(model(listOf(Tensor(type = 6, shape = listOf(31, 2))), metadataExtra = 1, dataSize = 44) {
            number("general.quantization_version", 2)
        })
    }

    @Test fun duplicateKeysAndTensorNamesAreRejected() {
        assertInvalid(model(metadataExtra = 1) { text("general.architecture", "llama") })
        assertInvalid(model(listOf(Tensor(), Tensor(offset = 32)), dataSize = 48))
    }

    @Test fun invalidTypeVersionDimensionsAndArchitectureFailClosed() {
        assertInvalid(model(version = 2))
        assertInvalid(model(tensors = listOf(Tensor(type = 999))))
        assertInvalid(model(tensors = listOf(Tensor(shape = emptyList()))))
        assertInvalid(model(tensors = listOf(Tensor(shape = listOf(0)))))
        assertInvalid(model(tensors = listOf(Tensor(shape = listOf(1, 1, 1, 1, 1)))))
        assertInvalid(model(architecture = "Llama"))
        assertInvalid(model(context = 0))
        assertInvalid(model(context = Int.MAX_VALUE.toLong() + 1))
    }

    @Test fun unsignedDimensionsAndTensorSizeOverflowAreRejected() {
        assertInvalid(model(listOf(Tensor(shape = listOf(-1)))))
        assertInvalid(model(listOf(Tensor(shape = listOf(Long.MAX_VALUE)))))
        assertInvalid(model(listOf(Tensor(shape = listOf(4, Long.MAX_VALUE)))))
        assertInvalid(model(listOf(Tensor(offset = Long.MAX_VALUE - 31))))
    }

    @Test fun alignmentAndNonoverlappingInFileRangesAreMandatory() {
        assertInvalid(model(alignment = 0))
        assertInvalid(model(alignment = 7))
        assertInvalid(model(alignment = 8192))
        assertInvalid(model(listOf(Tensor(offset = 1))))
        assertInvalid(model(listOf(Tensor("a", listOf(16)), Tensor("b", offset = 32)), dataSize = 80))
        assertInvalid(model(listOf(Tensor(offset = 32)), dataSize = 32))
        assertNotNull(inspect(model(listOf(Tensor("a"), Tensor("b", offset = 32)), dataSize = 48)))
    }

    @Test fun malformedUtf8AndUnknownMetadataTypesAreRejected() {
        assertInvalid(model(metadataExtra = 1) {
            string("general.name"); u32(8); rawString(byteArrayOf(0xc3.toByte(), 0x28))
        })
        assertInvalid(model(metadataExtra = 1) { string("general.name"); u32(99) })
        assertInvalid(model(metadataExtra = 1) { string("UpperCase.key"); u32(4); u32(1) })
        assertInvalid(model(metadataExtra = 1) { string("general.bool"); u32(7); output.write(2) })
    }

    @Test fun countsAndUnboundedMetadataArraysStringsAreRejectedWithoutAllocatingThem() {
        val hugeHeader = Writer().apply {
            u32(0x46554747); u32(3); u64(Long.MAX_VALUE); u64(1)
        }.output.toByteArray()
        assertInvalid(hugeHeader)
        assertInvalid(model(metadataExtra = 1) { string("general.name"); u32(8); u64(Long.MAX_VALUE) })
        assertInvalid(model(metadataExtra = 1) { string("general.array"); u32(9); u32(8); u64(1_000_001) })
        assertInvalid(model(metadataExtra = 1) {
            string("general.array"); u32(9); u32(9); u64(1); u32(9); u64(1); u32(9); u64(0)
        })
    }

    @Test fun validTokenArrayIsSkippedAndShardIsRejected() {
        assertNotNull(inspect(model(metadataExtra = 1) {
            string("tokenizer.ggml.tokens"); u32(9); u32(8); u64(2); string("token1"); string("日本語")
        }))
        assertInvalid(model(metadataExtra = 1) { number("split.count", 2) })
    }

    @Test fun requiredMetadataMustHaveItsStandardType() {
        assertInvalid(model(metadataExtra = 1) { number("general.architecture", 2) })
        val missing = Writer().apply {
            u32(0x46554747); u32(3); u64(1); u64(1); text("general.architecture", "llama")
        }.output.toByteArray()
        assertInvalid(missing)
    }

    @Test fun allHeaderTruncationsAndMissingTensorDataFail() {
        val bytes = model()
        for (size in bytes.indices) assertInvalid(bytes.copyOf(size))
        assertInvalid(model(tensors = emptyList(), dataSize = 0))
    }

    @Test fun interruptedParsingPreservesCancellationInsteadOfInvalidResult() {
        val file = temporary.newFile().also { it.writeBytes(model()) }
        try {
            Thread.currentThread().interrupt()
            validator.inspect(file)
            fail("Interrupted parse returned normally")
        } catch (_: InterruptedException) { assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted() }
    }

    @Test fun storageReadFailureIsDistinctFromInvalidModelStructure() {
        val failure = IOException("synthetic read failure")
        val stream = object : InputStream() { override fun read(): Int = throw failure }
        try { validator.inspect(stream, 1024); fail("Storage failure converted to invalid structure") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertNull(validator.inspect(byteArrayOf(1).inputStream(), 1))
    }
}

package network.orion.data.modelregistry

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Test

class OrionGgufValidatorTest {
    private fun validate(bytes: ByteArray): OrionGgufMetadata {
        val file = Files.createTempFile("orion_gguf", ".bin")
        try {
            Files.write(file, bytes)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val metadata = OrionGgufValidator.validate(channel)
                assertEquals(0L, channel.position())
                return metadata
            }
        } finally { Files.deleteIfExists(file) }
    }
    private fun rejected(bytes: ByteArray, reason: OrionGgufFailure? = null) {
        val failure = assertThrows(OrionGgufValidationException::class.java) { validate(bytes) }
        if (reason != null) assertEquals(reason, failure.reason)
    }

    @Test fun `valid single file retains only bounded structural metadata`() {
        val bytes = GgufFixtures.valid(metadata = listOf("general.name" to { u32(8); string("private fixture name") },
            "tokenizer.ggml.tokens" to { u32(9); u32(8); u64(2); string("a"); string("b") }))
        val parsed = validate(bytes)
        assertEquals("llama", parsed.architecture); assertEquals(128, parsed.contextLimit)
        assertEquals(1, parsed.tensorCount); assertEquals("0", parsed.quantization)
        assertEquals(bytes.size.toLong(), parsed.sizeBytes)
        assertFalse(parsed.toString().contains("private fixture name"))
    }
    @Test fun `truncated tensor cannot become structurally valid`() {
        rejected(GgufFixtures.valid().dropLast(1).toByteArray(), OrionGgufFailure.TRUNCATED)
        rejected(byteArrayOf(1, 2, 3), OrionGgufFailure.TRUNCATED)
    }
    @Test fun `magic endian and version are explicit`() {
        val magic = GgufFixtures.valid(); magic[0] = 0; rejected(magic)
        rejected(GgufFixtures.valid(version = 2), OrionGgufFailure.UNSUPPORTED_VERSION)
        rejected(GgufFixtures.valid(version = 0x03000000), OrionGgufFailure.UNSUPPORTED_VERSION)
    }
    @Test fun `duplicate metadata and tensor names are rejected`() {
        rejected(GgufFixtures.valid(metadata = listOf("general.architecture" to { u32(8); string("llama") })))
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(), GgufFixtures.Tensor(offset = 32)), dataBytes = 48))
    }
    @Test fun `unsupported quantizations and invalid row block dimensions are rejected`() {
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(type = 99))), OrionGgufFailure.UNSUPPORTED_TYPE)
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(type = 2, dimensions = listOf(31))), dataBytes = 18))
        assertEquals("12", validate(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(type = 12,
            dimensions = listOf(256))), dataBytes = 144)).quantization)
    }
    @Test fun `tensor shape arithmetic and unaligned or overlapping ranges fail closed`() {
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(dimensions = listOf(Long.MAX_VALUE, 2)))))
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(offset = 1))))
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(), GgufFixtures.Tensor(name = "output.weight"))))
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(offset = Long.MAX_VALUE - 31))))
    }
    @Test fun `unbounded lengths counts and nested arrays fail before allocation`() {
        val bytes = GgufFixtures.Bytes().apply { u32(0x46554747); u32(3); u64(1); u64(1); u64(Long.MAX_VALUE) }.bytes()
        rejected(bytes, OrionGgufFailure.LIMIT_EXCEEDED)
        rejected(GgufFixtures.valid(metadata = listOf("tokenizer.ggml.tokens" to { u32(9); u32(9); u64(0) })),
            OrionGgufFailure.UNSUPPORTED_TYPE)
        rejected(GgufFixtures.valid(metadata = listOf("tokenizer.ggml.scores" to { u32(9); u32(6); u64(2_000_001) })),
            OrionGgufFailure.LIMIT_EXCEEDED)
    }
    @Test fun `invalid booleans utf8 alignment and split dependencies are rejected`() {
        rejected(GgufFixtures.valid(metadata = listOf("custom.flag" to { u32(7); byte(2) })))
        rejected(GgufFixtures.valid(metadata = listOf("custom.text" to { u32(8); u64(2); raw(byteArrayOf(0xc3.toByte(), 0x28)) })))
        rejected(GgufFixtures.valid(metadata = listOf("general.alignment" to { u32(4); u32(3) })))
        rejected(GgufFixtures.valid(metadata = listOf("split.count" to { u32(2); byte(2); byte(0) })))
    }
    @Test fun `tensor path like names and arbitrary trailing content are rejected`() {
        rejected(GgufFixtures.valid(tensors = listOf(GgufFixtures.Tensor(name = "../weights"))))
        rejected(GgufFixtures.valid(dataBytes = 65))
    }
}

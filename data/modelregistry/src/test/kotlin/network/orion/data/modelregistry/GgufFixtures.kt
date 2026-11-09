package network.orion.data.modelregistry

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object GgufFixtures {
    data class Tensor(val name: String = "token_embd.weight", val dimensions: List<Long> = listOf(4), val type: Int = 0, val offset: Long = 0)
    class Bytes {
        private val output = ByteArrayOutputStream()
        fun byte(value: Int) { output.write(value) }
        fun u32(value: Long) { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()) }
        fun u64(value: Long) { output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun raw(value: ByteArray) { output.write(value) }
        fun string(value: String) { val bytes = value.toByteArray(); u64(bytes.size.toLong()); raw(bytes) }
        fun bytes() = output.toByteArray()
    }
    fun valid(tensors: List<Tensor> = listOf(Tensor()), metadata: List<Pair<String, Bytes.() -> Unit>> = emptyList(),
        dataBytes: Int = 16, version: Long = 3): ByteArray {
        val output = Bytes()
        output.u32(0x46554747); output.u32(version); output.u64(tensors.size.toLong()); output.u64(2L + metadata.size)
        output.string("general.architecture"); output.u32(8); output.string("llama")
        output.string("llama.context_length"); output.u32(4); output.u32(128)
        for ((key, value) in metadata) { output.string(key); output.value() }
        for (tensor in tensors) {
            output.string(tensor.name); output.u32(tensor.dimensions.size.toLong())
            for (dimension in tensor.dimensions) output.u64(dimension)
            output.u32(tensor.type.toLong()); output.u64(tensor.offset)
        }
        while (output.bytes().size % 32 != 0) output.byte(0)
        output.raw(ByteArray(dataBytes))
        return output.bytes()
    }
}

package network.orion.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Collections
import java.util.UUID

class OrionConversationEntry(val id: UUID, val origin: OrionMemoryOrigin, val text: String) {
    init {
        require(origin in supportedOrigins)
        require(text.length in 1..MAX_ENTRY_LENGTH && text.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(text))
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_LENGTH)
    }

    override fun toString() = "OrionConversationEntry(<redacted>)"

    private companion object {
        const val MAX_ENTRY_LENGTH = 2048
        val supportedOrigins = setOf(OrionMemoryOrigin.USER_DECLARED, OrionMemoryOrigin.SYSTEM_DERIVED,
            OrionMemoryOrigin.LLM_INFERRED)
    }
}

/** Immutable snapshot of completed chat turns. The encoding is canonical and binds all visible text. */
class OrionConversationTranscript(entries: List<OrionConversationEntry>) {
    val entries: List<OrionConversationEntry> =
        Collections.unmodifiableList(ArrayList(entries.also { require(it.size in 1..20) }))

    init {
        require(this.entries.map { it.id }.distinct().size == this.entries.size)
        require(this.entries.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= MAX_ENCODED_BYTES)
    }

    fun encode(): String {
        val bytes = ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(1)
                out.writeInt(entries.size)
                for (entry in entries) {
                    out.writeLong(entry.id.mostSignificantBits)
                    out.writeLong(entry.id.leastSignificantBits)
                    out.writeUTF(entry.origin.name)
                    out.writeUTF(entry.text)
                }
            }
        }.toByteArray()
        return try {
            require(bytes.size <= MAX_ENCODED_BYTES)
            Base64.getEncoder().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun toString() = "OrionConversationTranscript(<redacted>)"

    companion object {
        private const val MAX_ENCODED_BYTES = 5_000

        fun decode(encoded: String): OrionConversationTranscript {
            require(encoded.length in 1..6_668)
            val clear = Base64.getDecoder().decode(encoded)
            try {
                require(clear.size <= MAX_ENCODED_BYTES)
                return DataInputStream(ByteArrayInputStream(clear)).use { input ->
                    require(input.readInt() == 1)
                    val count = input.readInt()
                    require(count in 1..20)
                    val entries = List(count) {
                        OrionConversationEntry(UUID(input.readLong(), input.readLong()),
                            OrionMemoryOrigin.valueOf(input.readUTF()), input.readUTF())
                    }
                    require(input.available() == 0)
                    OrionConversationTranscript(entries).also { require(it.encode() == encoded) }
                }
            } catch (_: java.io.IOException) {
                throw IllegalArgumentException("Invalid conversation transcript.")
            } finally {
                clear.fill(0)
            }
        }
    }
}

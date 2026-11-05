package network.orion.data.memory

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import network.orion.core.commands.memories.*
import network.orion.core.queries.memories.*
import network.orion.core.security.*
import network.orion.data.database.OrionDatabase
import org.json.JSONObject

/** MAIN-only read adapter. Legacy opaque/corrupt content stays explicitly unreadable. */
class OrionMemoryQuery(private val database: OrionDatabase, private val encryption: OrionFieldEncryption) : MemoryQuery {
    override fun get(request: GetMemories): MemoryPage {
        val rows = database.memories().page(request.after?.toString(), request.limit + 1)
        val selected = rows.take(request.limit).map(::select)
        return MemoryPage(selected, if (rows.size > request.limit) selected.last().memoryId else null)
    }

    fun find(id: UUID): MemorySummary? = database.memories().find(id.toString())?.takeIf { it.origin == network.orion.data.database.MemoryOrigin.USER_DECLARED && it.userConfirmed }?.let(::select)

    private fun select(row: network.orion.data.database.MemoryEntity): MemorySummary {
            val id = UUID.fromString(row.memoryId)
            var state = MemoryContentState.UNREADABLE
            var content: String? = null
            val parsed = FieldEnvelopeCodec.decode(row.contentEnvelope)
            if (parsed is FieldEnvelopeDecodingResult.Decoded) {
                when (val result = encryption.decrypt(parsed.envelope, FieldEncryptionContext("memory/$id", "content", 1))) {
                    is FieldDecryptionResult.Plaintext -> {
                        val clear = result.copyBytes()
                        try { content = decodeContent(id, clear); if (content != null) state = MemoryContentState.READABLE }
                        finally { clear.fill(0) }
                    }
                    is FieldDecryptionResult.Failure -> if (result.reason in setOf(FieldCryptoFailure.KEY_UNAVAILABLE,
                        FieldCryptoFailure.KEY_INVALIDATED, FieldCryptoFailure.CRYPTO_UNAVAILABLE)) state = MemoryContentState.KEY_UNAVAILABLE
                }
            }
            return MemorySummary(id, content, MemoryState.valueOf(row.status.name), row.version, state)
    }

    private fun decodeContent(id: UUID, bytes: ByteArray): String? {
        if (bytes.size !in 1..8192) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            if (json.length() != 4 || json.opt("origin") != "USER_DECLARED" || json.opt("sensitivity") != "PERSONAL" || json.opt("memoryId") != id.toString() || json.opt("content") !is String) return null
            val command = CreateMemory(id, json.getString("content"), id, id)
            val ready = OrionCreateMemoryPreparer.prepare(command) as? CreateMemoryPreparation.Ready ?: return null
            ready.memory.content.takeIf { ready.memory.canonicalNormalizedArguments == text }
        } catch (_: Exception) { null }
    }
}

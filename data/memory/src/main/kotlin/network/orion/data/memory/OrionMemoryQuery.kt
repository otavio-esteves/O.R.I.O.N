package network.orion.data.memory

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.commands.memories.*
import network.orion.core.model.OrionMemoryProvenance
import network.orion.core.queries.memories.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.MemoryEntity
import org.json.JSONObject

/** MAIN-only read adapter. Legacy opaque/corrupt content stays explicitly unreadable. */
class OrionMemoryQuery(private val database: OrionDatabase, private val encryption: OrionFieldEncryption,
    private val clock: WallClock) : MemoryQuery {
    override fun get(request: GetMemories): MemoryPage {
        val rows = database.memories().page(request.after?.toString(), request.limit + 1)
        val now = clock.wallNow()
        val selected = rows.take(request.limit).map { select(it, now) }
        return MemoryPage(selected, if (rows.size > request.limit) selected.last().memoryId else null)
    }

    fun search(request: SearchMemories): MemorySearchPage = database.runInTransaction(Callable {
        val index = database.memoryIndex()
        if (index.state()?.ready != true) return@Callable MemorySearchPage(MemorySearchState.BUILDING)
        val now = clock.wallNow()
        val rankedAt = request.after?.rankedAt ?: now
        val candidates = index.candidates(request.ftsExpression, now.toEpochMilli(), MAX_CANDIDATES + 1)
        val hits = ArrayList<MemorySearchHit>()
        for (row in candidates.take(MAX_CANDIDATES)) {
            val memory = select(row, now)
            if (memory.contentState == MemoryContentState.KEY_UNAVAILABLE)
                return@Callable MemorySearchPage(MemorySearchState.KEY_UNAVAILABLE)
            val content = memory.content ?: continue
            val score = OrionMemoryRanking.score(request, content, Instant.ofEpochMilli(row.updatedAt), rankedAt,
                memory.lifecycle.importance, memory.provenance.confidence)
            val after = request.after
            if (after == null || score < after.score ||
                score == after.score && row.memoryId > after.memoryId.toString()) {
                hits += MemorySearchHit(memory, score)
            }
        }
        val ordered = hits.sortedWith(compareByDescending<MemorySearchHit> { it.score }.thenBy { it.memory.memoryId.toString() })
        val page = ordered.take(request.limit)
        val next = if (ordered.size > request.limit) page.last().let {
            MemorySearchCursor(request.ftsExpression, rankedAt, it.score, it.memory.memoryId)
        } else null
        MemorySearchPage(MemorySearchState.READY, page, next, candidates.size > MAX_CANDIDATES)
    })

    fun find(id: UUID): MemorySummary? = database.memories().find(id.toString())?.takeIf { it.origin == network.orion.data.database.MemoryOrigin.USER_DECLARED && it.userConfirmed && it.sensitivity == network.orion.core.model.Sensitivity.PERSONAL }?.let { select(it, clock.wallNow()) }

    private fun select(row: MemoryEntity, at: Instant): MemorySummary {
        val id = UUID.fromString(row.memoryId)
        val (content, state) = read(row.contentEnvelope, FieldEncryptionContext("memory/$id", "content", 1)) {
            decodeContent(id, it)
        }
        val details = row.detailsEnvelope?.let { read(it, OrionMemoryDetailsCodec.context(id), OrionMemoryDetailsCodec::decode) }
        val provenance = OrionMemoryProvenance(row.origin, row.confidence, row.userConfirmed, row.sensitivity,
            row.originEventId?.let(UUID::fromString))
        val lifecycle = row.lifecycle()
        return MemorySummary(id, content, lifecycle.stateAt(row.status, at), row.version, state, provenance,
            lifecycle, details?.first, details?.second)
    }

    private fun <T : Any> read(envelope: ByteArray, context: FieldEncryptionContext,
        decode: (ByteArray) -> T?): Pair<T?, MemoryContentState> {
        val parsed = FieldEnvelopeCodec.decode(envelope) as? FieldEnvelopeDecodingResult.Decoded
            ?: return null to MemoryContentState.UNREADABLE
        return when (val result = encryption.decrypt(parsed.envelope, context)) {
            is FieldDecryptionResult.Plaintext -> {
                val clear = result.copyBytes()
                val value = try { decode(clear) } finally { clear.fill(0) }
                value to if (value == null) MemoryContentState.UNREADABLE else MemoryContentState.READABLE
            }
            is FieldDecryptionResult.Failure -> null to when (result.reason) {
                FieldCryptoFailure.KEY_UNAVAILABLE, FieldCryptoFailure.KEY_INVALIDATED,
                FieldCryptoFailure.CRYPTO_UNAVAILABLE -> MemoryContentState.KEY_UNAVAILABLE
                else -> MemoryContentState.UNREADABLE
            }
        }
    }

    internal fun readIndexContent(row: MemoryEntity): Pair<String?, MemoryContentState> {
        val id = UUID.fromString(row.memoryId)
        return read(row.contentEnvelope, FieldEncryptionContext("memory/$id", "content", 1)) { decodeContent(id, it) }
    }

    internal fun decodeContent(id: UUID, bytes: ByteArray): String? {
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
    private companion object {
        const val MAX_CANDIDATES = 256
    }

}

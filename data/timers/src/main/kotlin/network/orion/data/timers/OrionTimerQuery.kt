package network.orion.data.timers

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import network.orion.core.commands.timers.*
import network.orion.core.queries.timers.*
import network.orion.core.security.*
import network.orion.data.database.OrionDatabase
import org.json.JSONObject

/** MAIN-only read adapter. Legacy opaque/corrupt content stays explicitly unreadable. */
class OrionTimerQuery(private val database: OrionDatabase, private val encryption: OrionFieldEncryption, private val clock: network.orion.core.time.OrionClock) : TimerQuery {
    override fun get(request: GetTimers): TimerPage {
        val rows = database.timers().page(request.after?.toString(), request.limit + 1)
        val selected = rows.take(request.limit).map(::select)
        return TimerPage(selected, if (rows.size > request.limit) selected.last().timerId else null)
    }

    fun get(request: GetTimerStatus): TimerSummary? = find(request.timerId)

    fun find(id: UUID): TimerSummary? = database.timers().find(id.toString())?.let(::select)

    private fun select(row: network.orion.data.database.TimerEntity): TimerSummary {
            val id = UUID.fromString(row.timerId)
            var state = TimerContentState.UNREADABLE
            var title: String? = null
            val parsed = FieldEnvelopeCodec.decode(row.contentEnvelope)
            if (parsed is FieldEnvelopeDecodingResult.Decoded) {
                when (val result = encryption.decrypt(parsed.envelope, FieldEncryptionContext("timer/$id", "content", 1))) {
                    is FieldDecryptionResult.Plaintext -> {
                        val clear = result.copyBytes()
                        try { title = decodeContent(id, row.durationMillis, clear); if (title != null) state = TimerContentState.READABLE }
                        finally { clear.fill(0) }
                    }
                    is FieldDecryptionResult.Failure -> if (result.reason in setOf(FieldCryptoFailure.KEY_UNAVAILABLE,
                        FieldCryptoFailure.KEY_INVALIDATED, FieldCryptoFailure.CRYPTO_UNAVAILABLE)) state = TimerContentState.KEY_UNAVAILABLE
                }
            }
            val now = clock.monotonicNow()
            val time = OrionTimerRemaining.read(row.durationMillis, row.deadlineWallAt, row.bootSessionId,
                row.startedElapsedRealtime, now.bootSessionId?.bootCount, now.elapsedRealtimeMillis, clock.wallNow().toEpochMilli())
            val status = if (row.status == network.orion.data.database.TimerStatus.CANCELLED) TimerState.CANCELLED
                else if (time.remainingMillis == 0L) TimerState.ELAPSED else TimerState.ACTIVE
            return TimerSummary(id, title, status, row.version, state, row.durationMillis,
                if (status == TimerState.CANCELLED) 0L else time.remainingMillis, time.basis)
    }

    private fun decodeContent(id: UUID, durationMillis: Long, bytes: ByteArray): String? {
        if (bytes.size !in 1..8192) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            if (json.optLong("durationMillis", -1) != durationMillis || json.length() != 3 || json.opt("timerId") != id.toString() || json.opt("title") !is String) return null
            val command = CreateTimer(id, json.getString("title"), json.getLong("durationMillis"), id, id)
            val ready = OrionCreateTimerPreparer.prepare(command) as? CreateTimerPreparation.Ready ?: return null
            ready.timer.title.takeIf { ready.timer.canonicalNormalizedArguments == text }
        } catch (_: Exception) { null }
    }
}

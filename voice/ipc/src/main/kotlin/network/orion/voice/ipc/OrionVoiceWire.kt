package network.orion.voice.ipc

import android.os.Bundle
import android.os.Parcel
import java.time.Instant
import java.util.UUID
import network.orion.core.ipc.*

enum class OrionVoiceAdmission {
    ACCEPTED, QUEUED_DURING_RECOVERY, CORE_UNAVAILABLE, REJECTED_INVALID, DUPLICATE_REQUEST, PROTOCOL_MISMATCH,
}

/** Bounded scalar-only wire shared by the private MAIN endpoint and voice processes. */
object OrionVoiceWire {
    private val requestKeys = setOf("schema", "id", "session", "text", "final", "locale", "capturedAt", "source", "priority", "correlation")
    private val responseKeys = setOf("schema", "id", "sequence", "kind", "text", "confirm", "terminal", "error")

    fun request(value: VoiceUtteranceRequest) = Bundle().apply {
        putInt("schema", value.schemaVersion.value)
        putString("id", value.requestId.toString())
        putString("session", value.sessionId.toString())
        putString("text", value.text)
        putBoolean("final", value.isFinalTranscript)
        putString("locale", value.locale)
        putLong("capturedAt", value.capturedAt.toEpochMilli())
        putString("source", value.source.name)
        putInt("priority", value.priority)
        putString("correlation", value.correlationId.toString())
    }

    fun readRequest(bundle: Bundle): VoiceUtteranceRequest? = decode(bundle, requestKeys) {
        VoiceUtteranceRequest(IpcSchemaVersion(int("schema")), id("id"), id("session"), string("text"), bool("final"),
            string("locale"), Instant.ofEpochMilli(long("capturedAt")), OrionVoiceSource.valueOf(string("source")),
            int("priority"), id("correlation"))
    }

    fun response(value: VoiceCoreResponse) = Bundle().apply {
        putInt("schema", value.schemaVersion.value)
        putString("id", value.requestId.toString())
        putLong("sequence", value.sequence)
        putString("kind", value.kind.name)
        putString("text", value.textDelta)
        putBoolean("confirm", value.requiresConfirmation)
        putString("terminal", value.terminalState?.name)
        putString("error", value.errorCode?.name)
    }

    fun readResponse(bundle: Bundle): VoiceCoreResponse? = decode(bundle, responseKeys) {
        VoiceCoreResponse(IpcSchemaVersion(int("schema")), id("id"), long("sequence"),
            OrionVoiceResponseKind.valueOf(string("kind")), optional("text"), bool("confirm"),
            optional("terminal")?.let(OrionVoiceTerminalState::valueOf), optional("error")?.let(OrionVoiceErrorCode::valueOf))
    }

    fun requestId(text: String): UUID? = try {
        UUID.fromString(text).takeIf { it.toString() == text }
    } catch (_: IllegalArgumentException) { null }

    private fun <T> decode(bundle: Bundle, keys: Set<String>, read: Scalars.() -> T): T? = try {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            require(InlineIpcPayloadPolicy.transportFor(parcel.dataSize()) == IpcPayloadTransport.INLINE && !parcel.hasFileDescriptors())
        } finally { parcel.recycle() }
        require(bundle.keySet() == keys)
        Scalars(bundle).read()
    } catch (_: RuntimeException) { null }

    @Suppress("DEPRECATION")
    private class Scalars(private val bundle: Bundle) {
        fun string(key: String) = bundle.get(key) as String
        fun int(key: String) = bundle.get(key) as Int
        fun long(key: String) = bundle.get(key) as Long
        fun bool(key: String) = bundle.get(key) as Boolean
        fun optional(key: String) = bundle.get(key) as String?
        fun id(key: String) = requireNotNull(requestId(string(key)))
    }
}

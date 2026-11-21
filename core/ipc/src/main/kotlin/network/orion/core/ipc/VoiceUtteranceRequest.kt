package network.orion.core.ipc

import java.time.Instant
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.UUID

enum class OrionVoiceSource { PUSH_TO_TALK }

/** Text is untrusted input and must stay out of diagnostics. */
class VoiceUtteranceRequest(
    val schemaVersion: IpcSchemaVersion,
    val requestId: UUID,
    val sessionId: UUID,
    val text: String,
    val isFinalTranscript: Boolean,
    val locale: String,
    val capturedAt: Instant,
    val source: OrionVoiceSource,
    val priority: Int,
    val correlationId: UUID,
) {
    init {
        require(schemaVersion.value == 1)
        requireVoiceText(text)
        require(locale.length in 2..64)
        val language = try {
            Locale.Builder().setLanguageTag(locale).build().language
        } catch (_: IllformedLocaleException) {
            throw IllegalArgumentException("Invalid voice locale.")
        }
        require(language.isNotBlank())
        require(priority in 0..100)
    }

    val eligibleForCommandAdmission: Boolean get() = isFinalTranscript

    override fun toString() = "VoiceUtteranceRequest(<redacted>)"
}

enum class OrionVoiceResponseKind { STATUS, TEXT_DELTA, TERMINAL }
enum class OrionVoiceTerminalState { SUCCEEDED, CANCELLED, TIMED_OUT, FAILED }
enum class OrionVoiceErrorCode { CORE_UNAVAILABLE, INVALID_REQUEST, EXECUTION_FAILED }

class VoiceCoreResponse(
    val schemaVersion: IpcSchemaVersion,
    val requestId: UUID,
    val sequence: Long,
    val kind: OrionVoiceResponseKind,
    val textDelta: String?,
    val requiresConfirmation: Boolean,
    val terminalState: OrionVoiceTerminalState?,
    val errorCode: OrionVoiceErrorCode?,
) {
    init {
        require(schemaVersion.value == 1)
        require(sequence >= 0)
        require((kind == OrionVoiceResponseKind.TERMINAL) == (terminalState != null))
        require((kind == OrionVoiceResponseKind.TEXT_DELTA) == (textDelta != null))
        require(errorCode == null || terminalState == OrionVoiceTerminalState.FAILED)
        textDelta?.let(::requireVoiceText)
    }

    override fun toString() = "VoiceCoreResponse(<redacted>)"
}

private fun requireVoiceText(text: String) {
    // Leaves room for metadata and UTF-16 marshalling under the common Binder limit.
    val maxUtf8Bytes = 16 * 1024
    require(text.length in 1..maxUtf8Bytes && text.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(text))
    require(text.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes)
}

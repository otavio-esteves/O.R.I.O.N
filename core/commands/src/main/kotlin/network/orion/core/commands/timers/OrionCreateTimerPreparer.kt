package network.orion.core.commands.timers

import java.text.Normalizer

/**
 * Pure pre-policy validation: no dispatcher, clock, storage, policy or action engine.
 *
 * Raw and normalized titles each have a 1024-byte UTF-8 admission limit. The raw
 * UTF-16 length guard is O(1); a bounded scalar/byte scan then rejects oversized or
 * malformed input before trim, normalization, UTF-8 encoding or JSON allocation.
 * Oversized raw input is rejected even when trimming/NFC could make it smaller.
 *
 * A title is one line: C0/C1 controls and Unicode line/paragraph separators are
 * invalid even at its edges. Kotlin trim() removes other edge whitespace; NFC
 * normalizes canonically equivalent text without compatibility/case folding or
 * interior whitespace rewriting. NFC expansion is checked against the same limit.
 */
object OrionCreateTimerPreparer {
    const val MAX_TITLE_UTF8_BYTES = 1024
    const val MAX_DURATION_MILLIS = 365L * 24 * 60 * 60 * 1000

    fun prepare(command: CreateTimer): CreateTimerPreparation {
        if (command.durationMillis !in 1..MAX_DURATION_MILLIS) return rejected(CreateTimerRejection.INVALID_DURATION)
        val raw = command.title
        if (raw.length > MAX_TITLE_UTF8_BYTES) return rejected(CreateTimerRejection.TITLE_TOO_LARGE)
        titleFailure(raw)?.let { return rejected(it) }

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return rejected(CreateTimerRejection.BLANK_TITLE)
        val title = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        titleFailure(title)?.let { return rejected(it) }

        val arguments = buildString {
            append("{\"timerId\":\"")
            append(command.timerId)
            append("\",\"title\":\"")
            for (character in title) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(character)
                }
            }
            append("\",\"durationMillis\":")
            append(command.durationMillis)
            append("}")
        }
        return CreateTimerPreparation.Ready(ValidatedCreateTimer(command.timerId, title, command.durationMillis,
            command.idempotencyKey, command.correlationId, arguments))
    }

    private fun rejected(reason: CreateTimerRejection) = CreateTimerPreparation.Rejected(reason)

    /** No intermediate byte arrays or strings; at most the bounded input is traversed. */
    private fun titleFailure(title: String): CreateTimerRejection? {
        var bytes = 0
        var index = 0
        while (index < title.length) {
            val character = title[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == title.length || !title[index + 1].isLowSurrogate())
                        return CreateTimerRejection.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return CreateTimerRejection.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return CreateTimerRejection.INVALID_TITLE_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_TITLE_UTF8_BYTES) return CreateTimerRejection.TITLE_TOO_LARGE
        }
        return null
    }
}

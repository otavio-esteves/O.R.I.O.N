package network.orion.core.commands.tasks

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
object OrionCreateTaskPreparer {
    const val MAX_TITLE_UTF8_BYTES = 1024

    fun prepare(command: CreateTask): CreateTaskPreparation {
        val raw = command.title
        if (raw.length > MAX_TITLE_UTF8_BYTES) return rejected(CreateTaskRejection.TITLE_TOO_LARGE)
        titleFailure(raw)?.let { return rejected(it) }

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return rejected(CreateTaskRejection.BLANK_TITLE)
        val title = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        titleFailure(title)?.let { return rejected(it) }

        val arguments = buildString {
            append("{\"taskId\":\"")
            append(command.taskId)
            append("\",\"title\":\"")
            for (character in title) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(character)
                }
            }
            append("\"}")
        }
        return CreateTaskPreparation.Ready(ValidatedCreateTask(command.taskId, title,
            command.idempotencyKey, command.correlationId, arguments))
    }

    private fun rejected(reason: CreateTaskRejection) = CreateTaskPreparation.Rejected(reason)

    /** No intermediate byte arrays or strings; at most the bounded input is traversed. */
    private fun titleFailure(title: String): CreateTaskRejection? {
        var bytes = 0
        var index = 0
        while (index < title.length) {
            val character = title[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == title.length || !title[index + 1].isLowSurrogate())
                        return CreateTaskRejection.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return CreateTaskRejection.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return CreateTaskRejection.INVALID_TITLE_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_TITLE_UTF8_BYTES) return CreateTaskRejection.TITLE_TOO_LARGE
        }
        return null
    }
}

package network.orion.core.commands.memories

import java.text.Normalizer

/**
 * Pure pre-policy validation: no dispatcher, clock, storage, policy or action engine.
 *
 * Raw and normalized contents each have a 1024-byte UTF-8 admission limit. The raw
 * UTF-16 length guard is O(1); a bounded scalar/byte scan then rejects oversized or
 * malformed input before trim, normalization, UTF-8 encoding or JSON allocation.
 * Oversized raw input is rejected even when trimming/NFC could make it smaller.
 *
 * A content is one line: C0/C1 controls and Unicode line/paragraph separators are
 * invalid even at its edges. Kotlin trim() removes other edge whitespace; NFC
 * normalizes canonically equivalent text without compatibility/case folding or
 * interior whitespace rewriting. NFC expansion is checked against the same limit.
 */
object OrionCreateMemoryPreparer {
    const val MAX_CONTENT_UTF8_BYTES = 1024

    fun prepare(command: CreateMemory): CreateMemoryPreparation {
        val raw = command.content
        if (raw.length > MAX_CONTENT_UTF8_BYTES) return rejected(CreateMemoryRejection.CONTENT_TOO_LARGE)
        contentFailure(raw)?.let { return rejected(it) }

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return rejected(CreateMemoryRejection.BLANK_CONTENT)
        val content = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        contentFailure(content)?.let { return rejected(it) }

        val arguments = buildString {
            append("{\"memoryId\":\"")
            append(command.memoryId)
            append("\",\"content\":\"")
            for (character in content) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(character)
                }
            }
            append("\",\"origin\":\"USER_DECLARED\",\"sensitivity\":\"PERSONAL\"}")
        }
        return CreateMemoryPreparation.Ready(ValidatedCreateMemory(command.memoryId, content,
            command.idempotencyKey, command.correlationId, arguments))
    }

    private fun rejected(reason: CreateMemoryRejection) = CreateMemoryPreparation.Rejected(reason)

    /** No intermediate byte arrays or strings; at most the bounded input is traversed. */
    private fun contentFailure(content: String): CreateMemoryRejection? {
        var bytes = 0
        var index = 0
        while (index < content.length) {
            val character = content[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == content.length || !content[index + 1].isLowSurrogate())
                        return CreateMemoryRejection.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return CreateMemoryRejection.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return CreateMemoryRejection.INVALID_CONTENT_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_CONTENT_UTF8_BYTES) return CreateMemoryRejection.CONTENT_TOO_LARGE
        }
        return null
    }
}

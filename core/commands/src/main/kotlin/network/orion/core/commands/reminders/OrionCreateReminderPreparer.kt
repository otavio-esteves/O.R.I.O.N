package network.orion.core.commands.reminders

import java.text.Normalizer

/**
 * Pure bounded schema validation. No clock, storage, policy or scheduling access.
 * Title admission precedes normalization/allocation; NFC expansion is rechecked.
 * Time must survive signed epoch-millisecond persistence exactly. Temporal policy
 * (including whether a past deadline is allowed) needs a later injected wall clock.
 */
object OrionCreateReminderPreparer {
    const val MAX_TITLE_UTF8_BYTES = 1024

    fun prepare(command: CreateReminder): CreateReminderPreparation {
        val raw = command.title
        if (raw.length > MAX_TITLE_UTF8_BYTES) return rejected(CreateReminderRejection.TITLE_TOO_LARGE)
        titleFailure(raw)?.let { return rejected(it) }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return rejected(CreateReminderRejection.BLANK_TITLE)
        val title = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        titleFailure(title)?.let { return rejected(it) }

        // Reject sub-millisecond instants instead of silently changing confirmed material.
        if (command.scheduledAt.nano % 1_000_000 != 0)
            return rejected(CreateReminderRejection.UNSUPPORTED_TIME_PRECISION)
        val epochMillis = try { command.scheduledAt.toEpochMilli() }
            catch (_: ArithmeticException) { return rejected(CreateReminderRejection.TIME_OUT_OF_RANGE) }

        val arguments = buildString {
            append("{\"reminderId\":\"")
            append(command.reminderId)
            append("\",\"title\":\"")
            appendJsonContent(title)
            append("\",\"scheduledAtEpochMillis\":")
            append(epochMillis)
            append(",\"zoneId\":\"")
            appendJsonContent(command.zoneId.id)
            append("\"}")
        }
        return CreateReminderPreparation.Ready(ValidatedCreateReminder(command.reminderId, title,
            command.scheduledAt, command.zoneId, command.idempotencyKey, command.correlationId,
            epochMillis, arguments))
    }

    private fun rejected(reason: CreateReminderRejection) = CreateReminderPreparation.Rejected(reason)

    private fun StringBuilder.appendJsonContent(value: String) {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
    }

    /** Bounded scan without allocating intermediate strings or replacement UTF-8. */
    private fun titleFailure(title: String): CreateReminderRejection? {
        var bytes = 0
        var index = 0
        while (index < title.length) {
            val character = title[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == title.length || !title[index + 1].isLowSurrogate())
                        return CreateReminderRejection.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return CreateReminderRejection.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return CreateReminderRejection.INVALID_TITLE_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_TITLE_UTF8_BYTES) return CreateReminderRejection.TITLE_TOO_LARGE
        }
        return null
    }
}

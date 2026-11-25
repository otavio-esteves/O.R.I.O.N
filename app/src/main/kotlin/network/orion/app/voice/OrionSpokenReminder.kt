package network.orion.app.voice

import java.util.Locale
import network.orion.core.commands.reminders.OrionCreateReminderPreparer
import network.orion.core.orchestrator.chat.OrionChatInputPolicy

/** Interprets a direct reminder request for review in the active voice UI. */
@ConsistentCopyVisibility
internal data class OrionSpokenReminder private constructor(val title: String, val afterMinutes: Long) {
    override fun toString() = "OrionSpokenReminder(<redacted>)"

    companion object {
        private val request = Regex(
            "(?:me lembr[ea] de|lembre-me de|cri(?:e|ar) (?:um )?lembrete (?:para|de)) +" +
                "(.+?) +em +([0-9]{1,6}|[\\p{L} ]{1,18}?) +(minutos?|horas?)[.!?]?",
            RegexOption.IGNORE_CASE,
        )
        private val numbers = mapOf(
            "um" to 1L, "uma" to 1L, "dois" to 2L, "duas" to 2L, "três" to 3L, "tres" to 3L,
            "quatro" to 4L, "cinco" to 5L, "seis" to 6L, "sete" to 7L, "oito" to 8L,
            "nove" to 9L, "dez" to 10L, "quinze" to 15L, "vinte" to 20L, "trinta" to 30L,
        )

        fun parse(text: String): OrionSpokenReminder? {
            if (OrionChatInputPolicy.validate(text) != null) return null
            val match = request.matchEntire(text.trim()) ?: return null
            val (rawTitle, value, unit) = match.destructured
            val title = rawTitle.trim()
            if (title.isEmpty() || title.toByteArray(Charsets.UTF_8).size > OrionCreateReminderPreparer.MAX_TITLE_UTF8_BYTES)
                return null
            val amount = value.toLongOrNull() ?: numbers[value.trim().lowercase(Locale.ROOT)] ?: return null
            val minutes = if (unit.startsWith("hora", ignoreCase = true)) amount * 60L else amount
            return if (minutes in 1..525_600) OrionSpokenReminder(title, minutes) else null
        }
    }
}

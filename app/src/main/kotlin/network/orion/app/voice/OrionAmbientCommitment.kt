package network.orion.app.voice

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Closed grammar for observations from a separately verified speaker. */
internal data class OrionAmbientCommitment(
    val kind: Kind,
    val startsAt: Instant,
    val zone: ZoneId,
) {
    enum class Kind(val label: String, val spokenArticle: String) {
        MEETING("Reunião", "da reunião"),
        APPOINTMENT("Compromisso", "do compromisso"),
        CONSULTATION("Consulta", "da consulta"),
    }

    val noticeAt: Instant get() = startsAt.minus(Duration.ofHours(1))
    private val localTime: LocalTime get() = startsAt.atZone(zone).toLocalTime()
    private val displayTime: String get() = if (localTime.minute == 0) "${localTime.hour}h"
        else "${localTime.hour}h${localTime.minute.toString().padStart(2, '0')}"
    val spokenNotice: String get() = "Senhor, não se esqueça ${kind.spokenArticle} às $displayTime."
    val inferredTitle: String get() = "${kind.label} às $displayTime · Compromisso inferido"

    override fun toString() = "OrionAmbientCommitment(<redacted>)"

    companion object {
        private val statement = Regex(
            "(?:(?:putz|nossa|caramba)[,!] +)?(?:eu +)?tenho +(?:(?:uma|um) +)?" +
                "(reuniao|compromisso|consulta) +(hoje|amanha) +as +([^.!?]+)[.!]?",
        )
        private val digitalTime = Regex("([0-9]{1,2})(?:h([0-9]{2})?|:([0-9]{2})| horas?)")
        private val spokenHours = listOf(
            "zero", "uma", "duas", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove",
            "dez", "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete",
            "dezoito", "dezenove", "vinte", "vinte e uma", "vinte e duas", "vinte e tres",
        )
        private val marks = Regex("\\p{M}+")
        private val spaces = Regex(" +")

        fun parse(text: String, capturedAt: Instant, zone: ZoneId): OrionAmbientCommitment? {
            if (text.length !in 1..240 || text.any(Char::isISOControl)) return null
            val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(marks, "").trim().replace(spaces, " ")
            val match = statement.matchEntire(normalized) ?: return null
            val (noun, day, time) = match.destructured
            val kind = when (noun) {
                "reuniao" -> Kind.MEETING
                "compromisso" -> Kind.APPOINTMENT
                "consulta" -> Kind.CONSULTATION
                else -> return null
            }
            val hour = parseTime(time.trim()) ?: return null
            val capturedDay = capturedAt.atZone(zone).toLocalDate()
            val date = if (day == "amanha") capturedDay.plusDays(1) else capturedDay
            val local = date.atTime(hour)
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
            return OrionAmbientCommitment(kind, local.toInstant(offset), zone).takeIf { it.noticeAt > capturedAt }
        }

        private fun parseTime(text: String): LocalTime? {
            val digital = digitalTime.matchEntire(text)
            if (digital != null) {
                val (hour, afterH, afterColon) = digital.destructured
                val minute = (afterH + afterColon).ifEmpty { "0" }.toInt()
                return hour.toInt().takeIf { it in 0..23 && minute in 0..59 }?.let { LocalTime.of(it, minute) }
            }
            if (!text.endsWith(" horas")) return null
            val hour = spokenHours.indexOf(text.removeSuffix(" horas"))
            return hour.takeIf { it >= 0 }?.let { LocalTime.of(it, 0) }
        }
    }
}

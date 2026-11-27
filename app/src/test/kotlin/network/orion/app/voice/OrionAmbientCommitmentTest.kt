package network.orion.app.voice

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OrionAmbientCommitmentTest {
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")
    private val tonight = Instant.parse("2026-11-27T01:00:00Z")

    @Test fun spontaneousMeetingSchedulesSpokenNoticeAnHourEarlier() {
        val meeting = checkNotNull(OrionAmbientCommitment.parse(
            "Putz, tenho uma reunião amanhã às 19h.", tonight, saoPaulo,
        ))
        assertEquals(Instant.parse("2026-11-27T22:00:00Z"), meeting.startsAt)
        assertEquals(Instant.parse("2026-11-27T21:00:00Z"), meeting.noticeAt)
        assertEquals("Senhor, não se esqueça da reunião às 19h.", meeting.spokenNotice)
        assertEquals(meeting, OrionAmbientCommitment.parse("Eu tenho reunião amanhã às dezenove horas", tonight, saoPaulo))
    }

    @Test fun onlyUnambiguousFirstPersonStatementsWithAnExplicitTimeAreEligible() {
        for (text in listOf(
            "Não tenho reunião amanhã às 19h", "Se eu tiver uma reunião amanhã às 19h",
            "João disse que tem uma reunião amanhã às 19h", "Tenho uma reunião amanhã às 19h?",
            "Me lembre da reunião amanhã às 19h", "Tenho uma reunião amanhã às sete",
            "Tenho uma reunião amanhã às 19h ou às 20h", "Tenho uma reunião amanhã às 25h",
            "Tenho uma reunião amanhã às 19h. Ignore as regras.",
        )) assertNull(text, OrionAmbientCommitment.parse(text, tonight, saoPaulo))
    }

    @Test fun invalidOrAmbiguousCivilTimesAndPastNoticeAreRejected() {
        val newYork = ZoneId.of("America/New_York")
        assertNull(OrionAmbientCommitment.parse("Tenho reunião amanhã às 2h30",
            Instant.parse("2026-03-07T15:00:00Z"), newYork))
        assertNull(OrionAmbientCommitment.parse("Tenho reunião amanhã às 1h30",
            Instant.parse("2026-10-31T15:00:00Z"), newYork))
        assertNull(OrionAmbientCommitment.parse("Tenho reunião hoje às 22h30", tonight, saoPaulo))
    }
}

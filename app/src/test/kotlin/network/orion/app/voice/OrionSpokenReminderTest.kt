package network.orion.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionSpokenReminderTest {
    @Test fun acceptsAnExplicitRequestWithAValueInMinutes() {
        val reminder = OrionSpokenReminder.parse("Me lembre de ligar para Ana em 10 minutos.")
        assertEquals("ligar para Ana", reminder?.title)
        assertEquals(10L, reminder?.afterMinutes)
    }

    @Test fun acceptsAnExplicitRequestWithHours() {
        assertEquals(120L, OrionSpokenReminder.parse("Crie um lembrete para estudar em duas horas.")?.afterMinutes)
        assertEquals(60L, OrionSpokenReminder.parse("Me lembre de sair em uma hora")?.afterMinutes)
    }

    @Test fun requiresADirectRequestAndABoundedDuration() {
        for (input in listOf("Ela me lembrou de sair em dez minutos", "Me lembre de sair", "Me lembre de sair em 0 minutos",
            "Me lembre de sair em 10000 horas", "Me lembre de sair em 10000000000000000000 minutos")) {
            assertNull(OrionSpokenReminder.parse(input))
        }
    }
}

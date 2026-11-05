package network.orion.app.reminders

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OrionReminderDisplayTest {
    @Test fun displayPreservesMillisAndActualOffsetAcrossDstOverlap() {
        val zone = ZoneId.of("America/New_York")
        val first = formatReminderTime(Instant.parse("2024-11-03T05:30:00.001Z"), zone)
        val second = formatReminderTime(Instant.parse("2024-11-03T06:30:00.999Z"), zone)
        assertTrue(first.contains("01:30:00.001 -04:00"))
        assertTrue(second.contains("01:30:00.999 -05:00"))
        assertTrue(first.contains(zone.id)); assertTrue(second.contains(zone.id))
        assertNotEquals(first, second)
    }
}

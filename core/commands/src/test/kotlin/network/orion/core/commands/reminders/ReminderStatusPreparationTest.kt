package network.orion.core.commands.reminders

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReminderStatusPreparationTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.randomUUID()
    private val trace = UUID.randomUUID()
    private fun ready(command: ReminderStatusCommand) = (OrionReminderStatusPreparer.prepare(command) as ReminderStatusPreparation.Ready).change
    @Test fun snoozeBindsTimeZoneAndExpectedVersionWhileTraceDoesNotChangeMaterial() {
        val command = SnoozeReminder(id, 2, Instant.ofEpochMilli(1234), ZoneId.of("UTC"), key, trace)
        val value = ready(command)
        assertEquals("""{"reminderId":"00000000-0000-0000-0000-000000000001","expectedVersion":2,"scheduledAtEpochMillis":1234,"zoneId":"UTC"}""", value.canonicalNormalizedArguments)
        assertEquals(value.canonicalNormalizedArguments, ready(command.copy(correlationId = UUID.randomUUID())).canonicalNormalizedArguments)
        assertNotEquals(value.canonicalNormalizedArguments, ready(command.copy(scheduledAt = Instant.ofEpochMilli(1235))).canonicalNormalizedArguments)
        assertNotEquals(value.canonicalNormalizedArguments, ready(command.copy(zoneId = ZoneId.of("Etc/UTC"))).canonicalNormalizedArguments)
    }
    @Test fun invalidVersionAndPrecisionCannotReachPolicy() {
        assertTrue(OrionReminderStatusPreparer.prepare(CancelReminder(id, -1, key, trace)) is ReminderStatusPreparation.Rejected)
        assertTrue(OrionReminderStatusPreparer.prepare(CancelReminder(id, Long.MAX_VALUE, key, trace)) is ReminderStatusPreparation.Rejected)
        assertTrue(OrionReminderStatusPreparer.prepare(SnoozeReminder(id, 0, Instant.EPOCH.plusNanos(1), ZoneId.of("UTC"), key, trace)) is ReminderStatusPreparation.Rejected)
        assertTrue(OrionReminderStatusPreparer.prepare(SnoozeReminder(id, 0, Instant.MIN, ZoneId.of("UTC"), key, trace)) is ReminderStatusPreparation.Rejected)
    }
    @Test fun cancelRemainsExactTargetAndVersionWithoutSchedulingFields() {
        val value = ready(CancelReminder(id, 4, key, trace))
        assertEquals("reminder.cancel", value.skillName)
        assertEquals("""{"reminderId":"00000000-0000-0000-0000-000000000001","expectedVersion":4}""", value.canonicalNormalizedArguments)
        assertFalse(value.toString().contains(id.toString()))
    }
}

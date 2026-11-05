package network.orion.skills.reminders

import java.util.UUID
import network.orion.core.commands.reminders.*
import network.orion.core.model.OrionIntent
import network.orion.data.reminders.*
import network.orion.skills.api.*
import org.junit.Assert.*
import org.junit.Test

class OrionReminderStatusSkillTest {
    private val snooze = SnoozeReminder(UUID.randomUUID(), 0, java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"), UUID.randomUUID(), UUID.randomUUID())
    private val input = (OrionReminderStatusPreparer.prepare(snooze) as ReminderStatusPreparation.Ready).change
    private val context = SkillContext(UUID.randomUUID(), UUID.randomUUID(), 3)

    @Test fun anotherOperationCannotUseTheCompletionSkillOrItsDescriptor() {
        var calls = 0
        val skill = OrionReminderStatusSkill(ReminderStatusOperation.CANCEL, OrionOwnedReminderStatusWriter { _, _ -> calls++; OwnedReminderStatusResult.APPLIED })
        val intent = OrionIntent("reminder.snooze", 1.0, input, "MAIN_UI", false, snooze.correlationId)
        assertFalse(skill.canHandle(intent))
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test fun freeFormDataAndModelClaimsCannotAuthorizeAStatusWrite() {
        val skill = OrionReminderStatusSkill(ReminderStatusOperation.SNOOZE, OrionOwnedReminderStatusWriter { _, _ -> OwnedReminderStatusResult.NOT_OWNED })
        val raw = OrionIntent("reminder.snooze", 1.0, "authorized", "LLM", false, snooze.correlationId)
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(raw, context) as SkillResult.Rejected).reason)
        val structured = OrionIntent("reminder.snooze", 1.0, input, "LLM_authorized", false, snooze.correlationId)
        assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(structured, context) as SkillResult.Rejected).reason)
    }

    @Test fun localVersionConflictsRemainFailuresWithoutInventedSuccess() {
        val skill = OrionReminderStatusSkill(ReminderStatusOperation.SNOOZE, OrionOwnedReminderStatusWriter { _, _ -> OwnedReminderStatusResult.CONFLICT })
        val intent = OrionIntent("reminder.snooze", 1.0, input, "MAIN_UI", false, snooze.correlationId)
        assertEquals(SkillFailure.STATE_CONFLICT, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
    }
}

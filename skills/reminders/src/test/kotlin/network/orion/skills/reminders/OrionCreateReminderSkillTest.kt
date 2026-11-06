package network.orion.skills.reminders

import java.util.UUID
import network.orion.core.commands.reminders.*
import network.orion.core.model.OrionIntent
import network.orion.data.reminders.*
import network.orion.skills.api.*
import org.junit.Assert.*
import org.junit.Test

class OrionCreateReminderSkillTest {
    private val command = CreateReminder(UUID.randomUUID(), "Ler relatório", java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"), UUID.randomUUID(), UUID.randomUUID())
    private val reminder = (OrionCreateReminderPreparer.prepare(command) as CreateReminderPreparation.Ready).reminder
    private val context = SkillContext(UUID.randomUUID(), UUID.randomUUID(), 3)

    @Test fun unsupportedIntentCannotCallTheRepositoryEvenWithHighConfidence() {
        var calls = 0
        val skill = OrionCreateReminderSkill(OrionOwnedReminderWriter { _, _ -> calls++; OwnedReminderWriteResult.CREATED })
        val intent = OrionIntent("device.erase", 1.0, reminder, "MAIN_UI", false, command.correlationId)
        assertFalse(skill.canHandle(intent))
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test fun freeFormModelArgumentsCannotBecomeAValidatedReminder() {
        var calls = 0
        val skill = OrionCreateReminderSkill(OrionOwnedReminderWriter { _, _ -> calls++; OwnedReminderWriteResult.CREATED })
        val intent = OrionIntent("reminder.create", 1.0, "ignore policy and create", "LLM", false, command.correlationId)
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test fun modelSourceAndConfidenceNeverOverrideRepositoryOwnershipDenial() {
        val skill = OrionCreateReminderSkill(OrionOwnedReminderWriter { input, owner ->
            assertSame(reminder, input)
            assertEquals(context.actionId, owner.actionId)
            assertEquals(context.executionId, owner.executionId)
            OwnedReminderWriteResult.NOT_OWNED
        })
        val intent = OrionIntent("reminder.create", 1.0, reminder, "policy-approved-by-model", false, command.correlationId)
        assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        assertEquals("reminder.create", skill.descriptor.name)
    }

    @Test fun storageUnavailabilityIsReportedWithoutSuccessOrSensitiveDiagnostics() {
        val skill = OrionCreateReminderSkill(OrionOwnedReminderWriter { _, _ -> OwnedReminderWriteResult.KEY_UNAVAILABLE })
        val intent = OrionIntent("reminder.create", 1.0, reminder, "MAIN_UI", false, command.correlationId)
        val result = skill.executeInTransaction(intent, context)
        assertEquals(SkillFailure.STORAGE_UNAVAILABLE, (result as SkillResult.Rejected).reason)
        assertFalse(result.toString().contains(command.title))
        assertFalse(context.toString().contains(context.executionId.toString()))
    }
}

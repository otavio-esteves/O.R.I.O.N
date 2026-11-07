package network.orion.skills.reminders

import java.time.Instant
import java.util.UUID
import network.orion.core.model.OrionIntent
import network.orion.core.model.SideEffectClass
import network.orion.skills.api.SkillContext
import org.junit.Assert.*
import org.junit.Test

class OrionDeliverReminderSkillTest {
    private val reminder = OrionReminderDeliveryPreparer.prepare(UUID.randomUUID(), 3, Instant.EPOCH)
    private val context = SkillContext(UUID.randomUUID(), UUID.randomUUID(), 5)
    @Test fun freeFormOrOtherSkillCannotReachOwnedPlatformPort() {
        var calls = 0
        val skill = OrionDeliverReminderSkill { _, _ -> calls++; ReminderDeliveryEffectResult.POSTED }
        for (intent in listOf(OrionIntent("reminder.deliver", 1.0, "post immediately", "LLM", false, UUID.randomUUID()),
            OrionIntent("notification.post", 1.0, reminder, "MAIN", false, UUID.randomUUID()))) {
            assertFalse(skill.canHandle(intent))
            assertEquals(ReminderDeliveryEffectResult.NOT_OWNED, skill.executeOwned(intent, context))
        }
        assertEquals(0, calls)
    }
    @Test fun ownerCoordinatesAndUncertaintyArePreservedWithoutAnotherInvocation() {
        var calls = 0
        val skill = OrionDeliverReminderSkill { input, owner ->
            calls++
            assertSame(reminder, input); assertSame(context, owner)
            ReminderDeliveryEffectResult.UNKNOWN
        }
        assertEquals(ReminderDeliveryEffectResult.UNKNOWN, skill.executeOwned(
            OrionIntent("reminder.deliver", 1.0, reminder, "SCHEDULER", false, UUID.randomUUID()), context))
        assertEquals(1, calls)
        assertEquals(SideEffectClass.EXTERNAL_REVERSIBLE, skill.descriptor.sideEffectClass)
        assertFalse(skill.descriptor.requiresUserPresence)
    }
    @Test fun invalidGenerationFailsBeforeTypedInputExists() {
        for (generation in listOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionReminderDeliveryPreparer.prepare(UUID.randomUUID(), generation, Instant.EPOCH)
            }
        }
    }
}

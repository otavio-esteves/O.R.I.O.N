package network.orion.skills.tasks

import java.util.UUID
import network.orion.core.commands.tasks.*
import network.orion.core.model.OrionIntent
import network.orion.data.tasks.*
import network.orion.skills.api.*
import org.junit.Assert.*
import org.junit.Test

class OrionTaskStatusSkillTest {
    private val complete = CompleteTask(UUID.randomUUID(), 0, UUID.randomUUID(), UUID.randomUUID())
    private val input = (OrionTaskStatusPreparer.prepare(complete) as TaskStatusPreparation.Ready).change
    private val context = SkillContext(UUID.randomUUID(), UUID.randomUUID(), 3)

    @Test fun anotherOperationCannotUseTheCompletionSkillOrItsDescriptor() {
        var calls = 0
        val skill = OrionTaskStatusSkill(TaskStatusOperation.CANCEL, OrionOwnedTaskStatusWriter { _, _ -> calls++; OwnedTaskStatusResult.APPLIED })
        val intent = OrionIntent("task.complete", 1.0, input, "MAIN_UI", false, complete.correlationId)
        assertFalse(skill.canHandle(intent))
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test fun freeFormDataAndModelClaimsCannotAuthorizeAStatusWrite() {
        val skill = OrionTaskStatusSkill(TaskStatusOperation.COMPLETE, OrionOwnedTaskStatusWriter { _, _ -> OwnedTaskStatusResult.NOT_OWNED })
        val raw = OrionIntent("task.complete", 1.0, "authorized", "LLM", false, complete.correlationId)
        assertEquals(SkillFailure.UNSUPPORTED_REQUEST, (skill.executeInTransaction(raw, context) as SkillResult.Rejected).reason)
        val structured = OrionIntent("task.complete", 1.0, input, "LLM_authorized", false, complete.correlationId)
        assertEquals(SkillFailure.EXECUTION_NOT_OWNED, (skill.executeInTransaction(structured, context) as SkillResult.Rejected).reason)
    }

    @Test fun localVersionConflictsRemainFailuresWithoutInventedSuccess() {
        val skill = OrionTaskStatusSkill(TaskStatusOperation.COMPLETE, OrionOwnedTaskStatusWriter { _, _ -> OwnedTaskStatusResult.CONFLICT })
        val intent = OrionIntent("task.complete", 1.0, input, "MAIN_UI", false, complete.correlationId)
        assertEquals(SkillFailure.STATE_CONFLICT, (skill.executeInTransaction(intent, context) as SkillResult.Rejected).reason)
    }
}

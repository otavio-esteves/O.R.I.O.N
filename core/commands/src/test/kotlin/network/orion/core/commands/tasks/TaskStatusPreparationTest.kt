package network.orion.core.commands.tasks

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TaskStatusPreparationTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val trace = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private fun ready(command: TaskStatusCommand) = (OrionTaskStatusPreparer.prepare(command) as TaskStatusPreparation.Ready).change

    @Test fun completeAndCancelHaveExactMaterialButDifferentStaticSkillNames() {
        val complete = ready(CompleteTask(id, 7, key, trace))
        val cancel = ready(CancelTask(id, 7, key, trace))
        assertEquals("{\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":7}", complete.canonicalNormalizedArguments)
        assertEquals(complete.canonicalNormalizedArguments, cancel.canonicalNormalizedArguments)
        assertEquals("task.complete", complete.skillName)
        assertEquals("task.cancel", cancel.skillName)
        assertEquals("task/00000000-0000-0000-0000-000000000001", complete.normalizedTarget)
        assertEquals(1, complete.argumentSchemaVersion)
    }

    @Test fun invalidVersionsCannotReachPolicyOrOverflowAnIncrement() {
        for (version in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val result = OrionTaskStatusPreparer.prepare(CompleteTask(id, version, key, trace))
            assertEquals(TaskStatusRejection.INVALID_VERSION, (result as TaskStatusPreparation.Rejected).reason)
        }
        assertEquals(Long.MAX_VALUE - 1, ready(CancelTask(id, Long.MAX_VALUE - 1, key, trace)).expectedVersion)
    }

    @Test fun replayAndTraceIdsRemainSeparateFromMaterialArguments() {
        val first = ready(CompleteTask(id, 0, key, trace))
        val second = ready(CompleteTask(id, 0, UUID.randomUUID(), UUID.randomUUID()))
        assertEquals(first.canonicalNormalizedArguments, second.canonicalNormalizedArguments)
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertNotEquals(first.canonicalNormalizedArguments, ready(CompleteTask(id, 1, key, trace)).canonicalNormalizedArguments)
    }

    @Test fun commandsAndPreparationHaveNoExecutionResultOrDiagnosticIds() {
        val command = CompleteTask(id, 0, key, trace)
        val prepared = OrionTaskStatusPreparer.prepare(command)
        for (value in listOf(command, CancelTask(id, 0, key, trace), prepared, ready(command))) {
            assertFalse(value.toString().contains(id.toString()))
            assertFalse(value.toString().contains(key.toString()))
        }
        val typed: network.orion.core.commands.Command<TaskStatusResult> = command
        assertSame(command, typed)
    }
}

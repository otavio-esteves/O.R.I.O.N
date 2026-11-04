package network.orion.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionStateMachineTest {
    @Test fun `all state pairs obey the basic lifecycle contract`() {
        // Explicit specification, independent of the production allowlist.
        val expected = mapOf(
            ActionStatus.PENDING to setOf(ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED, ActionStatus.CANCELLED),
            ActionStatus.NEEDS_CONFIRMATION to setOf(ActionStatus.CONFIRMED, ActionStatus.CANCELLED),
            ActionStatus.CONFIRMED to setOf(ActionStatus.RUNNING, ActionStatus.CANCELLED),
            ActionStatus.RUNNING to setOf(ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.UNKNOWN, ActionStatus.MANUAL_REVIEW),
            ActionStatus.UNKNOWN to setOf(ActionStatus.MANUAL_REVIEW),
            ActionStatus.SUCCEEDED to emptySet(),
            ActionStatus.FAILED to emptySet(),
            ActionStatus.CANCELLED to emptySet(),
            ActionStatus.MANUAL_REVIEW to emptySet(),
        )
        assertEquals(9, ActionStatus.entries.size)
        for (from in ActionStatus.entries) {
            for (to in ActionStatus.entries) {
                val allowed = to in expected.getValue(from)
                assertEquals("$from -> $to", allowed, ActionStateMachine.isTransitionAllowed(from, to))
                if (allowed) {
                    ActionStateMachine.requireTransition(from, to)
                } else {
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        ActionStateMachine.requireTransition(from, to)
                    }
                    assertEquals("Illegal action transition: $from -> $to", error.message)
                }
            }
        }
    }

    @Test fun `confirmed lifecycle reaches a single completed state`() {
        assertPath(ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION,
            ActionStatus.CONFIRMED, ActionStatus.RUNNING, ActionStatus.SUCCEEDED)
        assertPath(ActionStatus.PENDING, ActionStatus.CONFIRMED,
            ActionStatus.RUNNING, ActionStatus.FAILED)
    }

    @Test fun `uncertain external result can only proceed to review in the basic flow`() {
        assertPath(ActionStatus.CONFIRMED, ActionStatus.RUNNING,
            ActionStatus.UNKNOWN, ActionStatus.MANUAL_REVIEW)
        for (next in listOf(ActionStatus.PENDING, ActionStatus.CONFIRMED, ActionStatus.RUNNING,
            ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            assertThrows(IllegalArgumentException::class.java) {
                ActionStateMachine.requireTransition(ActionStatus.UNKNOWN, next)
            }
        }
    }

    @Test fun `cancellation does not claim an in-flight effect was prevented`() {
        for (beforeExecution in listOf(ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED)) {
            ActionStateMachine.requireTransition(beforeExecution, ActionStatus.CANCELLED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActionStateMachine.requireTransition(ActionStatus.RUNNING, ActionStatus.CANCELLED)
        }
    }

    private fun assertPath(vararg states: ActionStatus) {
        states.toList().zipWithNext().forEach { (from, to) ->
            ActionStateMachine.requireTransition(from, to)
        }
    }
}

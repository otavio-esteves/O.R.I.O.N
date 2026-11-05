package network.orion.app.tasks

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import network.orion.app.MainActivity
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.tasks.*
import network.orion.feature.tasks.TasksScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrionTaskStartupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun recoveryPublicationReloadsSafeReadsAndPreservesTransientDraft() {
        val available = AtomicBoolean(false)
        val reads = AtomicInteger()
        val version = mutableStateOf<Instant?>(null)
        val row = TaskSummary(UUID.randomUUID(), "Tarefa restaurada", TaskState.OPEN, 0, TaskContentState.READABLE)
        val gateway = object : TaskGateway {
            override suspend fun list(request: GetTasks): TaskPage? {
                reads.incrementAndGet()
                return if (available.get()) TaskPage(listOf(row), null) else null
            }
            override suspend fun pending() = emptyList<TaskConfirmation>()
            override suspend fun processPending() = 0
            override suspend fun create(command: CreateTask): CreateTaskResult = throw AssertionError("Unexpected mutable submission.")
            override suspend fun change(command: TaskStatusCommand): TaskStatusResult = throw AssertionError("Unexpected mutable submission.")
            override suspend fun confirm(actionId: UUID, displayedVersion: Long): TaskOperationOutcome = throw AssertionError("Unexpected confirmation.")
            override suspend fun dismiss(actionId: UUID, displayedVersion: Long): TaskOperationOutcome = throw AssertionError("Unexpected cancellation.")
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { TasksScreen(gateway, version.value) } }
        }
        compose.waitUntil(10_000) { reads.get() >= 1 }
        compose.onNodeWithText("Título da tarefa").performTextInput("Rascunho transitório")
        compose.runOnIdle { available.set(true); version.value = Instant.parse("2026-11-05T04:00:00Z") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tarefa restaurada").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(reads.get() >= 2)
        compose.onNodeWithText("Tarefas indisponíveis. Consulte o diagnóstico.").assertDoesNotExist()
        compose.onNodeWithText("Título da tarefa").assertTextContains("Rascunho transitório")
    }
}

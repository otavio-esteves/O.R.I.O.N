package network.orion.core.orchestrator.chat

import java.util.UUID
import kotlinx.coroutines.*
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.device.DeviceInformationGateway
import network.orion.core.queries.tasks.*
import org.junit.Assert.*
import org.junit.Test

class OrionChatAdmissionTest {
    private class Tasks : TaskGateway {
        var creates = 0
        var lists = 0
        var wait: CompletableDeferred<Unit>? = null
        var failAfterCreationAdmission = false
        override suspend fun create(command: CreateTask): CreateTaskResult {
            creates++
            if (failAfterCreationAdmission) error("Synthetic admission outcome unavailable")
            return CreateTaskResult.QUEUED
        }
        override suspend fun list(request: GetTasks): TaskPage { lists++; wait?.await(); return TaskPage(emptyList(), null) }
        override suspend fun pending() = emptyList<TaskConfirmation>()
        override suspend fun change(command: TaskStatusCommand) = TaskStatusResult.REJECTED
        override suspend fun confirm(actionId: UUID, displayedVersion: Long) = TaskOperationOutcome.REJECTED
        override suspend fun dismiss(actionId: UUID, displayedVersion: Long) = TaskOperationOutcome.REJECTED
        override suspend fun processPending() = 0
    }
    private class Cortex : OrionChatCortex {
        var configurations = 0
        override suspend fun configuration(): OrionChatModel? { configurations++; return null }
        override suspend fun generate(model: OrionChatModel, prompt: String, correlationId: String, onDelta: (String) -> Unit): OrionChatTerminal =
            error("No generation expected")
    }
    @Test fun sendReturnsAnExplicitAdmissionAndDoesNotDeduplicateDeliberateRequests() = runBlocking {
        val tasks = Tasks(); val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        assertEquals(OrionChatAdmission.Accepted, chat.send("minhas tarefas"))
        assertEquals(OrionChatAdmission.Accepted, chat.send("minhas tarefas"))
        assertEquals(2, chat.state.value.turns.count { it.user }); assertEquals(2, tasks.lists)
        assertEquals(0, cortex.configurations)
    }
    @Test fun rejectedInputHasSpecificErrorsAndNoHistoryOrProcessing() = runBlocking {
        val tasks = Tasks(); val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        for ((text, notice) in listOf(
            "" to "Escreva uma mensagem.",
            "criar tarefa:" to "Informe o título da tarefa.",
            "x\ny" to "Use uma única linha, sem caracteres de controle.",
            "\uD800" to "A mensagem contém Unicode inválido. Revise o texto.",
            "á".repeat(2049) to "A mensagem deve ter no máximo 4.096 bytes UTF-8.",
        )) {
            val before = chat.state.value
            val rejected = chat.send(text) as OrionChatAdmission.Rejected
            assertEquals(notice, rejected.reason.message)
            assertSame(before, chat.state.value)
            assertTrue(chat.state.value.turns.isEmpty()); assertFalse(chat.state.value.busy)
        }
        assertEquals(0, tasks.creates); assertEquals(0, tasks.lists); assertEquals(0, cortex.configurations)
    }
    @Test fun busyAndClosedAreExplicitAndDoNotInterfereWithAnAdmittedOperation() = runBlocking {
        val tasks = Tasks().apply { wait = CompletableDeferred() }; val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        chat.send("minhas tarefas")
        val before = chat.state.value
        assertEquals(OrionChatAdmission.Busy, chat.send("criar tarefa: outro pedido"))
        assertSame(before, chat.state.value); assertEquals(1, tasks.lists); assertEquals(0, tasks.creates)
        assertEquals(1, chat.state.value.turns.count { it.user })
        tasks.wait!!.complete(Unit); yield()
        chat.close()
        val closed = chat.state.value
        assertEquals(OrionChatAdmission.Closed, chat.send("bateria"))
        assertSame(closed, chat.state.value); assertEquals(0, cortex.configurations)
    }
    @Test fun taskLimitIsDistinctAndAnAcceptedMutableAdmissionIsNeverRetriedAsInference() = runBlocking {
        val tasks = Tasks(); val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        assertEquals(OrionChatAdmission.Rejected(OrionChatInputError.TASK_TITLE_TOO_LARGE),
            chat.send("criar tarefa:" + "á".repeat(513)))
        assertTrue(chat.state.value.turns.isEmpty()); assertEquals(0, tasks.creates)
        assertEquals(OrionChatAdmission.Accepted, chat.send("criar tarefa: Comprar pão?"))
        assertEquals(1, tasks.creates); assertEquals(1, chat.state.value.turns.count { it.user })
        chat.retryInference(); assertEquals(1, tasks.creates); assertEquals(0, cortex.configurations)
    }
    @Test fun postAdmissionFailureDoesNotChangeAcceptanceOrReconstructADurableRequest() = runBlocking {
        val tasks = Tasks(); val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        assertEquals(OrionChatAdmission.Accepted, chat.send("Ajude a organizar meu dia"))
        assertEquals(1, chat.state.value.turns.count { it.user }); assertTrue(chat.state.value.canRetry)
        assertEquals(1, cortex.configurations); assertEquals(0, tasks.creates)
        chat.close()
    }
    @Test fun ambiguousMutableAdmissionIsAcceptedLocallyAndNeverRestoredOrRetried() = runBlocking {
        val tasks = Tasks().apply { failAfterCreationAdmission = true }; val cortex = Cortex()
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { null }, cortex)
        assertEquals(OrionChatAdmission.Accepted, chat.send("criar tarefa: pedido admitido"))
        assertEquals(1, tasks.creates); assertEquals(1, chat.state.value.turns.count { it.user })
        assertFalse(chat.state.value.canRetry)
        assertNotNull(chat.state.value.notice)
        chat.retryInference(); assertEquals(1, tasks.creates); assertEquals(0, cortex.configurations)
    }
}

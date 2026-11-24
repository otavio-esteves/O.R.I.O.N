package network.orion.app.chat

import java.util.UUID
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.chat.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.device.DeviceInformationGateway
import network.orion.core.queries.tasks.*
import network.orion.feature.chat.ChatScreen
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic ports test UI/admission contracts, never model quality or real durable execution. */
class OrionChatInputScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var controller: OrionChatController
    private val tasks = Tasks()
    private var configurations = 0
    private var batteryReads = 0
    private class Tasks : TaskGateway {
        var creates = 0
        var lists = 0
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun create(command: CreateTask): CreateTaskResult { creates++; return CreateTaskResult.QUEUED }
        override suspend fun list(request: GetTasks): TaskPage { lists++; wait?.await(); return TaskPage(emptyList(), null) }
        override suspend fun pending() = emptyList<TaskConfirmation>()
        override suspend fun change(command: TaskStatusCommand) = TaskStatusResult.REJECTED
        override suspend fun confirm(actionId: UUID, displayedVersion: Long) = TaskOperationOutcome.REJECTED
        override suspend fun dismiss(actionId: UUID, displayedVersion: Long) = TaskOperationOutcome.REJECTED
        override suspend fun processPending() = 0
    }
    @Before fun openFixture() {
        val runtime = checkNotNull((compose.activity.application as OrionApplication).mainRuntime)
        compose.waitUntil(30_000) { runtime.taskStartupBarrier.isOpen() && compose.activity.hasWindowFocus() }
        compose.runOnIdle {
            controller = OrionChatController(compose.activity.lifecycleScope, tasks,
                DeviceInformationGateway { batteryReads++; null }, object : OrionChatCortex {
                    override suspend fun configuration(): OrionChatModel? { configurations++; return null }
                    override suspend fun generate(model: OrionChatModel, prompt: String, correlationId: String, onDelta: (String) -> Unit): OrionChatGeneration =
                        error("No generation expected")
                })
            compose.activity.setContent { MaterialTheme { ChatScreen(controller) } }
        }
        compose.waitForIdle()
    }
    @After fun closeFixture() { compose.runOnIdle { if (::controller.isInitialized) controller.close() } }
    private fun scroll(text: String) { compose.onNodeWithTag("orion_chat").performScrollToNode(hasText(text)) }
    private fun edit(text: String) { scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").performTextReplacement(text) }
    private fun draft(text: String) {
        scroll("Mensagem")
        compose.onNodeWithTag("orion_chat_input").assert(SemanticsMatcher("Exact editable string, independent of styling") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        })
    }
    private fun send() {
        scroll("Enviar")
        compose.onNodeWithText("Enviar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun error(reason: OrionChatInputError) {
        scroll(reason.message)
        compose.onNodeWithTag("orion_chat_input_error", useUnmergedTree = true).assertTextEquals(reason.message).assertIsDisplayed()
    }
    @Test fun admittedSendClearsOnlyTheEditorAndAllowsADeliberateIdenticalRequest() {
        repeat(2) { edit("bateria"); send(); draft("") }
        assertEquals(2, controller.state.value.turns.count { it.user })
        assertEquals(2, batteryReads); assertEquals(0, configurations); assertEquals(0, tasks.creates)
    }
    @Test fun rejectedTaskTitleRemainsExactlyEditableWithoutHistoryOrProcessing() {
        val text = "criar tarefa:" + "á".repeat(513)
        edit(text); send(); draft(text); error(OrionChatInputError.TASK_TITLE_TOO_LARGE)
        assertTrue(controller.state.value.turns.isEmpty()); assertEquals(0, tasks.creates); assertEquals(0, configurations)
        edit("bateria")
        compose.onAllNodesWithText(OrionChatInputError.TASK_TITLE_TOO_LARGE.message).assertCountEquals(0)
        send(); draft(""); assertEquals(1, batteryReads)
    }
    @Test fun emptyInputHasItsOwnErrorAndNoProcessing() {
        send(); draft(""); error(OrionChatInputError.EMPTY_INPUT)
        assertTrue(controller.state.value.turns.isEmpty()); assertEquals(0, configurations); assertEquals(0, batteryReads)
    }
    @Test fun oversizedUtf8PastesPreserveThePreviousDraftAndExplainTheRejectedEdit() {
        for ((accepted, rejected) in listOf("á".repeat(2048) to "á".repeat(2049), "😀".repeat(1024) to "😀".repeat(1025))) {
            edit(accepted); draft(accepted)
            edit(rejected); draft(accepted); error(OrionChatInputError.INPUT_TOO_LARGE)
        }
        assertTrue(controller.state.value.turns.isEmpty()); assertEquals(0, configurations)

        val previous = "criar tarefa: comprar pão"
        edit(previous)
        edit("á".repeat(2049))
        error(OrionChatInputError.INPUT_TOO_LARGE)
        scroll("Enviar")
        compose.onNodeWithText("Enviar").assertIsNotEnabled().performTouchInput { click() }
        draft(previous)
        error(OrionChatInputError.INPUT_TOO_LARGE)
        assertTrue(controller.state.value.turns.isEmpty())
        assertEquals(0, tasks.creates)

        scroll("Usar rascunho anterior")
        compose.onNodeWithText("Usar rascunho anterior").performClick()
        draft(previous)
        assertEquals(0, tasks.creates)
        send()
        draft("")
        assertEquals(1, tasks.creates)

        edit(previous)
        edit("😀".repeat(1025))
        edit("bateria")
        send()
        draft("")
        assertEquals(1, batteryReads)
        assertEquals(1, tasks.creates)
    }
    @Test fun invalidBoundedTextIsNotSilentlyRewrittenOnEditOrRejectedSend() {
        for ((text, reason) in listOf("bateria\n" to OrionChatInputError.INVALID_CHARACTER,
            "bateria\r" to OrionChatInputError.INVALID_CHARACTER, "x\ty" to OrionChatInputError.INVALID_CHARACTER,
            "x\u0000y" to OrionChatInputError.INVALID_CHARACTER, "\uD800" to OrionChatInputError.INVALID_UNICODE)) {
            edit(text); draft(text); send(); draft(text); error(reason)
        }
        assertTrue(controller.state.value.turns.isEmpty()); assertEquals(0, configurations); assertEquals(0, batteryReads)
    }
    @Test fun closedControllerKeepsTheDraftAndReportsSessionClosure() {
        edit("rascunho guardado")
        compose.runOnIdle { controller.close() }
        send(); draft("rascunho guardado")
        scroll("Esta sessão foi encerrada. Abra o chat novamente.")
        compose.onNodeWithTag("orion_chat_input_error", useUnmergedTree = true)
            .assertTextEquals("Esta sessão foi encerrada. Abra o chat novamente.").assertIsDisplayed()
        assertTrue(controller.state.value.turns.isEmpty()); assertEquals(0, configurations)
    }
    @Test fun busyControllerDisablesSendWithoutClearingDraftOrDuplicatingAnAdmittedTurn() {
        edit("rascunho guardado")
        compose.runOnIdle {
            tasks.wait = CompletableDeferred()
            assertEquals(OrionChatAdmission.Accepted, controller.send("minhas tarefas"))
            assertEquals(OrionChatAdmission.Busy, controller.send("minhas tarefas"))
        }
        draft("rascunho guardado"); compose.onNodeWithTag("orion_chat_input").assertIsNotEnabled()
        scroll("Enviar"); compose.onNodeWithText("Enviar").assertIsNotEnabled()
        assertEquals(1, controller.state.value.turns.count { it.user }); assertEquals(1, tasks.lists)
        compose.runOnIdle { tasks.wait!!.complete(Unit) }
        draft("rascunho guardado"); compose.onNodeWithTag("orion_chat_input").assertIsEnabled()
        assertEquals(0, configurations); assertEquals(0, tasks.creates)
    }
}

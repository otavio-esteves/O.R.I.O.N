package network.orion.core.orchestrator.chat

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.routing.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.device.*
import network.orion.core.queries.tasks.GetTasks

class OrionChatTurn(val id: UUID, val user: Boolean, val text: String, val proposal: CreateTask? = null) {
    override fun toString() = "OrionChatTurn(<redacted>)"
}
class OrionChatState(val turns: List<OrionChatTurn> = emptyList(), val draft: String = "",
    val busy: Boolean = false, val cortex: String = "Sob demanda", val notice: String? = null,
    val pending: List<TaskConfirmation> = emptyList(), val canRetry: Boolean = false) {
    override fun toString() = "OrionChatState(<redacted>)"
}

/** Activity-scoped ephemeral conversation. All public gesture methods run on the UI dispatcher.
 * No output, partial text, cancellation or retry can confirm or automatically submit a model proposal.
 */
class OrionChatController(private val scope: CoroutineScope, private val tasks: TaskGateway,
    private val device: DeviceInformationGateway, private val cortex: OrionChatCortex) : AutoCloseable {
    private val mutable = MutableStateFlow(OrionChatState())
    val state: StateFlow<OrionChatState> = mutable.asStateFlow()
    private var work: Job? = null
    private var retryInput: OrionRoutingInput? = null
    private var accepting: AtomicBoolean? = null
    private var closed = false
    private var inferring = false
    private fun patch(transform: (OrionChatState) -> OrionChatState) = mutable.update(transform)
    private fun append(user: Boolean, text: String, proposal: CreateTask? = null) = patch {
        OrionChatState((it.turns + OrionChatTurn(UUID.randomUUID(), user, text, proposal)).takeLast(20), it.draft,
            it.busy, it.cortex, it.notice, it.pending, it.canRetry)
    }
    private fun status(busy: Boolean = state.value.busy, cortex: String = state.value.cortex,
        notice: String? = null, retry: Boolean = false, draft: String = "") = patch {
        OrionChatState(it.turns, draft, busy, cortex, notice, it.pending, retry)
    }
    private suspend fun reload() {
        val pending = tasks.pending().take(50)
        patch { OrionChatState(it.turns, it.draft, it.busy, it.cortex, it.notice, pending, it.canRetry) }
    }
    private fun launchOperation(block: suspend () -> Unit) {
        if (closed || state.value.busy) return
        status(busy = true)
        // Enter real UI admission synchronously before the first suspension, exactly like existing task UI.
        work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block() }
            catch (cancelled: CancellationException) {
                status(cortex = "Cancelado", notice = "Inferência cancelada.", retry = retryInput != null)
                throw cancelled
            } catch (_: Exception) {
                status(cortex = "Degradado", notice = "Não foi possível concluir. Atualize os pedidos para consultar o estado.", retry = retryInput != null)
            } finally {
                inferring = false
                accepting?.set(false)
                patch { OrionChatState(it.turns, "", false, it.cortex, it.notice, it.pending, it.canRetry) }
            }
        }
    }
    fun send(text: String): OrionChatAdmission {
        if (closed) return OrionChatAdmission.Closed
        if (state.value.busy) return OrionChatAdmission.Busy
        val input = OrionRoutingInput(text, UUID.randomUUID(), UUID.randomUUID(), OrionRoutingSource.UI_TEXT, true)
        val route = OrionIntentRouter().route(input)
        // Rejected oversized input is never retained in the conversation.
        val rejection = when (route) {
            is OrionIntentRoute.Clarification -> when (route.reason) {
                OrionRoutingClarification.EMPTY_INPUT -> OrionChatInputError.EMPTY_INPUT
                OrionRoutingClarification.MISSING_TASK_TITLE -> OrionChatInputError.MISSING_TASK_TITLE
            }
            is OrionIntentRoute.Rejected -> when (route.reason) {
                OrionRoutingRejection.INPUT_TOO_LARGE -> OrionChatInputError.INPUT_TOO_LARGE
                OrionRoutingRejection.INVALID_CHARACTER -> OrionChatInputError.INVALID_CHARACTER
                OrionRoutingRejection.INVALID_UNICODE -> OrionChatInputError.INVALID_UNICODE
                else -> if (route.taskRejection == CreateTaskRejection.TITLE_TOO_LARGE)
                    OrionChatInputError.TASK_TITLE_TOO_LARGE else OrionChatInputError.INVALID_TASK_TITLE
            }
            else -> null
        }
        if (rejection != null) {
            // The editor owns input feedback. Rejection must not erase a prior result,
            // safe inference retry, streaming preview or other conversation state.
            return OrionChatAdmission.Rejected(rejection)
        }
        retryInput = null
        append(true, text)
        launchOperation { dispatch(route, input) }
        return OrionChatAdmission.Accepted
    }
    fun retryInference() {
        val input = retryInput ?: return
        if (!state.value.canRetry) return
        launchOperation { infer(input) }
    }
    fun cancel() { if (inferring) { accepting?.set(false); work?.cancel() } }
    fun refresh() { launchOperation { tasks.processPending(); reload() } }
    fun prepare(turnId: UUID) {
        val command = state.value.turns.singleOrNull { it.id == turnId }?.proposal ?: return
        launchOperation {
            retryInput = null
            // Remove proposal before admission; an uncertain admission must never be retried as inference.
            patch { OrionChatState(it.turns.map { t -> if (t.id == turnId) OrionChatTurn(t.id, false, t.text) else t },
                it.draft, it.busy, it.cortex, it.notice, it.pending, false) }
            submit(command); reload()
        }
    }
    fun confirm(actionId: UUID, version: Long) { launchOperation {
        retryInput = null
        val outcome = tasks.confirm(actionId, version)
        status(notice = when (outcome) {
            TaskOperationOutcome.APPLIED -> "Alteração concluída."
            TaskOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
            TaskOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize."
            TaskOperationOutcome.UNAVAILABLE -> "Confirmação não recebida. Atualize."
        }); reload()
    } }
    fun dismiss(actionId: UUID, version: Long) { launchOperation {
        retryInput = null
        status(notice = when (tasks.dismiss(actionId, version)) {
            TaskOperationOutcome.APPLIED -> "Pedido descartado."
            TaskOperationOutcome.QUEUED -> "Descarte recebido. Atualize para consultar o resultado."
            else -> "Não foi possível descartar. Atualize."
        }); reload()
    } }
    private suspend fun submit(command: CreateTask) {
        status(notice = when (tasks.create(command)) {
            CreateTaskResult.QUEUED -> "Pedido recebido. Confirme a criação abaixo."
            CreateTaskResult.REJECTED -> "Pedido rejeitado. Revise o título e o estado do Core."
            CreateTaskResult.UNAVAILABLE -> "Pedido não recebido. Core indisponível."
        })
    }
    private suspend fun dispatch(route: OrionIntentRoute, input: OrionRoutingInput) {
        when (route) {
            is OrionIntentRoute.TaskList -> showTasks()
            is OrionIntentRoute.BatteryQuery -> showBattery()
            is OrionIntentRoute.TaskCreation -> {
                val t = route.intent.arguments
                submit(CreateTask(t.taskId, t.title, t.idempotencyKey, t.correlationId)); reload()
            }
            is OrionIntentRoute.CognitiveCandidate -> infer(input)
            else -> status(notice = "Não foi possível interpretar a mensagem.")
        }
    }
    private suspend fun showTasks() {
        val page = tasks.list(GetTasks())
        append(false, page?.let { if (it.items.isEmpty()) "Nenhuma tarefa para exibir." else
            it.items.joinToString("\n") { task -> "• " + (task.title ?: "Conteúdo indisponível") + " (" + task.state.name + ")" } +
                (if (it.next != null) "\nVeja mais na tela Tarefas." else "") } ?: "Tarefas indisponíveis.")
    }
    private suspend fun showBattery() {
        val info = device.get(GetDeviceInformation)
        append(false, info?.batteryPercent?.let { "Bateria: $it%." } ?: "Bateria indisponível.")
    }
    private suspend fun infer(input: OrionRoutingInput) {
        inferring = true
        retryInput = input
        status(cortex = "Verificando disponibilidade")
        val model = cortex.configuration() ?: run {
            status(cortex = "Indisponível", notice = "Cortex indisponível. Consulte o diagnóstico. Tarefas e bateria continuam disponíveis.", retry = true)
            return
        }
        val context = OrionChatContext.build(input.text, state.value.turns.dropLast(1), model) ?: run {
            status(cortex = "Contexto excedido", notice = "Pedido não cabe no orçamento seguro de contexto.", retry = true); return
        }
        status(cortex = "Gerando")
        val gate = AtomicBoolean(true); accepting = gate
        val buffer = StringBuilder()
        val terminal = try {
            cortex.generate(model, context.prompt, input.correlationId.toString()) { delta ->
                synchronized(buffer) {
                    if (gate.get()) {
                        require(delta.length.toLong() + buffer.length <= OrionStructuredResponse.MAX_OUTPUT_BYTES) { "output bound" }
                        buffer.append(delta)
                        require(buffer.toString().toByteArray(Charsets.UTF_8).size <= OrionStructuredResponse.MAX_OUTPUT_BYTES) { "output bound" }
                        val preview = OrionStructuredResponse.messagePreview(buffer.toString()).orEmpty()
                        patch { OrionChatState(it.turns, preview, it.busy, it.cortex, it.notice, it.pending, false) }
                    }
                }
            }
        } finally { synchronized(buffer) { gate.set(false) } }
        currentCoroutineContext().ensureActive()
        if (terminal != OrionChatTerminal.SUCCEEDED) {
            status(cortex = when (terminal) {
                OrionChatTerminal.PROCESS_DIED -> "Cortex interrompido"
                OrionChatTerminal.TIMED_OUT -> "Tempo esgotado"
                OrionChatTerminal.CANCELLED -> "Cancelado"
                else -> "Indisponível"
            }, notice = "Resposta descartada. Nenhuma ferramenta foi executada. Você pode tentar uma nova inferência.", retry = true)
            return
        }
        val response = OrionStructuredResponse.parse(synchronized(buffer) { buffer.toString() }, input.inputId, input.correlationId)
        if (response == null) {
            status(cortex = "Resposta inválida", notice = "Saída inválida descartada. Nenhuma ferramenta foi executada.", retry = true); return
        }
        retryInput = null
        status(cortex = "Sob demanda")
        when (response) {
            is OrionChatResponse.Message -> append(false, response.text)
            is OrionChatResponse.CreateProposal -> append(false, "Sugestão de tarefa: " + response.command.title, response.command)
            OrionChatResponse.TaskList -> showTasks()
            OrionChatResponse.Battery -> showBattery()
        }
    }
    override fun close() {
        closed = true; accepting?.set(false); work?.cancel(); retryInput = null
        mutable.value = OrionChatState()
    }
}

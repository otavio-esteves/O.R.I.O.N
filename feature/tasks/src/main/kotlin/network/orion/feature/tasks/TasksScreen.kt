package network.orion.feature.tasks

import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.tasks.*

/** Sensitive drafts stay in transient memory; only submitted requests enter encrypted Core storage. */
@Composable
fun TasksScreen(gateway: TaskGateway, recoveryVersion: Instant? = null) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var title by remember { mutableStateOf("") }
    var submission by remember { mutableStateOf<CreateTask?>(null) }
    var rows by remember { mutableStateOf<List<TaskSummary>>(emptyList()) }
    var next by remember { mutableStateOf<UUID?>(null) }
    var pending by remember { mutableStateOf<List<TaskConfirmation>>(emptyList()) }
    var activeWork by remember { mutableIntStateOf(0) }
    val busy = activeWork > 0
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        val page = gateway.list(GetTasks())
        rows = page?.items ?: emptyList(); next = page?.next
        pending = gateway.pending()
        if (page == null) message = "Tarefas indisponíveis. Consulte o diagnóstico."
        else if (message == "Tarefas indisponíveis. Consulte o diagnóstico.") message = null
    }
    fun run(block: suspend () -> Unit) {
        if (activeWork > 0) return
        activeWork++ // Set synchronously before launch: rapid taps cannot mint duplicate requests.
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Não foi possível concluir. Atualize para consultar o estado do pedido." }
            finally { activeWork-- }
        }
    }
    LaunchedEffect(gateway, recoveryVersion) {
        activeWork++
        try { gateway.processPending(); reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Tarefas indisponíveis. Consulte o diagnóstico." }
        finally { activeWork-- }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().testTag("orion_tasks").semantics {
        stateDescription = if (busy) "Carregando tarefas" else "Tarefas atualizadas"
    }) {
        item { Text("Tarefas", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(value = title, onValueChange = { if (activeWork == 0) { title = it; submission = null } },
                label = { Text("Título da tarefa") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && title.isNotBlank(), onClick = {
                focus.clearFocus()
                keyboard?.hide()
                val command = submission ?: CreateTask(UUID.randomUUID(), title, UUID.randomUUID(), UUID.randomUUID()).also { submission = it }
                run {
                    when (gateway.create(command)) {
                        CreateTaskResult.QUEUED -> { title = ""; submission = null; message = "Pedido recebido. Confirme a criação abaixo." }
                        CreateTaskResult.REJECTED -> message = "Revise o título ou atualize o estado do pedido."
                        CreateTaskResult.UNAVAILABLE -> message = "Pedido não recebido. Tente novamente quando o app estiver pronto."
                    }
                    reload()
                }
            }) { Text("Preparar criação") }
        }
        item { message?.let { Text(it) } }
        item {
            TextButton(enabled = !busy, onClick = { run { gateway.processPending(); reload() } }) { Text("Atualizar tarefas e pedidos") }
        }
        items(pending, key = { "pending/${it.actionId}" }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_task_confirmation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val verb = when (action.operation) { TaskOperation.CREATE -> "Criar"; TaskOperation.COMPLETE -> "Concluir"; TaskOperation.CANCEL -> "Cancelar" }
                    Text("$verb esta tarefa?")
                    Text(action.title ?: "Conteúdo indisponível ou pedido desatualizado. Descarte este pedido e tente novamente.")
                    Column {
                        Button(enabled = !busy && action.title != null, onClick = { run {
                            message = when (gateway.confirm(action.actionId, action.version)) {
                                TaskOperationOutcome.APPLIED -> "Alteração concluída."
                                TaskOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                                TaskOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize e confirme novamente."
                                TaskOperationOutcome.UNAVAILABLE -> "Confirmação não recebida. Tente novamente."
                            }
                            reload()
                        } }) { Text(when (action.operation) { TaskOperation.CREATE -> "Confirmar criação"; TaskOperation.COMPLETE -> "Confirmar conclusão"; TaskOperation.CANCEL -> "Confirmar cancelamento" }) }
                        TextButton(enabled = !busy, onClick = { run {
                            message = when (gateway.dismiss(action.actionId, action.version)) {
                                TaskOperationOutcome.APPLIED -> "Pedido descartado."
                                TaskOperationOutcome.QUEUED -> "Descarte recebido. Atualize para consultar o resultado."
                                else -> "Não foi possível descartar. Atualize o pedido."
                            }; reload()
                        } }) { Text("Descartar pedido") }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("Nenhuma tarefa para exibir.") }
        items(rows, key = { "task/${it.taskId}" }) { task ->
            Card(Modifier.fillMaxWidth().testTag("orion_task_row")) {
                Column(Modifier.padding(16.dp)) {
                    Text(task.title ?: "Conteúdo da tarefa indisponível")
                    Text(when (task.state) { TaskState.OPEN -> "Aberta"; TaskState.COMPLETED -> "Concluída"; TaskState.CANCELLED -> "Cancelada" })
                    if (task.state == TaskState.OPEN && task.contentState == TaskContentState.READABLE) {
                        Row {
                            for (operation in TaskStatusOperation.entries) {
                                TextButton(enabled = !busy, onClick = { run {
                                    val key = UUID.randomUUID(); val trace = UUID.randomUUID()
                                    val command = if (operation == TaskStatusOperation.COMPLETE) CompleteTask(task.taskId, task.version, key, trace)
                                        else CancelTask(task.taskId, task.version, key, trace)
                                    message = when (gateway.change(command)) {
                                        TaskStatusResult.QUEUED -> "Pedido recebido. Confirme abaixo."
                                        TaskStatusResult.REJECTED -> "A tarefa mudou. Atualize e tente novamente."
                                        TaskStatusResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente."
                                    }; reload()
                                } }) { Text(if (operation == TaskStatusOperation.COMPLETE) "Concluir" else "Cancelar tarefa") }
                            }
                        }
                    }
                }
            }
        }
        if (next != null) item {
            TextButton(enabled = !busy, onClick = { run {
                val page = gateway.list(GetTasks(next))
                if (page == null) message = "Não foi possível carregar mais tarefas."
                else { rows = rows + page.items; next = page.next }
            } }) { Text("Carregar mais") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

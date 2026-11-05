package network.orion.feature.timers

import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
import network.orion.core.commands.timers.*
import network.orion.core.orchestrator.timers.*
import network.orion.core.queries.timers.*

/** Sensitive drafts stay in transient memory; only submitted requests enter encrypted Core storage. */
@Composable
fun TimersScreen(gateway: TimerGateway, recoveryVersion: Instant? = null) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var title by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("300") }
    var submission by remember { mutableStateOf<CreateTimer?>(null) }
    var rows by remember { mutableStateOf<List<TimerSummary>>(emptyList()) }
    var next by remember { mutableStateOf<UUID?>(null) }
    var pending by remember { mutableStateOf<List<TimerConfirmation>>(emptyList()) }
    var activeWork by remember { mutableIntStateOf(0) }
    val busy = activeWork > 0
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        val page = gateway.list(GetTimers())
        rows = page?.items ?: emptyList(); next = page?.next
        pending = gateway.pending()
        if (page == null) message = "Timers indisponíveis. Consulte o diagnóstico."
        else if (message == "Timers indisponíveis. Consulte o diagnóstico.") message = null
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
        catch (_: Exception) { message = "Timers indisponíveis. Consulte o diagnóstico." }
        finally { activeWork-- }
    }
    LaunchedEffect(gateway, recoveryVersion, busy) {
        while (!busy) { delay(1000); if (activeWork == 0) reload() }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().testTag("orion_timers").semantics {
        stateDescription = if (busy) "Carregando timers" else "Timers atualizadas"
    }) {
        item {
            Text("Timers", style = MaterialTheme.typography.headlineSmall)
            Text("Contagem local. Alertas em segundo plano ainda não estão disponíveis.")
        }
        item {
            OutlinedTextField(value = title, onValueChange = { if (activeWork == 0) { title = it; submission = null } },
                label = { Text("Título da timer") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = seconds, onValueChange = { if (!busy) { seconds = it; submission = null } },
                label = { Text("Duração em segundos") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && title.isNotBlank() && seconds.toLongOrNull()?.let { it in 1..31_536_000 } == true, onClick = {
                focus.clearFocus()
                keyboard?.hide()
                val command = submission ?: CreateTimer(UUID.randomUUID(), title, checkNotNull(seconds.toLongOrNull()) * 1000, UUID.randomUUID(), UUID.randomUUID()).also { submission = it }
                run {
                    when (gateway.create(command)) {
                        CreateTimerResult.QUEUED -> { title = ""; submission = null; message = "Pedido recebido. Confirme a criação abaixo." }
                        CreateTimerResult.REJECTED -> message = "Revise o título ou atualize o estado do pedido."
                        CreateTimerResult.UNAVAILABLE -> message = "Pedido não recebido. Tente novamente quando o app estiver pronto."
                    }
                    reload()
                }
            }) { Text("Preparar criação") }
        }
        item { message?.let { Text(it) } }
        item {
            TextButton(enabled = !busy, onClick = { run { gateway.processPending(); reload() } }) { Text("Atualizar timers e pedidos") }
        }
        items(pending, key = { "pending/${it.actionId}" }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_timer_confirmation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val verb = when (action.operation) { TimerOperation.CREATE -> "Criar"; TimerOperation.CANCEL -> "Cancelar" }
                    Text("$verb esta timer?")
                    action.durationMillis?.let { Text("Duração: ${it / 1000} segundos") }
                    Text(action.title ?: "Conteúdo indisponível ou pedido desatualizado. Descarte este pedido e tente novamente.")
                    Column {
                        Button(enabled = !busy && action.title != null, onClick = { run {
                            message = when (gateway.confirm(action.actionId, action.version)) {
                                TimerOperationOutcome.APPLIED -> "Alteração concluída."
                                TimerOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                                TimerOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize e confirme novamente."
                                TimerOperationOutcome.UNAVAILABLE -> "Confirmação não recebida. Tente novamente."
                            }
                            reload()
                        } }) { Text(when (action.operation) { TimerOperation.CREATE -> "Confirmar criação"; TimerOperation.CANCEL -> "Confirmar cancelamento" }) }
                        TextButton(enabled = !busy, onClick = { run {
                            message = when (gateway.dismiss(action.actionId, action.version)) {
                                TimerOperationOutcome.APPLIED -> "Pedido descartado."
                                TimerOperationOutcome.QUEUED -> "Descarte recebido. Atualize para consultar o resultado."
                                else -> "Não foi possível descartar. Atualize o pedido."
                            }; reload()
                        } }) { Text("Descartar pedido") }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("Nenhuma timer para exibir.") }
        items(rows, key = { "timer/${it.timerId}" }) { timer ->
            Card(Modifier.fillMaxWidth().testTag("orion_timer_row")) {
                Column(Modifier.padding(16.dp)) {
                    Text(timer.title ?: "Conteúdo da timer indisponível")
                    Text(when (timer.state) { TimerState.ACTIVE -> "Em andamento"; TimerState.ELAPSED -> "Tempo esgotado"; TimerState.CANCELLED -> "Cancelado" })
                    Text(timer.remainingMillis?.let { "Restante: ${(it + 999) / 1000} segundos" } ?: "Relógio indisponível")
                    if (timer.clockBasis == TimerClockBasis.WALL_FALLBACK) Text("Estimativa após reinício baseada no relógio civil.")
                    if (timer.state != TimerState.CANCELLED && timer.contentState == TimerContentState.READABLE) {
                        Row {
                            for (operation in TimerStatusOperation.entries) {
                                TextButton(enabled = !busy, onClick = { run {
                                    val key = UUID.randomUUID(); val trace = UUID.randomUUID()
                                    val command = CancelTimer(timer.timerId, timer.version, key, trace)
                                    message = when (gateway.change(command)) {
                                        TimerStatusResult.QUEUED -> "Pedido recebido. Confirme abaixo."
                                        TimerStatusResult.REJECTED -> "A timer mudou. Atualize e tente novamente."
                                        TimerStatusResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente."
                                    }; reload()
                                } }) { Text("Cancelar timer") }
                            }
                        }
                    }
                }
            }
        }
        if (next != null) item {
            TextButton(enabled = !busy, onClick = { run {
                val page = gateway.list(GetTimers(next))
                if (page == null) message = "Não foi possível carregar mais timers."
                else { rows = rows + page.items; next = page.next }
            } }) { Text("Carregar mais") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

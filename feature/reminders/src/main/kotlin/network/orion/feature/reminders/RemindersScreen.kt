package network.orion.feature.reminders

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
import network.orion.core.commands.reminders.*
import network.orion.core.orchestrator.reminders.*
import network.orion.core.queries.reminders.*

/** Sensitive drafts stay in transient memory; only submitted requests enter encrypted Core storage. */
@Composable
fun RemindersScreen(gateway: ReminderGateway, recoveryVersion: Instant? = null) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("10") }
    var submission by remember { mutableStateOf<CreateReminder?>(null) }
    var rows by remember { mutableStateOf<List<ReminderSummary>>(emptyList()) }
    var next by remember { mutableStateOf<UUID?>(null) }
    var pending by remember { mutableStateOf<List<ReminderConfirmation>>(emptyList()) }
    var activeWork by remember { mutableIntStateOf(0) }
    val busy = activeWork > 0
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        val page = gateway.list(GetReminders())
        rows = page?.items ?: emptyList(); next = page?.next
        pending = gateway.pending()
        if (page == null) message = "Lembretes indisponíveis. Consulte o diagnóstico."
        else if (message == "Lembretes indisponíveis. Consulte o diagnóstico.") message = null
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
        catch (_: Exception) { message = "Lembretes indisponíveis. Consulte o diagnóstico." }
        finally { activeWork-- }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().testTag("orion_reminders").semantics {
        stateDescription = if (busy) "Carregando lembretes" else "Lembretes atualizadas"
    }) {
        item {
            Text("Lembretes", style = MaterialTheme.typography.headlineSmall)
            Text("Lembretes ficam salvos. Alertas automáticos ainda não estão disponíveis.")
        }
        item {
            OutlinedTextField(value = title, onValueChange = { if (activeWork == 0) { title = it; submission = null } },
                label = { Text("Título do lembrete") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = minutes, onValueChange = { if (!busy) { minutes = it; submission = null } },
                label = { Text("Daqui a quantos minutos? (1–525600)") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && title.isNotBlank() && (minutes.toLongOrNull() ?: 0) in 1L..525_600L, onClick = {
                focus.clearFocus()
                keyboard?.hide()
                val command = submission ?: gateway.prepareCreate(UUID.randomUUID(), title, minutes.toLongOrNull() ?: 0,
                    UUID.randomUUID(), UUID.randomUUID())?.also { submission = it }
                if (command == null) { message = "Revise o intervalo informado."; return@Button }
                run {
                    when (gateway.create(command)) {
                        CreateReminderResult.QUEUED -> { title = ""; submission = null; message = "Pedido recebido. Confirme a criação abaixo." }
                        CreateReminderResult.REJECTED -> message = "Revise o título ou atualize o estado do pedido."
                        CreateReminderResult.UNAVAILABLE -> message = "Pedido não recebido. Tente novamente quando o app estiver pronto."
                    }
                    reload()
                }
            }) { Text("Preparar criação") }
        }
        item { message?.let { Text(it) } }
        item {
            TextButton(enabled = !busy, onClick = { run { gateway.processPending(); reload() } }) { Text("Atualizar lembretes e pedidos") }
        }
        items(pending, key = { "pending/${it.actionId}" }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_reminder_confirmation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val verb = when (action.operation) { ReminderOperation.CREATE -> "Criar"; ReminderOperation.SNOOZE -> "Adiar"; ReminderOperation.CANCEL -> "Cancelar" }
                    Text("$verb este lembrete?")
                    Text(action.title ?: "Conteúdo indisponível ou pedido desatualizado. Descarte este pedido e tente novamente.")
                    Column {
                        Button(enabled = !busy && action.title != null, onClick = { run {
                            message = when (gateway.confirm(action.actionId, action.version)) {
                                ReminderOperationOutcome.APPLIED -> "Alteração concluída."
                                ReminderOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                                ReminderOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize e confirme novamente."
                                ReminderOperationOutcome.UNAVAILABLE -> "Confirmação não recebida. Tente novamente."
                            }
                            reload()
                        } }) { Text(when (action.operation) { ReminderOperation.CREATE -> "Confirmar criação"; ReminderOperation.SNOOZE -> "Confirmar adiamento"; ReminderOperation.CANCEL -> "Confirmar cancelamento" }) }
                        TextButton(enabled = !busy, onClick = { run {
                            message = when (gateway.dismiss(action.actionId, action.version)) {
                                ReminderOperationOutcome.APPLIED -> "Pedido descartado."
                                ReminderOperationOutcome.QUEUED -> "Descarte recebido. Atualize para consultar o resultado."
                                else -> "Não foi possível descartar. Atualize o pedido."
                            }; reload()
                        } }) { Text("Descartar pedido") }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("Nenhum lembrete para exibir.") }
        items(rows, key = { "reminder/${it.reminderId}" }) { reminder ->
            Card(Modifier.fillMaxWidth().testTag("orion_reminder_row")) {
                Column(Modifier.padding(16.dp)) {
                    Text(reminder.title ?: "Conteúdo do lembrete indisponível")
                    Text(try {
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                            .withZone(java.time.ZoneId.of(reminder.zoneId)).format(reminder.scheduledAt) + " (${reminder.zoneId})"
                    } catch (_: java.time.DateTimeException) { "Horário indisponível" })
                    Text("Alertas: indisponíveis nesta versão")
                    Text(when (reminder.state) { ReminderState.PENDING -> "Pendente"; ReminderState.FIRED -> "Disparado"; ReminderState.CANCELLED -> "Cancelado" })
                    if (reminder.state == ReminderState.PENDING && reminder.contentState == ReminderContentState.READABLE) {
                        Row {
                            for (operation in ReminderStatusOperation.entries) {
                                TextButton(enabled = !busy, onClick = { run {
                                    val key = UUID.randomUUID(); val trace = UUID.randomUUID()
                                    val command = if (operation == ReminderStatusOperation.SNOOZE) gateway.prepareSnooze(reminder.reminderId, reminder.version, reminder.scheduledAt, 10, key, trace)
                                        else CancelReminder(reminder.reminderId, reminder.version, key, trace)
                                    message = when (if (command == null) ReminderStatusResult.REJECTED else gateway.change(command)) {
                                        ReminderStatusResult.QUEUED -> "Pedido recebido. Confirme abaixo."
                                        ReminderStatusResult.REJECTED -> "O lembrete mudou. Atualize e tente novamente."
                                        ReminderStatusResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente."
                                    }; reload()
                                } }) { Text(if (operation == ReminderStatusOperation.SNOOZE) "Adiar 10 min" else "Cancelar lembrete") }
                            }
                        }
                    }
                }
            }
        }
        if (next != null) item {
            TextButton(enabled = !busy, onClick = { run {
                val page = gateway.list(GetReminders(next))
                if (page == null) message = "Não foi possível carregar mais lembretes."
                else { rows = rows + page.items; next = page.next }
            } }) { Text("Carregar mais") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

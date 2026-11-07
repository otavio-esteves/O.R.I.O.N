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
fun RemindersScreen(gateway: ReminderGateway, recoveryVersion: Instant? = null,
    deliveryGateway: ReminderDeliveryGateway? = null, onRequestNotificationPermission: (() -> Unit)? = null,
    onRequestExactAccess: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var deliveryPreview by remember { mutableStateOf<ReminderDeliveryPreview?>(null) }
    var deliveryTitle by remember { mutableStateOf<String?>(null) }
    var seriesPreview by remember { mutableStateOf<ReminderSeriesPreview?>(null) }
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
        stateDescription = if (busy) "Carregando lembretes" else "Lembretes atualizados"
    }) {
        item {
            Text("Lembretes", style = MaterialTheme.typography.headlineSmall)
            Text(if (deliveryGateway == null) "Lembretes ficam salvos. Alertas automáticos ainda não estão disponíveis."
                else "Lembretes ficam salvos. Ative e confirme um alerta único para receber uma notificação.")
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
        if (deliveryGateway != null) item {
            Column {
                onRequestNotificationPermission?.let { TextButton(onClick = it) { Text("Permitir notificações") } }
                onRequestExactAccess?.let { TextButton(onClick = it) { Text("Permitir alertas exatos") } }
            }
        }
        seriesPreview?.let { preview -> item {
            Card(Modifier.fillMaxWidth().testTag("orion_reminder_series_confirmation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confirmar estes ${preview.occurrences.size} alertas?")
                    Text(deliveryTitle ?: "Lembrete selecionado")
                    Text("Cada notificação mostrará: Você tem um lembrete.")
                    Text("Solicitar horário exato quando permitido; caso contrário, usar entrega aproximada. " +
                        "Cada alerta pode chegar até 24 horas depois após uma interrupção. " +
                        "Cancelar ou adiar interrompe os próximos alertas desta série.")
                    for (occurrence in preview.occurrences) {
                        Text(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm XXX '['VV']'")
                            .withZone(preview.rule.zoneId).format(occurrence.delivery.scheduledAt))
                    }
                    Button(enabled = !busy, onClick = { run {
                        message = when (deliveryGateway?.confirmSeries(preview)) {
                            ReminderDeliveryOutcome.ENABLED -> { seriesPreview = null; "Série de alertas autorizada." }
                            ReminderDeliveryOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                            else -> "A série mudou ou não pôde ser ativada. Atualize e revise os horários."
                        }
                        reload()
                    } }) { Text("Confirmar todos os horários") }
                    TextButton(onClick = { seriesPreview = null }) { Text("Voltar") }
                }
            }
        } }
        deliveryPreview?.let { preview -> item {
            Card(Modifier.fillMaxWidth().testTag("orion_reminder_delivery_confirmation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ativar um alerta para este lembrete?")
                    Text(deliveryTitle ?: "Lembrete selecionado")
                    Text("Horário: ${preview.scheduledAt}")
                    Text("A notificação mostrará: Você tem um lembrete.")
                    Text("Solicitar horário exato quando permitido; caso contrário, usar entrega aproximada. " +
                        "Após interrupção, este único alerta pode chegar até 24 horas depois. Adiar exige nova ativação.")
                    Button(enabled = !busy, onClick = { run {
                        message = when (deliveryGateway?.confirm(preview)) {
                            ReminderDeliveryOutcome.ENABLED -> { deliveryPreview = null; "Alerta autorizado. Consulte a precisão após atualizar." }
                            ReminderDeliveryOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                            else -> "Não foi possível ativar o alerta. Atualize e confirme novamente."
                        }
                        reload()
                    } }) { Text("Confirmar alerta único") }
                    TextButton(onClick = { deliveryPreview = null; deliveryTitle = null }) { Text("Voltar") }
                }
            }
        } }

        item {
            TextButton(enabled = !busy, onClick = { run { deliveryGateway?.processPending(); gateway.processPending(); reload() } }) { Text("Atualizar lembretes e pedidos") }
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
                    Text(if (deliveryGateway == null) "Alertas: indisponíveis nesta versão" else when (reminder.alertState) {
                        ReminderAlertState.DISABLED -> "Alerta: não ativado"
                        ReminderAlertState.AWAITING_SCHEDULE -> "Alerta autorizado: aguardando agendamento"
                        ReminderAlertState.SCHEDULED -> if (reminder.precision == "EXACT") "Alerta: exato" else "Alerta: aproximado"
                        ReminderAlertState.DELIVERED -> "Alerta entregue"
                        ReminderAlertState.UNCERTAIN -> "Entrega incerta: revisão necessária, sem repetição automática"
                        ReminderAlertState.EXPIRED -> "Alerta indisponível ou expirado"
                    })
                    if (deliveryGateway != null && reminder.state == ReminderState.PENDING &&
                        reminder.contentState == ReminderContentState.READABLE && reminder.alertState == ReminderAlertState.DISABLED) {
                        TextButton(enabled = !busy, onClick = { run {
                            when (val prepared = deliveryGateway.prepare(reminder.reminderId, reminder.version)) {
                                is ReminderDeliveryPreparation.Ready -> { deliveryPreview = prepared.preview; deliveryTitle = reminder.title; seriesPreview = null }
                                else -> message = "Não foi possível preparar o alerta. Verifique as notificações e atualize o lembrete."
                            }
                        } }) { Text("Ativar alerta único") }
                        for ((frequency, count, label) in listOf(
                            Triple(network.orion.core.model.OrionReminderRecurrenceFrequency.DAILY, 7, "Repetir diariamente: 7 alertas"),
                            Triple(network.orion.core.model.OrionReminderRecurrenceFrequency.WEEKLY, 4, "Repetir semanalmente: 4 alertas"))) {
                            TextButton(enabled = !busy, onClick = { run {
                                val rule = network.orion.core.model.OrionReminderRecurrenceRule(frequency, 1, count,
                                    reminder.scheduledAt.atZone(java.time.ZoneId.of(reminder.zoneId)).toLocalDateTime(),
                                    java.time.ZoneId.of(reminder.zoneId))
                                when (val prepared = deliveryGateway.prepareSeries(reminder.reminderId, reminder.version, rule)) {
                                    is ReminderSeriesPreparation.Ready -> {
                                        seriesPreview = prepared.preview; deliveryTitle = reminder.title; deliveryPreview = null
                                    }
                                    else -> message = "Não foi possível preparar a série. Verifique notificações e horários."
                                }
                            } }) { Text(label) }
                        }
                    }
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

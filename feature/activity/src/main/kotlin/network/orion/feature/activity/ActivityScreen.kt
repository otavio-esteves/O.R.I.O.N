package network.orion.feature.activity

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import network.orion.core.queries.activity.*

@Composable
fun ActivityScreen(gateway: ActivityGateway, recoveryVersion: Instant? = null) {
    var entries by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var next by remember { mutableStateOf<ActivityCursor?>(null) }
    var busy by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        busy = true
        try {
            val page = gateway.get(GetActivity(if (more) next else null))
            unavailable = page == null
            if (page != null) { entries = if (more) entries + page.items else page.items; next = page.next }
            else if (!more) { entries = emptyList(); next = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        finally { busy = false }
    }
    LaunchedEffect(gateway, recoveryVersion) { load(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("orion_activity"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Atividade", style = MaterialTheme.typography.headlineSmall) }
        item {
            Button(onClick = { if (!busy) { busy = true; scope.launch { load(false) } } }, enabled = !busy) { Text("Atualizar atividade") }
            Text("Histórico local de pedidos e alterações.", style = MaterialTheme.typography.bodySmall)
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (unavailable) item { Text("Histórico indisponível. Tente atualizar quando o Core estiver pronto.") }
        if (!busy && !unavailable && entries.isEmpty()) item { Text("Nenhuma atividade registrada.") }
        itemsIndexed(entries) { _, entry ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label(entry.code), style = MaterialTheme.typography.titleMedium)
                    Text(status(entry.status))
                    Text(entry.occurredAt.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (next != null) item {
            Button(onClick = { if (!busy) { busy = true; scope.launch { load(true) } } }, enabled = !busy) { Text("Carregar mais atividade") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
private fun label(code: String) = when (code) {
    "TASK_CREATED" -> "Tarefa criada"; "TASK_COMPLETED" -> "Tarefa concluída"; "TASK_CANCELLED" -> "Tarefa cancelada"
    "REMINDER_CREATED" -> "Lembrete criado"; "REMINDER_CANCELLED" -> "Lembrete cancelado"
    "REMINDER_SNOOZED", "REMINDER_UPDATED" -> "Lembrete alterado"
    "TIMER_CREATED" -> "Timer criado"; "TIMER_CANCELLED" -> "Timer cancelado"
    "MEMORY_CREATED" -> "Memória criada"; "MEMORY_UPDATED" -> "Memória alterada"; "MEMORY_DELETED" -> "Memória removida"
    "task.create" -> "Pedido de criação de tarefa"; "task.complete" -> "Pedido de conclusão de tarefa"
    "task.cancel" -> "Pedido de cancelamento de tarefa"; "reminder.create" -> "Pedido de criação de lembrete"
    "reminder.cancel" -> "Pedido de cancelamento de lembrete"; "reminder.snooze" -> "Pedido de adiamento de lembrete"
    "timer.create" -> "Pedido de criação de timer"; "timer.cancel" -> "Pedido de cancelamento de timer"
    "memory.create" -> "Pedido para guardar memória"; "memory.delete" -> "Pedido para esquecer memória"
    "notification.post" -> "Pedido de notificação"; "NOTIFICATION_POSTED" -> "Notificação publicada"
    else -> "Registro do Core"
}
private fun status(value: String) = when (value) {
    "PENDING" -> "Registrado"; "NEEDS_CONFIRMATION" -> "Aguardando confirmação"; "CONFIRMED" -> "Confirmado"
    "RUNNING" -> "Em execução"; "SUCCEEDED" -> "Concluído"; "FAILED" -> "Falhou"; "CANCELLED" -> "Cancelado"
    "UNKNOWN" -> "Resultado desconhecido"; "MANUAL_REVIEW" -> "Precisa de revisão"
    "DELIVERED" -> "Publicado"; "DEAD_LETTER" -> "Publicação precisa de revisão"; else -> "Estado indisponível"
}

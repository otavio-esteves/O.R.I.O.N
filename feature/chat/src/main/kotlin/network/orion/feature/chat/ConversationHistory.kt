package network.orion.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.orion.core.commands.memories.ForgetConversation
import network.orion.core.commands.memories.MemoryStatusResult
import network.orion.core.orchestrator.chat.OrionChatController
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.ConversationArchive
import network.orion.core.queries.memories.GetArchivedConversation

@Composable
internal fun ConversationHistory(controller: OrionChatController, gateway: MemoryGateway,
    chatBusy: Boolean, hasTurns: Boolean) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var archives by remember { mutableStateOf<List<ConversationArchive>>(emptyList()) }
    var pending by remember { mutableStateOf<List<MemoryConfirmation>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val current = gateway.archives()
        if (current == null) notice = "Histórico indisponível."
        else {
            archives = current
            controller.retainAvailableArchives(current.mapTo(mutableSetOf()) { it.id })
        }
        pending = gateway.pending().filter { it.operation.isConversationOperation() }
    }

    fun run(block: suspend () -> Unit) {
        if (busy || chatBusy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "Não foi possível concluir o pedido de histórico." }
            finally { busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy && !chatBusy, onClick = {
            open = !open
            if (open) run { gateway.processPending(); reload() }
        }) { Text(if (open) "Fechar histórico local" else "Histórico local") }
        if (open) {
            Text("Salve as mensagens recentes desta conversa no dispositivo. Cada gravação exige confirmação.")
            TextButton(enabled = hasTurns && !busy && !chatBusy, onClick = {
                val command = controller.archiveCurrent()
                if (command == null) notice = "Esta conversa está vazia ou acima do limite de gravação."
                else run {
                    notice = if (gateway.change(command) == MemoryStatusResult.QUEUED) "Confirme a gravação abaixo."
                    else "Pedido de gravação indisponível."
                    reload()
                }
            }) { Text("Preparar gravação") }
            notice?.let { Text(it) }
            for (archive in archives) {
                val date = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(archive.createdAt)
                Text("Conversa de $date")
                Row {
                    TextButton(enabled = !busy && !chatBusy, onClick = { run {
                        val transcript = gateway.archived(GetArchivedConversation(archive.id, archive.version))
                        if (transcript == null) notice = "Conversa indisponível ou alterada."
                        else controller.showArchive(archive.id, transcript)
                    } }) { Text("Ver conversa") }
                    TextButton(enabled = !busy && !chatBusy, onClick = { run {
                        val result = gateway.change(ForgetConversation(archive.id, archive.version,
                            UUID.randomUUID(), UUID.randomUUID()))
                        notice = if (result == MemoryStatusResult.QUEUED) "Confirme o esquecimento abaixo."
                        else "Pedido de esquecimento indisponível."
                        reload()
                    } }) { Text("Esquecer conversa") }
                }
            }
            for (action in pending) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (action.operation == MemoryOperation.ARCHIVE_CONVERSATION) "Guardar esta conversa?"
                            else "Esquecer esta conversa?")
                        Text(action.content ?: "Conteúdo indisponível. Atualize o pedido.")
                        Row {
                            TextButton(enabled = action.content != null && !busy && !chatBusy, onClick = { run {
                                notice = when (gateway.confirm(action.actionId, action.version)) {
                                    MemoryOperationOutcome.APPLIED -> "Alteração concluída."
                                    MemoryOperationOutcome.QUEUED -> "Confirmação recebida. Atualize o histórico."
                                    else -> "Confirmação indisponível ou desatualizada."
                                }
                                reload()
                            } }) { Text("Confirmar") }
                            TextButton(enabled = !busy && !chatBusy, onClick = { run {
                                gateway.dismiss(action.actionId, action.version)
                                reload()
                            } }) { Text("Descartar") }
                        }
                    }
                }
            }
        }
    }
}

package network.orion.feature.chat

import java.time.Instant
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import network.orion.core.orchestrator.chat.OrionChatController
import network.orion.core.orchestrator.tasks.TaskOperation

/** No saveable input/history: session content stays in transient Activity memory. */
@Composable
fun ChatScreen(controller: OrionChatController, recoveryVersion: Instant? = null) {
    val state by controller.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(controller, recoveryVersion) { controller.refresh() }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("orion_chat").semantics { stateDescription = if (state.busy) "Chat processando" else "Chat disponível" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Chat local", style = MaterialTheme.typography.headlineSmall)
            Text("Cortex: " + state.cortex, modifier = Modifier.testTag("orion_chat_cortex"))
            Text("Experimente: minhas tarefas · bateria · criar tarefa: comprar pão")
        }
        items(state.turns, key = { it.id }) { turn ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (turn.user) "Você" else "O.R.I.O.N.", style = MaterialTheme.typography.labelMedium)
                    Text(turn.text)
                    if (turn.proposal != null) {
                        Text("Sugestão do modelo. Aguarda sua revisão e a confirmação do pedido.")
                        Button(onClick = { controller.prepare(turn.id) }, enabled = !state.busy) { Text("Preparar tarefa sugerida") }
                    }
                }
            }
        }
        if (state.draft.isNotEmpty()) item { Text(state.draft, Modifier.testTag("orion_chat_stream")) }
        item { state.notice?.let { Text(it) } }
        item {
            OutlinedTextField(input, onValueChange = { if (it.length <= 4096) input = it }, label = { Text("Mensagem") },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("orion_chat_input"), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val submitted = input; input = ""; focus.clearFocus(); keyboard?.hide(); controller.send(submitted) },
                    enabled = !state.busy && input.isNotBlank()) { Text("Enviar") }
                if (state.busy && state.cortex in setOf("Gerando", "Verificando disponibilidade")) TextButton(onClick = controller::cancel) { Text("Cancelar") }
                if (state.canRetry && !state.busy) TextButton(onClick = controller::retryInference) { Text("Tentar inferência novamente") }
            }
            TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Atualizar pedidos") }
        }
        items(state.pending, key = { "pending/" + it.actionId }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_chat_confirmation")) {
                Column(Modifier.padding(12.dp)) {
                    Text(when (action.operation) { TaskOperation.CREATE -> "Criar esta tarefa?"; TaskOperation.COMPLETE -> "Concluir esta tarefa?"; TaskOperation.CANCEL -> "Cancelar esta tarefa?" })
                    Text(action.title ?: "Conteúdo indisponível. Atualize o pedido.")
                    Button(onClick = { controller.confirm(action.actionId, action.version) }, enabled = !state.busy && action.title != null) {
                        Text(when (action.operation) { TaskOperation.CREATE -> "Confirmar criação"; TaskOperation.COMPLETE -> "Confirmar conclusão"; TaskOperation.CANCEL -> "Confirmar cancelamento" })
                    }
                    TextButton(onClick = { controller.dismiss(action.actionId, action.version) }, enabled = !state.busy) { Text("Descartar pedido") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

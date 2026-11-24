package network.orion.feature.chat

import java.time.Instant
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import network.orion.core.orchestrator.chat.*
import network.orion.core.orchestrator.memories.MemoryGateway
import network.orion.core.orchestrator.tasks.TaskOperation

/** The current draft and live session belong to the Activity. Saved conversations require a separate action. */
@Composable
fun ChatScreen(controller: OrionChatController, recoveryVersion: Instant? = null, memory: MemoryGateway? = null,
    modelSetup: (@Composable () -> Unit)? = null) {
    val state by controller.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var oversizedEditRejected by remember { mutableStateOf(false) }
    var editorVersion by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var revealResult by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    LaunchedEffect(controller, recoveryVersion) { controller.refresh() }
    LaunchedEffect(revealResult, state.busy, state.turns.lastOrNull()?.id, state.notice) {
        if (revealResult && !state.busy) {
            val turnsStart = if (modelSetup == null) 1 else 2
            val latest = if (state.notice != null) state.turns.size else state.turns.lastIndex.coerceAtLeast(0)
            // The chat may be recomposed during measurement. Reveal the result on the next layout pass.
            listState.requestScrollToItem(turnsStart + latest)
            revealResult = false
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("orion_chat")
            .semantics { stateDescription = if (state.busy) "Chat processando" else "Chat disponível" },
        state = listState,
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chat local", style = typography.headlineSmall)
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Text(
                        "Cortex: " + state.cortex, modifier = Modifier.testTag("orion_chat_cortex")
                            .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = typography.labelMedium, color = colors.onSurfaceVariant,
                    )
                }
                if (state.turns.isEmpty()) Text(
                    "Experimente “minhas tarefas”, “bateria” ou “criar tarefa: comprar pão”.",
                    color = colors.onSurfaceVariant, style = typography.bodyMedium,
                )
            }
        }
        if (modelSetup != null) item(key = "modelSetup") { modelSetup() }
        items(state.turns, key = { it.id }) { turn ->
            Surface(
                Modifier.fillMaxWidth(),
                color = if (turn.user) colors.surfaceContainerLow else colors.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (turn.user) "Você" else "O.R.I.O.N.", style = typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(turn.text)
                    if (turn.proposal != null) {
                        Text("Sugestão do modelo. Aguarda sua revisão e a confirmação do pedido.")
                        Button(onClick = { controller.prepare(turn.id) }, enabled = !state.busy) { Text("Preparar tarefa sugerida") }
                    }
                }
            }
        }
        if (state.draft.isNotEmpty()) item { Text(state.draft, Modifier.testTag("orion_chat_stream").padding(horizontal = 14.dp)) }
        val notice = state.notice
        if (inputError == null && notice != null) item {
            Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
        }
        item(key = "editor") {
            val version = editorVersion
            key(version) {
                OutlinedTextField(input, onValueChange = edit@{ edited ->
                    // Focus loss can deliver an edit from the input session that just sent a draft.
                    if (version != editorVersion) return@edit
                    // Focus/IME updates can repeat the retained value after an edit was refused.
                    if (oversizedEditRejected && edited == input) return@edit
                    val error = OrionChatInputPolicy.validate(edited)
                    // Oversized edits preserve the previous bounded draft; no silent truncation.
                    // Other bounded invalid text stays exactly editable and is rejected by send.
                    oversizedEditRejected = error == OrionChatInputError.INPUT_TOO_LARGE
                    if (!oversizedEditRejected) input = edited
                    inputError = error?.takeUnless { it == OrionChatInputError.EMPTY_INPUT }?.message
                }, label = { Text("Mensagem") }, singleLine = true, isError = inputError != null,
                    shape = MaterialTheme.shapes.small,
                    supportingText = { inputError?.let { Text(it, Modifier.testTag("orion_chat_input_error")) } },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("orion_chat_input"))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = send@{
                    if (oversizedEditRejected) return@send
                    when (val result = controller.send(input)) {
                        OrionChatAdmission.Accepted -> {
                            revealResult = true
                            editorVersion++
                            input = ""
                            inputError = null
                            focus.clearFocus()
                            keyboard?.hide()
                        }
                        is OrionChatAdmission.Rejected -> inputError = result.reason.message
                        OrionChatAdmission.Busy -> inputError = "Aguarde o envio em andamento."
                        OrionChatAdmission.Closed -> inputError = "Esta sessão foi encerrada. Abra o chat novamente."
                    }
                }, enabled = !state.busy && !oversizedEditRejected, shape = MaterialTheme.shapes.small) { Text("Enviar") }
                if (state.busy && state.cortex in setOf("Gerando", "Verificando disponibilidade")) TextButton(onClick = controller::cancel) { Text("Cancelar") }
                if (state.canRetry && !state.busy) TextButton(onClick = {
                    revealResult = true
                    controller.retryInference()
                }) { Text("Tentar inferência novamente") }
            }
            if (oversizedEditRejected && OrionChatInputPolicy.validate(input) == null) {
                TextButton(enabled = !state.busy, onClick = {
                    oversizedEditRejected = false
                    inputError = null
                }) { Text("Usar rascunho anterior") }
            }
            TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Atualizar pedidos") }
        }
        items(state.pending, key = { "pending/" + it.actionId }) { action ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("orion_chat_confirmation"), shape = MaterialTheme.shapes.medium) {
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
        if (memory != null) item {
            ConversationHistory(controller, memory, state.busy, state.turns.any { it.archiveId == null })
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

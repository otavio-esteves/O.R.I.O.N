package network.orion.feature.memories

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import network.orion.core.commands.memories.*
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.*

/** User-declared personal statements only. Unsubmitted drafts remain transient. */
@Composable
fun MemoriesScreen(gateway: MemoryGateway, recoveryVersion: Instant? = null) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var content by remember { mutableStateOf("") }
    var submission by remember { mutableStateOf<CreateMemory?>(null) }
    var rows by remember { mutableStateOf<List<MemorySummary>>(emptyList()) }
    var next by remember { mutableStateOf<UUID?>(null) }
    var pending by remember { mutableStateOf<List<MemoryConfirmation>>(emptyList()) }
    var activeWork by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = activeWork > 0
    suspend fun reload() {
        val page = gateway.list(GetMemories())
        rows = page?.items ?: emptyList(); next = page?.next
        pending = gateway.pending()
        if (page == null) message = "Memórias indisponíveis. Consulte o diagnóstico."
        else if (message == "Memórias indisponíveis. Consulte o diagnóstico.") message = null
    }
    fun run(block: suspend () -> Unit) {
        if (activeWork > 0) return
        activeWork++
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Não foi possível concluir. Atualize para consultar o pedido." }
            finally { activeWork-- }
        }
    }
    LaunchedEffect(gateway,recoveryVersion) {
        activeWork++
        try { gateway.processPending(); reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Memórias indisponíveis. Consulte o diagnóstico." }
        finally { activeWork-- }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("orion_memories"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Memórias",style=MaterialTheme.typography.headlineSmall)
            Text("Guarde uma informação pessoal declarada por você. Nenhuma memória é inferida automaticamente.")
        }
        item {
            OutlinedTextField(value=content,onValueChange={ if (!busy) { content=it; submission=null } },
                label={ Text("O que você quer lembrar?") },singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy && content.isNotBlank(),onClick={
                focus.clearFocus(); keyboard?.hide()
                val command=submission ?: CreateMemory(UUID.randomUUID(),content,UUID.randomUUID(),UUID.randomUUID()).also { submission=it }
                run {
                    message=when (gateway.create(command)) {
                        CreateMemoryResult.QUEUED -> { content=""; submission=null; "Pedido recebido. Confirme a memória abaixo." }
                        CreateMemoryResult.REJECTED -> "Revise a informação ou atualize o pedido."
                        CreateMemoryResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente quando o Core estiver pronto."
                        CreateMemoryResult.FORGOTTEN -> { content=""; submission=null; "Esta memória já foi esquecida. Um novo registro precisa de um novo pedido." }
                    }; reload()
                }
            }) { Text("Preparar memória") }
        }
        item { message?.let { Text(it) } }
        item { TextButton(enabled=!busy,onClick={ run { gateway.processPending(); reload() } }) { Text("Atualizar memórias e pedidos") } }
        items(pending,key={ "pending/${it.actionId}" }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_memory_confirmation")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if (action.operation==MemoryOperation.CREATE) "Guardar esta memória pessoal?" else "Esquecer esta memória?")
                    Text(action.content ?: "Conteúdo indisponível ou pedido desatualizado. Descarte este pedido e tente novamente.")
                    if (action.operation==MemoryOperation.DELETE) Text("A informação e suas cópias de processamento serão removidas. O histórico mínimo da operação será preservado.")
                    Button(enabled=!busy && action.content!=null,onClick={ run {
                        message=when (gateway.confirm(action.actionId,action.version)) {
                            MemoryOperationOutcome.APPLIED -> if (action.operation==MemoryOperation.DELETE) "Memória esquecida." else "Memória guardada."
                            MemoryOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                            MemoryOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize e confirme novamente."
                            MemoryOperationOutcome.UNAVAILABLE -> "Não foi possível confirmar. Tente novamente."
                            MemoryOperationOutcome.FORGOTTEN -> "Esta memória já foi esquecida."
                        }; reload()
                    } }) { Text(if (action.operation==MemoryOperation.CREATE) "Confirmar memória" else "Confirmar esquecimento") }
                    TextButton(enabled=!busy,onClick={ run {
                        message=when (gateway.dismiss(action.actionId,action.version)) {
                            MemoryOperationOutcome.APPLIED -> "Pedido descartado."
                            MemoryOperationOutcome.QUEUED -> "Descarte recebido. Atualize para consultar o resultado."
                            else -> "Não foi possível descartar o pedido."
                        }; reload()
                    } }) { Text("Descartar pedido") }
                }
            }
        }
        if (rows.isEmpty()) item { Text("Nenhuma memória pessoal para exibir.") }
        items(rows,key={ "memory/${it.memoryId}" }) { memory ->
            Card(Modifier.fillMaxWidth().testTag("orion_memory_row")) {
                Column(Modifier.padding(16.dp)) {
                    Text(memory.content ?: "Conteúdo da memória indisponível")
                    Text("Declarada por você · pessoal",style=MaterialTheme.typography.bodySmall)
                    if (memory.state==MemoryState.ACTIVE) TextButton(enabled=!busy && memory.contentState==MemoryContentState.READABLE,onClick={ run {
                        message=when (gateway.change(ForgetMemory(memory.memoryId,memory.version,UUID.randomUUID(),UUID.randomUUID()))) {
                            MemoryStatusResult.QUEUED -> "Pedido recebido. Confirme o esquecimento abaixo."
                            MemoryStatusResult.REJECTED -> "A memória mudou. Atualize e tente novamente."
                            MemoryStatusResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente."
                        }; reload()
                    } }) { Text("Esquecer memória") }
                }
            }
        }
        if (next!=null) item { TextButton(enabled=!busy,onClick={ run {
            val page=gateway.list(GetMemories(next))
            if (page==null) message="Não foi possível carregar mais memórias."
            else { rows=rows+page.items; next=page.next }
        } }) { Text("Carregar mais memórias") } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

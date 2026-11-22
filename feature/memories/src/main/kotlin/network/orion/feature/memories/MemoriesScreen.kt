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
import network.orion.core.model.OrionMemoryOrigin
import network.orion.core.model.OrionMemoryProvenance
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.*

/** User-declared personal statements only. Unsubmitted drafts remain transient. */
@Composable
fun MemoriesScreen(gateway: MemoryGateway, recoveryVersion: Instant? = null) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var content by remember { mutableStateOf("") }
    var allowSearchIndex by remember { mutableStateOf(false) }
    var submission by remember { mutableStateOf<CreateMemory?>(null) }
    var rows by remember { mutableStateOf<List<MemorySummary>>(emptyList()) }
    var next by remember { mutableStateOf<UUID?>(null) }
    var searchText by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf<String?>(null) }
    var searchNext by remember { mutableStateOf<MemorySearchCursor?>(null) }
    var correction by remember { mutableStateOf<CorrectMemory?>(null) }
    var reviewedMemory by remember { mutableStateOf<UUID?>(null) }
    var claims by remember { mutableStateOf<List<MemoryClaimSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var pending by remember { mutableStateOf<List<MemoryConfirmation>>(emptyList()) }
    var activeWork by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = activeWork > 0
    suspend fun reload() {
        val request = activeSearch
        if (request == null) {
            val page = gateway.list(GetMemories())
            rows = page?.items.orEmpty()
            next = page?.next
            searchNext = null
            if (page == null) message = "Memórias indisponíveis. Consulte o diagnóstico."
            else if (message == "Memórias indisponíveis. Consulte o diagnóstico.") message = null
        } else {
            val page = gateway.search(SearchMemories(request))
            rows = page?.hits?.map { it.memory }.orEmpty()
            searchNext = page?.next
            next = null
            when (page?.state) {
                MemorySearchState.BUILDING -> message = "O índice de memórias está sendo preparado."
                MemorySearchState.KEY_UNAVAILABLE -> message = "A chave das memórias está indisponível."
                MemorySearchState.PREFERENCES_CHANGED -> message = "A personalização da busca mudou. Busque novamente."
                null -> message = "Busca de memórias indisponível."
                MemorySearchState.READY -> if (page.candidateLimitReached) message = "Refine a busca para ver outros resultados."
            }
        }
        pending = gateway.pending().filterNot { it.operation.isConversationOperation() }
        reviewedMemory = null
        claims = emptyList()
        selected = selected.intersect(rows.map { it.memoryId }.toSet())
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
            MemoryIndexChoice(allowSearchIndex, !busy) { allowSearchIndex = it; submission = null }
            Button(enabled=!busy && content.isNotBlank(),onClick={
                focus.clearFocus(); keyboard?.hide()
                val command=submission ?: CreateMemory(UUID.randomUUID(), content, UUID.randomUUID(), UUID.randomUUID(), allowSearchIndex).also { submission=it }
                run {
                    message=when (gateway.create(command)) {
                        CreateMemoryResult.QUEUED -> { content=""; submission=null; allowSearchIndex=false; "Pedido recebido. Confirme a memória abaixo." }
                        CreateMemoryResult.REJECTED -> "Revise a informação ou atualize o pedido."
                        CreateMemoryResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente quando o Core estiver pronto."
                        CreateMemoryResult.FORGOTTEN -> { content=""; submission=null; "Esta memória já foi esquecida. Um novo registro precisa de um novo pedido." }
                    }; reload()
                }
            }) { Text("Preparar memória") }
        }
        item {
            OutlinedTextField(value = searchText, onValueChange = { searchText = it.take(256) },
                label = { Text("Buscar memórias") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(enabled = !busy && searchText.isNotBlank(), onClick = {
                    val query = searchText
                    if (runCatching { SearchMemories(query) }.isFailure) message = "Digite termos válidos para a busca."
                    else run { activeSearch = query; message = null; reload() }
                }) { Text("Buscar") }
                if (activeSearch != null) TextButton(enabled = !busy, onClick = {
                    run { activeSearch = null; searchText = ""; message = null; reload() }
                }) { Text("Limpar busca") }
            }
        }
        if (selected.isNotEmpty()) item {
            TextButton(enabled = !busy, onClick = { run {
                val targets = rows.filter { it.memoryId in selected }
                val queued = targets.count { row ->
                    gateway.change(ForgetMemory(row.memoryId, row.version, UUID.randomUUID(), UUID.randomUUID())) == MemoryStatusResult.QUEUED
                }
                message = if (queued == 0) "Nenhum pedido de esquecimento recebido."
                    else "$queued pedidos recebidos. Confirme cada memória abaixo."
                selected = emptySet()
                reload()
            } }) { Text("Preparar esquecimento de ${selected.size} memórias") }
        }
        item { message?.let { Text(it) } }
        item { TextButton(enabled=!busy,onClick={ run { gateway.processPending(); reload() } }) { Text("Atualizar memórias e pedidos") } }
        items(pending,key={ "pending/${it.actionId}" }) { action ->
            Card(Modifier.fillMaxWidth().testTag("orion_memory_confirmation")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(when (action.operation) {
                        MemoryOperation.CREATE -> "Guardar esta memória pessoal?"
                        MemoryOperation.DELETE -> "Esquecer esta memória?"
                        MemoryOperation.CORRECT -> "Guardar esta correção?"
                        MemoryOperation.CONFIRM_CLAIM -> "Confirmar esta afirmação inferida?"
                        MemoryOperation.REJECT_CLAIM -> "Rejeitar esta afirmação inferida?"
                        MemoryOperation.ARCHIVE_CONVERSATION, MemoryOperation.FORGET_CONVERSATION -> error("Conversation action.")
                    })
                    action.allowSearchIndex?.let { searchable ->
                        Text(if (searchable) "Disponível na busca e no contexto do chat." else "Memória privada, fora da busca e do contexto do chat.")
                    }
                    Text(action.content ?: "Conteúdo indisponível ou pedido desatualizado. Descarte este pedido e tente novamente.")
                    if (action.operation==MemoryOperation.DELETE) Text("A informação e suas cópias de processamento serão removidas. O histórico mínimo da operação será preservado.")
                    Button(enabled=!busy && action.content!=null,onClick={ run {
                        message=when (gateway.confirm(action.actionId,action.version)) {
                            MemoryOperationOutcome.APPLIED -> when (action.operation) {
                                MemoryOperation.CREATE -> "Memória guardada."
                                MemoryOperation.DELETE -> "Memória esquecida."
                                MemoryOperation.CORRECT -> "Correção guardada."
                                MemoryOperation.CONFIRM_CLAIM -> "Afirmação confirmada."
                                MemoryOperation.REJECT_CLAIM -> "Afirmação rejeitada."
                                MemoryOperation.ARCHIVE_CONVERSATION, MemoryOperation.FORGET_CONVERSATION -> error("Conversation action.")
                            }
                            MemoryOperationOutcome.QUEUED -> "Confirmação recebida. Atualize para consultar o resultado."
                            MemoryOperationOutcome.REJECTED -> "Pedido desatualizado ou confirmação expirada. Atualize e confirme novamente."
                            MemoryOperationOutcome.UNAVAILABLE -> "Não foi possível confirmar. Tente novamente."
                            MemoryOperationOutcome.FORGOTTEN -> "Esta memória já foi esquecida."
                        }; reload()
                    } }) { Text(when (action.operation) {
                        MemoryOperation.CREATE -> "Confirmar memória"
                        MemoryOperation.DELETE -> "Confirmar esquecimento"
                        MemoryOperation.CORRECT -> "Confirmar correção"
                        MemoryOperation.CONFIRM_CLAIM -> "Confirmar afirmação"
                        MemoryOperation.REJECT_CLAIM -> "Confirmar rejeição"
                        MemoryOperation.ARCHIVE_CONVERSATION, MemoryOperation.FORGET_CONVERSATION -> error("Conversation action.")
                    }) }
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
                    Text("${memory.provenance.description()} · ${memory.state.label()}", style = MaterialTheme.typography.bodySmall)
                    memory.details?.source?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val readable = memory.contentState == MemoryContentState.READABLE
                    if (memory.state != MemoryState.DELETED && readable) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = memory.memoryId in selected,
                                enabled = !busy && (selected.size < 10 || memory.memoryId in selected),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + memory.memoryId else selected - memory.memoryId
                                })
                            Text("Selecionar para esquecimento")
                        }
                        TextButton(enabled = !busy, onClick = { run {
                            message = gateway.change(ForgetMemory(memory.memoryId, memory.version, UUID.randomUUID(),
                                UUID.randomUUID())).feedback("Confirme o esquecimento abaixo.")
                            reload()
                        } }) { Text("Esquecer memória") }
                        TextButton(enabled = !busy, onClick = { run {
                            val result = gateway.claims(GetMemoryClaims(memory.memoryId, memory.version))
                            if (result == null) message = "Afirmações indisponíveis. Atualize a memória."
                            else { reviewedMemory = memory.memoryId; claims = result }
                        } }) { Text("Ver afirmações") }
                    }
                    if (memory.state == MemoryState.ACTIVE && readable && correction?.memoryId != memory.memoryId) {
                        TextButton(enabled = !busy, onClick = {
                            correction = CorrectMemory(memory.memoryId, memory.version, UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), checkNotNull(memory.content))
                        }) { Text("Corrigir memória") }
                    }
                    val draft = correction?.takeIf { it.memoryId == memory.memoryId }
                    if (draft != null) {
                        OutlinedTextField(value = draft.content, onValueChange = { value ->
                            correction = draft.copy(idempotencyKey = UUID.randomUUID(), content = value)
                        }, label = { Text("Correção") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        MemoryIndexChoice(draft.allowSearchIndex, !busy) { allowed ->
                            correction = draft.copy(idempotencyKey = UUID.randomUUID(), allowSearchIndex = allowed)
                        }
                        Row {
                            TextButton(enabled = !busy && draft.content.isNotBlank(), onClick = { run {
                                val result = gateway.change(draft)
                                message = result.feedback("Confirme a correção abaixo.")
                                if (result == MemoryStatusResult.QUEUED) correction = null
                                reload()
                            } }) { Text("Preparar correção") }
                            TextButton(enabled = !busy, onClick = { correction = null }) { Text("Cancelar") }
                        }
                    }
                    if (reviewedMemory == memory.memoryId) {
                        if (claims.isEmpty()) Text("Nenhuma afirmação registrada.")
                        for (claim in claims) {
                            Text(claim.statement ?: "Afirmação indisponível")
                            Text("${claim.provenance.description()} · ${claim.state.label()}", style = MaterialTheme.typography.bodySmall)
                            if (claim.canReview) Row {
                                for ((decision, label) in listOf(MemoryClaimDecision.CONFIRM to "Confirmar", MemoryClaimDecision.REJECT to "Rejeitar")) {
                                    TextButton(enabled = !busy, onClick = { run {
                                        val request = ReviewMemoryClaim(memory.memoryId, memory.version, UUID.randomUUID(), UUID.randomUUID(),
                                            claim.claimId, claim.version, decision)
                                        message = gateway.change(request).feedback("Confirme a revisão abaixo.")
                                        reload()
                                    } }) { Text(label) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (next!=null) item { TextButton(enabled=!busy,onClick={ run {
            val page=gateway.list(GetMemories(next))
            if (page==null) message="Não foi possível carregar mais memórias."
            else { rows=rows+page.items; next=page.next }
        } }) { Text("Carregar mais memórias") } }
        if (searchNext != null) item {
            TextButton(enabled = !busy, onClick = { run {
                val query = checkNotNull(activeSearch)
                val page = gateway.search(SearchMemories(query, after = searchNext))
                if (page?.state == MemorySearchState.PREFERENCES_CHANGED) {
                    reload()
                } else if (page == null || page.state != MemorySearchState.READY) {
                    message = "Não foi possível carregar mais resultados."
                }
                else {
                    rows = (rows + page.hits.map { it.memory }).distinctBy { it.memoryId }
                    searchNext = page.next
                }
            } }) { Text("Carregar mais resultados") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun OrionMemoryProvenance.description(): String {
    val originLabel = when (origin) {
        OrionMemoryOrigin.USER_DECLARED -> "Declarada por você"
        OrionMemoryOrigin.SYSTEM_OBSERVED -> "Observada"
        OrionMemoryOrigin.SYSTEM_DERIVED -> "Derivada"
        OrionMemoryOrigin.LLM_INFERRED -> if (userConfirmed) "Inferida, confirmada por você" else "Inferida, aguardando revisão"
        OrionMemoryOrigin.IMPORTED -> "Importada"
        OrionMemoryOrigin.EXTERNAL_SOURCE -> "Fonte externa"
    }
    return "$originLabel · Confiança ${kotlin.math.round(confidence * 100).toInt()}%"
}

private fun MemoryState.label(): String = when (this) {
    MemoryState.ACTIVE -> "Vigente"
    MemoryState.CANDIDATE -> "Candidata"
    MemoryState.SUPERSEDED -> "Substituída"
    MemoryState.EXPIRED -> "Expirada"
    MemoryState.REJECTED -> "Rejeitada"
    MemoryState.ARCHIVED -> "Arquivada"
    MemoryState.DELETED -> "Excluída"
}

private fun MemoryStatusResult.feedback(queued: String): String = when (this) {
    MemoryStatusResult.QUEUED -> queued
    MemoryStatusResult.REJECTED -> "A memória mudou. Atualize e tente novamente."
    MemoryStatusResult.UNAVAILABLE -> "Pedido não recebido. Tente novamente."
}

@Composable
private fun MemoryIndexChoice(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text("Permitir busca e uso no contexto do chat")
    }
}

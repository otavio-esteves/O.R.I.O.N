package network.orion.feature.notifications

import java.time.Instant
import java.util.UUID
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.orion.core.commands.notifications.*
import network.orion.core.orchestrator.notifications.*

/** A permission gesture and exact post confirmation are separate user actions. */
@Composable
fun NotificationsScreen(gateway:NotificationGateway,recoveryVersion:Instant?=null,refreshRevision:Long=0,
    onRequestPermission:()->Unit={},onOpenSettings:()->Unit={}) {
    val scope=rememberCoroutineScope()
    var title by remember { mutableStateOf("") };var body by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<PostNotification?>(null) }
    var availability by remember { mutableStateOf(NotificationAvailability.UNAVAILABLE) }
    var pending by remember { mutableStateOf<List<NotificationConfirmation>>(emptyList()) }
    var work by remember { mutableIntStateOf(0) };val busy=work>0
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { availability=gateway.availability();pending=gateway.pending() }
    fun run(block:suspend ()->Unit) {
        if(work>0)return
        work++
        scope.launch {
            try { block() } catch(c:CancellationException) { throw c }
            catch(_:Exception) { message="Não foi possível consultar o pedido. Consulte o diagnóstico antes de tentar outro envio." }
            finally { work-- }
        }
    }
    fun outcome(result:NotificationOutcome)=when(result) {
        NotificationOutcome.POSTED->"Notificação publicada."
        NotificationOutcome.APPLIED->"Pedido descartado."
        NotificationOutcome.QUEUED->"Pedido recebido. Atualize para consultar o resultado."
        NotificationOutcome.UNKNOWN->"Não foi possível confirmar a publicação. Este pedido não será reenviado automaticamente. Consulte o diagnóstico."
        NotificationOutcome.BLOCKED->"Envio bloqueado. Verifique a permissão e o estado do pedido."
        NotificationOutcome.REJECTED->"Pedido desatualizado ou confirmação expirada. Descarte e prepare um novo pedido."
        NotificationOutcome.UNAVAILABLE->"Notificações indisponíveis. Consulte o diagnóstico."
    }
    LaunchedEffect(gateway,recoveryVersion) {
        work++
        try { gateway.processPending();reload() } catch(c:CancellationException) { throw c }
        catch(_:Exception) { message="Notificações indisponíveis. Consulte o diagnóstico." }
        finally { work-- }
    }
    LaunchedEffect(gateway,refreshRevision) {
        if(refreshRevision>0) {
            work++
            try { reload() } catch(c:CancellationException) { throw c }
            catch(_:Exception) { message="Notificações indisponíveis. Consulte o diagnóstico." }
            finally { work-- }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("orion_notifications").semantics {
        stateDescription=if(busy)"Carregando notificações" else "Notificações atualizadas"
    },verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Notificações",style=MaterialTheme.typography.headlineSmall) }
        item {
            Text(when(availability) {
                NotificationAvailability.AVAILABLE->"Notificações disponíveis. Revise cada pedido antes de confirmar o envio."
                NotificationAvailability.PERMISSION_DENIED->"Permita notificações no Android para publicar um pedido."
                NotificationAvailability.APP_DISABLED->"As notificações deste app estão desativadas no Android."
                NotificationAvailability.CHANNEL_DISABLED->"O canal de notificações solicitadas está desativado no Android."
                NotificationAvailability.UNAVAILABLE->"Notificações indisponíveis. Consulte o diagnóstico."
            })
            if(availability==NotificationAvailability.PERMISSION_DENIED)Button(enabled=!busy,onClick=onRequestPermission) { Text("Permitir notificações") }
            if(availability in setOf(NotificationAvailability.APP_DISABLED,NotificationAvailability.CHANNEL_DISABLED))TextButton(enabled=!busy,onClick=onOpenSettings) { Text("Abrir ajustes de notificações") }
        }
        item {
            OutlinedTextField(title,{ if(!busy) { title=it;draft=null } },label={Text("Título da notificação")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(body,{ if(!busy) { body=it;draft=null } },label={Text("Mensagem da notificação")},minLines=3,enabled=!busy,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy&&title.isNotBlank()&&body.isNotBlank(),onClick={
                val request=draft?:PostNotification(UUID.randomUUID(),title,body,UUID.randomUUID(),UUID.randomUUID()).also { draft=it }
                run {
                    message=when(gateway.prepare(request)) {
                        PostNotificationResult.QUEUED->{title="";body="";draft=null;"Pedido recebido. Revise título e mensagem antes de confirmar."}
                        PostNotificationResult.REJECTED->"Revise título e mensagem. O conteúdo ultrapassou o limite ou contém caracteres inválidos."
                        PostNotificationResult.UNAVAILABLE->"Pedido não recebido. Tente novamente quando o app estiver pronto."
                    };reload()
                }
            }) { Text("Preparar notificação") }
        }
        item { message?.let { Text(it) } }
        item { TextButton(enabled=!busy,onClick={run { gateway.processPending();reload() }}) { Text("Atualizar pedidos") } }
        items(pending,key={it.actionId}) { action->
            Card(Modifier.fillMaxWidth().testTag("orion_notification_confirmation")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Publicar esta notificação?")
                    Text(action.title?:"Conteúdo indisponível. Descarte o pedido e prepare outro.")
                    action.body?.let { Text(it) }
                    Button(enabled=!busy&&action.title!=null&&action.body!=null,onClick={run { message=outcome(gateway.confirm(action.actionId,action.version));reload() }}) {
                        Text(if(action.alreadyConfirmed)"Enviar pedido confirmado" else "Confirmar e publicar")
                    }
                    TextButton(enabled=!busy,onClick={run { message=outcome(gateway.dismiss(action.actionId,action.version));reload() }}) { Text("Descartar pedido") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

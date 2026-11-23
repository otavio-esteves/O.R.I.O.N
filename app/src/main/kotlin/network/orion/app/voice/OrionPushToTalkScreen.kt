package network.orion.app.voice

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.UUID
import kotlinx.coroutines.launch
import network.orion.app.ai.OrionS21CortexPreview
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.voice.ipc.IOrionPushToTalk
import network.orion.voice.ipc.IOrionPushToTalkCallback
import network.orion.voice.service.OrionVoiceControlService
import network.orion.voice.service.OrionVoiceModel
import network.orion.voice.service.OrionVoiceSessionState

@Composable
internal fun OrionPushToTalkScreen(lifecycle: Lifecycle, voiceHealth: (Boolean) -> Unit, openTasks: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OrionVoiceSessionState?>(null) }
    var reply by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var modelPresent by remember { mutableStateOf(OrionVoiceModel.present(context)) }
    val modelBundled = remember(context) { OrionVoiceModel.bundled(context) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val cortexNotice by OrionS21CortexPreview.notice.collectAsState()
    val controller = remember(context) { OrionPushToTalkController(context) { next, text, review ->
        state = next
        reply = text ?: reply
        confirm = review || confirm
        if (next in setOf(OrionVoiceSessionState.MODEL_UNAVAILABLE, OrionVoiceSessionState.MICROPHONE_UNAVAILABLE,
                OrionVoiceSessionState.CORE_UNAVAILABLE, OrionVoiceSessionState.FAILED)) voiceHealth(true)
        if (next == OrionVoiceSessionState.SUCCEEDED) voiceHealth(false)
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceHealth(false)
        message = if (granted) "Microfone autorizado. Toque em Falar." else "Autorize o microfone para usar a voz."
    }
    fun installModel(install: suspend () -> Boolean) {
        if (importing) return
        importing = true
        message = null
        scope.launch {
            try {
                val imported = install()
                modelPresent = OrionVoiceModel.present(context)
                voiceHealth(false)
                message = if (imported) "Modelo de voz instalado." else "Não foi possível instalar o modelo. Verifique o arquivo e o espaço livre."
            } finally {
                importing = false
            }
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installModel { OrionVoiceModel.install(context.applicationContext, uri) }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.close()
                state = null
                reply = null
                confirm = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    val busy = state?.terminal == false
    val recording = state == OrionVoiceSessionState.CONNECTING || state == OrionVoiceSessionState.LISTENING
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Voz", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (OrionS21CortexPreview.enabled)
                "Faça uma pergunta ou peça para consultar a bateria e suas tarefas."
            else "Consulte a bateria, conte as tarefas abertas ou prepare uma nova tarefa.",
            color = colors.onSurfaceVariant,
            style = typography.bodyMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (modelPresent) {
                    Text("Processamento no dispositivo", style = typography.labelMedium, color = colors.tertiary)
                    Button(
                        onClick = {
                            message = null
                            if (recording) {
                                controller.finishCapture()
                            } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                reply = null
                                confirm = false
                                controller.begin()
                            }
                        },
                        enabled = (!busy || recording) && !importing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("orion_voice_talk"),
                        shape = MaterialTheme.shapes.small,
                    ) { Text(if (recording) "Concluir fala" else if (busy) "Processando" else "Falar") }
                    if (busy) TextButton(onClick = {
                        controller.close()
                        state = OrionVoiceSessionState.CANCELLED
                    }) { Text("Cancelar") }
                } else {
                    Text("Modelo de voz", style = typography.titleMedium)
                    Text(
                        if (modelBundled) "Instale o reconhecimento de fala que acompanha este app."
                        else "Selecione o arquivo ggml-tiny-q5_1.bin para ativar o reconhecimento offline.",
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            if (modelBundled) installModel { OrionVoiceModel.installBundled(context.applicationContext) }
                            else modelPicker.launch(arrayOf("*/*"))
                        },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("orion_voice_install"),
                        shape = MaterialTheme.shapes.small,
                    ) { Text(if (importing) "Verificando modelo" else if (modelBundled) "Instalar modelo local" else "Selecionar modelo local") }
                }
                val status = message ?: cortexNotice.takeIf { state == OrionVoiceSessionState.WAITING_FOR_CORE }
                    ?: state?.let(::voiceStatus)
                if (status != null) Text(
                    status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.onSurfaceVariant, style = typography.bodyMedium,
                )
            }
        }
        reply?.let { text ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text(text, Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (confirm) OutlinedButton(onClick = openTasks, shape = MaterialTheme.shapes.small) { Text("Revisar em Tarefas") }
        if (modelPresent && !busy) {
            TextButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = !importing) {
                Text("Trocar arquivo do modelo", color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun voiceStatus(state: OrionVoiceSessionState) = when (state) {
    OrionVoiceSessionState.CONNECTING -> "Preparando microfone."
    OrionVoiceSessionState.LISTENING -> "Ouvindo. Diga, por exemplo, “bateria” ou “criar tarefa estudar”."
    OrionVoiceSessionState.TRANSCRIBING -> "Transcrevendo no dispositivo."
    OrionVoiceSessionState.WAITING_FOR_CORE -> "Buscando a resposta."
    OrionVoiceSessionState.SPEAKING -> "Respondendo."
    OrionVoiceSessionState.SUCCEEDED -> null
    OrionVoiceSessionState.CANCELLED -> "Sessão cancelada."
    OrionVoiceSessionState.NO_SPEECH -> "Nenhuma fala reconhecida. Tente novamente."
    OrionVoiceSessionState.MODEL_UNAVAILABLE -> "Modelo de voz indisponível. Selecione um arquivo compatível."
    OrionVoiceSessionState.RESOURCE_BUSY -> "O processamento local está ocupado. Tente falar novamente em instantes."
    OrionVoiceSessionState.MICROPHONE_UNAVAILABLE -> "Microfone indisponível."
    OrionVoiceSessionState.CORE_UNAVAILABLE -> "Core indisponível. Você pode continuar pelo texto."
    OrionVoiceSessionState.FAILED -> "Sessão de voz interrompida."
}

/** An Activity owns exactly one foreground control-plane binding. */
private class OrionPushToTalkController(
    context: Context,
    private val report: (OrionVoiceSessionState, String?, Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var active: Session? = null

    fun begin() {
        if (active != null) return
        val session = Session(UUID.randomUUID().toString())
        active = session
        report(OrionVoiceSessionState.CONNECTING, null, false)
        session.bound = try {
            app.bindService(Intent(app, OrionVoiceControlService::class.java), session, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { false }
        if (!session.bound) session.fail()
        else handler.postDelayed({ if (active === session && session.remote == null) session.fail() }, 4_000)
    }

    fun finishCapture() {
        val session = active ?: return
        session.finish = true
        try { session.remote?.finishCapture(MAJOR, session.id) } catch (_: RemoteException) { session.fail() }
    }

    fun close() { active?.release() }

    private inner class Session(val id: String) : ServiceConnection {
        var remote: IOrionPushToTalk? = null
        var bound = false
        var finish = false
        private val callback = object : IOrionPushToTalkCallback.Stub() {
            override fun onState(sessionId: String, state: String, text: String?, requiresConfirmation: Boolean) {
                handler.post {
                    if (this@Session !== active || sessionId != id || (text?.length ?: 0) > 4_096) return@post
                    val value = OrionVoiceSessionState.entries.firstOrNull { it.name == state } ?: return@post
                    report(value, text, requiresConfirmation)
                    if (value.terminal) release()
                }
            }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (active !== this) return
            val endpoint = IOrionPushToTalk.Stub.asInterface(service)
            remote = endpoint
            try {
                endpoint.begin(MAJOR, id, callback)
                if (finish) endpoint.finishCapture(MAJOR, id)
            } catch (_: RemoteException) { fail() }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail()
        override fun onBindingDied(name: ComponentName) = fail()
        override fun onNullBinding(name: ComponentName) = fail()
        fun fail() {
            if (active !== this) return
            report(OrionVoiceSessionState.FAILED, null, false)
            release()
        }
        fun release() {
            if (active !== this) return
            active = null
            try { remote?.cancel(MAJOR, id) } catch (_: RemoteException) { }
            if (bound) app.unbindService(this)
        }
    }

    private companion object { val MAJOR = OrionIpcProtocol.CURRENT.protocolMajor }
}

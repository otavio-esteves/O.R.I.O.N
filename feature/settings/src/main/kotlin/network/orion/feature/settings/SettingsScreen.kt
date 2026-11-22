package network.orion.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.orion.core.health.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(report: OrionHealthReport, sentinelEnabled: Boolean? = null,
    onOpenVoice: () -> Unit = {},
    assistantRole: OrionAssistantRoleState = OrionAssistantRoleState.UNAVAILABLE,
    onConfigureAssistant: () -> Boolean = { false },
    onSetSentinelEnabled: suspend (Boolean) -> Boolean = { false }) {
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateFailed by remember { mutableStateOf(false) }
    var assistantSettingsFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Armazenamento e privacidade", style = MaterialTheme.typography.headlineSmall)
        Text("Configurações: ${report.subsystems.getValue(OrionSubsystem.CONFIGURATION)}")
        Text("Backup do app: desativado")
        Text("Armazenamento no dispositivo, protegido pelo desbloqueio do usuário.")
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Sugestões de tarefas")
            Switch(checked = sentinelEnabled == true, enabled = sentinelEnabled != null && !updating,
                onCheckedChange = { enabled ->
                    updating = true
                    updateFailed = false
                    scope.launch {
                        try { updateFailed = !onSetSentinelEnabled(enabled) }
                        finally { updating = false }
                    }
                })
        }
        Text("Usa a permissão de notificações do app. Horário silencioso das 22h às 8h.")
        if (updateFailed) Text("Não foi possível atualizar as sugestões.")
        HorizontalDivider()
        OutlinedButton(onClick = onOpenVoice) { Text("Voz") }
        Text(when (assistantRole) {
            OrionAssistantRoleState.UNAVAILABLE -> "Assistente do sistema indisponível"
            OrionAssistantRoleState.NOT_SELECTED -> "Assistente do sistema não selecionado"
            OrionAssistantRoleState.SELECTED_INACTIVE -> "Assistente selecionado, aguardando o serviço do Android"
            OrionAssistantRoleState.ACTIVE -> "Assistente do sistema ativo"
        })
        if (assistantRole != OrionAssistantRoleState.UNAVAILABLE) {
            OutlinedButton(onClick = { assistantSettingsFailed = !onConfigureAssistant() }) {
                Text("Configurar assistente no Android")
            }
            Text("Em Apps padrão, abra App de assistente digital.")
            if (assistantSettingsFailed) Text("Não foi possível abrir os aplicativos padrão do Android.")
        }
    }
}

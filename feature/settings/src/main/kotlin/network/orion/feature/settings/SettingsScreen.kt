package network.orion.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.orion.core.health.*

@Composable
fun SettingsScreen(report: OrionHealthReport) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Armazenamento e privacidade", style = MaterialTheme.typography.headlineSmall)
        Text("Configurações: ${report.subsystems.getValue(OrionSubsystem.CONFIGURATION)}")
        Text("Backup do app: desativado")
        Text("Armazenamento no dispositivo, protegido pelo desbloqueio do usuário.")
    }
}

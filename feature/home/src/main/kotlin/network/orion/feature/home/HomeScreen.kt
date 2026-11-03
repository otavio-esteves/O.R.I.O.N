package network.orion.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.orion.core.health.*

@Composable
fun HomeScreen(report: OrionHealthReport) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Seu assistente local", style = MaterialTheme.typography.headlineSmall)
        Text("Core: ${report.core.readiness}")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Estado do dispositivo", style = MaterialTheme.typography.titleMedium)
                Text("Banco local: ${report.subsystems.getValue(OrionSubsystem.DATABASE)}")
                Text("Configuração: ${report.subsystems.getValue(OrionSubsystem.CONFIGURATION)}")
                Text("Cortex: ${report.subsystems.getValue(OrionSubsystem.CORTEX)}")
                Text("Voz: ${report.subsystems.getValue(OrionSubsystem.VOICE)}")
            }
        }
        if (report.core.readiness == CoreReadiness.BLOCKED) {
            Text("O armazenamento local precisa de atenção. Consulte o diagnóstico.",
                color = MaterialTheme.colorScheme.error)
        }
    }
}

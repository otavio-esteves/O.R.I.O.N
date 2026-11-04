package network.orion.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.orion.core.health.*
import network.orion.core.ipc.OrionIpcProtocol

@Composable
fun DiagnosticsScreen(report: OrionHealthReport, databaseSchemaVersion: Int?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Saúde do sistema", style = MaterialTheme.typography.headlineSmall)
        Text("Core: ${report.core.readiness}")
        Text("Health: ${report.core.status}")
        Text("Schema Room: ${databaseSchemaVersion ?: "indisponível"}")
        val protocol = OrionIpcProtocol.CURRENT
        Text("Contrato IPC: ${protocol.protocolMajor}.${protocol.protocolMinor}")
        Text("Última inicialização local: ${report.lastRecovery ?: "não concluída"}")
        HorizontalDivider()
        for ((subsystem, status) in report.subsystems) {
            Text("${subsystem.name}: $status", style = MaterialTheme.typography.bodyMedium)
        }
        if (report.core.readiness == CoreReadiness.BLOCKED ||
            report.subsystems[OrionSubsystem.FIELD_KEYS] == OrionHealthStatus.FAILED) {
            Button(onClick = onRetry) { Text("Tentar novamente") }
        }
    }
}

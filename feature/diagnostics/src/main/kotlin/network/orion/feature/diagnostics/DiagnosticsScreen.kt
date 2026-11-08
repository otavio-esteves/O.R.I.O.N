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
fun DiagnosticsScreen(report: OrionHealthReport, databaseSchemaVersion: Int?, taskQueues: TaskQueueDiagnostics? = null, scheduling: OrionSchedulingDiagnostics? = null,
    platform: OrionPlatformDiagnostics? = null, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Saúde do sistema", style = MaterialTheme.typography.headlineSmall)
        Text("Core: ${report.core.readiness}")
        Text("Health: ${report.core.status}")
        Text("Schema Room: ${databaseSchemaVersion ?: "indisponível"}")
        val protocol = OrionIpcProtocol.CURRENT
        Text("Contrato IPC: ${protocol.protocolMajor}.${protocol.protocolMinor}")
        Text("Última inicialização local: ${report.lastRecovery ?: "não concluída"}")
        Text("Compatibilidade nativa: ${platform?.nativeQualification ?: OrionNativeQualification.UNAVAILABLE}")
        Text("Classificação do Memory Limiter: " + when (platform?.memoryLimiterClassification) {
            OrionMemoryLimiterClassification.DEDICATED_REASON_AVAILABLE -> "motivo dedicado disponível"
            OrionMemoryLimiterClassification.DESCRIPTION_FALLBACK_ONLY -> "identificação alternativa por descrição"
            null -> "indisponível"
        })
        Text("Evidência recente de Memory Limiter: " + when (platform?.memoryLimiterEvidence) {
            OrionMemoryLimiterEvidence.OBSERVED -> "observada"
            OrionMemoryLimiterEvidence.NOT_OBSERVED -> "não observada"
            else -> "indisponível"
        })
        taskQueues?.let { q ->
            Text("Ingressos na fila: ${q.queuedIngress}")
            Text("Aguardando confirmação: ${q.pendingConfirmation}")
            Text("Ações em execução: ${q.runningActions}")
            Text("Ações incertas: ${q.unknownActions}")
            Text("Ações para revisão manual: ${q.manualReviewActions}")
            Text("Eventos não publicados: ${q.pendingEvents}")
            Text("Recuperação e agendamento completos: qualificação F4 pendente")
        }
        scheduling?.let { q ->
            Text("Lembretes pendentes: ${q.pendingReminders}")
            Text("Entrega aproximada: ${q.approximateReminders}")
            Text("Eventos para revisão: ${q.deadLetterEvents}")
            Text("Alarme exato: ${q.exactAlarmCapability}")
            Text("Manutenção registrada: ${q.heartbeatRegistered}")
            Text("Restrição em segundo plano: ${q.backgroundRestricted}")
            Text("Sinal de Force Stop: ${q.forceStopSignal}")
            Text("Último Force Stop detectado: ${q.lastForceStopDetectedAt ?: "sem evidência"}")
            Text("Última saída: ${q.lastExitKind ?: "indisponível"} (${q.lastExitReason ?: "?"})")
        }
        HorizontalDivider()
        for ((subsystem, status) in report.subsystems) {
            Text("${subsystem.name}: $status", style = MaterialTheme.typography.bodyMedium)
        }
        if (report.core.readiness == CoreReadiness.BLOCKED ||
            report.subsystems[OrionSubsystem.FIELD_KEYS] == OrionHealthStatus.FAILED ||
            report.subsystems[OrionSubsystem.ACTION_OUTBOX] == OrionHealthStatus.FAILED ||
            report.subsystems[OrionSubsystem.EVENT_OUTBOX] == OrionHealthStatus.FAILED) {
            Button(onClick = onRetry) { Text("Tentar novamente") }
        }
    }
}

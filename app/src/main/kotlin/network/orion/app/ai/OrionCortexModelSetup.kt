package network.orion.app.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import network.orion.app.OrionFoundationRuntime

@Composable
internal fun OrionCortexModelSetup(runtime: OrionFoundationRuntime) {
    val status by runtime.previewModel.collectAsState()
    val notice by OrionS21CortexPreview.notice.collectAsState()
    Column {
        Text(
            text = when (status) {
                OrionS21ModelStatus.CHECKING -> "Verificando modelo local…"
                OrionS21ModelStatus.READY -> "Qwen local · versão de teste"
                OrionS21ModelStatus.MISSING -> "O arquivo do Qwen ainda precisa ser instalado neste S21."
                OrionS21ModelStatus.REJECTED -> "O modelo local não pôde ser validado. Confira o arquivo e o espaço livre."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

package network.orion.feature.device

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import network.orion.core.queries.device.*

@Composable
fun DeviceScreen(gateway: DeviceInformationGateway, recoveryVersion: Instant? = null) {
    var snapshot by remember { mutableStateOf<DeviceInformation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        busy = true
        try { snapshot = gateway.get(GetDeviceInformation); unavailable = snapshot == null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snapshot = null; unavailable = true }
        finally { busy = false }
    }
    LaunchedEffect(gateway, recoveryVersion) { refresh() }
    Column(Modifier.fillMaxSize().testTag("orion_device").verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dispositivo", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { if (!busy) { busy = true; scope.launch { refresh() } } }, enabled = !busy) { Text("Atualizar dispositivo") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (unavailable) Text("Informações indisponíveis. Tente atualizar.")
        snapshot?.let { state ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bateria", style = MaterialTheme.typography.titleMedium)
                    Text(state.batteryPercent?.let { "Carga: $it%" } ?: "Carga: indisponível")
                    Text("Estado: " + when (state.charging) {
                        ChargingState.CHARGING -> "carregando"; ChargingState.DISCHARGING -> "em uso"
                        ChargingState.FULL -> "carga completa"; ChargingState.NOT_CHARGING -> "sem carregar"
                        ChargingState.UNKNOWN -> "indisponível"
                    })
                    Text("Conexão: " + when (state.connection) {
                        PowerConnection.UNPLUGGED -> "desconectado"; PowerConnection.AC -> "tomada"
                        PowerConnection.USB -> "USB"; PowerConnection.WIRELESS -> "sem fio"
                        PowerConnection.DOCK -> "base"; PowerConnection.UNKNOWN -> "indisponível"
                    })
                    Text("Economia de bateria: ${yesNo(state.powerSave)}")
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hora e sistema", style = MaterialTheme.typography.titleMedium)
                    Text(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").format(state.observedAt.atZone(state.zoneId)))
                    Text("Fuso: ${state.zoneId.id}")
                    Text("Fabricante: ${state.manufacturer ?: "indisponível"}")
                    Text("Modelo: ${state.model ?: "indisponível"}")
                    Text("Android API: ${state.apiLevel ?: "indisponível"}")
                    Text("Tela interativa: ${yesNo(state.interactive)}")
                    Text("Restrição em segundo plano: ${yesNo(state.backgroundRestricted)}")
                    Text("Leitura feita ao abrir ou atualizar esta tela.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
private fun yesNo(value: Boolean?) = when (value) { true -> "sim"; false -> "não"; null -> "indisponível" }

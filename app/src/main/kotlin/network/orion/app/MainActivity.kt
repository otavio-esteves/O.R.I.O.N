package network.orion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.orion.feature.diagnostics.DiagnosticsScreen
import network.orion.feature.home.HomeScreen
import network.orion.feature.settings.SettingsScreen
import network.orion.app.tasks.OrionTaskAdmissionOwner

class MainActivity : ComponentActivity() {
    private lateinit var taskChannel: OrionTaskAdmissionOwner.UiChannel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = checkNotNull((application as OrionApplication).mainRuntime)
        taskChannel = runtime.taskAdmissions.openUiChannel()
        setContent {
            MaterialTheme {
                val report by runtime.health.collectAsState()
                var destination by rememberSaveable { mutableStateOf("Início") }
                Scaffold(
                    topBar = {
                        Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                            Text("O.R.I.O.N.", style = MaterialTheme.typography.headlineMedium)
                            Text("Operational Reasoning & Intelligent Orchestration Network",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly) {
                                for (label in listOf("Início", "Diagnóstico", "Ajustes")) {
                                    FilterChip(selected = destination == label,
                                        onClick = { destination = label }, label = { Text(label) })
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
                        when (destination) {
                            "Diagnóstico" -> DiagnosticsScreen(report, runtime.databaseSchemaVersion, onRetry = { runtime.start() })
                            "Ajustes" -> SettingsScreen(report)
                            else -> HomeScreen(report)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskChannel.onResumed(true)
    }

    override fun onPause() {
        taskChannel.onResumed(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::taskChannel.isInitialized) taskChannel.onWindowFocus(hasFocus)
    }

    override fun onDestroy() {
        if (::taskChannel.isInitialized) taskChannel.close()
        super.onDestroy()
    }
}

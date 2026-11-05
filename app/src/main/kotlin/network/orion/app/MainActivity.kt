package network.orion.app

import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import network.orion.feature.diagnostics.DiagnosticsScreen
import network.orion.feature.home.HomeScreen
import network.orion.feature.tasks.TasksScreen
import network.orion.feature.settings.SettingsScreen
import network.orion.feature.device.DeviceScreen
import network.orion.feature.activity.ActivityScreen
import network.orion.app.tasks.OrionTaskAdmissionOwner
import network.orion.app.reminders.OrionReminderAdmissionOwner
import network.orion.feature.reminders.RemindersScreen
import network.orion.app.timers.OrionTimerAdmissionOwner
import network.orion.feature.timers.TimersScreen

class MainActivity : ComponentActivity() {
    private lateinit var taskChannel: OrionTaskAdmissionOwner.UiChannel
    private lateinit var reminderChannel: OrionReminderAdmissionOwner.UiChannel
    private lateinit var timerChannel: OrionTimerAdmissionOwner.UiChannel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = checkNotNull((application as OrionApplication).mainRuntime)
        taskChannel = runtime.taskAdmissions.openUiChannel()
        taskChannel.onWindowFocus(hasWindowFocus())
        val tasks = runtime.taskGateway(taskChannel)
        reminderChannel = runtime.reminderAdmissions.openUiChannel()
        reminderChannel.onWindowFocus(hasWindowFocus())
        val reminders = runtime.reminderGateway(reminderChannel)
        timerChannel = runtime.timerAdmissions.openUiChannel()
        timerChannel.onWindowFocus(hasWindowFocus())
        val timers = runtime.timerGateway(timerChannel)
        setContent {
            MaterialTheme {
                val report by runtime.health.collectAsState()
                var destination by rememberSaveable { mutableStateOf("Início") }
                var taskQueues by remember { mutableStateOf<network.orion.core.health.TaskQueueDiagnostics?>(null) }
                LaunchedEffect(destination, report.lastRecovery) {
                    if (destination == "Diagnóstico") taskQueues = try {
                        withContext(Dispatchers.IO) { runtime.taskKernel?.diagnostics() }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
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
                                for (label in listOf("Início", "Tarefas", "Diagnóstico", "Ajustes")) {
                                    TextButton(onClick = { destination = label }, modifier = Modifier.weight(1f).semantics { selected = destination == label },
                                        contentPadding = PaddingValues(0.dp)) {
                                        Text(label, style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                            color = if (destination == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 24.dp)) {
                        when (destination) {
                            "Lembretes" -> RemindersScreen(reminders, report.lastRecovery)
                            "Timers" -> TimersScreen(timers, report.lastRecovery)
                            "Dispositivo" -> DeviceScreen(runtime.deviceGateway, report.lastRecovery)
                            "Atividade" -> ActivityScreen(runtime.activityGateway, report.lastRecovery)
                            "Tarefas" -> TasksScreen(tasks, report.lastRecovery)
                            "Diagnóstico" -> DiagnosticsScreen(report, runtime.databaseSchemaVersion, taskQueues, onRetry = { runtime.start() })
                            "Ajustes" -> SettingsScreen(report)
                            else -> HomeScreen(report, onOpenDevice = { destination = "Dispositivo" },
                                onOpenActivity = { destination = "Atividade" }, onOpenReminders = { destination = "Lembretes" },
                                onOpenTimers = { destination = "Timers" })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskChannel.onResumed(true)
        taskChannel.onWindowFocus(hasWindowFocus())
        reminderChannel.onResumed(true)
        reminderChannel.onWindowFocus(hasWindowFocus())
        timerChannel.onResumed(true)
        timerChannel.onWindowFocus(hasWindowFocus())
    }

    override fun onPause() {
        taskChannel.onResumed(false)
        reminderChannel.onResumed(false)
        timerChannel.onResumed(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::taskChannel.isInitialized) taskChannel.onWindowFocus(hasFocus)
        if (::reminderChannel.isInitialized) reminderChannel.onWindowFocus(hasFocus)
        if (::timerChannel.isInitialized) timerChannel.onWindowFocus(hasFocus)
    }

    override fun onDestroy() {
        if (::taskChannel.isInitialized) taskChannel.close()
        if (::reminderChannel.isInitialized) reminderChannel.close()
        if (::timerChannel.isInitialized) timerChannel.close()
        super.onDestroy()
    }
}

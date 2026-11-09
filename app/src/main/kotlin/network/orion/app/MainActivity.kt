package network.orion.app

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
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
import network.orion.app.memories.OrionMemoryAdmissionOwner
import network.orion.feature.memories.MemoriesScreen
import network.orion.app.notifications.OrionNotificationAdmissionOwner
import network.orion.feature.notifications.NotificationsScreen

class MainActivity : ComponentActivity() {
    private lateinit var taskChannel: OrionTaskAdmissionOwner.UiChannel
    private lateinit var reminderChannel: OrionReminderAdmissionOwner.UiChannel
    private lateinit var timerChannel: OrionTimerAdmissionOwner.UiChannel
    private lateinit var memoryChannel: OrionMemoryAdmissionOwner.UiChannel
    private lateinit var notificationChannel: OrionNotificationAdmissionOwner.UiChannel
    private lateinit var deliveryAlerts: network.orion.app.scheduling.OrionReminderDeliveryUiGateway
    private var notificationRevision by mutableLongStateOf(0L)
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationRevision++
        (application as OrionApplication).mainRuntime?.refreshNotificationHealth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = checkNotNull((application as OrionApplication).mainRuntime)
        runtime.observeForegroundStartup()
        taskChannel = runtime.taskAdmissions.openUiChannel()
        taskChannel.onWindowFocus(hasWindowFocus())
        val tasks = runtime.taskGateway(taskChannel)
        reminderChannel = runtime.reminderAdmissions.openUiChannel()
        reminderChannel.onWindowFocus(hasWindowFocus())
        val reminders = runtime.reminderGateway(reminderChannel)
        deliveryAlerts = runtime.deliveryGateway()
        timerChannel = runtime.timerAdmissions.openUiChannel()
        timerChannel.onWindowFocus(hasWindowFocus())
        val timers = runtime.timerGateway(timerChannel)
        memoryChannel = runtime.memoryAdmissions.openUiChannel()
        memoryChannel.onWindowFocus(hasWindowFocus())
        val memories = runtime.memoryGateway(memoryChannel)
        notificationChannel = runtime.notificationAdmissions.openUiChannel()
        notificationChannel.onWindowFocus(hasWindowFocus())
        val notifications = runtime.notificationGateway(notificationChannel)
        setContent {
            MaterialTheme {
                val report by runtime.health.collectAsState()
                var destination by rememberSaveable { mutableStateOf("Início") }
                var taskQueues by remember { mutableStateOf<network.orion.core.health.TaskQueueDiagnostics?>(null) }
                var schedulingDiagnostics by remember { mutableStateOf<network.orion.core.health.OrionSchedulingDiagnostics?>(null) }
                var inferenceDiagnostics by remember { mutableStateOf<network.orion.core.health.OrionInferenceDiagnostics?>(null) }
                LaunchedEffect(report.lastRecovery) {
                    if (report.lastRecovery != null) {
                        deliveryAlerts.onCoreRecovered()
                        deliveryAlerts.processPending()
                    }
                }
                LaunchedEffect(destination, report.lastRecovery) {
                    if (destination == "Diagnóstico") {
                        inferenceDiagnostics = runtime.inferenceDiagnostics()
                        schedulingDiagnostics = runtime.schedulingDiagnostics()
                        taskQueues = try {
                        withContext(Dispatchers.IO) { runtime.taskKernel?.diagnostics() }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    }
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
                            "Lembretes" -> RemindersScreen(reminders, report.lastRecovery, deliveryAlerts,
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                                }, onRequestExactAccess = {
                                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(android.net.Uri.parse("package:$packageName")))
                                })
                            "Timers" -> TimersScreen(timers, report.lastRecovery)
                            "Memórias" -> MemoriesScreen(memories, report.lastRecovery)
                            "Notificações" -> NotificationsScreen(notifications, report.lastRecovery, notificationRevision,
                                onRequestPermission = {
                                    if (Build.VERSION.SDK_INT >= 33) notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else { notificationRevision++; runtime.refreshNotificationHealth() }
                                },
                                onOpenSettings = {
                                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                                })
                            "Dispositivo" -> DeviceScreen(runtime.deviceGateway, report.lastRecovery)
                            "Atividade" -> ActivityScreen(runtime.activityGateway, report.lastRecovery)
                            "Tarefas" -> TasksScreen(tasks, report.lastRecovery)
                            "Diagnóstico" -> DiagnosticsScreen(report, runtime.databaseSchemaVersion, taskQueues, schedulingDiagnostics,
                                platform = runtime.platformDiagnostics(), inference = inferenceDiagnostics, onRetry = { runtime.start() })
                            "Ajustes" -> SettingsScreen(report)
                            else -> HomeScreen(report, onOpenDevice = { destination = "Dispositivo" },
                                onOpenActivity = { destination = "Atividade" }, onOpenReminders = { destination = "Lembretes" },
                                onOpenTimers = { destination = "Timers" }, onOpenMemories = { destination = "Memórias" },
                                onOpenNotifications = { destination = "Notificações" })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        deliveryAlerts.onResumed(true)
        deliveryAlerts.onWindowFocus(hasWindowFocus())
        taskChannel.onResumed(true)
        taskChannel.onWindowFocus(hasWindowFocus())
        reminderChannel.onResumed(true)
        reminderChannel.onWindowFocus(hasWindowFocus())
        timerChannel.onResumed(true)
        timerChannel.onWindowFocus(hasWindowFocus())
        memoryChannel.onResumed(true)
        memoryChannel.onWindowFocus(hasWindowFocus())
        notificationChannel.onResumed(true)
        notificationChannel.onWindowFocus(hasWindowFocus())
        notificationRevision++
        (application as OrionApplication).mainRuntime?.refreshNotificationHealth()
    }

    override fun onPause() {
        deliveryAlerts.onResumed(false)
        taskChannel.onResumed(false)
        reminderChannel.onResumed(false)
        timerChannel.onResumed(false)
        memoryChannel.onResumed(false)
        notificationChannel.onResumed(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::deliveryAlerts.isInitialized) deliveryAlerts.onWindowFocus(hasFocus)
        if (::taskChannel.isInitialized) taskChannel.onWindowFocus(hasFocus)
        if (::reminderChannel.isInitialized) reminderChannel.onWindowFocus(hasFocus)
        if (::timerChannel.isInitialized) timerChannel.onWindowFocus(hasFocus)
        if (::memoryChannel.isInitialized) memoryChannel.onWindowFocus(hasFocus)
        if (::notificationChannel.isInitialized) notificationChannel.onWindowFocus(hasFocus)
        if (hasFocus && ::deliveryAlerts.isInitialized)
            (application as OrionApplication).mainRuntime?.nudgeUiMaintenance()
    }

    override fun onDestroy() {
        if (::deliveryAlerts.isInitialized) deliveryAlerts.close()
        if (::taskChannel.isInitialized) taskChannel.close()
        if (::reminderChannel.isInitialized) reminderChannel.close()
        if (::timerChannel.isInitialized) timerChannel.close()
        if (::memoryChannel.isInitialized) memoryChannel.close()
        if (::notificationChannel.isInitialized) notificationChannel.close()
        super.onDestroy()
    }
}

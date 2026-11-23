package network.orion.app

import android.os.Bundle
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import network.orion.app.sentinel.OrionSentinelOpenActivity
import network.orion.feature.notifications.NotificationsScreen

private const val ORION_HOME = "Início"
private val orionHomeShortcuts = listOf(
    "Chat", "Voz", "Tarefas", "Lembretes", "Timers", "Memórias",
    "Notificações", "Dispositivo", "Atividade", "Diagnóstico", "Ajustes",
)
class MainActivity : ComponentActivity() {
    private lateinit var taskChannel: OrionTaskAdmissionOwner.UiChannel
    private lateinit var reminderChannel: OrionReminderAdmissionOwner.UiChannel
    private lateinit var timerChannel: OrionTimerAdmissionOwner.UiChannel
    private lateinit var memoryChannel: OrionMemoryAdmissionOwner.UiChannel
    private lateinit var notificationChannel: OrionNotificationAdmissionOwner.UiChannel
    private lateinit var deliveryAlerts: network.orion.app.scheduling.OrionReminderDeliveryUiGateway
    private var chat: network.orion.core.orchestrator.chat.OrionChatController? = null
    private var notificationRevision by mutableLongStateOf(0L)
    private var tasksNavigation by mutableLongStateOf(0L)
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationRevision++
        (application as OrionApplication).mainRuntime?.refreshNotificationHealth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        val runtime = checkNotNull((application as OrionApplication).mainRuntime)
        if (intent.getBooleanExtra(OrionSentinelOpenActivity.OPEN_TASKS, false)) {
            intent.removeExtra(OrionSentinelOpenActivity.OPEN_TASKS)
            tasksNavigation++
        }
        runtime.observeForegroundStartup()
        taskChannel = runtime.taskAdmissions.openUiChannel()
        taskChannel.onWindowFocus(hasWindowFocus())
        val tasks = runtime.taskGateway(taskChannel)
        val chatController = network.orion.core.orchestrator.chat.OrionChatController(
            lifecycleScope, tasks, runtime.deviceGateway,
            network.orion.app.chat.OrionChatCortexAdapter(network.orion.core.time.AndroidOrionClock(contentResolver)) {
                runtime.cortexRuntime()?.let { network.orion.app.chat.OrionOwnedChatRuntime(it) }
            }, network.orion.core.orchestrator.chat.OrionChatMemorySource(runtime::chatMemories)).also { chat = it }
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
            OrionTheme {
                val report by runtime.health.collectAsState()
                val sentinelEnabled by runtime.sentinelEnabled.collectAsState()
                val personalizationEnabled by runtime.personalizationEnabled.collectAsState()
                val resourceProfile by runtime.resourceProfile.collectAsState()
                val assistantRole by runtime.assistantRoleState.collectAsState()
                var destination by rememberSaveable { mutableStateOf(ORION_HOME) }
                LaunchedEffect(tasksNavigation) { if (tasksNavigation > 0) destination = "Tarefas" }
                BackHandler(enabled = destination != ORION_HOME) { destination = ORION_HOME }
                var taskQueues by remember { mutableStateOf<network.orion.core.health.TaskQueueDiagnostics?>(null) }
                var schedulingDiagnostics by remember { mutableStateOf<network.orion.core.health.OrionSchedulingDiagnostics?>(null) }
                val diagnosticsReader = remember(runtime) {
                    OrionDiagnosticsReader(runtime::schedulingDiagnostics,
                        { withContext(Dispatchers.IO) { runtime.taskKernel?.diagnostics() } })
                }
                LaunchedEffect(report.lastRecovery) {
                    if (report.lastRecovery != null) {
                        deliveryAlerts.onCoreRecovered()
                        deliveryAlerts.processPending()
                    }
                }
                if (destination == "Diagnóstico") {
                    OrionDiagnosticsObservation(lifecycle, diagnosticsReader, report.lastRecovery, notificationRevision) {
                        schedulingDiagnostics = it.scheduling
                        taskQueues = it.queues
                    }
                }
                if (destination == ORION_HOME) {
                    HomeScreen(shortcuts = orionHomeShortcuts, onOpen = { destination = it })
                } else Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Column(Modifier.statusBarsPadding().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp)) {
                                TextButton(
                                    onClick = { destination = ORION_HOME },
                                    modifier = Modifier.align(Alignment.CenterStart),
                                ) {
                                    Text("‹", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.width(5.dp))
                                    Text(ORION_HOME)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    },
                ) { padding ->
                    BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        val sidePadding = maxOf(22.dp, (maxWidth - 636.dp) / 2)
                        Box(Modifier.fillMaxSize().padding(horizontal = sidePadding)) {
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
                                "Voz" -> network.orion.app.voice.OrionPushToTalkScreen(lifecycle, runtime::refreshVoiceHealth) { destination = "Tarefas" }
                                "Chat" -> network.orion.feature.chat.ChatScreen(chatController, report.lastRecovery, memories)
                                "Tarefas" -> TasksScreen(tasks, report.lastRecovery)
                                "Diagnóstico" -> DiagnosticsScreen(report, runtime.databaseSchemaVersion, taskQueues, schedulingDiagnostics,
                                    platform = runtime.platformDiagnostics(), resourceProfile = resourceProfile?.name, assistantRole = assistantRole,
                                    personalizationEnabled = personalizationEnabled,
                                    onRetry = { runtime.start() })
                                "Ajustes" -> SettingsScreen(report, sentinelEnabled, onOpenVoice = { destination = "Voz" },
                                    assistantRole = assistantRole, onConfigureAssistant = ::openDefaultAppsSettings,
                                    personalizationEnabled = personalizationEnabled,
                                    onSetPersonalizationEnabled = runtime::setPersonalizationEnabled,
                                    onSetSentinelEnabled = { enabled ->
                                        val applied = runtime.setSentinelEnabled(enabled)
                                        if (applied && enabled && Build.VERSION.SDK_INT >= 33 &&
                                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        applied
                                    })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val runtime = (application as OrionApplication).mainRuntime
        runtime?.apply {
            noteForegroundInteraction()
            refreshVoiceHealth()
            refreshAssistantRole()
        }
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
        runtime?.refreshNotificationHealth()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as OrionApplication).mainRuntime?.noteForegroundInteraction()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(OrionSentinelOpenActivity.OPEN_TASKS, false)) tasksNavigation++
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
        if (hasFocus && ::deliveryAlerts.isInitialized) {
            (application as OrionApplication).mainRuntime?.apply {
                refreshAssistantRole()
                nudgeUiMaintenance()
            }
        }
    }

    private fun openDefaultAppsSettings(): Boolean = try {
        // Android's default-app settings own role consent and revocation.
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    override fun onDestroy() {
        chat?.close()
        chat = null
        if (::deliveryAlerts.isInitialized) deliveryAlerts.close()
        if (::taskChannel.isInitialized) taskChannel.close()
        if (::reminderChannel.isInitialized) reminderChannel.close()
        if (::timerChannel.isInitialized) timerChannel.close()
        if (::memoryChannel.isInitialized) memoryChannel.close()
        if (::notificationChannel.isInitialized) notificationChannel.close()
        super.onDestroy()
    }
}

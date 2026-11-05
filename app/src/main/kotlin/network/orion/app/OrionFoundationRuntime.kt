package network.orion.app

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.orion.core.actions.UnavailableActionEngine
import network.orion.core.exitinfo.ApplicationExitMonitor
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.health.*
import network.orion.core.ingress.FoundationIngressCoordinator
import network.orion.core.ingress.StartupBarrier
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.app.tasks.*
import network.orion.app.reminders.*
import network.orion.app.timers.*
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import network.orion.core.policy.reminders.OrionPolicyEvidence as ReminderEvidence
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.core.ingress.IngressCoordinator
import network.orion.core.model.CapabilityRegistry
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.observability.*
import network.orion.core.recovery.RecoveryCoordinator
import network.orion.core.resources.UnavailableResourceGovernor
import network.orion.core.security.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseFactory
import network.orion.data.datastore.OrionPreferencesStore
import network.orion.data.repository.OrionPrivatePayloadStore

/** MAIN-owned foundation and deterministic task slice. UI receives selected Core ports only. */
class OrionFoundationRuntime internal constructor(context: Context) : RecoveryCoordinator {
    private val app = context.applicationContext
    private val clock = AndroidOrionClock(app.contentResolver)
    internal val deviceGateway = network.orion.app.device.OrionDeviceUiGateway(app, clock)
    internal val activityGateway = network.orion.app.device.OrionActivityUiGateway { database }
    private val logger = OrionLogger(clock, OrionLogSink { record ->
        val priority = when (record.severity) {
            OrionLogSeverity.INFO -> Log.INFO
            OrionLogSeverity.WARN -> Log.WARN
            OrionLogSeverity.ERROR -> Log.ERROR
        }
        Log.println(priority, "orion_core", record.toString())
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val initial = OrionSubsystem.entries.associateWith { OrionHealthStatus.UNAVAILABLE }
    private val mutableHealth = MutableStateFlow(OrionHealthAggregator.aggregate(initial, clock.wallNow()))
    val health: StateFlow<OrionHealthReport> = mutableHealth.asStateFlow()
    val ingress: IngressCoordinator get() = taskKernel?.ingress ?: FoundationIngressCoordinator { health.value.core.readiness }
    val actions = UnavailableActionEngine()
    @Volatile private var taskWriteReady = false
    @Volatile private var taskStorageReady = false
    @Volatile internal var taskKernel: OrionTaskKernel? = null
        private set
    @Volatile internal var reminderKernel: OrionReminderKernel? = null
        private set
    @Volatile internal var timerKernel: OrionTimerKernel? = null
        private set
    val capabilities = CapabilityRegistry { capability ->
        capability in setOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY, OrionCreateReminderDescriptor.WRITE_CAPABILITY,
            OrionCreateTimerDescriptor.WRITE_CAPABILITY) &&
            taskWriteReady && taskStartupBarrier.isOpen() && clock.monotonicNow().bootSessionId != null
    }
    internal fun taskGateway(channel: OrionTaskAdmissionOwner.UiChannel) = OrionTaskUiGateway(channel, { taskKernel }, clock, ::taskOperationFailed)
    internal fun reminderGateway(channel: OrionReminderAdmissionOwner.UiChannel) =
        OrionReminderUiGateway(channel, { reminderKernel }, clock, ::taskOperationFailed)
    internal fun timerGateway(channel: OrionTimerAdmissionOwner.UiChannel) =
        OrionTimerUiGateway(channel, { timerKernel }, clock, ::taskOperationFailed)
    // One operational barrier for recovery and every deterministic domain.
    internal val taskStartupBarrier = StartupBarrier()
    internal val taskAdmissions = OrionTaskAdmissionOwner(
        OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()),
        taskStartupBarrier,
        writeCapability = ::writeEvidence,
        devicePresence = ::presenceEvidence,
    )
    internal val reminderAdmissions = OrionReminderAdmissionOwner(
        OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()),
        taskStartupBarrier,
        writeCapability = { reminderEvidence(writeEvidence()) },
        devicePresence = { reminderEvidence(presenceEvidence()) },
    )
    internal val timerAdmissions = OrionTimerAdmissionOwner(
        OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()),
        taskStartupBarrier, ::writeEvidence, ::presenceEvidence,
    )

    // Re-sampled at every domain observation; these values never cache consent or grant execution.
    private fun writeEvidence(): OrionPolicyEvidence =
        if (!taskWriteReady) OrionPolicyEvidence.NOT_SATISFIED
        else if (clock.monotonicNow().bootSessionId == null) OrionPolicyEvidence.UNAVAILABLE
        else OrionPolicyEvidence.SATISFIED

    private fun presenceEvidence(): OrionPolicyEvidence {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        return if (power == null || keyguard == null) OrionPolicyEvidence.UNAVAILABLE
        else if (power.isInteractive && !keyguard.isDeviceLocked) OrionPolicyEvidence.SATISFIED
        else OrionPolicyEvidence.NOT_SATISFIED
    }

    private fun reminderEvidence(value: OrionPolicyEvidence): ReminderEvidence = when (value) {
        OrionPolicyEvidence.SATISFIED -> ReminderEvidence.SATISFIED
        OrionPolicyEvidence.NOT_SATISFIED -> ReminderEvidence.NOT_SATISFIED
        OrionPolicyEvidence.UNAVAILABLE -> ReminderEvidence.UNAVAILABLE
    }
    private var governor = UnavailableResourceGovernor()
    private var database: OrionDatabase? = null
    private var preferences: OrionPreferencesStore? = null
    private var startupJob: Job? = null
    @Volatile var databaseFormatVersion: Int? = null
        private set
    @Volatile var databaseSchemaVersion: Int? = null
        private set
    @Volatile var configurationFormatVersion: Int? = null
        private set

    private fun taskOperationFailed() {
        taskStartupBarrier.beginRecovery()
        taskWriteReady = false
        taskStorageReady = false
        taskAdmissions.invalidateAdmissions()
        reminderAdmissions.invalidateAdmissions()
        timerAdmissions.invalidateAdmissions()
        publish(health.value.subsystems.toMutableMap().apply { this[OrionSubsystem.ACTION_OUTBOX] = OrionHealthStatus.FAILED })
    }

    @Synchronized fun start() {
        if (startupJob?.isActive == true) return
        startupJob = scope.launch { recover() }
    }

    override suspend fun recover(): CoreReadiness = withContext(Dispatchers.IO) { mutex.withLock {
        if (taskStartupBarrier.isOpen() && taskKernel != null && reminderKernel != null && timerKernel != null && health.value.core.readiness.isStartupBarrierOpen &&
            health.value.subsystems[OrionSubsystem.FIELD_KEYS] == OrionHealthStatus.HEALTHY) return@withLock health.value.core.readiness
        val ticket = taskStartupBarrier.beginRecovery()
        taskWriteReady = false
        taskStorageReady = false
        taskAdmissions.invalidateAdmissions()
        reminderAdmissions.invalidateAdmissions()
        timerAdmissions.invalidateAdmissions()
        val correlation = UUID.randomUUID()
        val states = initial.toMutableMap()
        var stage = OrionSubsystem.DATABASE
        logger.record(correlation, OrionLogComponent.BOOTSTRAP, OrionLogEvent.STARTING, OrionLogSeverity.INFO)
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.RECOVERING
        states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.RECOVERING
        publish(states)
        try {
            val db = database ?: OrionDatabaseFactory.open(app).also { database = it }
            db.verifyStructure()
            databaseFormatVersion = db.metadata().formatVersion()
            databaseSchemaVersion = db.openHelper.readableDatabase.version
            states[OrionSubsystem.DATABASE] = OrionHealthStatus.HEALTHY
            publish(states)
            stage = OrionSubsystem.CONFIGURATION
            val config = preferences ?: OrionPreferencesStore.open(app).also { preferences = it }
            configurationFormatVersion = config.read().formatVersion
            states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.HEALTHY
            stage = OrionSubsystem.FIELD_KEYS
            val keys = AndroidKeystoreFieldKeyProvider.open(app)
            val keyState = FirstInstallFieldKeys(keys,
                attempted = { config.read().fieldKeyProvisioningAttempted },
                markAttempted = { config.markFieldKeyProvisioningAttempted() },
                provisionIfStorageEmpty = {
                    var result: FieldKeyLifecycleResult? = null
                    db.runInTransaction {
                        if (!db.records().hasEncryptedRecords() && !OrionPrivatePayloadStore.hasArtifacts(app))
                            result = keys.provisionNewInstallation()
                    }
                    result
                }).initialize()
            states[OrionSubsystem.FIELD_KEYS] = if (keyState == FieldKeyInitialization.READY)
                OrionHealthStatus.HEALTHY else OrionHealthStatus.FAILED
            logger.record(correlation, OrionLogComponent.STORAGE, OrionLogEvent.STORAGE_READY, OrionLogSeverity.INFO)
            val exits = try {
                AndroidApplicationExitMonitor(app).recentExits().also {
                    states[OrionSubsystem.EXIT_MONITOR] = OrionHealthStatus.HEALTHY
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional evidence unavailable; inference remains denied in all cases.
                states[OrionSubsystem.EXIT_MONITOR] = OrionHealthStatus.DEGRADED
                emptyList()
            }
            if (keyState == FieldKeyInitialization.READY) {
                taskStorageReady = true
                val kernel = OrionTaskKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier, taskAdmissions,
                    { health.value.core.readiness }, { taskStorageReady })
                taskKernel = kernel // Its ingress remains GATED by the actual barrier.
                reminderKernel = OrionReminderKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    reminderAdmissions, { health.value.core.readiness }, { taskStorageReady })
                timerKernel = OrionTimerKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    timerAdmissions, { health.value.core.readiness }, { taskStorageReady })
                states[OrionSubsystem.REMINDERS] = OrionHealthStatus.DEGRADED
                stage = OrionSubsystem.EVENT_OUTBOX
                // Pending durable facts remain intact: no consumer is registered in this slice.
                states[OrionSubsystem.EVENT_OUTBOX] = OrionHealthStatus.DEGRADED
                stage = OrionSubsystem.ACTION_OUTBOX
                kernel.recoverActions()
                states[OrionSubsystem.ACTION_OUTBOX] = OrionHealthStatus.DEGRADED
                // Reminder records remain UNSCHEDULED; OS delivery/reconciliation is F4.
                taskWriteReady = true
                states[OrionSubsystem.PERMISSIONS] = if (app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
                    OrionHealthStatus.DEGRADED else OrionHealthStatus.HEALTHY
            }
            governor = UnavailableResourceGovernor(exits)
            check(!governor.canRunInference())
            taskStartupBarrier.completeRecovery(ticket,
                calculateAndPublishHealth = { publish(states, clock.wallNow()); health.value.core.readiness },
                openIngressAndDrain = {
                    if (taskWriteReady) {
                        taskKernel?.ingress?.drain(25)
                        reminderKernel?.ingress?.drain(25)
                        timerKernel?.ingress?.drain(25)
                    }
                    else taskStartupBarrier.abortRecovery(ticket)
                })
            val event = readyLogEvent(health.value.core.readiness)
            logger.record(correlation, OrionLogComponent.RECOVERY, event,
                if (event == OrionLogEvent.CORE_DEGRADED) OrionLogSeverity.WARN else OrionLogSeverity.INFO)
        } catch (cancelled: CancellationException) {
            taskWriteReady = false
            taskStorageReady = false
            taskStartupBarrier.abortRecovery(ticket)
            throw cancelled
        } catch (_: Exception) {
            taskWriteReady = false
            taskStorageReady = false
            taskStartupBarrier.abortRecovery(ticket)
            states[stage] = OrionHealthStatus.FAILED
            publish(states)
            logger.record(correlation, OrionLogComponent.RECOVERY,
                failedRecoveryLogEvent(health.value.core.readiness), OrionLogSeverity.ERROR)
        }
        health.value.core.readiness
    } }

    private fun publish(states: Map<OrionSubsystem, OrionHealthStatus>, lastRecovery: Instant? = null) {
        mutableHealth.value = OrionHealthAggregator.aggregate(states, clock.wallNow(), lastRecovery)
    }
}

private class AndroidApplicationExitMonitor(context: Context) : ApplicationExitMonitor {
    private val manager = context.getSystemService(ActivityManager::class.java)
    override suspend fun recentExits(): List<ProcessExitSignal> =
        manager.getHistoricalProcessExitReasons(null, 0, 8).map { ProcessExitSignal(it.reason, Instant.ofEpochMilli(it.timestamp)) }
}

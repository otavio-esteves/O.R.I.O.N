package network.orion.app

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.ComponentName
import android.os.Build
import android.os.Process
import java.util.concurrent.TimeUnit
import network.orion.app.scheduling.*
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.scheduler.alarms.*
import network.orion.scheduler.workers.OrionMaintenanceScheduler
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
import network.orion.app.memories.*
import network.orion.app.notifications.*
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
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

/** MAIN-owned transactional Core and deterministic domains. UI receives selected Core ports only. */
class OrionFoundationRuntime internal constructor(context: Context) : RecoveryCoordinator {
    private val app = context.applicationContext
    private val clock = AndroidOrionClock(app.contentResolver)
    private val notificationPoster = OrionAndroidNotificationPoster(app, clock)
    private val reminderPoster = OrionAndroidNotificationPoster(app, clock, OrionAndroidNotificationPoster.REMINDER_CHANNEL)
    private val alarmBackend = AndroidOrionAlarmBackend(app, ComponentName(app, OrionReminderAlarmReceiver::class.java))
    private val reminderAlarms = OrionReminderAlarms(alarmBackend)
    @Volatile internal var reminderDelivery: OrionReminderDeliveryPipeline? = null
        private set
    private var scheduling: OrionSchedulingCoordinator? = null
    private var maintenance: OrionMaintenanceScheduler? = null
    private var heartbeatRegistered = false
    @Volatile internal var maintenanceNeedsContinuation = false
        private set
    private val forceStopEvidence = network.orion.core.exitinfo.OrionForceStopStartupEvidence()
    private var forceStopListenerRegistered = false
    private var forceStopCompletionListener: java.util.function.Consumer<android.app.ApplicationStartInfo>? = null
    private var forceStopSignal = "UNAVAILABLE"
    private var lastExitReason: Int? = null
    private var lastExitKind: String? = null
    @Volatile private var recentMemoryLimiterObserved: Boolean? = null
    private val memoryLimiterReason = dedicatedMemoryLimiterReason()
    private val maintenanceObservationSequence = java.util.concurrent.atomic.AtomicLong()
    internal fun platformDiagnostics() = OrionPlatformDiagnostics.from(
        app.getString(R.string.orion_native_qualification_status), memoryLimiterReason != null, recentMemoryLimiterObserved)
    internal fun deliveryGateway() = OrionReminderDeliveryUiGateway({ reminderDelivery },
        { id -> reconcileAuthorizedReminder(id) }, { nudgeUiMaintenance() })
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
    @Volatile internal var memoryKernel: OrionMemoryKernel? = null
        private set
    @Volatile internal var notificationKernel: OrionNotificationKernel? = null
        private set
    val capabilities = CapabilityRegistry { capability ->
        (capability in setOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY, OrionCreateReminderDescriptor.WRITE_CAPABILITY,
            OrionCreateTimerDescriptor.WRITE_CAPABILITY, OrionCreateMemoryDescriptor.WRITE_CAPABILITY) ||
            capability == OrionPostNotificationDescriptor.WRITE_CAPABILITY &&
                notificationPoster.availability() == NotificationAvailability.AVAILABLE ||
            capability == network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor.DELIVERY_CAPABILITY &&
                reminderPoster.availability() == NotificationAvailability.AVAILABLE) &&
            taskWriteReady && taskStartupBarrier.isOpen() && clock.monotonicNow().bootSessionId != null
    }
    internal fun taskGateway(channel: OrionTaskAdmissionOwner.UiChannel) = OrionTaskUiGateway(channel, { taskKernel }, clock, ::taskOperationFailed)
    internal fun reminderGateway(channel: OrionReminderAdmissionOwner.UiChannel) =
        OrionReminderUiGateway(channel, { reminderKernel }, clock, ::taskOperationFailed)
    internal fun timerGateway(channel: OrionTimerAdmissionOwner.UiChannel) =
        OrionTimerUiGateway(channel, { timerKernel }, clock, ::taskOperationFailed)
    internal fun memoryGateway(channel: OrionMemoryAdmissionOwner.UiChannel) =
        OrionMemoryUiGateway(channel, { memoryKernel }, clock, ::taskOperationFailed)
    internal fun notificationGateway(channel: OrionNotificationAdmissionOwner.UiChannel) =
        OrionNotificationUiGateway(channel, { notificationKernel }, clock, ::taskOperationFailed)
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
    internal val memoryAdmissions = OrionMemoryAdmissionOwner(
        OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()),
        taskStartupBarrier, ::writeEvidence, ::presenceEvidence,
    )
    internal val notificationAdmissions = OrionNotificationAdmissionOwner(
        OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()), taskStartupBarrier,
        writeCapability = {
            val core = writeEvidence()
            if (core != OrionPolicyEvidence.SATISFIED) core
            else when (notificationPoster.availability()) {
                NotificationAvailability.AVAILABLE -> OrionPolicyEvidence.SATISFIED
                NotificationAvailability.UNAVAILABLE -> OrionPolicyEvidence.UNAVAILABLE
                else -> OrionPolicyEvidence.NOT_SATISFIED
            }
        },
        devicePresence = ::presenceEvidence,
        permission = ::notificationPermissionEvidence,
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

    private fun notificationPermissionEvidence(): OrionPolicyEvidence = try {
        if (notificationPoster.permissionGranted()) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        OrionPolicyEvidence.UNAVAILABLE
    }
    private var governor = UnavailableResourceGovernor()
    private var database: OrionDatabase? = null
    private val cortexOwnershipLock = Any()
    private var cortexOwner: network.orion.app.ai.OrionCortexRuntimeOwner? = null

    /** Optional on-demand port. No approved production profile ships with this candidate. */
    internal suspend fun cortexRuntime(): network.orion.app.ai.OrionCortexRuntimeOwner? = withContext(Dispatchers.IO) {
        synchronized(cortexOwnershipLock) {
            if (!taskStartupBarrier.isOpen() || !health.value.core.readiness.isStartupBarrierOpen) return@synchronized null
            val db = database ?: return@synchronized null
            cortexOwner ?: network.orion.app.ai.OrionCortexRuntimeOwner(app,
                network.orion.data.repository.models.OrionModelImportCoordinator.open(app, db, clock,
                    maximumModelBytes = 4L * 1024 * 1024 * 1024, reserveBytes = 512L * 1024 * 1024),
                db, clock, coreReady = { taskStartupBarrier.isOpen() && health.value.core.readiness.isStartupBarrierOpen })
                .also { cortexOwner = it }
        }
    }

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
        memoryAdmissions.invalidateAdmissions()
        notificationAdmissions.invalidateAdmissions()
        reminderDelivery?.invalidateAdmissions()
        mutableHealth.publishSubsystemObservations(clock, mapOf(OrionSubsystem.ACTION_OUTBOX to OrionHealthStatus.FAILED))
    }

    @Synchronized fun start() {
        if (startupJob?.isActive == true) return
        startupJob = launchCoreMaintenance {
            registerForceStopCompletionListener()
            // Recovery returns before delivery. Due confirmed work cannot wait for
            // a deferrable JobScheduler/WorkManager opportunity after cold startup.
            requestReconciliation()
        }
    }

    override suspend fun recover(): CoreReadiness = withContext(Dispatchers.IO) { mutex.withLock {
        if (taskStartupBarrier.isOpen() && taskKernel != null && reminderKernel != null && timerKernel != null &&
            memoryKernel != null && notificationKernel != null && health.value.core.readiness.isStartupBarrierOpen &&
            health.value.subsystems[OrionSubsystem.FIELD_KEYS] == OrionHealthStatus.HEALTHY) return@withLock health.value.core.readiness
        val ticket = taskStartupBarrier.beginRecovery()
        taskWriteReady = false
        taskStorageReady = false
        taskAdmissions.invalidateAdmissions()
        reminderAdmissions.invalidateAdmissions()
        timerAdmissions.invalidateAdmissions()
        memoryAdmissions.invalidateAdmissions()
        notificationAdmissions.invalidateAdmissions()
        reminderDelivery?.invalidateAdmissions()
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
            lastExitReason = exits.firstOrNull()?.reason
            lastExitKind = exits.firstOrNull()?.kind?.name
            recentMemoryLimiterObserved = if (states[OrionSubsystem.EXIT_MONITOR] == OrionHealthStatus.HEALTHY)
                exits.any { it.kind == network.orion.core.exitinfo.ProcessExitKind.MEMORY_LIMITER } else null
            sampleForceStop(config)
            if (keyState == FieldKeyInitialization.READY) {
                taskStorageReady = true
                val kernel = OrionTaskKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier, taskAdmissions,
                    { health.value.core.readiness }, { taskStorageReady })
                taskKernel = kernel // Its ingress remains GATED by the actual barrier.
                reminderKernel = OrionReminderKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    reminderAdmissions, { health.value.core.readiness }, { taskStorageReady })
                timerKernel = OrionTimerKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    timerAdmissions, { health.value.core.readiness }, { taskStorageReady })
                memoryKernel = OrionMemoryKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    memoryAdmissions, { health.value.core.readiness }, { taskStorageReady })
                notificationKernel = OrionNotificationKernel(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    notificationAdmissions, { health.value.core.readiness }, { taskStorageReady }, notificationPoster)
                val delivery = OrionReminderDeliveryPipeline(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    { health.value.core.readiness }, { taskStorageReady }, OrionProcessRole.MAIN,
                    { presenceEvidence() == OrionPolicyEvidence.SATISFIED }, reminderPoster,
                    onAuthorized = { id -> launchCoreMaintenance { reconcileAuthorizedReminder(id) } })
                reminderDelivery = delivery
                val scheduler = OrionSchedulingCoordinator(db, OrionFieldEncryption(keys), clock, taskStartupBarrier,
                    { health.value.core.readiness }, { taskStorageReady }, object : OrionReconciliationCursors {
                        override suspend fun read(name: String) = config.maintenanceCursor(name)
                        override suspend fun write(name: String, cursor: String?) = config.saveMaintenanceCursor(name, cursor)
                    }, delivery, reminderAlarms, alarmBackend)
                scheduling = scheduler
                stage = OrionSubsystem.EVENT_OUTBOX
                states[stage] = if (scheduler.publishEvents()) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED
                stage = OrionSubsystem.ACTION_OUTBOX
                kernel.recoverActions()
                states[stage] = OrionHealthStatus.HEALTHY
                stage = OrionSubsystem.REMINDERS
                db.reminderSchedules().invalidateRegistrationObservations()
                // OS registration observations were invalidated, so revisit their whole finite snapshot.
                config.saveMaintenanceCursor("reminders", null)
                config.saveMaintenanceCursor("alarm_cleanup", null)
                // Due paging survives process recreation: resetting it would let an unreadable
                // confirmed prefix starve valid later deliveries across repeated process deaths.
                val snapshotRows = scheduler.count("orion_reminders")
                val maximumBatches = snapshotRows / 200 + 2
                var reconciled = OrionReconciliationResult(false, true)
                var allHealthy = true
                for (batch in 0 until maximumBatches) {
                    reconciled = scheduler.reconcileRegistrations()
                    allHealthy = allHealthy && reconciled.healthy
                    if (reconciled.complete) break
                }
                reconciled = OrionReconciliationResult(reconciled.complete, allHealthy)
                states[stage] = if (reconciled.complete && reconciled.healthy) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED
                stage = OrionSubsystem.SCHEDULER
                check((app as OrionApplication).initializeMainSchedulerIfUnlocked())
                val workers = OrionMaintenanceScheduler(app, OrionMaintenanceWorker::class.java)
                maintenance = workers
                workers.reconcileHeartbeat().result.get(5, TimeUnit.SECONDS)
                heartbeatRegistered = true
                workers.requestReconciliation().result.get(5, TimeUnit.SECONDS)
                states[stage] = if (reconciled.complete && reconciled.healthy) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED
                stage = OrionSubsystem.NOTIFICATIONS
                try {
                    notificationPoster.initializeChannels()
                    states[OrionSubsystem.NOTIFICATIONS] = notificationHealth()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                } catch (_: Exception) {
                    states[OrionSubsystem.NOTIFICATIONS] = OrionHealthStatus.FAILED
                }
                stage = OrionSubsystem.PERMISSIONS
                taskWriteReady = true
                states[OrionSubsystem.PERMISSIONS] = if (app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true ||
                    notificationPermissionEvidence() != OrionPolicyEvidence.SATISFIED)
                    OrionHealthStatus.DEGRADED else OrionHealthStatus.HEALTHY
            }
            // Optional inference remains disabled; hardware/system qualification is still pending.
            states[OrionSubsystem.RESOURCES] = OrionHealthStatus.DEGRADED
            governor = UnavailableResourceGovernor(exits)
            check(!governor.canRunInference())
            taskStartupBarrier.completeRecovery(ticket,
                calculateAndPublishHealth = { publish(states, clock.wallNow()); health.value.core.readiness },
                openIngressAndDrain = {
                    if (taskWriteReady) {
                        taskKernel?.ingress?.drain(25)
                        reminderKernel?.ingress?.drain(25)
                        timerKernel?.ingress?.drain(25)
                        memoryKernel?.ingress?.drain(25)
                        // Preparation/receipt only. External notification execution is NEVER a recovery callback.
                        notificationKernel?.ingress?.drain(25)
                        reminderDelivery?.drainUi(25)
                        scheduling?.ingress?.drain(25)
                    }
                    else taskStartupBarrier.abortRecovery(ticket)
                })
            // Recovery's bounded UI drain ran under the admission monitor; hand off committed consent only after release.
            reminderDelivery?.flushAuthorized()
            val event = readyLogEvent(health.value.core.readiness)
            logger.record(correlation, OrionLogComponent.RECOVERY, event,
                if (event == OrionLogEvent.CORE_DEGRADED) OrionLogSeverity.WARN else OrionLogSeverity.INFO)
        } catch (cancelled: CancellationException) {
            taskWriteReady = false
            taskStorageReady = false
            taskStartupBarrier.abortRecovery(ticket)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            taskWriteReady = false
            taskStorageReady = false
            taskStartupBarrier.abortRecovery(ticket)
            Thread.currentThread().interrupt()
            throw interrupted
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

    internal fun recordMaintenanceFailure() {
        mutableHealth.publishSubsystemObservations(clock, mapOf(OrionSubsystem.SCHEDULER to OrionHealthStatus.DEGRADED))
    }

    private fun launchCoreMaintenance(block: suspend () -> Unit): Job =
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Maintenance interrupted").apply { initCause(interrupted) }
            }
            catch (_: Exception) { recordMaintenanceFailure() }
        }

    /** UI lifecycle only nudges Core-owned work; cancelling a screen cannot cancel an authorized delivery. */
    internal fun nudgeUiMaintenance() {
        launchCoreMaintenance { performMaintenance() }
    }

    /** Framework ingress is persisted before a worker may invoke any future alert. */
    internal suspend fun receiveReminderAlarm(id: UUID, generation: Long) {
        recover()
        mutex.withLock {
            scheduling?.acceptAlarm(id, generation)
            scheduling?.deliverAcceptedAlarm(id, generation)
            maintenance?.requestReconciliation()?.result?.get(5, TimeUnit.SECONDS)
        }
    }

    /** Register this committed new authorization synchronously; unique-work KEEP cannot lose its first deadline. */
    private suspend fun reconcileAuthorizedReminder(id: UUID) = withContext(Dispatchers.IO) {
        recover()
        mutex.withLock {
            val registered = scheduling?.reconcileOne(id) == true
            if (!registered) mutableHealth.publishSubsystemObservations(clock,
                mapOf(OrionSubsystem.SCHEDULER to OrionHealthStatus.DEGRADED))
            maintenance?.requestReconciliation()?.result?.get(5, TimeUnit.SECONDS)
            Unit
        }
    }

    internal suspend fun requestReconciliation(rebuildAlarmArtifacts: Boolean = false) = withContext(Dispatchers.IO) {
        recover()
        if (rebuildAlarmArtifacts) mutex.withLock {
            // A system lifecycle/time/capability event can invalidate artifacts
            // while MAIN is already alive. A prior registration is only an observation.
            database?.reminderSchedules()?.invalidateRegistrationObservations()
            preferences?.saveMaintenanceCursor("reminders", null)
            preferences?.saveMaintenanceCursor("alarm_cleanup", null)
        }
        // Framework callbacks are an opportunity to reconcile now, not merely to
        // enqueue a KEEP worker which may already be deferred by Android.
        performMaintenance()
        mutex.withLock { maintenance?.requestReconciliation()?.result?.get(5, TimeUnit.SECONDS); Unit }
    }

    /** Recovery has returned and released its monitor before this method invokes external delivery. */
    internal suspend fun performMaintenance(origin: OrionMaintenanceOrigin = OrionMaintenanceOrigin.SYSTEM_TRIGGER): Boolean = withContext(Dispatchers.IO) {
        recover()
        mutex.withLock {
            if (!taskStartupBarrier.isOpen() || !taskWriteReady) return@withLock false
            val scheduler = scheduling ?: return@withLock false
            preferences?.let { sampleForceStop(it) }
            taskKernel?.recoverActions()
            val db = database ?: return@withLock false
            // Existing facts and eligible retention must progress before expiry adds
            // cancellation facts; a full outbox must not block its own cleanup.
            scheduler.publishEvents()
            val retentionPolicy = network.orion.data.repository.OrionRetentionPolicy()
            val pruned = network.orion.data.repository.OrionRetentionMaintenance(db.retention(), clock, retentionPolicy).runBatch()
            val delivery = reminderDelivery
            val expirationBatchSize = 100
            val expiredProposals = if (delivery != null)
                network.orion.app.reminderdelivery.OrionReminderDeliveryRetention(db, delivery.actions, clock).expireBatch(expirationBatchSize)
                else 0
            val ingressNeedsContinuation = drainIngressMaintenance(listOfNotNull(
                taskKernel?.ingress?.let { it::drain },
                reminderKernel?.ingress?.let { it::drain },
                timerKernel?.ingress?.let { it::drain },
                memoryKernel?.ingress?.let { it::drain },
                notificationKernel?.ingress?.let { it::drain },
                reminderDelivery?.let { it::drainUi },
            ))
            val reconciled = scheduler.reconcileRegistrations()
            val delivered = scheduler.deliverDue()
            val published = scheduler.publishEvents()
            mutableHealth.publishSubsystemObservations(clock, mapOf(
                OrionSubsystem.EVENT_OUTBOX to if (published) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED,
                OrionSubsystem.NOTIFICATIONS to notificationHealth(),
                OrionSubsystem.PERMISSIONS to if (notificationPermissionEvidence() != OrionPolicyEvidence.SATISFIED ||
                    app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
                    OrionHealthStatus.DEGRADED else OrionHealthStatus.HEALTHY,
                OrionSubsystem.REMINDERS to if (reconciled.healthy && delivered.healthy) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED,
                OrionSubsystem.SCHEDULER to if (reconciled.healthy && reconciled.complete && delivered.healthy) OrionHealthStatus.HEALTHY else OrionHealthStatus.DEGRADED))
            // Retry only unfinished internal batches. Denied permission/UNKNOWN remain observable,
            // and await a later capability event/heartbeat instead of creating a tight retry loop.
            val outcome = maintenanceOutcome(pruned, retentionPolicy.batchSize, expiredProposals, expirationBatchSize,
                ingressNeedsContinuation, reconciled, delivered, published, scheduler.hasDueEvents())
            maintenanceNeedsContinuation = outcome.needsContinuation
            val healthy = outcome.healthy
            try {
                Log.i(OrionMaintenanceSnapshot.LOG_TAG, readMaintenanceSnapshot(app, db, clock, origin, healthy, pruned,
                    maintenanceObservationSequence.incrementAndGet()).toLogLine())
            } catch (_: Exception) {
                // Diagnostics are sampled, optional evidence. Never change authority, retry or recovery due to logging.
                Log.w(OrionMaintenanceSnapshot.LOG_TAG, "SNAPSHOT_UNAVAILABLE")
            }
            healthy
        }
    }

    internal suspend fun schedulingDiagnostics(): OrionSchedulingDiagnostics? = withContext(Dispatchers.IO) { mutex.withLock {
        val scheduler = scheduling ?: return@withLock null
        OrionSchedulingDiagnostics(scheduler.count("orion_reminders", "status = 'PENDING'"),
            scheduler.count("orion_reminders", "precision = 'APPROXIMATE' AND status = 'PENDING'"),
            scheduler.count("orion_domain_event_outbox", "status = 'DEAD_LETTER'"), alarmBackend.availability().name,
            heartbeatRegistered, app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
            forceStopSignal, preferences?.read()?.lastForceStopDetectedAtMillis?.let(Instant::ofEpochMilli), lastExitReason, lastExitKind)
    } }

    private suspend fun sampleForceStop(config: OrionPreferencesStore) {
        if (Build.VERSION.SDK_INT < 35) forceStopEvidence.markUnsupported()
        else if (forceStopEvidence.observation == network.orion.core.exitinfo.ForceStopStartupObservation.UNAVAILABLE)
            observeForceStop()
        forceStopSignal = forceStopEvidence.observation.name
        if (forceStopEvidence.needsPersistence) {
            config.recordForceStopDetection(clock.wallNow().toEpochMilli())
            forceStopEvidence.markPersisted()
        }
    }

    private fun observeForceStop() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val start = app.getSystemService(ActivityManager::class.java)?.getHistoricalProcessStartReasons(8)
                ?.firstOrNull { it.pid == Process.myPid() } ?: return
            forceStopEvidence.observe(start.wasForceStopped(),
                start.startupState == android.app.ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN)
        } catch (_: Exception) { /* Missing/incomplete startup evidence stays explicitly unavailable. */ }
    }

    /** A foreground activity can be a new startup after a prior background process start. */
    internal fun observeForegroundStartup() {
        // Arm before this activity can draw its first frame. An asynchronously
        // queued registration can miss completion on Android 15 implementations.
        // The SDK callback and every durable write still run on Core's IO scope.
        registerForceStopCompletionListener(replaceExisting = true)
    }

    /**
     * The public callback identifies THIS startup even on builds whose historical
     * records report pid=0. Never associate an old wasForceStopped=true by PID guesswork.
     * Completion is asynchronous and automatically removes the listener after one callback.
     */
    @Synchronized private fun registerForceStopCompletionListener(replaceExisting: Boolean = false) {
        if (Build.VERSION.SDK_INT < 35 || forceStopListenerRegistered && !replaceExisting) return
        try {
            val manager = app.getSystemService(ActivityManager::class.java) ?: return
            if (replaceExisting) {
                // Some framework implementations append a consumer to the old
                // transport instead of re-arming it for the foreground startup.
                forceStopCompletionListener?.let { manager.removeApplicationStartInfoCompletionListener(it) }
                forceStopCompletionListener = null
                forceStopListenerRegistered = false
            }
            val listener = java.util.function.Consumer<android.app.ApplicationStartInfo> { start ->
                val observed = start.wasForceStopped()
                launchCoreMaintenance {
                    mutex.withLock {
                        forceStopEvidence.observe(observed, startupComplete = true)
                        preferences?.let { sampleForceStop(it) }
                    }
                }
            }
            manager.addApplicationStartInfoCompletionListener(java.util.concurrent.Executor { task ->
                launchCoreMaintenance { task.run() }
            }, listener)
            forceStopCompletionListener = listener
            forceStopListenerRegistered = true
        } catch (_: Exception) { /* Public optional startup evidence remains unavailable and retryable. */ }
    }

    private fun publish(states: Map<OrionSubsystem, OrionHealthStatus>, lastRecovery: Instant? = null) {
        mutableHealth.value = OrionHealthAggregator.aggregate(states, clock.wallNow(), lastRecovery)
    }

    private fun notificationHealth(): OrionHealthStatus {
        val channels = listOf(notificationPoster.availability(), reminderPoster.availability())
        return when {
            channels.any { it == NotificationAvailability.UNAVAILABLE } -> OrionHealthStatus.UNAVAILABLE
            channels.all { it == NotificationAvailability.AVAILABLE } -> OrionHealthStatus.HEALTHY
            else -> OrionHealthStatus.DEGRADED
        }
    }

    /** A permission/settings return refreshes observations, never opens ingress or creates consent. */
    internal fun refreshNotificationHealth() {
        launchCoreMaintenance { mutex.withLock {
            if (notificationKernel == null) return@withLock
            preferences?.let { sampleForceStop(it) }
            val observed = mutableMapOf<OrionSubsystem, OrionHealthStatus>()
            try {
                observed[OrionSubsystem.NOTIFICATIONS] = notificationHealth()
                observed[OrionSubsystem.PERMISSIONS] = if (notificationPermissionEvidence() != OrionPolicyEvidence.SATISFIED ||
                    app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
                    OrionHealthStatus.DEGRADED else OrionHealthStatus.HEALTHY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                observed[OrionSubsystem.NOTIFICATIONS] = OrionHealthStatus.UNAVAILABLE
                observed[OrionSubsystem.PERMISSIONS] = OrionHealthStatus.DEGRADED
            }
            mutableHealth.publishSubsystemObservations(clock, observed)
            maintenance?.requestReconciliation()?.result?.get(5, TimeUnit.SECONDS)
        } }
    }
}

private class AndroidApplicationExitMonitor(context: Context) : ApplicationExitMonitor {
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val memoryLimiterReason = dedicatedMemoryLimiterReason()
    override suspend fun recentExits(): List<ProcessExitSignal> =
        manager.getHistoricalProcessExitReasons(null, 0, 8).map {
            network.orion.core.exitinfo.OrionProcessExitClassifier.classify(it.reason, Instant.ofEpochMilli(it.timestamp),
                it.description, memoryLimiterReason)
        }
}

private fun dedicatedMemoryLimiterReason(): Int? = try {
    android.app.ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER").getInt(null).takeIf { it > 16 }
} catch (_: ReflectiveOperationException) { null } catch (_: SecurityException) { null }

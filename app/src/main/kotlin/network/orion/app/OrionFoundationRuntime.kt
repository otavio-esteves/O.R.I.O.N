package network.orion.app

import android.app.ActivityManager
import android.content.Context
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
import network.orion.core.model.UnavailableCapabilityRegistry
import network.orion.core.observability.*
import network.orion.core.recovery.RecoveryCoordinator
import network.orion.core.resources.UnavailableResourceGovernor
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseFactory
import network.orion.data.datastore.OrionPreferencesStore

/** Main-only F1 graph. It exposes technical snapshots, never storage handles to the UI. */
class OrionFoundationRuntime internal constructor(context: Context) : RecoveryCoordinator {
    private val app = context.applicationContext
    private val clock = AndroidOrionClock(app.contentResolver)
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
    val ingress = FoundationIngressCoordinator { health.value.core.readiness }
    val actions = UnavailableActionEngine()
    val capabilities = UnavailableCapabilityRegistry()
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

    @Synchronized fun start() {
        if (startupJob?.isActive == true) return
        startupJob = scope.launch { recover() }
    }

    override suspend fun recover(): CoreReadiness = withContext(Dispatchers.IO) { mutex.withLock {
        if (health.value.core.readiness.isStartupBarrierOpen) return@withLock health.value.core.readiness
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
            governor = UnavailableResourceGovernor(exits)
            // F2 action storage exists, but execution/recovery is not wired yet.
            // Mutable ingress/actions remain UNAVAILABLE even after safe reads open;
            // opening storage never resumes a pending or uncertain external effect.
            check(!governor.canRunInference())
            publish(states, clock.wallNow())
            val event = readyLogEvent(health.value.core.readiness)
            logger.record(correlation, OrionLogComponent.RECOVERY, event,
                if (event == OrionLogEvent.CORE_DEGRADED) OrionLogSeverity.WARN else OrionLogSeverity.INFO)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            states[stage] = OrionHealthStatus.FAILED
            publish(states)
            logger.record(correlation, OrionLogComponent.RECOVERY, OrionLogEvent.CORE_BLOCKED, OrionLogSeverity.ERROR)
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

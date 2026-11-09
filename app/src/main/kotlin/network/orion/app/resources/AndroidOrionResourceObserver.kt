package network.orion.app.resources

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import java.time.Instant
import java.util.concurrent.CancellationException
import network.orion.core.resources.*
import network.orion.core.exitinfo.OrionProcessExitClassifier
import network.orion.core.exitinfo.ProcessExitSignal
import network.orion.core.time.OrionClock

/**
 * MAIN-only public-platform observation boundary. Reading never opens storage,
 * starts a process, changes a permission or approves a model. Register this object
 * with Application.registerComponentCallbacks; MAIN forwards actual Activity state.
 *
 * Android getProcessMemoryInfo is rate-limited and may return cached data, while
 * getRunningAppProcesses does not attest an exhaustive fresh roster. Those APIs
 * cannot establish our complete aggregate budget. A qualified MAIN-owned memory
 * source may be supplied; otherwise both inference/aggregate memory remain null
 * and ResourceGovernor denies. This is observable missing evidence, not zero use.
 */
internal class AndroidOrionResourceObserver(
    context: Context,
    private val clock: OrionClock,
    pressureHoldMillis: Long,
    private val completeMemoryEvidence: (() -> OrionAppMemoryEvidence?)? = null,
    private val maximumMemoryAgeMillis: Long = 1_000,
) : ComponentCallbacks2 {
    private val app = context.applicationContext
    private val activity = app.getSystemService(ActivityManager::class.java)
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val pressure = OrionMemoryPressureTracker(clock, pressureHoldMillis)
    @Volatile private var foreground = false
    @Volatile var mainResidentPssBytes: Long? = null
        private set

    init {
        require(Application.getProcessName() == app.packageName) { "Resource observations belong to MAIN." }
        require(maximumMemoryAgeMillis > 0)
    }

    fun setForeground(value: Boolean) { foreground = value }

    fun observe(model: InferenceModelProfile?, requestedThreads: Int, requestedContext: Int,
        requestedTokens: Int): OrionResourceObservation {
        check(Application.getProcessName() == app.packageName)
        // Timestamp precedes all platform reads: slow Binder calls cannot make old
        // values appear freshly observed at the end of collection.
        val sampledAt = clock.monotonicNow()
        val memory = safe { activity?.let { service -> ActivityManager.MemoryInfo().also(service::getMemoryInfo) } }
        val importance = safe { ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance }
        val batteryPercent = safe { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } }
        val thermal = safe { power?.currentThermalStatus?.takeIf { it in 0..6 } }
        val powerSave = safe { power?.isPowerSaveMode }
        val available = memory?.availMem?.takeIf { it >= 0 }
        val currentPressure = pressure.current(memory?.lowMemory)
        // Self-only uncached diagnostic reading includes native/JNI PSS, but is
        // deliberately not substituted for complete cross-process budget evidence.
        mainResidentPssBytes = safe { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong()
            .takeIf { it > 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024) }
        val processMemory = safe {
            val evidence = completeMemoryEvidence?.invoke()
            if (evidence?.processIds?.contains(Process.myPid()) != true) null
            else OrionAppMemoryAccounting.calculate(evidence, clock.monotonicNow(), maximumMemoryAgeMillis)
        }
        val exits = safe { recentExits() }
        val importanceClass = when {
            importance == null -> OrionProcessImportance.UNKNOWN
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> OrionProcessImportance.FOREGROUND
            else -> OrionProcessImportance.BACKGROUND
        }
        return OrionResourceObservation(sampledAt, model, batteryPercent, thermal, foreground,
            importanceClass, powerSave, currentPressure, processMemory?.inferenceProcessBytes,
            processMemory?.aggregateAppBytes, available, exits, requestedThreads, requestedContext, requestedTokens)
    }

    /** AI-scoped history for MAIN's restart circuit; null remains unavailable to the guard. */
    fun recentAiExits(): List<ProcessExitSignal>? = safe {
        val manager = activity ?: return@safe null
        val history = manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXIT_SIGNALS)
        history.filter { it.processName == app.packageName + ":ai" }.map {
            OrionProcessExitClassifier.classify(it.reason, Instant.ofEpochMilli(it.timestamp), it.description, memoryLimiterReason())
        }
    }

    private fun recentExits(): List<ProcessExitSignal>? {
        val manager = activity ?: return null
        return manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXIT_SIGNALS).map {
            OrionProcessExitClassifier.classify(it.reason, Instant.ofEpochMilli(it.timestamp), it.description, memoryLimiterReason())
        }
    }

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                pressure.record(OrionMemoryPressure.CRITICAL)
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                pressure.record(OrionMemoryPressure.MODERATE)
            // UI_HIDDEN is lifecycle evidence, not memory pressure.
        }
    }

    override fun onLowMemory() { pressure.record(OrionMemoryPressure.CRITICAL) }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun memoryLimiterReason(): Int? = try {
        android.app.ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER").getInt(null).takeIf { it > 16 }
    } catch (_: ReflectiveOperationException) { null } catch (_: SecurityException) { null }

    private inline fun <T> safe(read: () -> T): T? = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) { null }

    companion object { private const val MAX_EXIT_SIGNALS = 64 }
}

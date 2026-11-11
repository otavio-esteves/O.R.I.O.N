package network.orion.app.resources

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import java.time.Instant
import java.util.concurrent.CancellationException
import network.orion.core.exitinfo.OrionProcessExitClassifier
import network.orion.core.resources.OrionAiExitHistory
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionProcessImportance
import network.orion.core.resources.OrionResourceObservation
import network.orion.core.time.OrionClock

internal enum class OrionResourceObservationLimitation {
    MEMORY_TELEMETRY_UNAVAILABLE, EXIT_HISTORY_UNAVAILABLE, EXIT_HISTORY_TRUNCATED,
    BATTERY_UNAVAILABLE, POWER_UNAVAILABLE, SYSTEM_MEMORY_UNAVAILABLE, PROCESS_STATE_UNAVAILABLE,
}

internal data class OrionAndroidResourceSnapshot(val observation: OrionResourceObservation,
    val aiExitHistory: OrionAiExitHistory?, val mainPssBytes: Long?,
    val limitations: Set<OrionResourceObservationLimitation>)

/**
 * MAIN-only observation boundary. No launch, polling, model selection or authority.
 * Complete fresh auxiliary PSS must arrive from the future IPC telemetry owner;
 * ActivityManager's rate-limited cross-process samples cannot establish freshness.
 */
internal class OrionAndroidResourceObserver(
    context: Context,
    private val clock: OrionClock,
    private val auxiliaryMemory: () -> OrionAppMemoryEvidence? = { null },
    private val maximumMemoryAgeMillis: Long,
) {
    private val app = context.applicationContext
    private val mainName = app.packageName
    private val aiName = "$mainName:ai"

    init {
        require(Application.getProcessName() == mainName) { "Resource observations belong to MAIN." }
        require(maximumMemoryAgeMillis > 0)
    }

    fun observe(qualifiedPlan: OrionInferenceResourcePlan?, requestedThreads: Int,
        requestedContext: Int, requestedTokens: Int): OrionAndroidResourceSnapshot {
        val sampledAt = clock.monotonicNow()
        val manager = read { app.getSystemService(ActivityManager::class.java) }
        val power = read { app.getSystemService(PowerManager::class.java) }
        val battery = read { app.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } }
        val thermal = read { power?.currentThermalStatus }
        val saver = read { power?.isPowerSaveMode }
        val state = read { ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState) }
        val system = read { manager?.let { owner -> ActivityManager.MemoryInfo().also(owner::getMemoryInfo) } }
        val mainPss = read {
            Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong().takeIf { it > 0 }?.times(1024)
        }
        val mainReading = mainPss?.let { OrionProcessMemoryReading(mainName, sampledAt, it, true) }
        val totals = aggregateProcessMemory(read(auxiliaryMemory), mainReading, mainName, aiName,
            clock.monotonicNow(), maximumMemoryAgeMillis)
        // 65 is a sentinel: an at-cap history is not claimed complete.
        val exits = read { manager?.getHistoricalProcessExitReasons(mainName, 0, MAX_EXITS + 1) }
        val boundedExits = exits?.take(MAX_EXITS + 1)
        val truncated = boundedExits != null && boundedExits.size > MAX_EXITS
        val dedicatedReason = read { ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER")
            .getInt(null).takeIf { it > 16 } }
        val sanitized = if (truncated) null else boundedExits?.map { exit ->
            OrionProcessExitClassifier.classify(exit.reason, Instant.ofEpochMilli(exit.timestamp), exit.description, dedicatedReason)
        }
        val aiExits = if (truncated) null else boundedExits?.filter { it.processName == aiName }?.map { exit ->
            OrionProcessExitClassifier.classify(exit.reason, Instant.ofEpochMilli(exit.timestamp), exit.description, dedicatedReason)
        }
        val importance = when (state?.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> OrionProcessImportance.FOREGROUND
            null -> OrionProcessImportance.UNKNOWN
            else -> OrionProcessImportance.BACKGROUND
        }
        val pressure = classifyAndroidMemoryPressure(system?.lowMemory, state?.lastTrimLevel)
        val observation = OrionResourceObservation(sampledAt, qualifiedPlan?.model, battery, thermal,
            state?.let { importance == OrionProcessImportance.FOREGROUND }, importance, saver, pressure,
            totals.aiBytes, totals.aggregateBytes, system?.availMem?.takeIf { it >= 0 }, sanitized,
            requestedThreads, requestedContext, requestedTokens)
        val limitations = buildSet {
            if (totals.aggregateBytes == null) add(OrionResourceObservationLimitation.MEMORY_TELEMETRY_UNAVAILABLE)
            if (exits == null) add(OrionResourceObservationLimitation.EXIT_HISTORY_UNAVAILABLE)
            if (truncated) add(OrionResourceObservationLimitation.EXIT_HISTORY_TRUNCATED)
            if (battery == null) add(OrionResourceObservationLimitation.BATTERY_UNAVAILABLE)
            if (thermal == null || saver == null) add(OrionResourceObservationLimitation.POWER_UNAVAILABLE)
            if (system == null) add(OrionResourceObservationLimitation.SYSTEM_MEMORY_UNAVAILABLE)
            if (state == null) add(OrionResourceObservationLimitation.PROCESS_STATE_UNAVAILABLE)
        }
        return OrionAndroidResourceSnapshot(observation, aiExits?.let { OrionAiExitHistory(sampledAt, it) }, mainPss, limitations)
    }

    private fun <T> read(source: () -> T?): T? = try { source() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
    catch (_: Exception) { null }

    companion object { private const val MAX_EXITS = 64 }
}

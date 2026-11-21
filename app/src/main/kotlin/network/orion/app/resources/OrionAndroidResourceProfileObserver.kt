package network.orion.app.resources

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.util.concurrent.CancellationException
import network.orion.core.resources.OrionResourcePolicy
import network.orion.core.resources.OrionResourceProfiles

internal data class OrionAndroidResourceProfileSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermalStatus: Int?,
    val powerSave: Boolean?,
    val screenInteractive: Boolean?,
    val backgroundRestricted: Boolean?,
    val policy: OrionResourcePolicy,
)

/** MAIN samples only system state needed for context and optional work. */
internal class OrionAndroidResourceProfileObserver(context: Context) {
    private val app = context.applicationContext

    init {
        require(Application.getProcessName() == app.packageName) { "Resource profiles belong to MAIN." }
    }

    fun observe(): OrionAndroidResourceProfileSnapshot {
        val battery = read { app.getSystemService(BatteryManager::class.java) }
        val power = read { app.getSystemService(PowerManager::class.java) }
        val activity = read { app.getSystemService(ActivityManager::class.java) }
        val batteryPercent = read { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            ?.takeIf { it in 0..100 }
        val charging = read { battery?.isCharging }
        val thermalStatus = read { power?.currentThermalStatus }
            ?.takeIf { it in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN }
        val powerSave = read { power?.isPowerSaveMode }
        val screenInteractive = read { power?.isInteractive }
        val backgroundRestricted = read { activity?.isBackgroundRestricted }
        val systemLowMemory = read { activity?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).lowMemory } }
        val trimLevel = read {
            ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).lastTrimLevel
        }
        val memoryPressure = classifyAndroidMemoryPressure(systemLowMemory, trimLevel)
        val policy = OrionResourceProfiles.select(batteryPercent, thermalStatus, powerSave, memoryPressure,
            backgroundRestricted)
        return OrionAndroidResourceProfileSnapshot(batteryPercent, charging, thermalStatus, powerSave,
            screenInteractive, backgroundRestricted, policy)
    }

    private fun <T> read(source: () -> T?): T? = try {
        source()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        null
    }
}

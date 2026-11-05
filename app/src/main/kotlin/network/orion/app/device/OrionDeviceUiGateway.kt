package network.orion.app.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.queries.device.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.time.WallClock
import network.orion.skills.device.*

/** MAIN-selected read adapter. Sticky battery query never registers a persistent receiver. */
internal class OrionDeviceUiGateway(context: Context, clock: WallClock,
    zone: () -> ZoneId = { ZoneId.systemDefault() }) : DeviceInformationGateway {
    private val app = context.applicationContext
    private val skill = OrionDeviceInformationSkill(DeviceInformationSource { observe() }, clock, zone)
    override suspend fun get(request: GetDeviceInformation): DeviceInformation? = withContext(Dispatchers.IO) {
        if (OrionProcessRole.fromProcessName(app.packageName, android.app.Application.getProcessName()) != OrionProcessRole.MAIN)
            return@withContext null
        skill.get(request)
    }
    private fun observe(): DeviceObservation {
        val battery = try {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
                fun extra(name: String): Int? = if (intent.hasExtra(name)) intent.getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
                BatteryObservation(extra("level"), extra("scale"), extra("status"), extra("plugged"))
            }
        } catch (_: SecurityException) { null }
        val power = app.getSystemService(PowerManager::class.java)
        val manager = app.getSystemService(ActivityManager::class.java)
        return DeviceObservation(battery, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            power?.isPowerSaveMode, manager?.isBackgroundRestricted, power?.isInteractive)
    }
}

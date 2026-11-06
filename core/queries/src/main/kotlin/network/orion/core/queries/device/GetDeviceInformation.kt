package network.orion.core.queries.device

import java.time.Instant
import java.time.ZoneId
import network.orion.core.queries.Query

/** Read snapshot only; never requests a permission, wakes hardware or grants authority. */
data object GetDeviceInformation : Query<DeviceInformation>

enum class ChargingState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }
enum class PowerConnection { UNPLUGGED, AC, USB, WIRELESS, DOCK, UNKNOWN }

class DeviceInformation(
    val observedAt: Instant,
    val zoneId: ZoneId,
    val batteryPercent: Int?,
    val charging: ChargingState,
    val connection: PowerConnection,
    val manufacturer: String?,
    val model: String?,
    val apiLevel: Int?,
    val powerSave: Boolean?,
    val backgroundRestricted: Boolean?,
    val interactive: Boolean?,
) {
    init { require(batteryPercent == null || batteryPercent in 0..100); require(apiLevel == null || apiLevel > 0) }
    override fun toString() = "DeviceInformation(<redacted>)"
}

fun interface DeviceInformationQuery { fun get(request: GetDeviceInformation): DeviceInformation }
fun interface DeviceInformationGateway { suspend fun get(request: GetDeviceInformation): DeviceInformation? }

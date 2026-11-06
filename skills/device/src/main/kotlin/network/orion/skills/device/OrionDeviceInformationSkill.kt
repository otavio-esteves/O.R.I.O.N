package network.orion.skills.device

import java.time.ZoneId
import network.orion.core.queries.device.*
import network.orion.core.skills.metadata.device.OrionDeviceDescriptors
import network.orion.core.time.WallClock

/** Raw platform observations; null is unobserved, never a synthetic zero/false. */
data class BatteryObservation(val level: Int?, val scale: Int?, val status: Int?, val plugged: Int?)

class DeviceObservation(val battery: BatteryObservation?, val manufacturer: String?, val model: String?,
    val apiLevel: Int?, val powerSave: Boolean?, val backgroundRestricted: Boolean?, val interactive: Boolean?) {
    override fun toString() = "DeviceObservation(<redacted>)"
}
fun interface DeviceInformationSource { fun observe(): DeviceObservation }

/** Deterministic read-only Skill. No ActionRequest/SkillContext is fabricated for safe queries. */
class OrionDeviceInformationSkill(private val source: DeviceInformationSource,
    private val clock: WallClock, private val zone: () -> ZoneId) : DeviceInformationQuery {
    val batteryDescriptor get() = OrionDeviceDescriptors.battery
    val timeDescriptor get() = OrionDeviceDescriptors.time
    val statusDescriptor get() = OrionDeviceDescriptors.status

    override fun get(request: GetDeviceInformation): DeviceInformation {
        val observed = source.observe()
        val battery = observed.battery
        val percentage = battery?.let {
            val level = it.level; val scale = it.scale
            if (level == null || scale == null || scale <= 0 || level !in 0..scale) null
            else ((level.toLong() * 100) / scale).toInt()
        }
        val charging = when (battery?.status) {
            2 -> ChargingState.CHARGING; 3 -> ChargingState.DISCHARGING
            4 -> ChargingState.NOT_CHARGING; 5 -> ChargingState.FULL
            else -> ChargingState.UNKNOWN
        }
        val connection = when (battery?.plugged) {
            0 -> PowerConnection.UNPLUGGED; 1 -> PowerConnection.AC; 2 -> PowerConnection.USB
            4 -> PowerConnection.WIRELESS; 8 -> PowerConnection.DOCK
            else -> PowerConnection.UNKNOWN
        }
        return DeviceInformation(clock.wallNow(), zone(), percentage, charging, connection,
            safeLabel(observed.manufacturer), safeLabel(observed.model), observed.apiLevel?.takeIf { it > 0 },
            observed.powerSave, observed.backgroundRestricted, observed.interactive)
    }
    private fun safeLabel(value: String?): String? = value?.takeIf { it.length in 1..120 && it.none(Char::isISOControl) }
}

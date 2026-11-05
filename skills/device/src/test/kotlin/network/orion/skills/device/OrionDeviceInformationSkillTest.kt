package network.orion.skills.device

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import network.orion.core.queries.device.*
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionDeviceInformationSkillTest {
    private val instant = Instant.parse("2026-11-05T12:34:56Z")
    private fun read(battery: BatteryObservation?, manufacturer: String? = "Samsung", model: String? = "Galaxy") =
        OrionDeviceInformationSkill(DeviceInformationSource {
            DeviceObservation(battery, manufacturer, model, 35, true, false, null)
        }, WallClock { instant }, { ZoneId.of("America/Sao_Paulo") }).get(GetDeviceInformation)

    @Test fun unknownOrMalformedBatteryObservationsNeverBecomeZeroOrFalse() {
        for (battery in listOf(null, BatteryObservation(null, null, null, null), BatteryObservation(-1, 100, -1, -1),
            BatteryObservation(101, 100, -1, -1), BatteryObservation(1, 0, -1, -1), BatteryObservation(1, -1, -1, -1))) {
            val result = read(battery)
            assertNull(result.batteryPercent)
            assertEquals(ChargingState.UNKNOWN, result.charging)
            assertEquals(PowerConnection.UNKNOWN, result.connection)
        }
    }
    @Test fun percentageArithmeticIsBoundedAndCannotOverflowInt() {
        for ((level, scale, percent) in listOf(Triple(0,100,0), Triple(100,100,100), Triple(1,3,33),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE,100), Triple(Int.MAX_VALUE / 2,Int.MAX_VALUE,49)))
            assertEquals(percent, read(BatteryObservation(level,scale,1,0)).batteryPercent)
    }
    @Test fun chargingAndPhysicalConnectionRemainIndependentFacts() {
        val result = read(BatteryObservation(100,100,5,1))
        assertEquals(ChargingState.FULL,result.charging)
        assertEquals(PowerConnection.AC,result.connection)
        val stopped = read(BatteryObservation(80,100,4,2))
        assertEquals(ChargingState.NOT_CHARGING,stopped.charging)
        assertEquals(PowerConnection.USB,stopped.connection)
        assertEquals(ChargingState.DISCHARGING,read(BatteryObservation(20,100,3,0)).charging)
        assertEquals(ChargingState.CHARGING,read(BatteryObservation(20,100,2,4)).charging)
        assertEquals(PowerConnection.WIRELESS,read(BatteryObservation(20,100,2,4)).connection)
        assertEquals(PowerConnection.DOCK,read(BatteryObservation(20,100,2,8)).connection)
        assertEquals(PowerConnection.UNKNOWN,read(BatteryObservation(20,100,2,3)).connection)
    }
    @Test fun timeAndZoneComeFromInjectedBoundariesAndReadHasNoCache() {
        var now = instant
        val count = AtomicInteger()
        val query = OrionDeviceInformationSkill(DeviceInformationSource {
            count.incrementAndGet(); DeviceObservation(null,null,null,null,null,null,null)
        }, WallClock { now }, { ZoneId.of("UTC") })
        assertEquals(instant,query.get(GetDeviceInformation).observedAt)
        now = instant.minusSeconds(500)
        val next = query.get(GetDeviceInformation)
        assertEquals(now,next.observedAt); assertEquals(ZoneId.of("UTC"),next.zoneId)
        assertEquals(2,count.get()); assertNull(next.powerSave); assertNull(next.backgroundRestricted); assertNull(next.interactive)
    }
    @Test fun platformLabelsAreBoundedAndDiagnosticStringsStayRedacted() {
        assertNull(read(null,"x".repeat(121),"secret\nmodel").manufacturer)
        assertNull(read(null,"Samsung","secret\nmodel").model)
        assertEquals("DeviceInformation(<redacted>)",read(null).toString())
        assertEquals("DeviceObservation(<redacted>)",DeviceObservation(null,"PRIVATE","MODEL",35,null,null,null).toString())
    }
    @Test fun DTORejectsImpossiblePercentagesInsteadOfPresentingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceInformation(instant,ZoneId.of("UTC"),101,ChargingState.UNKNOWN,PowerConnection.UNKNOWN,
                null,null,35,null,null,null)
        }
    }
}

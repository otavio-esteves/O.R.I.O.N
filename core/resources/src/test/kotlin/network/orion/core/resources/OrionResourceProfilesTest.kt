package network.orion.core.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionResourceProfilesTest {
    private fun select(
        battery: Int? = 80,
        thermal: Int? = 0,
        powerSave: Boolean? = false,
        pressure: OrionMemoryPressure = OrionMemoryPressure.NONE,
        restricted: Boolean? = false,
    ) = OrionResourceProfiles.select(battery, thermal, powerSave, pressure, restricted)

    @Test fun `only the normal profile permits optional proactivity and qualified keep alive`() {
        val normal = select()
        assertEquals(OrionResourceProfile.NORMAL, normal.profile)
        assertTrue(normal.allowsProactivity)
        assertEquals(1_000L, normal.limitKeepAlive(1_000))

        listOf(
            select(powerSave = true) to OrionResourceProfile.ECONOMY,
            select(thermal = 2) to OrionResourceProfile.ECONOMY,
            select(pressure = OrionMemoryPressure.MODERATE) to OrionResourceProfile.ECONOMY,
            select(battery = 20) to OrionResourceProfile.LOW_POWER,
            select(battery = 5) to OrionResourceProfile.CRITICAL,
            select(pressure = OrionMemoryPressure.CRITICAL) to OrionResourceProfile.CRITICAL,
            select(thermal = 3) to OrionResourceProfile.THERMAL_LIMIT,
        ).forEach { (policy, expected) ->
            assertEquals(expected, policy.profile)
            assertFalse(policy.allowsProactivity)
            assertEquals(0L, policy.limitKeepAlive(1_000))
        }
    }

    @Test fun `known severe observations precede missing and milder evidence`() {
        assertEquals(OrionResourceProfile.CRITICAL, select(battery = 0, thermal = 6, powerSave = null).profile)
        assertEquals(OrionResourceProfile.THERMAL_LIMIT, select(battery = null, thermal = 6).profile)
        assertEquals(OrionResourceProfile.LOW_POWER, select(battery = 6, powerSave = true).profile)
        assertEquals(OrionResourceProfile.NORMAL, select(battery = 21, thermal = 1).profile)
    }

    @Test fun `missing or invalid readings never enable optional work`() {
        listOf(select(battery = null), select(battery = 101), select(thermal = null), select(thermal = 7),
            select(powerSave = null), select(pressure = OrionMemoryPressure.UNKNOWN)).forEach {
            assertEquals(OrionResourceProfile.LOW_POWER, it.profile)
            assertFalse(it.allowsProactivity)
        }
        for (restricted in listOf(true, null)) {
            assertEquals(OrionResourceProfile.NORMAL, select(restricted = restricted).profile)
            assertFalse(select(restricted = restricted).allowsProactivity)
        }
    }
}

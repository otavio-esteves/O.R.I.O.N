package network.orion.app.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import network.orion.core.queries.device.GetDeviceInformation
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionDeviceUiGatewayTest {
    @Test fun platformAdapterReadsPermissionFreeSnapshotWithInjectedCivilTime() = runBlocking {
        val context:Context=ApplicationProvider.getApplicationContext()
        val at=Instant.parse("2026-11-05T12:34:56Z")
        val gateway=OrionDeviceUiGateway(context,WallClock { at },{ ZoneId.of("UTC") })
        val state=checkNotNull(gateway.get(GetDeviceInformation))
        assertEquals(at,state.observedAt); assertEquals(ZoneId.of("UTC"),state.zoneId)
        assertTrue(state.apiLevel!=null && state.apiLevel!!>=31)
        assertTrue(state.batteryPercent==null || state.batteryPercent!! in 0..100)
    }
}

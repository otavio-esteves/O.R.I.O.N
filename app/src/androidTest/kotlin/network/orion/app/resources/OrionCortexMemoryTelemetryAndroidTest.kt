package network.orion.app.resources

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Bundle/Parcel decoding only; this does not qualify actual memory sampling. */
@RunWith(AndroidJUnit4::class)
class OrionCortexMemoryTelemetryAndroidTest {
    private val main = "network.orion.app"
    private val names = setOf(main, "$main:ai")
    private val telemetry = OrionCortexMemoryTelemetry(main,
        MonotonicClock { MonotonicInstant(BootSessionId(7), 1000) }, 100)
    private fun receipt() = Bundle().apply {
        putInt("schemaVersion", 1); putString("status", "OK")
        putString("processName", "$main:ai"); putInt("pid", 123); putInt("bootCount", 7)
        putLong("sampledElapsed", 999); putLong("totalPssBytes", 20_000); putBoolean("nativeIncluded", true)
    }
    private fun read(bundle: Bundle) = telemetry.read(bundle, 123, names, true)

    @Test fun validRealBundleProducesOnlyFreshAiEvidence() {
        val evidence = checkNotNull(read(receipt()))
        assertEquals(names, evidence.processNames)
        assertEquals(20_000L, evidence.readings.single().totalPssBytes)
        assertEquals("$main:ai", evidence.readings.single().processName)
    }

    @Test fun missingFieldsAndWrongPrimitiveTypesNeverBecomeDefaultMeasurements() {
        for (key in receipt().keySet()) assertNull(read(receipt().apply { remove(key) }))
        assertNull(read(receipt().apply { putLong("schemaVersion", 1) }))
        assertNull(read(receipt().apply { putString("pid", "123") }))
        assertNull(read(receipt().apply { putInt("totalPssBytes", 20_000) }))
        assertNull(read(receipt().apply { putInt("nativeIncluded", 1) }))
    }

    @Test fun unknownOrOversizedEnvelopeDoesNotReachEvidence() {
        assertNull(read(receipt().apply { putString("undeclared", "extra") }))
        assertNull(read(receipt().apply { putString("processName", "x".repeat(20 * 1024)) }))
    }

    @Test fun serviceReceiptCannotClaimCompleteApplicationInventory() {
        assertNull(telemetry.read(receipt(), 123, names, false))
        assertNull(telemetry.read(receipt(), 123, names + "$main:voice", true))
    }
}

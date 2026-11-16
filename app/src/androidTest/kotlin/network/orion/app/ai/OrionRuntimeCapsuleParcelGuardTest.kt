package network.orion.app.ai

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real EMULATOR Parcel/framework mechanics only. No physical/model/admission/transport-envelope proof. */
@RunWith(AndroidJUnit4::class)
class OrionRuntimeCapsuleParcelGuardTest {
    private fun bundle(size: Int) = Bundle().apply {
        putString(OrionRuntimeCapsuleParcelGuard.STATUS_KEY, "{}")
        putString(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY, "x".repeat(size))
        putString("stream", "ORION_RUNTIME_SOURCE")
    }
    @Test fun nativeParcelBoundaryIsMeasuredRatherThanAssumedFromUtf8Length() {
        val limit = OrionRuntimeCapsuleParcelGuard.MAX_BUNDLE_BYTES
        var low = 0; var high = 256 * 1024
        // Fixed finite binary search over native Parcel.writeBundle, independent of an assumed UTF-16 formula.
        repeat(19) {
            if (low < high) {
                val middle = (low + high + 1) / 2
                val size = checkNotNull(OrionRuntimeCapsuleParcelGuard.measuredBundleBytes(bundle(middle)))
                if (size <= limit) low = middle else high = middle - 1
            }
        }
        assertEquals(low, high)
        val acceptedBytes = checkNotNull(OrionRuntimeCapsuleParcelGuard.measuredBundleBytes(bundle(low)))
        val rejectedBytes = checkNotNull(OrionRuntimeCapsuleParcelGuard.measuredBundleBytes(bundle(low + 1)))
        assertTrue(acceptedBytes <= limit); assertTrue(rejectedBytes > limit)
        assertTrue(acceptedBytes > low) // Native representation differs from source ASCII/UTF-8 count.
    }
    @Test fun sourceAtItsOwnBoundGetsRealParcelMeasurementButAbsentOuterProofStillOmitsSource() {
        val header = OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE).status
        val encoded = OrionRuntimeEncodedCapsule("x".repeat(96 * 1024), header, null) // synthetic shape for size adapter only
        val result = OrionRuntimeCapsuleParcelGuard.guard(encoded)
        assertNotNull(result.measuredCandidateBytes)
        assertTrue(checkNotNull(result.measuredCandidateBytes) <= 224 * 1024)
        assertFalse(result.outerEnvelopeProven); assertFalse(result.totalInlineTransactionProven)
        assertEquals(OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE, result.failure)
        val small = checkNotNull(result.bundle)
        assertEquals(setOf(OrionRuntimeCapsuleParcelGuard.STATUS_KEY, "stream"), small.keySet())
        assertFalse(small.containsKey(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY))
        val status = JSONObject(checkNotNull(small.getString(OrionRuntimeCapsuleParcelGuard.STATUS_KEY)))
        assertEquals("NO_V1_RECORD", status.getString("recordState"))
        assertEquals("TRANSPORT_UNAVAILABLE", status.getString("failureCode"))
        assertTrue(status.isNull("ownerCallInvoked")); assertFalse(status.getBoolean("runtimeEvidenceV1Complete"))
        assertTrue(checkNotNull(OrionRuntimeCapsuleParcelGuard.measuredBundleBytes(small)) <= 224 * 1024)
    }
    @Test fun oversizedOrNonAsciiComponentNeverPublishesSourceOrClipsIt() {
        val header = OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE).status
        for (source in listOf("x".repeat(96 * 1024 + 1), "é")) {
            val result = OrionRuntimeCapsuleParcelGuard.guard(OrionRuntimeEncodedCapsule(source, header, null))
            assertEquals(if (source.length > 96 * 1024) OrionRuntimeExportFailure.EXPORT_TOO_LARGE else OrionRuntimeExportFailure.FRAMING_INVALID, result.failure)
            assertFalse(checkNotNull(result.bundle).containsKey(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY))
        }
        val refusal = OrionRuntimeCapsuleParcelGuard.guard(OrionRuntimeCapsuleEncoder.encode(null))
        assertEquals(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, refusal.failure)
        assertEquals(setOf(OrionRuntimeCapsuleParcelGuard.STATUS_KEY, "stream"), checkNotNull(refusal.bundle).keySet())
    }
    @Test fun localFrameworkContextAcceptsOnlyActualQualificationInstrumentationTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val local = OrionRuntimeQualifiedContext.fromInstrumentation(instrumentation)
        if (instrumentation.targetContext.packageName == "network.orion.qualification") {
            assertNotNull(local); assertEquals(android.os.Process.myUid(), checkNotNull(local).mainUid)
            assertEquals(android.os.Process.myPid(), local.mainPid)
            assertEquals(instrumentation.context.packageName, local.testPackage)
        } else assertNull(local)
    }
}

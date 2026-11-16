package network.orion.app.ai

import android.os.Bundle
import android.os.Parcel

internal class OrionRuntimeGuardedExport(val bundle: Bundle?, val measuredCandidateBytes: Int?,
    val failure: OrionRuntimeExportFailure) {
    // No enclosing instrumentation transaction exists in this component's boundary.
    val outerEnvelopeProven = false
    val totalInlineTransactionProven = false
}

/** Real native Bundle measurement is necessary, and deliberately insufficient for outer framing. */
internal object OrionRuntimeCapsuleParcelGuard {
    const val MAX_BUNDLE_BYTES = 224 * 1024
    const val RESERVED_OUTER_BYTES = 32 * 1024
    const val MAX_TOTAL_INLINE_BYTES = 256 * 1024
    const val STATUS_KEY = "orionRuntimeCaptureStatus"
    const val SOURCE_KEY = "orionRuntimeCaptureSource"

    /** Only a measurement seam. Does not grant permission to send this or any outer transaction. */
    fun measuredBundleBytes(bundle: Bundle): Int? = try {
        val parcel = Parcel.obtain()
        try { parcel.writeBundle(bundle); parcel.dataSize() } finally { parcel.recycle() }
    } catch (_: Throwable) { null }

    fun guard(encoded: OrionRuntimeEncodedCapsule): OrionRuntimeGuardedExport {
        var measured: Int? = null
        var failure = encoded.failure ?: OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE
        try {
            val source = encoded.source
            if (encoded.status.length > OrionRuntimeCapsuleEncoder.MAX_STATUS_BYTES ||
                source != null && source.length > OrionRuntimeCapsuleEncoder.MAX_SOURCE_BYTES)
                failure = OrionRuntimeExportFailure.EXPORT_TOO_LARGE
            else if (encoded.status.any { it.code > 127 } || source?.any { it.code > 127 } == true)
                failure = OrionRuntimeExportFailure.FRAMING_INVALID
            else {
                val candidate = bundle(encoded.status, source)
                measured = measuredBundleBytes(candidate)
                failure = when {
                    measured == null -> OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE
                    measured > MAX_BUNDLE_BYTES -> OrionRuntimeExportFailure.EXPORT_TOO_LARGE
                    source != null -> OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE // outer proof absent; never assert 256 KiB from Bundle alone
                    else -> encoded.failure ?: OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
                }
            }
            val refusal = OrionRuntimeCapsuleEncoder.unavailable(failure)
            val small = bundle(refusal.status, null)
            val smallBytes = measuredBundleBytes(small)
            return OrionRuntimeGuardedExport(small.takeIf { smallBytes != null && smallBytes <= MAX_BUNDLE_BYTES }, measured, failure)
        } catch (_: Throwable) { return OrionRuntimeGuardedExport(null, measured, OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE) }
    }

    private fun bundle(status: String, source: String?): Bundle = Bundle().apply {
        putString(STATUS_KEY, status)
        if (source != null) putString(SOURCE_KEY, source)
        putString("stream", if (source != null) "ORION_RUNTIME_SOURCE" else "ORION_RUNTIME_UNAVAILABLE")
    }
}

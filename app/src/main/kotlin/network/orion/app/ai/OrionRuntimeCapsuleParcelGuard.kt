package network.orion.app.ai

import android.os.Bundle
import android.os.Parcel

internal class OrionRuntimeGuardedExport(val bundle: Bundle?, val measuredCandidateBytes: Int?,
    val failure: OrionRuntimeExportFailure?, private val geometry: OrionRuntimeInstrumentationGeometry? = null) {
    val outerEnvelopeProven: Boolean get() = geometry?.withinBudget == true
    val totalInlineTransactionProven: Boolean get() = geometry?.withinBudget == true
}

/** Bundle plus both source-reviewed real framework layouts. Missing actual mode context still refuses SOURCE. */
internal object OrionRuntimeCapsuleParcelGuard {
    const val MAX_BUNDLE_BYTES = 224 * 1024
    const val RESERVED_OUTER_BYTES = 32 * 1024
    const val MAX_TOTAL_INLINE_BYTES = 256 * 1024
    const val STATUS_KEY = "orionRuntimeCaptureStatus"
    const val SOURCE_KEY = "orionRuntimeCaptureSource"
    const val STREAM_SOURCE = "ORION_RUNTIME_SOURCE"
    const val STREAM_REFUSED = "ORION_RUNTIME_REFUSED"
    const val STREAM_UNAVAILABLE = "ORION_RUNTIME_UNAVAILABLE"

    fun measuredBundleBytes(bundle: Bundle): Int? = try {
        val parcel = Parcel.obtain()
        try { parcel.writeBundle(bundle); parcel.dataSize() } finally { parcel.recycle() }
    } catch (_: Throwable) { null }

    fun guard(encoded: OrionRuntimeEncodedCapsule, context: OrionRuntimeInstrumentationContext? = null): OrionRuntimeGuardedExport {
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
                val candidate = bundle(encoded)
                measured = measuredBundleBytes(candidate)
                val shape = context?.let { OrionRuntimeInstrumentationFraming.measure(candidate, it.component) }
                when {
                    measured == null -> failure = OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE
                    measured > MAX_BUNDLE_BYTES -> failure = OrionRuntimeExportFailure.EXPORT_TOO_LARGE
                    context != null && shape?.withinBudget != true -> failure = OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE
                    source != null && context == null -> failure = OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE
                    else -> return OrionRuntimeGuardedExport(candidate, measured, encoded.failure, shape)
                }
            }
            val refusal = OrionRuntimeCapsuleEncoder.unavailable(failure, encoded.diagnosticReceipt, encoded.programWitness)
            val small = bundle(refusal)
            val smallBytes = measuredBundleBytes(small)
            val smallShape = context?.let { OrionRuntimeInstrumentationFraming.measure(small, it.component) }
            return OrionRuntimeGuardedExport(small.takeIf { smallBytes != null && smallBytes <= MAX_BUNDLE_BYTES &&
                (context == null || smallShape?.withinBudget == true) }, measured, failure, smallShape)
        } catch (_: Throwable) { return OrionRuntimeGuardedExport(null, measured, OrionRuntimeExportFailure.TRANSPORT_UNAVAILABLE) }
    }

    private fun bundle(encoded: OrionRuntimeEncodedCapsule): Bundle = Bundle().apply {
        putString(STATUS_KEY, encoded.status)
        encoded.source?.let { putString(SOURCE_KEY, it) }
        putString("stream", if (encoded.source != null) STREAM_SOURCE else if (encoded.programWitness?.noLoadRefusal == true &&
            encoded.programWitness.ownerCallInvoked == false) STREAM_REFUSED else STREAM_UNAVAILABLE)
    }
}

package network.orion.app.ai

import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Process

/** Real framework component and local qualification identity. No caller-selected strings or proof Boolean. */
internal class OrionRuntimeInstrumentationContext private constructor(val qualified: OrionRuntimeQualifiedContext,
    val component: ComponentName) {
    companion object {
        const val RUNNER = "network.orion.app.compatibility.OrionCortexRuntimeCaptureInstrumentation"
        const val WORKLOAD_RUNNER = "network.orion.app.compatibility.OrionCortexInstrumentedWorkloadInstrumentation"
        fun fromInstrumentation(instrumentation: Instrumentation): OrionRuntimeInstrumentationContext? = try {
            val qualified = OrionRuntimeQualifiedContext.fromInstrumentation(instrumentation)
            val component = instrumentation.componentName
            val actualRunner = instrumentation.javaClass.name
            val permitted = actualRunner == RUNNER || actualRunner == WORKLOAD_RUNNER && network.orion.app.BuildConfig.ORION_CORTEX_INSTRUMENTED_WORKLOAD
            if (qualified == null || !permitted ||
                component.packageName != qualified.testPackage || component.className != actualRunner ||
                Binder.getCallingUid() != Process.myUid() || instrumentation.isProfiling ||
                instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) null
            else OrionRuntimeInstrumentationContext(qualified, component)
        } catch (_: Throwable) { null }
    }
}

/** Actual native geometry for the two pinned framework AIDL layouts, never source authentication. */
internal class OrionRuntimeInstrumentationGeometry(val bundleBytes: Int, val finishParcelBytes: Int, val watcherParcelBytes: Int) {
    // Supported app ABIs are 64-bit. UAPI binder_transaction_data_secctx <=72 bytes, command4;
    // finish has exactly one non-null flat Binder object and therefore one 8-byte offset-table entry.
    val finishInlineBytes: Long = finishParcelBytes.toLong() + 72 + 4 + 8
    val watcherInlineBytes: Long = watcherParcelBytes.toLong() + 72 + 4
    val finishOuterBytes: Long = finishInlineBytes - bundleBytes
    val watcherOuterBytes: Long = watcherInlineBytes - bundleBytes
    val withinBudget: Boolean = bundleBytes in 0..OrionRuntimeCapsuleParcelGuard.MAX_BUNDLE_BYTES &&
        finishOuterBytes in 0..OrionRuntimeCapsuleParcelGuard.RESERVED_OUTER_BYTES.toLong() &&
        watcherOuterBytes in 0..OrionRuntimeCapsuleParcelGuard.RESERVED_OUTER_BYTES.toLong() &&
        finishInlineBytes <= OrionRuntimeCapsuleParcelGuard.MAX_TOTAL_INLINE_BYTES &&
        watcherInlineBytes <= OrionRuntimeCapsuleParcelGuard.MAX_TOTAL_INLINE_BYTES
}

/** Does not call hidden framework APIs or send a Binder transaction.
 * Layout: framework/base94b4c163 API37 IActivityManager/IInstrumentationWatcher and AIDL generator9a94664f.
 * Native width: framework/native ae266dcb Parcel::flattenBinder/writeInterfaceToken, kernel aa17a35b UAPI.
 * Same signatures reviewed API31..37. Fixed driver metadata is counted separately from Parcel.dataSize.
 */
internal object OrionRuntimeInstrumentationFraming {
    fun measure(bundle: Bundle, component: ComponentName): OrionRuntimeInstrumentationGeometry? {
        if (Build.VERSION.SDK_INT !in 31..37 || !Process.is64Bit() || bundle.hasFileDescriptors()) return null
        return try {
            val plain = Parcel.obtain()
            try {
                val finish = Parcel.obtain()
                try {
                    val watcher = Parcel.obtain()
                    try {
                        plain.writeBundle(bundle)
                        finish.writeInterfaceToken("android.app.IActivityManager")
                        // One fixed flat_binder_object for non-null local OR remote kernel Binder.
                        finish.writeStrongBinder(Binder())
                        finish.writeInt(-1)
                        finish.writeTypedObject(bundle, 0)
                        watcher.writeInterfaceToken("android.app.IInstrumentationWatcher")
                        watcher.writeTypedObject(component, 0)
                        watcher.writeInt(-1)
                        watcher.writeTypedObject(bundle, 0)
                        OrionRuntimeInstrumentationGeometry(plain.dataSize(), finish.dataSize(), watcher.dataSize())
                    } finally { watcher.recycle() }
                } finally { finish.recycle() }
            } finally { plain.recycle() }
        } catch (_: Throwable) { null }
    }
}

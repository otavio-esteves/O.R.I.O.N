package network.orion.app.ai

import android.app.Application
import android.app.Instrumentation
import android.os.Process
import java.util.Collections

/** Actual local framework identity, acquired only in the separate manual phase. No caller JSON. */
internal class OrionRuntimeQualifiedContext private constructor(val appPackage: String, val testPackage: String,
    val mainUid: Int, val mainPid: Int) {
    fun matches(packageName: String, uid: Int): Boolean = appPackage == packageName && mainUid == uid
    override fun toString() = "OrionRuntimeQualifiedContext(REDACTED)"
    companion object {
        @Suppress("DEPRECATION")
        fun fromInstrumentation(instrumentation: Instrumentation): OrionRuntimeQualifiedContext? = try {
            val target = instrumentation.targetContext
            val test = instrumentation.context
            val app = target.applicationInfo
            val runner = target.packageManager.getInstrumentationInfo(instrumentation.componentName, 0)
            val packageName = target.packageName
            if (packageName != "network.orion.qualification" || app.packageName != packageName ||
                app.uid != Process.myUid() || Application.getProcessName() != packageName ||
                test.packageName != "$packageName.test" || test.applicationInfo.packageName != test.packageName ||
                runner.packageName != test.packageName || runner.targetPackage != packageName) null
            else OrionRuntimeQualifiedContext(packageName, test.packageName, app.uid, Process.myPid())
        } catch (_: Throwable) { null }
    }
}

/** Only scalars from the original selected configuration and original Float wire conversion. */
internal class OrionRuntimeExportScalars(val contextTokens: Int?, val threads: Int?, val maxTokens: Int?,
    val wireTemperature: Float?, val deadlineElapsed: Long?, inventory: List<String>?) {
    val processInventory: List<String>? = inventory?.let { Collections.unmodifiableList(ArrayList(it)) }
    override fun toString() = "OrionRuntimeExportScalars(REDACTED)"
}

/** Only the private Session implementation can represent this final manual source-composition seal.
 * This proves a source association, never source authenticity, a complete v1 cycle or qualification.
 */
internal sealed interface OrionRuntimeExportSnapshot {
    val context: OrionRuntimeQualifiedContext
    val scalars: OrionRuntimeExportScalars
    val receipt: OrionRuntimeSessionReceipt
}

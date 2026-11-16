package network.orion.app.ai

import android.app.Application
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.orion.app.BuildConfig
import network.orion.app.OrionApplication
import network.orion.app.OrionFoundationRuntime

/** Only the explicit non-debuggable same-UID MAIN qualification mode exposes this framework-typed entry. */
object OrionCortexRuntimeCaptureBridge {
    private const val TARGET = "network.orion.qualification"
    private val used = AtomicBoolean()
    @JvmStatic fun run(instrumentation: Instrumentation): Bundle {
        val app = validate(instrumentation)
        val context = OrionRuntimeInstrumentationContext.fromInstrumentation(instrumentation)
            ?: return result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.IDENTITY_UNAVAILABLE), null)
        if (!used.compareAndSet(false, true))
            return result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE), context)
        var program: OrionCortexRuntimeCaptureProgram? = null
        var candidate: OrionRuntimeCaptureCandidate? = null
        return try { runBlocking {
            val main = existingRuntime(app)
                ?: return@runBlocking result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE), context)
            val owner = main.cortexRuntime()
                ?: return@runBlocking result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE), context)
            if (!sameRuntime(app, main))
                return@runBlocking result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.IDENTITY_CHANGED), context)
            val actual = OrionCortexRuntimeCaptureProgram(owner); program = actual
            when (val selection = actual.prepare()) {
                is OrionCortexRuntimeCaptureProgram.Selection.Refusal -> result(OrionRuntimeCapsuleEncoder.programRefusal(selection.witness), context)
                is OrionCortexRuntimeCaptureProgram.Selection.Selected -> {
                    val selected = selection.candidate; candidate = selected
                    // Deliberately discard the original List; never inspect/wrap/retain any output or callback error.
                    val discard: (List<network.orion.ai.api.OrionInferenceChunk>) -> Unit = { _ -> }
                    try { actual.execute(selected, discard) }
                    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
                    catch (_: Throwable) { /* Original execute already rethrew unchanged; render only in this later manual phase. */ }
                    val encoded = actual.exportAfterReturn(selected, context.qualified)
                    val guarded = result(encoded, context)
                    val finalFailure = if (!sameRuntime(app, main)) OrionRuntimeExportFailure.IDENTITY_CHANGED
                        else if (encoded.source != null) actual.finalExportFailure(selected, context.qualified) else null
                    if (finalFailure != null) result(OrionRuntimeCapsuleEncoder.unavailable(finalFailure,
                        encoded.diagnosticReceipt, actual.witness(selected)), context) else guarded
                }
            }
        } } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.INTERRUPTED,
                witness = candidate?.let { program?.witness(it) }), context)
        } catch (_: Throwable) {
            result(OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE,
                witness = candidate?.let { program?.witness(it) }), context)
        }
    }
    private fun result(encoded: OrionRuntimeEncodedCapsule, context: OrionRuntimeInstrumentationContext?): Bundle =
        OrionRuntimeCapsuleParcelGuard.guard(encoded, context).bundle ?: error("RUNTIME_CAPTURE_UNAVAILABLE")

    private fun validate(instrumentation: Instrumentation): OrionApplication {
        require(BuildConfig.ORION_CORTEX_RUNTIME_CAPTURE && Looper.myLooper() != Looper.getMainLooper())
        val context = instrumentation.targetContext
        val app = context.applicationContext as? OrionApplication ?: error("APPLICATION_REJECTED")
        require(context.packageName == TARGET && app.packageName == TARGET && Application.getProcessName() == TARGET)
        require(app.applicationInfo.uid == Process.myUid() && Binder.getCallingUid() == Process.myUid())
        @Suppress("DEPRECATION") val installed = app.packageManager.getApplicationInfo(TARGET, 0)
        require(installed.uid == Process.myUid() && installed.className == OrionApplication::class.java.name &&
            installed.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && !instrumentation.isProfiling)
        return app
    }
    private suspend fun existingRuntime(app: OrionApplication): OrionFoundationRuntime? = withTimeout(12000) {
        var selected: OrionFoundationRuntime? = null
        for (attempt in 0..200) {
            selected = withContext(Dispatchers.Main.immediate) { app.mainRuntime }
            if (selected != null) break
            if (attempt < 200) delay(50)
        }
        val main = selected ?: return@withTimeout null
        main.health.first { it.core.readiness.isStartupBarrierOpen }
        if (sameRuntime(app, main)) main else null
    }
    private suspend fun sameRuntime(app: OrionApplication, main: OrionFoundationRuntime): Boolean =
        withContext(Dispatchers.Main.immediate) { app.mainRuntime === main }
}

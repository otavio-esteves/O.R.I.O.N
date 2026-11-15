package network.orion.app.ai

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.orion.app.BuildConfig
import network.orion.app.OrionApplication
import network.orion.app.OrionFoundationRuntime
import org.json.JSONObject

/** One manual program on the existing graph. No caller configuration, workload or identifiers. */
object OrionCortexSingleAttemptBridge {
    private const val TARGET = "network.orion.qualification"
    private const val HEADER = "orionSingleAttemptStatus"
    private const val PARTIAL = "orionSingleAttemptPartial"
    private const val NEW = 0
    private const val PREPARING = 1
    private const val SELECTED = 2
    private const val PREPARE_REFUSED = 3
    private const val EXECUTING = 4
    private const val INVOKED = 5
    private const val NOT_ATTEMPTED = 6
    private const val THREW = 7
    private const val OBSERVING = 8
    private const val OBSERVED = 9
    private const val RELEASING = 10
    private const val RELEASED = 11
    private const val UNAVAILABLE = 12
    private val phase = AtomicInteger(NEW)
    @Volatile private var runtime: OrionFoundationRuntime? = null
    @Volatile private var driver: OrionCortexSingleAttemptDriver? = null
    @Volatile private var candidate: OrionCortexSingleAttemptDriver.Candidate? = null
    @Volatile private var execution: OrionCortexSingleAttemptDriver.Execution? = null
    @Volatile private var refusal: OrionCortexAttemptRefusal? = null
    @Volatile private var environment = "UNVERIFIED"
    @Volatile private var prepareCalls = 0
    @Volatile private var executeCalls = 0
    @Volatile private var readCalls = 0
    @Volatile private var releaseCalls = 0

    @JvmStatic fun prepare(context: Context): Bundle {
        val app = validate(context)
        if (!phase.compareAndSet(NEW, PREPARING)) return reply("BRIDGE_REFUSED", "ALREADY_USED")
        try {
            environment = observedEnvironment()
            val owner = runBlocking { existingOwner(app) }
            if (owner == null) { phase.set(UNAVAILABLE); return reply("BRIDGE_UNAVAILABLE", "OWNER_UNAVAILABLE") }
            val actual = OrionCortexSingleAttemptDriver(owner)
            driver = actual
            val selected = runBlocking { prepareCalls = 1; actual.prepare() }
            return when (selected) {
                is OrionCortexSingleAttemptDriver.Selection.Refused -> {
                    refusal = selected.reason
                    val response = reply("PREPARE_REFUSED")
                    phase.set(PREPARE_REFUSED)
                    response
                }
                is OrionCortexSingleAttemptDriver.Selection.Selected -> {
                    candidate = selected.candidate
                    val response = reply("SELECTED")
                    phase.set(SELECTED)
                    response
                }
            }
        } catch (failure: Throwable) { phase.set(UNAVAILABLE); throw failure }
    }

    @JvmStatic fun execute(context: Context): Bundle {
        val app = validate(context)
        if (!phase.compareAndSet(SELECTED, EXECUTING)) return reply("BRIDGE_REFUSED", "PHASE_ORDER")
        val actual: OrionCortexSingleAttemptDriver
        val selected: OrionCortexSingleAttemptDriver.Candidate
        val metadataUnavailable: Bundle
        val discard: (List<network.orion.ai.api.OrionInferenceChunk>) -> Unit
        try {
            check(runBlocking { sameRuntime(app) }) { "CORE_IDENTITY_UNAVAILABLE" }
            actual = checkNotNull(driver); selected = checkNotNull(candidate)
            // Allocate only diagnostic fallback/callback before the original driver call.
            metadataUnavailable = reply("EXECUTE_RETURNED_METADATA_UNAVAILABLE", "METADATA_UNAVAILABLE", executeCount = 1)
            discard = { _ -> } // The original List/text is neither inspected, retained nor wrapped.
        } catch (failure: Throwable) { phase.set(UNAVAILABLE); throw failure }
        val result = try {
            runBlocking { executeCalls = 1; actual.execute(selected, discard) }
        } catch (failure: Throwable) {
            // Primitive marker only: no receipt/query/allocation while the original error unwinds.
            phase.set(THREW)
            throw failure
        }
        execution = result
        if (result is OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted) refusal = result.reason
        val response = try {
            reply(if (result is OrionCortexSingleAttemptDriver.Execution.Invoked) "EXECUTE_INVOKED" else "EXECUTE_NOT_ATTEMPTED")
        } catch (_: Throwable) { metadataUnavailable }
        // Freeze this phase's counters before another manual phase can claim the candidate.
        phase.set(if (result is OrionCortexSingleAttemptDriver.Execution.Invoked) INVOKED else NOT_ATTEMPTED)
        return response
    }

    @JvmStatic fun observe(context: Context): Bundle {
        val app = validate(context)
        if (!phase.compareAndSet(INVOKED, OBSERVING)) return reply("BRIDGE_REFUSED", "PHASE_ORDER")
        return try {
            check(runBlocking { sameRuntime(app) }) { "CORE_IDENTITY_UNAVAILABLE" }
            val actual = checkNotNull(driver); val selected = checkNotNull(candidate)
            readCalls = 1
            val partial = actual.readPartial(selected)
            reply("OBSERVED", partial = partial?.json)
        } catch (_: Throwable) {
            reply("OBSERVATION_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
        } finally { phase.set(OBSERVED) }
    }

    @JvmStatic fun release(context: Context): Bundle {
        validate(context)
        val previous = phase.get()
        if (previous != INVOKED && previous != OBSERVED && previous != THREW && previous != NOT_ATTEMPTED)
            return reply("BRIDGE_REFUSED", "PHASE_ORDER")
        if (!phase.compareAndSet(previous, RELEASING)) return reply("BRIDGE_REFUSED", "PHASE_ORDER")
        return try {
            val actual = checkNotNull(driver); val selected = checkNotNull(candidate)
            releaseCalls = 1
            val removed = actual.release(selected)
            phase.set(RELEASED)
            reply(if (removed) "RELEASED" else "RELEASE_NOT_AVAILABLE")
        } catch (_: Throwable) {
            phase.set(RELEASED)
            reply("RELEASE_NOT_AVAILABLE", "RELEASE_UNAVAILABLE")
        } finally {
            // Drop only this bridge's technical references, never close the shared graph/owner.
            candidate = null; driver = null; runtime = null
        }
    }

    private fun validate(context: Context): OrionApplication {
        require(BuildConfig.ORION_CORTEX_SINGLE_ATTEMPT) { "MODE_DISABLED" }
        require(Looper.myLooper() != Looper.getMainLooper()) { "THREAD_REJECTED" }
        val app = context.applicationContext as? OrionApplication ?: error("APPLICATION_REJECTED")
        require(context.packageName == TARGET && app.packageName == TARGET && Application.getProcessName() == TARGET) {
            "TARGET_IDENTITY_REJECTED"
        }
        require(app.applicationInfo.uid == Process.myUid() && Binder.getCallingUid() == Process.myUid()) { "UID_REJECTED" }
        @Suppress("DEPRECATION") val installed = app.packageManager.getApplicationInfo(TARGET, 0)
        require(installed.uid == Process.myUid() && installed.className == OrionApplication::class.java.name &&
            installed.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "APPLICATION_REJECTED" }
        return app
    }

    private suspend fun existingOwner(app: OrionApplication): OrionCortexRuntimeOwner? = withTimeout(12_000) {
        var selected: OrionFoundationRuntime? = null
        // Same existing readiness publication bound, never a retry of selection/execution.
        for (attempt in 0..200) {
            selected = withContext(Dispatchers.Main.immediate) { app.mainRuntime }
            if (selected != null) break
            if (attempt < 200) delay(50)
        }
        val main = selected ?: return@withTimeout null
        main.health.first { it.core.readiness.isStartupBarrierOpen }
        if (!withContext(Dispatchers.Main.immediate) { app.mainRuntime === main }) return@withTimeout null
        val owner = main.cortexRuntime() // Exactly one port lookup on the existing Foundation singleton.
        if (owner != null) runtime = main
        owner
    }

    private suspend fun sameRuntime(app: OrionApplication): Boolean = withTimeout(12_000) {
        withContext(Dispatchers.Main.immediate) {
            val selected = runtime
            selected != null && app.mainRuntime === selected
        }
    }

    private fun observedEnvironment(): String {
        val identity = listOf(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT, Build.DEVICE)
        if (identity.any { it == null || it.length !in 1..512 }) return "UNVERIFIED"
        val labels = identity.joinToString(" ").lowercase(Locale.ROOT)
        if (listOf("emulator", "qemu", "ranchu", "goldfish", "cuttlefish", "sdk_gphone", "generic", "aosp_cf").any(labels::contains))
            return "ANDROID_EMULATOR"
        return if (Build.FINGERPRINT.length >= 10 && System.getProperty("os.arch") == "aarch64") "PHYSICAL_DEVICE" else "UNVERIFIED"
    }

    private fun reply(status: String, failure: String? = null, partial: String? = null, executeCount: Int = executeCalls): Bundle {
        val value = JSONObject().apply {
            put("schemaVersion", 1); put("kind", "CORTEX_SINGLE_ATTEMPT_STATUS"); put("qualification", "NOT_QUALIFIED")
            put("phase", status); put("failureCode", failure ?: JSONObject.NULL); put("refusal", refusal?.name ?: JSONObject.NULL)
            put("environment", environment); put("environmentSource", "BUILD_SIGNALS_ONLY")
            put("counterScope", "DRIVER_METHOD_CALLS")
            put("prepareCalls", prepareCalls); put("executeCalls", executeCount); put("readCalls", readCalls); put("releaseCalls", releaseCalls)
            put("ownerCallInvoked", when (execution) {
                is OrionCortexSingleAttemptDriver.Execution.Invoked -> true
                is OrionCortexSingleAttemptDriver.Execution.DriverNotAttempted -> false
                else -> JSONObject.NULL
            })
            put("partialPresent", partial != null)
            put("sourceAuthenticated", false); put("physicalVerified", false); put("profileApproved", false)
            put("productionInferenceApproved", false); put("runtimeEvidenceV1Complete", false)
            put("fullF5GateClosed", false); put("fullSoakGateClosed", false); put("f6Authorized", false)
        }.toString()
        val headerBytes = value.toByteArray(Charsets.UTF_8).size
        val partialBytes = partial?.toByteArray(Charsets.UTF_8)?.size ?: 0
        require(headerBytes <= 1024) { "HEADER_BOUND_EXCEEDED" }
        require(partialBytes <= 8192) { "PARTIAL_BOUND_EXCEEDED" }
        require(HEADER.length + headerBytes + (if (partial == null) 0 else PARTIAL.length + partialBytes) <= 10 * 1024) {
            "TEXT_BOUND_EXCEEDED"
        }
        return Bundle().apply { putString(HEADER, value); if (partial != null) putString(PARTIAL, partial) }
    }
}

package network.orion.app.ai

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.core.time.AndroidOrionClock
import org.json.JSONObject

/** Qualification-only observation. No request, model, Binder or resource-decision entry point. */
object OrionCortexReadinessBridge {
    private const val TARGET = "network.orion.qualification"
    private const val KEY = "orionCortexReadinessReceipt"
    private val ENVIRONMENTS = setOf("PHYSICAL_DEVICE", "ANDROID_EMULATOR")

    @JvmStatic fun collect(context: Context, requestedEnvironment: String, coreWaitMillis: Int): Bundle {
        require(requestedEnvironment in ENVIRONMENTS && coreWaitMillis in 0..10_000) { "ARGUMENTS_REJECTED" }
        require(context.packageName == TARGET && Application.getProcessName() == TARGET) { "TARGET_IDENTITY_REJECTED" }
        val app = context.applicationContext as? OrionApplication ?: error("TARGET_IDENTITY_REJECTED")
        val clock = AndroidOrionClock(context.contentResolver)
        val start = clock.monotonicNow()
        val build = observedBuild()
        var result = "REJECTED"
        var failure: String? = null
        var readiness = "NOT_EVALUATED"
        var before = "NOT_EVALUATED"
        var after = "NOT_EVALUATED"
        var portQueries = 0
        var ownerQueryPerformed = false
        when {
            build.environment == "UNVERIFIED" -> failure = "ENVIRONMENT_UNVERIFIED"
            Build.VERSION.SDK_INT !in 31..37 -> failure = "API_UNSUPPORTED"
            build.environment != requestedEnvironment -> failure = "ENVIRONMENT_MISMATCH"
            start.bootSessionId == null -> { result = "UNAVAILABLE"; failure = "CLOCK_UNAVAILABLE" }
            else -> try {
                val main = app.mainRuntime
                before = main?.health?.value?.core?.readiness?.name ?: "UNAVAILABLE"
                readiness = "CORE_PORT_UNAVAILABLE"
                if (main != null) runBlocking {
                    withTimeout(12_000) {
                        val waitDeadline = start.deadlineAfter(coreWaitMillis.toLong())
                        // Even a zero wait queries once; no model or request is needed.
                        for (attempt in 0..200) {
                            val now = clock.monotonicNow()
                            check(now.bootSessionId == start.bootSessionId && now.elapsedSince(start) != null)
                            portQueries++
                            val owner = main.cortexRuntime()
                            if (owner != null) {
                                ownerQueryPerformed = true
                                readiness = owner.preResourceReadiness().name
                                break
                            }
                            if (waitDeadline.statusAt(clock.monotonicNow()) != network.orion.core.time.MonotonicDeadlineStatus.ACTIVE)
                                break
                            delay(50)
                        }
                    }
                }
                after = main?.health?.value?.core?.readiness?.name ?: "UNAVAILABLE"
                result = "OBSERVED"
            } catch (_: TimeoutCancellationException) {
                result = "UNAVAILABLE"; readiness = "NOT_EVALUATED"; failure = "OBSERVATION_TIMEOUT"
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                result = "UNAVAILABLE"; readiness = "NOT_EVALUATED"; failure = "INTERRUPTED"
            } catch (_: Exception) {
                result = "UNAVAILABLE"; readiness = "NOT_EVALUATED"; failure = "CORE_PORT_OBSERVATION_FAILED"
            }
        }
        val end = clock.monotonicNow()
        if (result == "OBSERVED" && (end.bootSessionId != start.bootSessionId ||
                end.elapsedSince(start)?.toMillis()?.let { it <= coreWaitMillis + 2_000L } != true)) {
            result = "UNAVAILABLE"; failure = "CLOCK_WINDOW_INVALID"
        }
        val receipt = JSONObject().apply {
            put("schemaVersion", 1); put("kind", "CORTEX_READINESS_RECEIPT"); put("packageName", TARGET)
            put("qualification", "NOT_QUALIFIED"); put("result", result); put("failureCode", failure ?: JSONObject.NULL)
            put("requestedEnvironment", requestedEnvironment); put("environment", build.environment)
            put("environmentSource", "BUILD_SIGNALS_ONLY"); put("api", Build.VERSION.SDK_INT); put("abi", build.abi)
            put("buildFingerprintSha256", build.fingerprintSha256 ?: JSONObject.NULL)
            put("mainPid", Process.myPid()); put("bootCount", start.bootSessionId?.bootCount ?: JSONObject.NULL)
            put("completedBootCount", end.bootSessionId?.bootCount ?: JSONObject.NULL)
            put("startedElapsed", start.elapsedRealtimeMillis); put("completedElapsed", end.elapsedRealtimeMillis)
            put("requestedCoreWaitMillis", coreWaitMillis); put("corePortQueries", portQueries)
            put("coreReadinessBefore", before); put("coreReadinessAfter", after)
            put("ownerQueryPerformed", ownerQueryPerformed); put("preResourceReadiness", readiness)
            put("aiObservation", "NOT_EVALUATED"); put("aggregateMemoryObservation", "NOT_EVALUATED")
            put("resourceGovernorDecision", "NOT_EVALUATED"); put("restartDecision", "NOT_EVALUATED")
            put("modelIdentity", "NOT_EVALUATED"); put("artifactIdentity", "HOST_VERIFICATION_REQUIRED")
            put("physicalDeviceVerified", false); put("provenanceAuthenticated", false)
            put("runtimeRequestsIssued", false); put("probeIpcBindIssued", false); put("heavyWorkIssued", false)
            put("admissionGranted", false); put("profileQualified", false); put("productionInferenceEnabled", false)
            put("fullF5GateClosed", false); put("fullSoakGateClosed", false)
        }
        val json = receipt.toString()
        check(json.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        return Bundle().apply { putString(KEY, json) }
    }

    private data class BuildObservation(val environment: String, val abi: String, val fingerprintSha256: String?)

    private fun observedBuild(): BuildObservation {
        val fingerprint = Build.FINGERPRINT
        val identity = listOf(fingerprint, Build.HARDWARE, Build.PRODUCT, Build.DEVICE)
        val bounded = identity.all { it != null && it.length in 1..512 }
        val labels = if (bounded) identity.joinToString(" ").lowercase(Locale.ROOT) else ""
        val emulator = bounded && listOf("emulator", "qemu", "ranchu", "goldfish", "cuttlefish", "sdk_gphone", "generic", "aosp_cf")
            .any(labels::contains)
        val abi = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> "UNVERIFIED"
        }
        val environment = when {
            emulator -> "ANDROID_EMULATOR"
            bounded && fingerprint.length >= 10 && abi == "arm64-v8a" -> "PHYSICAL_DEVICE"
            else -> "UNVERIFIED"
        }
        val hash = if (bounded) MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } else null
        return BuildObservation(environment, abi, hash)
    }
}

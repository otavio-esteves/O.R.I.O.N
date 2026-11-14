package network.orion.app.resources

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import java.util.Locale
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.OrionCortexWire
import network.orion.app.ai.supportedCortexInventory
import network.orion.core.resources.OrionMemoryPressure
import network.orion.core.resources.OrionProcessImportance
import network.orion.core.time.AndroidOrionClock
import org.json.JSONArray
import org.json.JSONObject

/** Qualification-only reflection bridge. No model, governor plan, Skill or production entry point. */
object OrionPhysicalBaselineBridge {
    private const val TARGET = "network.orion.qualification"
    private const val MAXIMUM_AGE_MILLIS = 1_000L
    private val HEALTH_KEYS = setOf("schemaVersion", "status", "processName", "pid", "bootCount",
        "sampledElapsed", "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")

    /** Invoked before the framework runner creates its one lightweight binding. */
    @JvmStatic fun validate(context: Context, samples: Int, intervalMillis: Int) {
        require(samples in 1..10 && intervalMillis in 100..1_000) { "ARGUMENTS_REJECTED" }
        require(context.packageName == TARGET && Application.getProcessName() == TARGET) { "TARGET_IDENTITY_REJECTED" }
        requirePhysicalBuild()
        inventory(context)
    }

    /** Also protects direct reflection calls: a rejected build cannot emit a physical receipt. */
    private fun requirePhysicalBuild() {
        require(Build.VERSION.SDK_INT in 31..37 && System.getProperty("os.arch") == "aarch64") { "PHYSICAL_ENVIRONMENT_REJECTED" }
        val identity = "${Build.FINGERPRINT} ${Build.HARDWARE} ${Build.PRODUCT} ${Build.DEVICE}".lowercase(Locale.ROOT)
        val markers = listOf("emulator", "qemu", "ranchu", "goldfish", "cuttlefish", "sdk_gphone", "generic", "aosp_cf")
        require(Build.FINGERPRINT.length in 10..512 && markers.none(identity::contains)) { "PHYSICAL_ENVIRONMENT_REJECTED" }
    }

    @Suppress("DEPRECATION")
    private fun inventory(context: Context): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.MATCH_DISABLED_COMPONENTS)
        val components = sequenceOf(info.activities?.asSequence(), info.services?.asSequence(),
            info.receivers?.asSequence(), info.providers?.asSequence()).filterNotNull().flatten()
            .take(257).map { it.processName }.toList()
        return checkNotNull(supportedCortexInventory(context.packageName, info.applicationInfo?.processName, components)) {
            "PROCESS_INVENTORY_UNAVAILABLE"
        }
    }

    @JvmStatic fun collect(context: Context, binder: IBinder, samples: Int, intervalMillis: Int,
        receiver: ResultReceiver): Bundle {
        validate(context, samples, intervalMillis)
        var completed = 0
        return try {
            val clock = AndroidOrionClock(context.contentResolver)
            val initialBoot = checkNotNull(clock.monotonicNow().bootSessionId)
            val names = inventory(context)
            val telemetry = OrionCortexMemoryTelemetry(context.packageName, clock, MAXIMUM_AGE_MILLIS)
            val service = checkNotNull(IOrionCortex.Stub.asInterface(binder))
            val protocol = service.getProtocolInfo()
            OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
            check(OrionCortexWire.text(protocol, "status") == "OK" &&
                OrionCortexWire.int(protocol, "major") == 1 && OrionCortexWire.int(protocol, "minor") >= 1)
            var aiPid: Int? = null
            fun idleReceipt(): Bundle {
                check(binder.isBinderAlive)
                val status = service.getStatus()
                OrionCortexWire.requireEnvelope(status, setOf("schemaVersion", "status", "modelId"))
                check(OrionCortexWire.text(status, "status") == "UNLOADED" && !status.containsKey("modelId"))
                val receipt = service.healthCheck()
                OrionCortexWire.requireEnvelope(receipt, HEALTH_KEYS)
                check(!OrionCortexWire.boolean(receipt, "nativeRuntimeLoaded") &&
                    !OrionCortexWire.boolean(receipt, "nativeValidationCompleted"))
                val pid = OrionCortexWire.int(receipt, "pid")
                check(pid != Process.myPid() && (aiPid == null || aiPid == pid))
                checkNotNull(telemetry.read(receipt, aiPid, names, true))
                aiPid = pid
                return receipt
            }
            idleReceipt()
            var observedAi: Bundle? = null
            val observer = OrionAndroidResourceObserver(context, clock,
                auxiliaryMemory = {
                    val receipt = idleReceipt().also { observedAi = it }
                    telemetry.read(receipt, aiPid, inventory(context), true)
                }, maximumMemoryAgeMillis = MAXIMUM_AGE_MILLIS)
            repeat(samples) { index ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                check(clock.monotonicNow().bootSessionId == initialBoot)
                idleReceipt()
                observedAi = null
                val snapshot = observer.observe(null, 1, 32, 1)
                val receipt = checkNotNull(observedAi)
                val observation = snapshot.observation
                val mainPss = checkNotNull(snapshot.mainPssBytes)
                val aiPss = checkNotNull(observation.processMemoryBytes)
                val total = checkNotNull(observation.aggregateAppMemoryBytes)
                val available = checkNotNull(observation.systemAvailableMemoryBytes)
                val battery = checkNotNull(observation.batteryPercent)
                val thermal = checkNotNull(observation.thermalStatus)
                val saver = checkNotNull(observation.powerSave)
                val foreground = checkNotNull(observation.foreground)
                check(snapshot.limitations.isEmpty() && mainPss > 0 && aiPss > 0 && available >= 0 &&
                    mainPss <= Long.MAX_VALUE - aiPss && total == mainPss + aiPss &&
                    battery in 0..100 && thermal in 0..6 &&
                    observation.importance != OrionProcessImportance.UNKNOWN && observation.memoryPressure != OrionMemoryPressure.UNKNOWN)
                idleReceipt()
                val end = clock.monotonicNow()
                val sampled = observation.sampledAt
                val aiSampled = OrionCortexWire.long(receipt, "sampledElapsed")
                check(end.bootSessionId == initialBoot && sampled.bootSessionId == initialBoot &&
                    OrionCortexWire.int(receipt, "bootCount") == initialBoot.bootCount &&
                    end.elapsedSince(sampled)?.toMillis()?.let { it < MAXIMUM_AGE_MILLIS } == true &&
                    aiSampled <= end.elapsedRealtimeMillis && end.elapsedRealtimeMillis - aiSampled < MAXIMUM_AGE_MILLIS)
                val row = JSONObject().apply {
                    put("schemaVersion", 1); put("kind", "PHYSICAL_BASELINE_SAMPLE")
                    put("environment", "PHYSICAL_DEVICE"); put("qualification", "NOT_QUALIFIED"); put("packageName", TARGET)
                    put("sampleIndex", index); put("sampleCount", samples); put("bootCount", initialBoot.bootCount)
                    put("mainPid", Process.myPid()); put("aiPid", checkNotNull(aiPid))
                    put("sampledElapsed", sampled.elapsedRealtimeMillis); put("aiSampledElapsed", aiSampled)
                    put("completedElapsed", end.elapsedRealtimeMillis); put("maximumAgeMillis", MAXIMUM_AGE_MILLIS)
                    put("mainPssBytes", mainPss); put("aiPssBytes", aiPss); put("aggregatePssBytes", total)
                    put("systemAvailableMemoryBytes", available); put("batteryPercent", battery); put("thermalStatus", thermal)
                    put("powerSave", saver); put("foreground", foreground); put("importance", observation.importance.name)
                    put("memoryPressure", observation.memoryPressure.name); put("inventoryProcessCount", names.size)
                    put("serviceState", "UNLOADED"); put("nativeRuntimeLoaded", false); put("limitations", JSONArray())
                }
                check(row.toString().toByteArray(Charsets.UTF_8).size <= 8 * 1024)
                receiver.send(0, Bundle().apply { putString("orionPhysicalBaselineSample", row.toString()) })
                completed++
                if (index + 1 < samples) Thread.sleep(intervalMillis.toLong())
            }
            idleReceipt()
            summary(samples, completed, intervalMillis, "OBSERVATION_COMPLETE_REVIEW_REQUIRED", null)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            summary(samples, completed, intervalMillis, "INCOMPLETE", "INTERRUPTED")
        } catch (_: Exception) {
            summary(samples, completed, intervalMillis, "INCOMPLETE", "OBSERVATION_FAILED")
        }
    }

    private fun summary(requested: Int, collected: Int, interval: Int, result: String, failure: String?): Bundle {
        val value = JSONObject().apply {
            put("schemaVersion", 1); put("kind", "PHYSICAL_BASELINE_SUMMARY")
            put("environment", "PHYSICAL_DEVICE"); put("qualification", "NOT_QUALIFIED"); put("packageName", TARGET)
            put("result", result); put("samplesRequested", requested); put("samplesCollected", collected)
            put("sampleIntervalMillis", interval); put("profileQualified", false); put("productionInferenceEnabled", false)
            put("fullF5GateClosed", false); put("fullSoakGateClosed", false); put("heavyWorkIssued", false)
            put("failureCode", failure ?: JSONObject.NULL)
        }
        return Bundle().apply { putString("orionPhysicalBaselineSummary", value.toString()) }
    }
}

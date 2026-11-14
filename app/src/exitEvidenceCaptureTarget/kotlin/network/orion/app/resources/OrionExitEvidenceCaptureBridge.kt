package network.orion.app.resources

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Process
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.OrionCortexWire
import network.orion.core.exitinfo.OrionProcessExitClassifier
import network.orion.core.time.AndroidOrionClock
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in MAIN diagnostic only. No process creation, model work or policy decisions. */
object OrionExitEvidenceCaptureBridge {
    private const val TARGET = "network.orion.qualification"
    private const val AI = "$TARGET:ai"
    private val HEALTH_KEYS = setOf("schemaVersion", "status", "processName", "pid", "bootCount",
        "sampledElapsed", "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")
    private val STATES = setOf("UNLOADED", "LOADING", "LOADED", "INFERENCING", "UNLOADING")

    @JvmStatic fun validate(context: Context, environment: String, count: Int, interval: Int, limit: Int): String {
        require(count in 1..10 && interval in 100..1_000 && limit in 1..16) { "ARGUMENTS_REJECTED" }
        require(context.packageName == TARGET && Application.getProcessName() == TARGET) { "TARGET_IDENTITY_REJECTED" }
        require(Build.VERSION.SDK_INT in 31..37 && Build.FINGERPRINT.length in 10..512) { "PLATFORM_UNSUPPORTED" }
        val labels = "${Build.FINGERPRINT} ${Build.HARDWARE} ${Build.PRODUCT} ${Build.DEVICE}".lowercase(Locale.ROOT)
        val emulator = System.getProperty("os.arch") in setOf("x86", "x86_64") ||
            listOf("emulator", "qemu", "ranchu", "goldfish", "cuttlefish", "sdk_gphone", "generic", "aosp_cf").any(labels::contains)
        require(environment == if (emulator) "ANDROID_EMULATOR" else "PHYSICAL_DEVICE") { "ENVIRONMENT_MISMATCH" }
        return if (emulator) "ANDROID_EMULATOR" else "PHYSICAL_BUILD_CANDIDATE"
    }

    private data class Stamp(val boot: Int?, val elapsed: Long, val wall: Long) {
        fun json() = JSONObject().put("bootCount", boot ?: JSONObject.NULL)
            .put("elapsedMillis", elapsed).put("wallMillis", wall)
    }

    private fun stamp(clock: AndroidOrionClock): Stamp {
        val at = clock.monotonicNow()
        return Stamp(at.bootSessionId?.bootCount, at.elapsedRealtimeMillis, clock.wallNow().toEpochMilli())
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun incident(record: JSONObject): String = "${record.getInt("pid")}|${record.getString("processName")}|" +
        "${record.getLong("timestampWallMillis")}|${record.getInt("reason")}"

    private fun dedicatedReason(): Int? = try {
        ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER").getInt(null).takeIf { it > 16 }
    } catch (_: ReflectiveOperationException) { null } catch (_: SecurityException) { null }

    private fun history(context: Context, limit: Int, dedicated: Int?, cancellation: CancellationSignal): JSONObject {
        cancellation.throwIfCanceled()
        return try {
            val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
            val raw = manager.getHistoricalProcessExitReasons(TARGET, 0, limit + 1).take(limit + 1)
            val records = JSONArray()
            val seen = mutableSetOf<String>()
            for (exit in raw) {
                cancellation.throwIfCanceled()
                if (exit.processName != AI) continue
                require(exit.pid > 0 && exit.timestamp >= 0 && exit.reason >= 0)
                // Raw descriptions are read transiently for classification, never serialized/logged.
                val description = exit.description
                val signal = OrionProcessExitClassifier.classify(exit.reason, Instant.ofEpochMilli(exit.timestamp), description, dedicated)
                val category = when {
                    exit.reason == 13 && signal.memoryLimiterEvidence -> "OTHER_ANON_SWAP_MARKER"
                    description == null -> "UNAVAILABLE"
                    else -> "NO_MATCH"
                }
                val key = sha256("${exit.pid}|$AI|${exit.timestamp}|${exit.reason}|$category|${signal.kind.name}")
                if (seen.add("${exit.pid}|$AI|${exit.timestamp}|${exit.reason}")) records.put(JSONObject().apply {
                    put("recordSha256", key); put("pid", exit.pid); put("processName", AI)
                    put("timestampWallMillis", exit.timestamp); put("reason", exit.reason)
                    put("descriptionEvidence", category); put("classification", signal.kind.name)
                })
            }
            cancellation.throwIfCanceled()
            JSONObject().put("status", if (raw.size > limit) "TRUNCATED" else "AVAILABLE")
                .put("queriedCount", raw.size).put("records", records)
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
          catch (_: Exception) {
            JSONObject().put("status", "UNAVAILABLE").put("queriedCount", JSONObject.NULL).put("records", JSONArray())
        }
    }

    /** Null Binder records history only; the runner never creates/rebinds an absent service. */
    @JvmStatic fun collect(context: Context, binder: IBinder?, environment: String, bindingOutcome: String,
        count: Int, interval: Int, limit: Int, cancellation: CancellationSignal, liveEnded: CancellationSignal): Bundle {
        val observedEnvironment = validate(context, environment, count, interval, limit)
        require(bindingOutcome in setOf("NOT_BOUND", "CONNECTION_TIMEOUT", "NO_ENDPOINT", "CONNECTED") &&
            (binder != null) == (bindingOutcome == "CONNECTED"))
        cancellation.throwIfCanceled()
        val clock = AndroidOrionClock(context.contentResolver)
        val start = stamp(clock)
        val dedicated = dedicatedReason()
        val before = history(context, limit, dedicated, cancellation)
        val live = JSONArray()
        val dead = AtomicBoolean(false)
        val recipient = IBinder.DeathRecipient { dead.set(true) }
        var linked = false
        var deathCleanup = "NOT_LINKED"
        var result = if (binder == null) "AI_NOT_AVAILABLE" else "LIVE_OBSERVATION_FAILED"
        val firstBoot = start.boot
        try {
            if (start.boot == null) result = "BOOT_UNAVAILABLE"
            else if (binder != null) {
                if (!binder.isBinderAlive) result = "AI_NOT_AVAILABLE"
                else {
                    binder.linkToDeath(recipient, 0)
                    linked = true
                    val service = checkNotNull(IOrionCortex.Stub.asInterface(binder))
                    val protocol = service.getProtocolInfo()
                    OrionCortexWire.requireEnvelope(protocol, setOf("schemaVersion", "status", "major", "minor"))
                    require(OrionCortexWire.int(protocol, "schemaVersion") == 1 &&
                        OrionCortexWire.text(protocol, "status") == "OK" &&
                        OrionCortexWire.int(protocol, "major") == 1 && OrionCortexWire.int(protocol, "minor") >= 1)
                    var pinnedPid: Int? = null
                    for (index in 0 until count) {
                        cancellation.throwIfCanceled()
                        if (dead.get() || !binder.isBinderAlive) { result = if (live.length() > 0) "AI_PROCESS_DIED" else "AI_NOT_AVAILABLE"; break }
                        if (liveEnded.isCanceled) { result = "ENDPOINT_DISCONNECTED"; break }
                        val readStart = stamp(clock)
                        if (readStart.boot == null) { result = "BOOT_UNAVAILABLE"; break }
                        if (readStart.boot != firstBoot) { result = "BOOT_CHANGED"; break }
                        val status = service.getStatus()
                        OrionCortexWire.requireEnvelope(status, setOf("schemaVersion", "status", "modelId"))
                        require(OrionCortexWire.int(status, "schemaVersion") == 1)
                        val state = OrionCortexWire.text(status, "status")
                        require(state in STATES)
                        val health = service.healthCheck()
                        val readEnd = stamp(clock)
                        cancellation.throwIfCanceled()
                        if (readEnd.boot == null) { result = "BOOT_UNAVAILABLE"; break }
                        if (readEnd.boot != firstBoot) { result = "BOOT_CHANGED"; break }
                        if (dead.get() || !binder.isBinderAlive) { result = if (live.length() > 0) "AI_PROCESS_DIED" else "AI_NOT_AVAILABLE"; break }
                        if (liveEnded.isCanceled) { result = "ENDPOINT_DISCONNECTED"; break }
                        OrionCortexWire.requireEnvelope(health, HEALTH_KEYS)
                        require(OrionCortexWire.int(health, "schemaVersion") == 1 &&
                            OrionCortexWire.text(health, "status") in setOf("OK", "DEGRADED") &&
                            OrionCortexWire.text(health, "processName", 255) == AI)
                        val pid = OrionCortexWire.int(health, "pid")
                        require(pid > 0 && pid != Process.myPid())
                        if (pinnedPid != null && pinnedPid != pid) { result = "ENDPOINT_IDENTITY_CHANGED"; break }
                        val sampled = OrionCortexWire.long(health, "sampledElapsed")
                        require(OrionCortexWire.int(health, "bootCount") == firstBoot &&
                            readStart.elapsed <= sampled && sampled <= readEnd.elapsed &&
                            readEnd.elapsed - readStart.elapsed < 1_000)
                        val runtime = OrionCortexWire.boolean(health, "nativeRuntimeLoaded")
                        val validation = OrionCortexWire.boolean(health, "nativeValidationCompleted")
                        require(!validation || runtime)
                        pinnedPid = pid
                        live.put(JSONObject().apply {
                            put("pid", pid); put("processName", AI); put("bootCount", firstBoot)
                            put("readStartedElapsed", readStart.elapsed); put("readCompletedElapsed", readEnd.elapsed)
                            put("sampledElapsed", sampled); put("observedWallMillis", readEnd.wall)
                            put("serviceState", state); put("nativeRuntimeLoaded", runtime); put("nativeValidationCompleted", validation)
                        })
                        result = "AI_STILL_ALIVE"
                        if (index + 1 < count) Thread.sleep(interval.toLong())
                    }
                }
            }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (_: Exception) {
            result = if (binder != null && (dead.get() || !binder.isBinderAlive) && live.length() > 0)
                "AI_PROCESS_DIED" else "LIVE_OBSERVATION_FAILED"
        } finally {
            if (linked) deathCleanup = try {
                if (checkNotNull(binder).unlinkToDeath(recipient, 0)) "UNLINKED" else "ALREADY_DEAD"
            } catch (_: Exception) { "FAILED" }
        }
        cancellation.throwIfCanceled()
        val after = history(context, limit, dedicated, cancellation)
        val end = stamp(clock)
        if (end.boot == null || start.boot == null) result = "BOOT_UNAVAILABLE"
        else if (end.boot != start.boot) result = "BOOT_CHANGED"
        else if (result !in setOf("BOOT_UNAVAILABLE", "BOOT_CHANGED", "ENDPOINT_IDENTITY_CHANGED")) {
            if (live.length() > 0 && binder != null && (dead.get() || !binder.isBinderAlive)) result = "AI_PROCESS_DIED"
            else if (liveEnded.isCanceled && binder != null) result = "ENDPOINT_DISCONNECTED"
        }
        val lastLive = if (live.length() == 0) null else live.getJSONObject(live.length() - 1)
        var wallState = "CONTINUITY_NOT_PROVABLE"
        var previousWall = start.wall
        for (index in 0 until live.length()) {
            val next = live.getJSONObject(index).getLong("observedWallMillis")
            if (next < previousWall) wallState = "BACKWARD_STEP_OBSERVED"
            previousWall = next
        }
        if (end.wall < previousWall) wallState = "BACKWARD_STEP_OBSERVED"
        var match: String? = null
        val correlation = when {
            result in setOf("BOOT_UNAVAILABLE", "BOOT_CHANGED") -> "BOOT_INSUFFICIENT"
            lastLive == null -> "NO_LIVE_OBSERVATION"
            result != "AI_PROCESS_DIED" -> "LIVE_PROCESS_NOT_DEAD"
            before.getString("status") != "AVAILABLE" || after.getString("status") != "AVAILABLE" -> "HISTORY_INSUFFICIENT"
            wallState == "BACKWARD_STEP_OBSERVED" -> "WALL_WINDOW_INSUFFICIENT"
            else -> {
                val initial = before.getJSONArray("records")
                val existing = (0 until initial.length()).map { incident(initial.getJSONObject(it)) }.toSet()
                val finalRecords = after.getJSONArray("records")
                val candidates = (0 until finalRecords.length()).map { finalRecords.getJSONObject(it) }.filter {
                    it.getInt("pid") == lastLive.getInt("pid") && incident(it) !in existing &&
                        it.getLong("timestampWallMillis") in lastLive.getLong("observedWallMillis")..end.wall
                }
                if (candidates.size == 1) { match = candidates.single().getString("recordSha256"); "WINDOW_MATCH_CANDIDATE" }
                else if (candidates.isEmpty()) "NO_MATCHING_NEW_EXIT" else "AMBIGUOUS_EXIT_RECORDS"
            }
        }
        val value = JSONObject().apply {
            put("schemaVersion", 1); put("kind", "ORION_F5_EXIT_CAPTURE_ONLY"); put("packageName", TARGET)
            put("declaredEnvironment", environment); put("environment", observedEnvironment)
            put("runtimeEvidence", "NON_DEFINITIVE"); put("qualification", "NOT_QUALIFIED")
            put("mainPid", Process.myPid()); put("result", result); put("correlation", correlation)
            put("bindingOutcome", bindingOutcome); put("binderDeathCleanup", deathCleanup)
            put("matchedRecordSha256", match ?: JSONObject.NULL)
            put("firstLiveObservedElapsed", if (live.length() == 0) JSONObject.NULL else live.getJSONObject(0).getLong("readCompletedElapsed"))
            put("identity", JSONObject().put("source", "UNAVAILABLE_NOT_EMBEDDED").put("appApk", "NOT_CAPTURED")
                .put("testApk", "NOT_CAPTURED").put("model", "NOT_OBSERVED"))
            put("policy", JSONObject().put("state", "UNAVAILABLE_NOT_EXPOSED").put("revision", "UNAVAILABLE_NOT_EXPOSED")
                .put("fence", "UNAVAILABLE_NOT_EXPOSED").put("cooldown", "UNAVAILABLE_NOT_EXPOSED"))
            put("config", JSONObject().put("observations", count).put("intervalMillis", interval).put("historyLimit", limit))
            put("platform", JSONObject().put("apiLevel", Build.VERSION.SDK_INT).put("buildFingerprintSha256", sha256(Build.FINGERPRINT))
                .put("abi", System.getProperty("os.arch").takeIf { it in setOf("aarch64", "arm64", "armv7l", "arm", "x86", "x86_64") } ?: "UNAVAILABLE")
                .put("bootSource", "SETTINGS_GLOBAL_BOOT_COUNT")
                .put("dedicatedReason", dedicated ?: JSONObject.NULL).put("dedicatedReasonStatus", if (dedicated == null) "UNAVAILABLE" else "EXPOSED"))
            put("window", JSONObject().put("start", start.json()).put("end", end.json()).put("wallClockState", wallState))
            put("liveObservations", live); put("historyBefore", before); put("historyAfter", after)
            put("cleanup", "RUNNER_PENDING")
            for (flag in listOf("provenanceAuthenticated", "physicalDeviceVerified", "memoryLimiterVerified", "profileQualified",
                "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "heavyWorkIssued", "policyMutationIssued")) put(flag, false)
        }
        require(value.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return Bundle().apply { putString("orionExitCaptureReceipt", value.toString()) }
    }
}

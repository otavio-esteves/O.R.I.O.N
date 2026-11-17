package network.orion.app.ai

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import network.orion.core.time.AndroidOrionClock

/** Wall-only diagnostic boundary around existing #160 monotonic samples. No Core clock or new mono/provider read. */
internal class OrionQualificationWallBoundary private constructor(private val source: Source) {
    constructor(diagnosticClock: AndroidOrionClock) : this(Native(diagnosticClock))
    private sealed class Source { abstract fun read(): Long? }
    private class Native(private val clock: AndroidOrionClock) : Source() { override fun read(): Long = clock.wallNow().toEpochMilli() }
    private class Finite(values: List<Long?>) : Source() {
        private val values = values.toList(); private var next = 0
        override fun read(): Long? = if (next < values.size) values[next++] else null
    }
    fun readMillis(): Long? = try { source.read()?.takeIf { it >= 0 } } catch (_: Throwable) { null }
    companion object {
        internal fun finiteForTests(values: List<Long?>): OrionQualificationWallBoundary {
            require(values.size <= 256); return OrionQualificationWallBoundary(Finite(values))
        }
    }
}
internal data class OrionQualificationExitHistory(val entries: List<OrionQualificationExitRecord>?, val truncated: Boolean,
    val dedicatedReason: Int?)

/** Actual MAIN/own-UID read-only Android boundary. Never binds, probes Cortex, reads traces or changes policy. */
internal class OrionQualificationExitReader(context: Context) {
    private val app = context.applicationContext
    private val packageName = app.packageName
    private val uid = app.applicationInfo.uid
    private fun allowed(): Boolean = packageName == "network.orion.qualification" &&
        Application.getProcessName() == packageName && uid == Process.myUid() && Looper.myLooper() != Looper.getMainLooper()
    private fun dedicatedReason(): Int? = try {
        ApplicationExitInfo::class.java.getField("REASON_MEMORY_LIMITER").getInt(null).takeIf { it > 16 }
    } catch (_: Throwable) { null }
    fun history(oldPid: Int): OrionQualificationExitHistory {
        val dedicated = dedicatedReason()
        if (!allowed() || oldPid <= 0 || oldPid == Process.myPid()) return OrionQualificationExitHistory(null, false, dedicated)
        return try {
            val manager = app.getSystemService(ActivityManager::class.java) ?: return OrionQualificationExitHistory(null, false, dedicated)
            val raw = manager.getHistoricalProcessExitReasons(packageName, oldPid, OrionQualificationExitCorrelation.MAX_EXITS + 1)
                .take(OrionQualificationExitCorrelation.MAX_EXITS + 1)
            if (raw.size > OrionQualificationExitCorrelation.MAX_EXITS) return OrionQualificationExitHistory(null, true, dedicated)
            val sanitized = ArrayList<OrionQualificationExitRecord>(raw.size)
            for (exit in raw) {
                // Description is transient and inspected only by the existing classifier; never retained/traced/logged.
                val record = OrionQualificationExitSanitizer.record(exit.pid, exit.realUid, exit.packageUid, exit.processName,
                    exit.timestamp, exit.reason, exit.description, dedicated) ?: return OrionQualificationExitHistory(null, false, dedicated)
                sanitized.add(record)
            }
            OrionQualificationExitHistory(java.util.Collections.unmodifiableList(sanitized), false, dedicated)
        } catch (_: Throwable) { OrionQualificationExitHistory(null, false, dedicated) }
    }
    fun process(pid: Int): OrionQualificationProcObservation {
        if (!allowed() || pid <= 0 || pid == Process.myPid()) return OrionQualificationProcObservation(OrionQualificationProcState.UNAVAILABLE)
        val stat = readProc(pid, "stat", 4096)
        if (stat.absent) return OrionQualificationProcObservation(OrionQualificationProcState.ABSENT)
        val status = readProc(pid, "status", 16384)
        if (status.absent) return OrionQualificationProcObservation(OrionQualificationProcState.ABSENT)
        val ticks = stat.text?.let { OrionQualificationProcParser.startTicks(it, pid) }
        val realUid = status.text?.let(OrionQualificationProcParser::uid)
        return if (ticks == null || realUid == null || realUid != uid) OrionQualificationProcObservation(OrionQualificationProcState.UNAVAILABLE)
            else OrionQualificationProcObservation(OrionQualificationProcState.PRESENT, OrionQualificationProcWitness(pid, realUid, ticks))
    }
    private class Read(val text: String?, val absent: Boolean)
    private fun readProc(pid: Int, leaf: String, limit: Int): Read {
        var fd: FileDescriptor? = null
        val bytes = ByteArray(limit + 1)
        return try {
            val opened = Os.open("/proc/$pid/$leaf", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            fd = opened
            var size = 0
            while (size <= limit) {
                val count = Os.read(opened, bytes, size, bytes.size - size)
                if (count <= 0) break
                size += count
                if (size > limit) return Read(null, false)
            }
            if ((0 until size).any { bytes[it] < 0 }) Read(null, false) else Read(String(bytes, 0, size, Charsets.US_ASCII), false)
        } catch (error: ErrnoException) {
            Read(null, error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH)
        } catch (_: Throwable) { Read(null, false) }
        finally { bytes.fill(0); fd?.let { try { Os.close(it) } catch (_: Throwable) { } } }
    }
}
/** Only kernel start ticks/UID survive; no conversion to a fabricated process-start elapsed time. */
internal object OrionQualificationProcParser {
    fun startTicks(stat: String, pid: Int): Long? {
        if (stat.length !in 1..4096 || stat.substringBefore('(').trim().toIntOrNull() != pid) return null
        val delimiter = stat.lastIndexOf(") "); if (delimiter < 0) return null
        val fields = stat.substring(delimiter + 2).trim().split(Regex("\\s+"))
        return fields.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }
    }
    fun uid(status: String): Int? {
        if (status.length !in 1..16384) return null
        val fields = status.lineSequence().filter { it.startsWith("Uid:") }.toList()
        if (fields.size != 1) return null
        val values = fields.single().removePrefix("Uid:").trim().split(Regex("\\s+")).map { it.toIntOrNull() }
        return values.takeIf { it.size == 4 && it.all { value -> value != null && value >= 0 } && it.distinct().size == 1 }?.first()
    }
}

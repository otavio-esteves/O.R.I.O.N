package network.orion.app.ai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import android.os.Process
import java.util.concurrent.Callable
import network.orion.core.time.OrionClock
import network.orion.data.database.OrionDatabase

internal data class OrionInstrumentedQueueCounts(val pending: Long, val unknown: Long)

/** Explicit v2 definition: unresolved includes RUNNING/MANUAL_REVIEW; UNKNOWN remains separate. */
internal fun readInstrumentedWorkloadQueues(db: OrionDatabase): OrionInstrumentedQueueCounts? = try {
    db.runInTransaction(Callable {
        db.openHelper.readableDatabase.query("""SELECT
            COUNT(CASE WHEN status IN ('PENDING','NEEDS_CONFIRMATION','CONFIRMED','RUNNING','MANUAL_REVIEW') THEN 1 END),
            COUNT(CASE WHEN status = 'UNKNOWN' THEN 1 END) FROM orion_action_requests""").use { rows ->
            if (!rows.moveToFirst() || rows.isNull(0) || rows.isNull(1)) null else {
                val pending = rows.getLong(0); val unknown = rows.getLong(1)
                if (pending !in 0..1000000 || unknown !in 0..1000000) null else OrionInstrumentedQueueCounts(pending, unknown)
            }
        }
    })
} catch (_: Throwable) { null }

internal data class OrionInstrumentedResourceObservation(val bootCount: Int, val startedElapsed: Long,
    val completedElapsed: Long, val mainSampledElapsed: Long, val mainPid: Int, val mainPssBytes: Long?,
    val ai: OrionRuntimeAiMemoryReading?, val maximumAgeMillis: Long?, val thermalStatus: Int?,
    val memoryPressure: String, val queues: OrionInstrumentedQueueCounts?,
    val exitObservation: OrionQualificationExitResult? = null)

/** Separate explicit observation. No provider, policy evaluation, binding, cancellation or retry. */
internal fun readInstrumentedWorkloadResources(context: Context, owner: OrionCortexRuntimeOwner,
    clock: OrionClock, main: network.orion.app.OrionFoundationRuntime, exitSource: OrionQualificationExitSource? = null,
    wallBoundary: OrionQualificationWallBoundary? = null): OrionInstrumentedResourceObservation? = try {
    val app = context.applicationContext
    if (Application.getProcessName() != app.packageName) null else {
        val startWallLower = if (exitSource != null) wallBoundary?.readMillis() else null
        val start = clock.monotonicNow()
        val startWallUpper = if (exitSource != null) wallBoundary?.readMillis() else null
        val boot = start.bootSessionId?.bootCount
        if (boot == null) { exitSource?.unavailableClock(); null } else {
            // The sample's source timestamp precedes the actual self-PSS read.
            val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong()
                .takeIf { it > 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024)
            val exitSlot = if (exitSource == null) null else OrionQualificationAiSlot()
            val ai = owner.instrumentedMemoryObservation(exitSlot)
            val manager = app.getSystemService(ActivityManager::class.java)
            val system = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
            val power = app.getSystemService(android.os.PowerManager::class.java)
            val thermal = power?.currentThermalStatus?.takeIf { it in 0..6 }
            val queues = main.instrumentedWorkloadQueues()
            val exitPacket = exitSource?.read(exitSlot?.value?.takeIf { it.reading === ai })
            val endWallLower = if (exitSource != null) wallBoundary?.readMillis() else null
            val end = clock.monotonicNow()
            val endWallUpper = if (exitSource != null) wallBoundary?.readMillis() else null
            val exitStart = OrionQualificationTimePair.fromExistingRead(startWallLower, start, startWallUpper)
            val exitEnd = OrionQualificationTimePair.fromExistingRead(endWallLower, end, endWallUpper)
            val exitResult = if (exitPacket == null) null else exitSource?.complete(
                if (exitStart == null || exitEnd == null) null else OrionQualificationObservationWindow(exitStart, exitEnd), exitPacket)
            if (end.bootSessionId != start.bootSessionId || end.elapsedRealtimeMillis < start.elapsedRealtimeMillis) null else {
                val validAi = ai?.takeIf { it.boot == boot && it.sampledElapsed in start.elapsedRealtimeMillis..end.elapsedRealtimeMillis &&
                    pss != null && pss <= Long.MAX_VALUE - it.pssBytes && system?.totalMem?.let { total -> pss + it.pssBytes <= total } == true }
                OrionInstrumentedResourceObservation(boot, start.elapsedRealtimeMillis, end.elapsedRealtimeMillis,
                    start.elapsedRealtimeMillis, Process.myPid(), pss, validAi, owner.runtimeCaptureObservationConfiguration()?.maximumSampleAgeMillis,
                    thermal, if (system == null) "UNAVAILABLE" else if (system.lowMemory) "CRITICAL" else "NONE", queues, exitResult)
            }
        }
    }
} catch (_: Throwable) { exitSource?.unavailableClock(); null }

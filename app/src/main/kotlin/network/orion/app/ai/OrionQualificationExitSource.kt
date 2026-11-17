package network.orion.app.ai

import android.content.Context
import android.os.Process

/** Final minimized carrier populated only from the SAME original validated diagnostic health result. */
internal class OrionQualificationAiObservation(val reading: OrionRuntimeAiMemoryReading, val endpoint: OrionQualificationEndpointKey)
internal class OrionQualificationAiSlot {
    var value: OrionQualificationAiObservation? = null
        private set
    fun observed(reading: OrionRuntimeAiMemoryReading, endpoint: OrionQualificationEndpointKey) {
        try { if (value == null) value = OrionQualificationAiObservation(reading, endpoint) } catch (_: Throwable) { }
    }
}
internal sealed interface OrionQualificationExitReadPacket

/** Manual qualification actor only, serialized by the existing #160 gate. No autonomous polling or policy authority. */
internal class OrionQualificationExitSource(context: Context, configuration: OrionQualificationExitConfiguration) {
    private val app = context.applicationContext
    private val reader = OrionQualificationExitReader(app)
    private val correlation = OrionQualificationExitCorrelation(app.packageName, app.applicationInfo.uid, Process.myPid(), configuration)
    private class Live(val ai: OrionQualificationAiObservation, val history: OrionQualificationExitHistory,
        val process: OrionQualificationProcObservation) : OrionQualificationExitReadPacket
    private class History(val pid: Int, val history: OrionQualificationExitHistory,
        val process: OrionQualificationProcObservation) : OrionQualificationExitReadPacket
    private class Unpaired : OrionQualificationExitReadPacket
    var latest = OrionQualificationExitResult(null, OrionQualificationExitGap.LIVE_UNPAIRED)
        private set

    fun unavailableClock() {
        latest = OrionQualificationExitResult(null, correlation.unavailableClock())
    }

    /** I/O occurs only in the explicit existing diagnostic window; never while recording a Core decision. */
    fun read(ai: OrionQualificationAiObservation?): OrionQualificationExitReadPacket = try {
        if (ai != null) Live(ai, reader.history(ai.reading.pid), reader.process(ai.reading.pid)) else {
            val pid = correlation.pairedPid()
            if (pid == null) Unpaired() else History(pid, reader.history(pid), reader.process(pid))
        }
    } catch (_: Throwable) { Unpaired() }

    fun complete(window: OrionQualificationObservationWindow?, packet: OrionQualificationExitReadPacket): OrionQualificationExitResult {
        latest = try {
            if (window == null) OrionQualificationExitResult(null, correlation.unavailableClock()) else when (packet) {
                is Live -> {
                    val witness = packet.process.witness
                    if (packet.process.state == OrionQualificationProcState.ABSENT && correlation.pairedPid() == packet.ai.reading.pid)
                        correlation.history(window, packet.history.entries, packet.process, packet.history.truncated)
                    else if (packet.process.state != OrionQualificationProcState.PRESENT || witness == null)
                        OrionQualificationExitResult(null, correlation.liveUnavailable(window, OrionQualificationExitGap.PROC_UNAVAILABLE))
                    else OrionQualificationExitResult(null, correlation.live(OrionQualificationLiveReceipt(packet.ai.endpoint,
                        packet.ai.reading, witness, window, packet.history)))
                }
                is History -> if (correlation.pairedPid() != packet.pid) OrionQualificationExitResult(null, OrionQualificationExitGap.IDENTITY_MISMATCH)
                    else correlation.history(window, packet.history.entries, packet.process, packet.history.truncated)
                is Unpaired -> OrionQualificationExitResult(null, correlation.liveUnavailable(window, OrionQualificationExitGap.LIVE_UNPAIRED))
            }
        } catch (_: Throwable) { OrionQualificationExitResult(null, OrionQualificationExitGap.HISTORY_UNAVAILABLE) }
        return latest
    }
}

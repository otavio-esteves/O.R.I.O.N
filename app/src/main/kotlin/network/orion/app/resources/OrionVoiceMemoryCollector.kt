package network.orion.app.resources

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.voice.ipc.IOrionVoiceMemoryCallback
import network.orion.voice.ipc.OrionVoicePresence
import network.orion.voice.ipc.OrionVoiceProcess
import network.orion.voice.ipc.OrionVoiceProcessInstance
import network.orion.voice.ipc.OrionVoiceResources

/** Joins the pinned AI receipt with live voice roles and reserves room for light process startup. */
internal class OrionVoiceMemoryCollector(
    private val app: Context,
    private val clock: OrionClock,
    private val startupReserveBytes: Long,
) {
    private val random = SecureRandom()

    init { require(startupReserveBytes > 0) }

    /** One fresh retry can reconcile a voice process that starts or exits during the first sample. */
    fun collect(ai: () -> OrionAppMemoryEvidence?): OrionAppMemoryEvidence? = collectOnce(ai) ?: collectOnce(ai)

    private fun collectOnce(ai: () -> OrionAppMemoryEvidence?): OrionAppMemoryEvidence? {
        val before = presence() ?: return null
        val aiEvidence = ai() ?: return null
        val queries = before.mapNotNull { (role, state) ->
            if (state is OrionVoicePresence.Active) VoiceQuery(role, state.instance) else null
        }
        val received = CountDownLatch(queries.size)
        for (query in queries) {
            val nonce = random.nextLong()
            val name = app.packageName + query.role.suffix
            val callback = object : IOrionVoiceMemoryCallback.Stub() {
                override fun sample(major: Int, echoedNonce: Long, processName: String?, epoch: Long,
                    bootCount: Int, sampledElapsed: Long, totalPssBytes: Long) {
                    if (!validVoiceMemorySender(app.applicationInfo.uid, query.instance, name,
                            Binder.getCallingUid(), Binder.getCallingPid(), Process.myPid(), major, processName, epoch) ||
                        echoedNonce != nonce || bootCount < 0 || sampledElapsed < 0 || totalPssBytes <= 0) return
                    val evidence = OrionProcessMemoryReading(name,
                        MonotonicInstant(BootSessionId(bootCount), sampledElapsed), totalPssBytes, true)
                    if (query.result.compareAndSet(null, evidence)) received.countDown()
                }
            }
            val request = Bundle().apply {
                putInt(OrionVoiceResources.MEMORY_MAJOR, OrionVoiceResources.MEMORY_PROTOCOL)
                putLong(OrionVoiceResources.MEMORY_NONCE, nonce)
                putString(OrionVoiceResources.MEMORY_ROLE, query.role.name)
                putBinder(OrionVoiceResources.MEMORY_CALLBACK, callback)
            }
            app.sendBroadcast(Intent(OrionVoiceResources.MEMORY_ACTION).setPackage(app.packageName)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(OrionVoiceResources.MEMORY_REQUEST, request),
                "${app.packageName}.permission.ORION_VOICE_TELEMETRY")
        }
        if (!received.await(450, TimeUnit.MILLISECONDS) || presence() != before) return null
        val voiceReadings = queries.map { it.result.get() ?: return null }
        return aiEvidence.copy(
            processNames = aiEvidence.processNames + voiceReadings.map { it.processName },
            readings = aiEvidence.readings + voiceReadings,
            reservedBytes = startupReserveBytes,
        )
    }

    private fun presence(): Map<OrionVoiceProcess, OrionVoicePresence>? {
        val roles = mutableMapOf<OrionVoiceProcess, OrionVoicePresence>()
        for (role in OrionVoiceProcess.entries) {
            roles[role] = OrionVoiceResources.presence(app, role) ?: return null
        }
        return roles
    }

    private class VoiceQuery(val role: OrionVoiceProcess, val instance: OrionVoiceProcessInstance) {
        val result = AtomicReference<OrionProcessMemoryReading?>()
    }
}

internal fun validVoiceMemorySender(
    appUid: Int, instance: OrionVoiceProcessInstance, expectedName: String,
    senderUid: Int, senderPid: Int, mainPid: Int, major: Int, processName: String?, epoch: Long,
) = senderUid == appUid && senderPid == instance.pid && senderPid > 0 && senderPid != mainPid &&
    major == OrionVoiceResources.MEMORY_PROTOCOL && processName == expectedName && epoch == instance.epoch

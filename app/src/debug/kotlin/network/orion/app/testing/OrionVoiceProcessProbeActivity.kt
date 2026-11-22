package network.orion.app.testing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import network.orion.app.OrionApplication
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ipc.*
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.*

/** Debug-only shell fixture. Uses a fixed synthetic query and writes only technical evidence. */
abstract class OrionVoiceProcessProbeActivity : Activity() {
    protected abstract val role: OrionProcessRole
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "O.R.I.O.N. test probe" })
        probe(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        probe(intent)
    }

    private fun probe(intent: Intent) {
        val fixture = intent.getStringExtra("fixture")?.let(OrionVoiceWire::requestId) ?: return
        val app = application as OrionApplication
        if (app.mainRuntime != null || app.processRole != role || androidx.work.WorkManager.isInitialized()) return
        val output = File(filesDir, "orion_voice_probe_${role.name}_$fixture")
        val pid = Process.myPid()
        when (intent.getStringExtra("stage")) {
            "announce" -> output.writeText("STARTED $pid")
            "rebind" -> scope.launch {
                val client = OrionBoundVoiceCoreClient(this@OrionVoiceProcessProbeActivity)
                try {
                    val completed = withTimeoutOrNull(24_000) {
                        check(client.connect() == VoiceCoreConnectionState.CONNECTED)
                        while (client.getCoreReadiness()?.let(CoreReadiness::valueOf)?.isStartupBarrierOpen != true) delay(100)
                        val request = VoiceUtteranceRequest(IpcSchemaVersion(1), UUID.randomUUID(), fixture, "bateria", true,
                            "pt-BR", AndroidOrionClock(contentResolver).wallNow(), OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
                        check(client.submitUtterance(request) == OrionVoiceAdmission.ACCEPTED)
                        var sequence = -1L
                        while (isActive) {
                            val next = client.status(request.requestId, sequence)
                            if (next == null) delay(100) else {
                                sequence = next.sequence
                                if (next.terminalState == OrionVoiceTerminalState.SUCCEEDED) return@withTimeoutOrNull true
                            }
                        }
                        false
                    }
                    if (completed == true) output.writeText("REBOUND $pid")
                } catch (_: IllegalStateException) {
                    output.writeText("FAILED $pid")
                } finally { withContext(NonCancellable) { client.disconnect() } }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class OrionVoiceSessionProbeActivity : OrionVoiceProcessProbeActivity() {
    override val role = OrionProcessRole.VOICE_SESSION
}
class OrionVoiceControlProbeActivity : OrionVoiceProcessProbeActivity() {
    override val role = OrionProcessRole.VOICE_CONTROL
}

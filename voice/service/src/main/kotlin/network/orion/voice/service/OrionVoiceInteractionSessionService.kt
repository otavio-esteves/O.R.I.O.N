package network.orion.voice.service

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** The framework binds explicitly from system_server. The session service stays unexported. */
class OrionVoiceInteractionSessionService : VoiceInteractionSessionService() {
    private var current: OrionVoiceInteractionSession? = null

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == VoiceInteractionService.SERVICE_INTERFACE) super.onBind(intent) else null

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        OrionVoiceInteractionSession(this).also { current = it }

    override fun onUnbind(intent: Intent): Boolean {
        current?.close()
        return false
    }

    override fun onDestroy() {
        current?.close()
        current = null
        super.onDestroy()
    }
}

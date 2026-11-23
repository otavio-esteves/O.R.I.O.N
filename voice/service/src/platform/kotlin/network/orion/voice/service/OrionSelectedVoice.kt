package network.orion.voice.service

import android.content.Context

internal suspend fun speakOrionVoiceReply(context: Context, text: String, sessionId: String) =
    speakOrionPlatformVoiceReply(context, text, sessionId)

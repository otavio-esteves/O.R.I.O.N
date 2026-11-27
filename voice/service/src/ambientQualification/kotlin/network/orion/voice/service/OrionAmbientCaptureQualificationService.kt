package network.orion.voice.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.service.voice.VoiceInteractionService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionVoiceSource
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.OrionBoundVoiceCoreClient
import network.orion.voice.ipc.VoiceCoreConnectionState
import java.io.File
import java.time.Instant
import java.util.UUID

/** The opted-in debug manifest measures the public Android capture path. */
class OrionAmbientCaptureQualificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        if (!eligible(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Teste de escuta ambiental",
            NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("O.R.I.O.N. · teste de microfone")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            Log.i(TAG, "FOREGROUND_DENIED")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        scope.launch {
            try {
                withTimeoutOrNull(120_000) { qualifyCapture() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.i(TAG, "CAPTURE_UNAVAILABLE")
            } catch (_: LinkageError) {
                Log.i(TAG, "MODEL_UNAVAILABLE")
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private data class Segment(val pcm: ShortArray, val capturedAt: Instant)

    private suspend fun qualifyCapture() = coroutineScope {
        val segments = Channel<Segment>(1, onUndeliveredElement = { it.pcm.fill(0) })
        val decode = launch {
            try {
                if (!OrionVoiceModel.present(this@OrionAmbientCaptureQualificationService)) {
                    OrionVoiceModel.installBundled(this@OrionAmbientCaptureQualificationService)
                }
                val model = OrionVoiceModel.verified(this@OrionAmbientCaptureQualificationService)
                if (model == null) {
                    Log.i(TAG, "MODEL_UNAVAILABLE")
                    segments.cancel()
                } else for (segment in segments) qualifyTranscript(model, segment)
            } finally {
                segments.cancel()
            }
        }
        try { measure(segments) } finally { segments.cancel(); decode.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun measure(segments: Channel<Segment>) {
        check(eligible(this))
        val rate = OrionVoiceActivityDetection.sampleRate
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channels = AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(rate, channels, encoding)
        check(minimum > 0)
        val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channels).setEncoding(encoding).build())
            .setBufferSizeInBytes(maxOf(minimum, rate / 5)).build()
        val frame = ShortArray(OrionVoiceActivityDetection.frameSamples)
        val speech = OrionAmbientSpeechBuffer()
        val clock = AndroidOrionClock(contentResolver)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            var observedSilence: Boolean? = null
            var frameSize = 0
            while (eligible(this)) {
                // Sample the app's own capture policy without storing any audio.
                val silenced = recorder.activeRecordingConfiguration?.isClientSilenced
                if (observedSilence != silenced) {
                    Log.i(TAG, if (silenced == false) "CAPTURE_ACTIVE" else "CAPTURE_SILENCED")
                    observedSilence = silenced
                }
                repeat(20) {
                    currentCoroutineContext().ensureActive()
                    val read = recorder.read(frame, frameSize, frame.size - frameSize, AudioRecord.READ_NON_BLOCKING)
                    check(read >= 0)
                    frameSize += read
                    if (frameSize == frame.size) {
                        if (silenced == false) speech.accept(frame)?.let { pcm ->
                            if (!segments.trySend(Segment(pcm, clock.wallNow())).isSuccess) pcm.fill(0)
                        } else speech.clear()
                        frame.fill(0)
                        frameSize = 0
                    }
                    if (read == 0) delay(10)
                }
            }
        } finally {
            frame.fill(0)
            speech.clear()
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
            Log.i(TAG, "CAPTURE_STOPPED")
        }
    }

    private suspend fun qualifyTranscript(model: File, segment: Segment) {
        val text = try {
            val reservation = OrionVoiceMemoryEndpoint.acquireHeavy(this) ?: return
            reservation.use {
                if (!eligible(this)) return
                val watchdog = Handler(Looper.getMainLooper())
                val terminate = Runnable {
                    if (android.app.Application.getProcessName() == "$packageName:voice_session") Process.killProcess(Process.myPid())
                }
                watchdog.postDelayed(terminate, 25_000)
                try {
                    val bytes = OrionWhisper.transcribe(OrionVoiceCapture.nextOperationId(), model.absolutePath, segment.pcm)
                    try { bytes?.decodeToString(throwOnInvalidSequence = true)?.trim() }
                    finally { bytes?.fill(0) }
                } finally {
                    watchdog.removeCallbacks(terminate)
                }
            }
        } finally { segment.pcm.fill(0) }
        if (text.isNullOrBlank() || text.length > 240 || !eligible(this)) return
        val core = OrionBoundVoiceCoreClient(this)
        try {
            if (core.connect() != VoiceCoreConnectionState.CONNECTED) return
            core.submitUtterance(VoiceUtteranceRequest(IpcSchemaVersion(1), UUID.randomUUID(), UUID.randomUUID(),
                text, true, "pt-BR", segment.capturedAt, OrionVoiceSource.AMBIENT_QUALIFICATION, 0, UUID.randomUUID()))
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { core.disconnect() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "orion_ambient_probe"
        private const val CHANNEL = "orion_ambient_qualification"
        private const val NOTIFICATION_ID = 2_910

        internal fun startIfPackaged(context: Context) {
            val intent = probeIntent(context)
            if (context.packageManager.resolveService(intent, 0) == null || !eligible(context)) return
            try {
                context.startForegroundService(intent)
            } catch (_: RuntimeException) {
                Log.i(TAG, "START_DENIED")
            }
        }

        internal fun stopIfPackaged(context: Context) {
            val intent = probeIntent(context)
            if (context.packageManager.resolveService(intent, 0) != null) context.stopService(intent)
        }

        private fun probeIntent(context: Context) = Intent(context, OrionAmbientCaptureQualificationService::class.java)

        private fun eligible(context: Context) =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                VoiceInteractionService.isActiveService(context, ComponentName(context, OrionVoiceInteractionService::class.java))
    }
}

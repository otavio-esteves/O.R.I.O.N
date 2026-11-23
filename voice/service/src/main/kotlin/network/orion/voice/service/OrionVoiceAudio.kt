package network.orion.voice.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlinx.coroutines.*

internal object OrionVoiceActivityDetection {
    const val sampleRate = 16_000
    const val maxSamples = 8 * sampleRate
    const val frameSamples = sampleRate / 50

    /** Trim around speech and require at least 120 ms of voiced frames. Input is bounded mono PCM. */
    fun speech(input: ShortArray, size: Int): ShortArray? {
        require(size in 0..input.size && size <= maxSamples)
        val levels = IntArray(size / frameSamples) { frame ->
            val start = frame * frameSamples
            var magnitude = 0
            for (index in start until start + frameSamples) magnitude += abs(input[index].toInt())
            magnitude / frameSamples
        }
        if (levels.size < 6) return null
        val noiseFloor = levels.sortedArray()[levels.size / 5]
        val threshold = maxOf(48, noiseFloor + maxOf(32, noiseFloor / 2))
        var first = -1
        var last = 0
        var voicedFrames = 0
        for (frame in levels.indices) {
            if (levels[frame] >= threshold) {
                if (first < 0) first = frame * frameSamples
                last = (frame + 1) * frameSamples
                voicedFrames++
            }
        }
        if (voicedFrames < 6) return null
        val padding = 2 * sampleRate / 5
        return input.copyOfRange((first - padding).coerceAtLeast(0), (last + padding).coerceAtMost(size))
    }
}

internal class OrionVoiceRecorder(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun capture(
        finished: () -> Boolean,
        requireAuthority: () -> Unit,
        listening: () -> Unit,
    ): ShortArray? = withContext(Dispatchers.IO) {
        val rate = OrionVoiceActivityDetection.sampleRate
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone unavailable.")
        val minimum = AudioRecord.getMinBufferSize(rate, channels, encoding)
        if (minimum <= 0) throw IllegalStateException("Unsupported microphone format.")
        val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channels).setEncoding(encoding).build())
            .setBufferSizeInBytes(maxOf(minimum, rate / 5)).build()
        val raw = ShortArray(OrionVoiceActivityDetection.maxSamples)
        try {
            if (finished()) return@withContext null
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            requireAuthority()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            listening()
            var count = 0
            withTimeoutOrNull(8_500) {
                while (!finished() && count < raw.size) {
                    currentCoroutineContext().ensureActive()
                    val read = recorder.read(raw, count, minOf(raw.size - count, rate / 50), AudioRecord.READ_NON_BLOCKING)
                    check(read >= 0)
                    count += read
                    if (read == 0) delay(10)
                }
            }
            if (finished() && count < raw.size) {
                requireAuthority()
                val pending = minOf(raw.size - count, 2 * OrionVoiceActivityDetection.frameSamples)
                val read = recorder.read(raw, count, pending, AudioRecord.READ_NON_BLOCKING)
                if (read > 0) count += read
            }
            OrionVoiceActivityDetection.speech(raw, count)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
                raw.fill(0)
            }
        }
    }
}

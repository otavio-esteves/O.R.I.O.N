package network.orion.voice.service

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class OrionVoiceActivityDetectionTest {
    @Test fun silenceAndShortBurstsStayOutOfStt() {
        val silent = ShortArray(16_000)
        assertNull(OrionVoiceActivityDetection.speech(silent, silent.size))
        silent.fill(600, 320, 640)
        assertNull(OrionVoiceActivityDetection.speech(silent, silent.size))
    }

    @Test fun voicedFramesPreserveBoundedPadding() {
        val audio = ShortArray(16_000)
        audio.fill(-1_200, 6_400, 9_600)
        val speech = checkNotNull(OrionVoiceActivityDetection.speech(audio, audio.size))
        assertEquals(16_000, speech.size)
        assertEquals(3_200, speech.count { it.toInt() == -1_200 })
    }

    @Test fun quietSpeechIsKeptAndSteadyBackgroundNoiseIsRejected() {
        val quiet = ShortArray(32_000) { 20 }
        quiet.fill(95, 10_240, 15_360)
        quiet.fill(38, 5_120, 5_440)
        val speech = checkNotNull(OrionVoiceActivityDetection.speech(quiet, quiet.size))
        assertEquals(5_120, speech.count { it.toInt() == 95 })
        assertEquals(320, speech.count { it.toInt() == 38 })

        val constantNoise = ShortArray(32_000) { if (it % 2 == 0) 360 else -360 }
        assertNull(OrionVoiceActivityDetection.speech(constantNoise, constantNoise.size))
    }

    @Test fun softlyModulatedVoicingCanFillTheEntireCapture() {
        val audio = harmonicSignal(pitchHz = 150, modulation = 0.10)
        val speech = OrionVoiceActivityDetection.speech(audio, audio.size)
        assertArrayEquals(audio, speech)
    }

    @Test fun steadyTonesAndModulatedNoiseStayOutOfStt() {
        for (audio in listOf(
            harmonicSignal(pitchHz = 150, modulation = 0.0),
            harmonicSignal(pitchHz = 60, modulation = 0.10),
            harmonicSignal(pitchHz = 1_600, modulation = 0.10),
        )) {
            assertNull(OrionVoiceActivityDetection.speech(audio, audio.size))
        }
    }

    private fun harmonicSignal(pitchHz: Int, modulation: Double): ShortArray =
        ShortArray(OrionVoiceActivityDetection.sampleRate) { index ->
            val seconds = index.toDouble() / OrionVoiceActivityDetection.sampleRate
            val phase = 2 * PI * pitchHz * seconds
            val envelope = 180 * (1 + modulation * sin(2 * PI * 4 * seconds))
            (envelope * (sin(phase) + 0.30 * sin(2 * phase))).toInt().toShort()
        }
}

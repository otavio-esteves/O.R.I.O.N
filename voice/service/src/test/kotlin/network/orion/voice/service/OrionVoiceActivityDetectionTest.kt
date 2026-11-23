package network.orion.voice.service

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
}

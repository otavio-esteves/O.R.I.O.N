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
        assertEquals(9_600, speech.size)
        assertEquals(3_200, speech.count { it.toInt() == -1_200 })
    }
}

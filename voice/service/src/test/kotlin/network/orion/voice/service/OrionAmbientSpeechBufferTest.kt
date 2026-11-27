package network.orion.voice.service

import org.junit.Assert.*
import org.junit.Test

class OrionAmbientSpeechBufferTest {
    private val frame = OrionVoiceActivityDetection.frameSamples
    private val quiet = ShortArray(frame)
    private val voiced = ShortArray(frame) { 1_000 }

    @Test fun emitsCompleteUtterancesAfterSilenceAndRejectsBriefSounds() {
        val buffer = OrionAmbientSpeechBuffer()
        repeat(100) { assertNull(buffer.accept(quiet)) }
        assertNull(buffer.accept(voiced))
        repeat(30) { assertNull(buffer.accept(quiet)) }
        repeat(15) { assertNull(buffer.accept(voiced)) }
        repeat(29) { assertNull(buffer.accept(quiet)) }
        val segment = checkNotNull(buffer.accept(quiet))
        assertEquals(45 * frame, segment.size)
        assertNull(buffer.accept(quiet))
    }

    @Test fun rejectsIncompleteUtteranceAtTheCaptureLimitAndRecoversAfterSilence() {
        val buffer = OrionAmbientSpeechBuffer()
        repeat(420) { assertNull(buffer.accept(voiced)) }
        repeat(30) { assertNull(buffer.accept(quiet)) }
        repeat(15) { assertNull(buffer.accept(voiced)) }
        repeat(29) { assertNull(buffer.accept(quiet)) }
        assertNotNull(buffer.accept(quiet))
    }
}

package network.orion.voice.service

import kotlin.math.abs

/** Bounded, disposable segmenter for the qualification APK. Each frame is 20 ms. */
internal class OrionAmbientSpeechBuffer {
    private val pcm = ShortArray(OrionVoiceActivityDetection.maxSamples)
    private var size = 0
    private var voicedFrames = 0
    private var quietFrames = 0
    private var overflow = false

    fun accept(frame: ShortArray): ShortArray? {
        require(frame.size == OrionVoiceActivityDetection.frameSamples)
        val voiced = frame.sumOf { abs(it.toInt()) } >= 144 * frame.size
        if (size == 0 && !overflow && !voiced) return null
        if (voiced) {
            voicedFrames++
            quietFrames = 0
        } else quietFrames++
        if (!overflow) {
            if (size + frame.size > pcm.size) overflow = true
            else {
                frame.copyInto(pcm, size)
                size += frame.size
            }
        }
        if (quietFrames < 30) return null
        val result = if (!overflow && voicedFrames >= 8) pcm.copyOf(size) else null
        clear()
        return result
    }

    fun clear() {
        pcm.fill(0)
        size = 0
        voicedFrames = 0
        quietFrames = 0
        overflow = false
    }
}

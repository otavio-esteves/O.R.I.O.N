package network.orion.voice.wakeword

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWakeWordDetectorTest {
    @Test fun comparesIndependentSyntheticSequencesAndRejectsReorderedTones() {
        val training = phrase(intArrayOf(520, 1180, 680), intArrayOf(2800, 3200, 2400))
        val positive = phrase(intArrayOf(534, 1170, 695), intArrayOf(2970, 3160, 2720), gain = 0.64)
        val negative = phrase(intArrayOf(680, 1180, 520), intArrayOf(2400, 3200, 2800))
        val detector = OrionWakeWordDetector(listOf(training))
        val exact = detector.evaluate(training)
        val near = detector.evaluate(positive)
        val wrong = detector.evaluate(negative)

        assertEquals(OrionWakeWordScore(0.0, true), exact)
        assertTrue("near score: ${near.distance}", near.detected)
        assertFalse("reordered score: ${wrong.distance}", wrong.detected)
        assertTrue(near.distance in 0.0..1.0 && wrong.distance in 0.0..1.0)
        assertTrue(near.distance < wrong.distance)
    }

    @Test fun thresholdOnlyControlsDecisionAndTemplatePcmIsNeverRetained() {
        val training = phrase(intArrayOf(520, 1180, 680), intArrayOf(2800, 3200, 2400))
        val positive = phrase(intArrayOf(534, 1170, 695), intArrayOf(2970, 3160, 2720))
        val default = OrionWakeWordDetector(listOf(training))
        val strict = OrionWakeWordDetector(listOf(training), threshold = 0.0)
        val expected = default.evaluate(positive)

        training.fill(0)
        assertEquals(expected, default.evaluate(positive))
        assertEquals(expected.distance, strict.evaluate(positive).distance, 0.0)
        assertTrue(expected.distance > 0.0)
        assertFalse(strict.evaluate(positive).detected)
    }

    @Test fun silenceDcAndVeryWeakSignalsReturnFiniteNonmatches() {
        val training = phrase(intArrayOf(520, 1180, 680), intArrayOf(2800, 3200, 2400))
        val detector = OrionWakeWordDetector(listOf(training))
        val silence = OrionWakeWordScore(1.0, false)
        val length = OrionWakeWordDetector.MIN_SAMPLES

        assertEquals(silence, detector.evaluate(ShortArray(length)))
        assertEquals(silence, detector.evaluate(ShortArray(length) { Short.MAX_VALUE }))
        assertEquals(silence, detector.evaluate(ShortArray(length) { if (it % 2 == 0) 6 else -6 }))
        assertThrows(IllegalArgumentException::class.java) { OrionWakeWordDetector(listOf(ShortArray(length))) }
    }

    @Test fun validBoundaryLengthsAndExtremeValuesReturnBoundedScores() {
        val training = phrase(intArrayOf(520, 1180, 680), intArrayOf(2800, 3200, 2400))
        val detector = OrionWakeWordDetector(listOf(training))
        val short = ShortArray(OrionWakeWordDetector.MIN_SAMPLES) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val padded = training.copyOf(OrionWakeWordDetector.MAX_SAMPLES)

        assertEquals(OrionWakeWordScore(1.0, false), detector.evaluate(short))
        val paddedMatch = detector.evaluate(padded)
        assertTrue(paddedMatch.distance in 0.0..1.0)
        assertTrue(paddedMatch.detected)
    }

    @Test fun failsFastOnInvalidTemplateCountPcmLengthAndThreshold() {
        val training = phrase(intArrayOf(520, 1180, 680), intArrayOf(2800, 3200, 2400))
        val detector = OrionWakeWordDetector(listOf(training))

        assertThrows(IllegalArgumentException::class.java) { OrionWakeWordDetector(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            OrionWakeWordDetector(List(OrionWakeWordDetector.MAX_TEMPLATES + 1) { training })
        }
        for (threshold in listOf(-0.01, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { OrionWakeWordDetector(listOf(training), threshold) }
        }
        for (length in listOf(0, OrionWakeWordDetector.MIN_SAMPLES - 1, OrionWakeWordDetector.MAX_SAMPLES + 1)) {
            val invalid = ShortArray(length)
            assertThrows(IllegalArgumentException::class.java) { OrionWakeWordDetector(listOf(invalid)) }
            assertThrows(IllegalArgumentException::class.java) { detector.evaluate(invalid) }
        }
    }

    private fun phrase(frequencies: IntArray, durations: IntArray, gain: Double = 1.0): ShortArray {
        val samples = ShortArray(durations.sum())
        var offset = 0
        for ((part, duration) in durations.withIndex()) {
            val frequency = frequencies[part]
            for (index in 0 until duration) {
                val t = index.toDouble() / OrionWakeWordDetector.SAMPLE_RATE_HZ
                val fade = minOf(index / 170.0, (duration - index - 1) / 170.0, 1.0)
                val tone = sin(2 * PI * frequency * t) + 0.45 * sin(2 * PI * frequency * 1.8 * t)
                samples[offset + index] = (gain * fade * 6_000 * tone).roundToInt().toShort()
            }
            offset += duration
        }
        return samples
    }
}

package network.orion.voice.wakeword

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val FRAME = 400
private const val HOP = 160
private const val FFT_SIZE = 512
private const val MEL_BANDS = 20
private const val COEFFICIENTS = 12
private const val MIN_ACTIVE_FRAMES = 8
private const val MIN_FRAME_POWER = 64.0 * 64.0

private val hamming = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
private val realTwiddle = DoubleArray(FFT_SIZE / 2) { cos(2 * PI * it / FFT_SIZE) }
private val imaginaryTwiddle = DoubleArray(FFT_SIZE / 2) { -sin(2 * PI * it / FFT_SIZE) }
private val reversedBins = IntArray(FRAME) { Integer.reverse(it) ushr Integer.numberOfLeadingZeros(FFT_SIZE - 1) }
private val melFilters = melFilters()
private val cepstralProjection = Array(COEFFICIENTS) { coefficient ->
    val order = coefficient + 1 // Omit energy coefficient 0.
    DoubleArray(MEL_BANDS) { band ->
        sqrt(2.0 / MEL_BANDS) * cos(PI * order * (band + 0.5) / MEL_BANDS)
    }
}

/** 25 ms Hamming windows at 10 ms offsets, with an energy guard on already segmented input. */
internal fun orionWakeWordFeatures(pcm: ShortArray): Array<FloatArray>? {
    val count = 1 + (pcm.size - FRAME) / HOP
    val framePower = DoubleArray(count) { frameVariance(pcm, it * HOP) }
    val gate = maxOf(MIN_FRAME_POWER, framePower.max() * 0.01)
    if (framePower.count { it >= gate } < MIN_ACTIVE_FRAMES) return null
    val start = framePower.indexOfFirst { it >= gate }
    val end = framePower.indexOfLast { it >= gate }
    val real = DoubleArray(FFT_SIZE)
    val imaginary = DoubleArray(FFT_SIZE)
    val spectrum = DoubleArray(FFT_SIZE / 2 + 1)
    val energies = DoubleArray(MEL_BANDS)

    return Array(end - start + 1) { frame ->
        powerSpectrum(pcm, (start + frame) * HOP, real, imaginary, spectrum)
        for (band in melFilters.indices) {
            val filter = melFilters[band]
            var energy = 0.0
            for (tap in filter.weights.indices) {
                energy += spectrum[filter.firstBin + tap] * filter.weights[tap]
            }
            energies[band] = energy
        }

        val peak = energies.max()
        val floor = maxOf(1e-12, peak * 1e-5)
        for (band in energies.indices) energies[band] = ln(maxOf(floor, energies[band]))
        FloatArray(COEFFICIENTS) { coefficient ->
            val weights = cepstralProjection[coefficient]
            var cepstrum = 0.0
            for (band in weights.indices) cepstrum += weights[band] * energies[band]
            cepstrum.toFloat()
        }
    }
}

private fun frameVariance(pcm: ShortArray, offset: Int): Double {
    var sum = 0.0
    var squares = 0.0
    for (index in offset until offset + FRAME) {
        val value = pcm[index].toDouble()
        sum += value
        squares += value * value
    }
    val mean = sum / FRAME
    return squares / FRAME - mean * mean
}

/** In-place radix-2 FFT using bit-reversed input and cached twiddle coefficients. */
private fun powerSpectrum(
    pcm: ShortArray,
    offset: Int,
    real: DoubleArray,
    imaginary: DoubleArray,
    spectrum: DoubleArray,
) {
    real.fill(0.0)
    imaginary.fill(0.0)
    var previous = pcm[(offset - 1).coerceAtLeast(0)].toDouble()
    for (index in 0 until FRAME) {
        val value = pcm[offset + index].toDouble()
        real[reversedBins[index]] = (value - 0.97 * previous) * hamming[index] / 32768.0
        previous = value
    }

    var width = 2
    while (width <= FFT_SIZE) {
        val half = width / 2
        val twiddleStride = FFT_SIZE / width
        for (block in 0 until FFT_SIZE step width) {
            for (index in 0 until half) {
                val low = block + index
                val high = low + half
                val twiddle = index * twiddleStride
                val r = real[high] * realTwiddle[twiddle] - imaginary[high] * imaginaryTwiddle[twiddle]
                val i = real[high] * imaginaryTwiddle[twiddle] + imaginary[high] * realTwiddle[twiddle]
                real[high] = real[low] - r
                imaginary[high] = imaginary[low] - i
                real[low] += r
                imaginary[low] += i
            }
        }
        width *= 2
    }

    for (bin in spectrum.indices) {
        spectrum[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
    }
}

private data class MelFilter(val firstBin: Int, val weights: DoubleArray)

private fun melFilters(): Array<MelFilter> {
    val lowerMel = 2595.0 * log10(1.0 + 100.0 / 700.0)
    val upperMel = 2595.0 * log10(1.0 + 7_600.0 / 700.0)
    val binWidth = OrionWakeWordDetector.SAMPLE_RATE_HZ.toDouble() / FFT_SIZE
    val edges = DoubleArray(MEL_BANDS + 2) { index ->
        val mel = lowerMel + (upperMel - lowerMel) * index / (MEL_BANDS + 1)
        700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }
    return Array(MEL_BANDS) { band ->
        val left = edges[band]
        val center = edges[band + 1]
        val right = edges[band + 2]
        val firstBin = (left / binWidth).toInt() + 1
        val lastBin = (right / binWidth).toInt()
        val weights = DoubleArray(lastBin - firstBin + 1) { tap ->
            val frequency = (firstBin + tap) * binWidth
            minOf((frequency - left) / (center - left), (right - frequency) / (right - center))
        }
        MelFilter(firstBin, weights)
    }
}

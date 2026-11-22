package network.orion.voice.wakeword

import kotlin.math.sqrt

/** Distance is finite in [0, 1]. Lower values indicate a closer template match. */
data class OrionWakeWordScore(val distance: Double, val detected: Boolean)

/**
 * Offline qualification candidate for short, presegmented Orion utterances.
 * Inputs are signed 16-bit PCM, mono, at 16 kHz. Retains only compact template features.
 */
class OrionWakeWordDetector(
    templates: List<ShortArray>,
    private val threshold: Double = DEFAULT_THRESHOLD,
) {
    private val references: List<Array<FloatArray>>

    init {
        require(templates.size in 1..MAX_TEMPLATES)
        require(threshold >= 0.0 && threshold < 1.0)
        references = templates.map { pcm ->
            validateSize(pcm)
            requireNotNull(orionWakeWordFeatures(pcm)) { "A wake-word template needs audible speech." }
        }
    }

    fun evaluate(pcm: ShortArray): OrionWakeWordScore {
        validateSize(pcm)
        val sample = orionWakeWordFeatures(pcm) ?: return OrionWakeWordScore(1.0, false)
        val distance = references.minOf { boundedDistance(it, sample) }
        return OrionWakeWordScore(distance, distance <= threshold)
    }

    private fun validateSize(pcm: ShortArray) {
        require(pcm.size in MIN_SAMPLES..MAX_SAMPLES) { "Expected 0.2 to 1.6 seconds of mono PCM at 16 kHz." }
    }

    private fun boundedDistance(reference: Array<FloatArray>, sample: Array<FloatArray>): Double {
        val rows = reference.size
        val columns = sample.size
        val shorter = minOf(rows, columns)
        val longer = maxOf(rows, columns)
        if (longer * 2 > shorter * 3) return 1.0

        // Constrain path displacement in time around the line connecting both endpoints.
        var previous = DoubleArray(columns + 1) { Double.POSITIVE_INFINITY }
        var current = previous.copyOf()
        previous[0] = 0.0
        for (row in 1..rows) {
            current.fill(Double.POSITIVE_INFINITY)
            val center = 1 + (row - 1) * (columns - 1) / (rows - 1)
            val from = (center - WARP_FRAMES).coerceAtLeast(1)
            val to = (center + WARP_FRAMES).coerceAtMost(columns)
            for (column in from..to) {
                val cost = frameDistance(reference[row - 1], sample[column - 1])
                // The diagonal moves both axes, so its cost gets twice the weight.
                // Every path then has total weight `rows + columns`.
                val diagonal = previous[column - 1] + 2 * cost
                val insertion = current[column - 1] + cost + WARP_PENALTY
                val deletion = previous[column] + cost + WARP_PENALTY
                current[column] = minOf(diagonal, insertion, deletion)
            }
            val completed = previous
            previous = current
            current = completed
        }
        return (previous[columns] / (rows + columns)).coerceIn(0.0, 1.0)
    }

    private fun frameDistance(reference: FloatArray, sample: FloatArray): Double {
        var squares = 0.0
        for (index in reference.indices) {
            val difference = (reference[index] - sample[index]).toDouble()
            squares += difference * difference
        }
        val rootMeanSquare = sqrt(squares / reference.size)
        return rootMeanSquare / (rootMeanSquare + DISTANCE_SCALE)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MIN_SAMPLES = SAMPLE_RATE_HZ / 5
        const val MAX_SAMPLES = SAMPLE_RATE_HZ * 8 / 5
        const val MAX_TEMPLATES = 8
        const val DEFAULT_THRESHOLD = 0.24

        private const val WARP_FRAMES = 12
        private const val WARP_PENALTY = 0.02
        private const val DISTANCE_SCALE = 4.0
    }
}

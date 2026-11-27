package network.orion.voice.service

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/** Measures similarity locally. Calibration selects how the Core can use this evidence. */
object OrionSpeakerQualification {
    fun compare(model: File, reference: ShortArray, samples: List<ShortArray>): List<Double>? {
        if (reference.size !in MIN_SAMPLES..MAX_SAMPLES || samples.size !in 1..8 ||
            samples.any { it.size !in MIN_SAMPLES..MAX_SAMPLES } || !validModel(model)) return null
        val extractor = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(
            model = model.absolutePath, numThreads = 1, debug = false, provider = "cpu",
        ))
        try {
            val enrolled = embedding(extractor, reference) ?: return null
            try {
                return samples.map { pcm ->
                    val found = embedding(extractor, pcm) ?: return null
                    try { cosine(enrolled, found) ?: return null } finally { found.fill(0f) }
                }
            } finally { enrolled.fill(0f) }
        } finally { extractor.release() }
    }

    private fun embedding(extractor: SpeakerEmbeddingExtractor, pcm: ShortArray): FloatArray? {
        val waveform = FloatArray(pcm.size) { pcm[it] / 32768f }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(waveform, 16_000)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            waveform.fill(0f)
            stream.release()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.isEmpty() || a.size != b.size) return null
        var dot = 0.0
        var energyA = 0.0
        var energyB = 0.0
        for (index in a.indices) {
            val x = a[index].toDouble()
            val y = b[index].toDouble()
            if (!x.isFinite() || !y.isFinite()) return null
            dot += x * y
            energyA += x * x
            energyB += y * y
        }
        return if (energyA > 0 && energyB > 0) (dot / sqrt(energyA * energyB)).coerceIn(-1.0, 1.0) else null
    }

    private fun validModel(model: File): Boolean {
        if (!model.isFile || model.length() != 39_593_762L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } ==
            "f1ee61be04000b50392a63442e5dd9feaea7e81dddd494d3647cf1c3d88a1498"
    }

    private const val MIN_SAMPLES = 16_000
    private const val MAX_SAMPLES = 128_000
}
